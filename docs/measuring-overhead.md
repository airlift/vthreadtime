# Measuring runtime overhead

This describes how the numbers in the README's "Runtime overhead" section were
measured, so the measurement can be repeated when the JVM or the library
changes. The scripts were throwaway and are not checked in; this document is
the part worth keeping.

## Question

How much does the library add to each virtual-thread unmount/mount cycle, and
how much of that is HotSpot's JVMTI machinery rather than the library's own
callback? Then, how large is that cost next to real operations that unmount?

## Environment

The October 2026 measurements used:

- AWS c9g.4xlarge (Graviton5), c8g.4xlarge (Graviton4), and c8i.4xlarge
  (Intel, two hardware threads per core)
- Amazon Linux 2023, kernel 6.18
- Corretto 25.0.4.1, `-Xms1g -Xmx1g -XX:+AlwaysPreTouch`
- The library built with the project's own Maven build. AL2023's default gcc 11
  cannot build it; install `gcc14` and set `CC=gcc14-gcc`.

## Isolating each layer

Each variant runs in its own JVM, because the costs being measured are
JVM-wide state that cannot be turned off once enabled. A small test JVMTI
library loads through FFM after VM start, the same way this library does, and
performs exactly one activation per variant. The mount and unmount events are
HotSpot extension events, found through `GetExtensionEvents` by the names
`com.sun.hotspot.events.VirtualThreadMount` and
`com.sun.hotspot.events.VirtualThreadUnmount` and registered with
`SetExtensionEventCallback`. The test library's callbacks only increment a
counter.

| Variant | What is active |
| --- | --- |
| `baseline` | No JVMTI environment and no library |
| `env-only` | Test library calls `GetEnv` only |
| `capability` | Test library also adds `can_support_virtual_threads` |
| `events-unrelated` | Test library enables the events for one other, parked virtual thread only (not used in the README) |
| `events-noop` | Test library enables mount and unmount events globally with no-op callbacks |
| `initialized` | `VirtualThreadTime.initialize()`, thread not registered |
| `tracked` | `initialize()` and the yielding thread registered |
| `javaagent`, `javaagent-initialized`, `javaagent-tracked` | The same three, with an empty `-javaagent` that registers no transformers |

The README's per-cycle numbers come from a single run of every variant except
`events-unrelated`, with the library as of PR #32, on fresh instances:

| README number | Difference |
| --- | --- |
| Unregistered thread, JVM with an agent | `javaagent-initialized - javaagent` |
| Unregistered thread, JVM without an agent | `initialized - baseline` |
| Tracking per cycle | `tracked - initialized` |

The same run splits each cycle into layers. These October 2026 results are the
reference point for a future comparison:

| Layer | Difference | c9g | c8g | c8i |
| --- | --- | ---: | ---: | ---: |
| Cycle without an agent | `baseline` | 150 ns | 184 ns | 290 ns |
| Slow path from any JVMTI environment | `capability - baseline` | +80 ns | +93 ns | +68–74 ns |
| JVM delivers the events | `events-noop - capability` | +65 ns | +81 ns | about +145 ns |
| Library skips an unregistered thread | `initialized - events-noop` | +8 ns | +14 ns | about +25 ns |
| Library times a registered thread | `javaagent-tracked - javaagent-initialized` | +51 ns | +69 ns | about +47 ns |
| Total, unregistered thread behind an agent | `javaagent-initialized - javaagent` | +73 ns | +97 ns | +162 ns |
| Total, registered thread behind an agent | `javaagent-tracked - javaagent` | +124 ns | +166 ns | +209 ns |

The totals are measured directly rather than summed. They agree with the sum
of the layers within a few nanoseconds on Graviton and within the noise on
Intel. `env-only`, `capability`, and `javaagent` cost the same, so the slow
path comes from creating any JVMTI environment, and `javaagent-initialized`
matched `initialized` within 1 ns on Graviton and within noise on Intel, so the
library adds nothing to the slow path an agent already pays for.

## Workloads

The per-cycle numbers come from a zero-work `Thread.yield()` loop on one
virtual thread with one carrier
(`-Djdk.virtualThreadScheduler.parallelism=1 -Djdk.virtualThreadScheduler.maxPoolSize=1`).
Each yield is exactly one unmount and one mount. Each fork ran 10 warmup and 20
measured rounds of 1,000,000 yields.

The README's park table and stress figure compare `baseline`, `initialized`,
and `tracked`, with the library as of PR #32 and no agent, on workloads that
run continuously while the main thread samples them in 1-second rounds
(5 warmup, 10 measured):

| Workload | Shape |
| --- | --- |
| Lock, `synchronized` | Uncontended; never unmounts. A control. |
| Ping-pong | Two virtual threads alternating with park/unpark on one carrier |
| Blocked handoff | The same, with a platform-thread driver, so the carrier idles in the OS between turns |
| Saturated | 64 ping-pong pairs on 16 carriers |
| TCP echo | One-byte blocking echo over loopback between two virtual threads |
| Timed park | `LockSupport.parkNanos(50_000)` in a loop |
| Contended `synchronized` | 16 threads on 4 carriers; almost never unmounts, a second control |
| Carrier scaling | The yield loop with 1, 4, and 16 carriers, and 64 threads on 16 carriers |

In `tracked` mode every virtual worker registers and reports its mount and
unmount counts at the end, which confirms the unmounts per operation.

## Measurement

- **Metric.** CPU time per operation for the whole process, from
  `OperatingSystemMXBean.getProcessCpuTime()`, plus wall time from
  `System.nanoTime()`. Process CPU matters because the added work lands on
  carriers and partner threads, not just the thread being measured. Process
  CPU has clock-tick resolution, so take each fork's total CPU over all
  measured rounds divided by its total operations, not a per-round median.
- **Pinning.** Most runs are pinned with `taskset` to four CPUs on distinct
  physical cores, skipping cores 0 and 1, so on SMT hosts the sibling hardware
  threads stay idle. The carrier-scaling yield runs, including the one-carrier
  one, and the saturated run use every CPU.
- **Forks and order.** Six forks per variant. Run all variants as one block,
  and reverse the order in alternate blocks, so slow host drift does not favor
  any variant.
- **Statistics.** Each fork contributes the median of its rounds. Report the
  median of fork medians, the min-max across forks, and the difference from
  the reference variant with a 95% Welch interval on the means.

## Checks

Before trusting a run, confirm:

- The test library counts exactly one callback per transition in the event
  variants.
- Each variant confirms its activation before measuring: the capability is
  held, events fire, `isSupported()` is true, and the agent marker is present
  exactly when expected.
- Mounts and unmounts are at least the number of yields when tracked.
- A busy spin's mounted time grows while it runs and never exceeds its wall
  time. It is carrier CPU time, so it is not comparable to `System.nanoTime()`
  beyond that bound; with three carriers per core the summed mounted time of
  spinning threads must stay within the process CPU time, which
  `TestNativeLibraryClassLoader` checks in its own JVM.
- Rounds with JIT compilation or GC activity are recorded, and each fork's
  second half of rounds is compared with its first half for drift.

## Pitfalls

- **Do not divide aggregate wall time across carriers.** An earlier README
  figure divided total wall time by the number of threads times iterations on
  the default carrier pool. That is throughput spread across carriers, not the
  cost of one cycle.
- **Any JVMTI environment changes the baseline.** The slow path comes from
  creating a JVMTI environment, not from holding a capability or enabling
  events, so profilers and agents on the benchmark host invalidate the
  `baseline` variant.
- **Expect noise on c8i.** Event-enabled forks stepped between two levels
  within a fork; the Intel ranges are wider for that reason.
- **Multi-carrier baselines drift on Graviton.** Compare scaling results only
  within one session.
- **Timed park and TCP on c8g.** Both showed 10–15% added CPU on c8g against
  1–5% on the other hosts; for timed park, wall time barely changed. This was
  not explained.

## November 2026: hooks retransformed into VirtualThread

Release 1.2 replaced the JVMTI mount and unmount events with calls retransformed
into `java.lang.VirtualThread.mount()`, `unmount()` and `run(Runnable)`, guarded
by an inline null check of the thread's thread-local map, and made mounted time
the carrier's CPU time, read with `CLOCK_THREAD_CPUTIME_ID` at each mount and
unmount of a registered thread. The JVMTI environment remains and with it
HotSpot's transition slow path. The events stay as the fallback and as
`-Dio.airlift.vthreadtime.mode=events`.

Measured in a Linux aarch64 virtual machine:

- Apple M5 Max host, macOS, OrbStack VM with 4 vCPUs, kernel
  7.2.12-orbstack
- Ubuntu 26.04 container, Temurin 25.0.5, gcc 15.2,
  `-Xms1g -Xmx1g -XX:+AlwaysPreTouch`
- The yield loop above with one carrier, 10 warmup and 20 measured rounds of
  1,000,000 yields, three forks per variant run back to back on an otherwise
  idle host, no pinning
- `accessor` is one registered thread calling `mountedTimeNanos()` in a loop,
  `count` the same for `mountCount()`, `nanotime` a `System.nanoTime()` loop as
  a reference, all 1,000,000 calls per round; `register` is 100,000 short-lived
  virtual threads per round, each calling `register()` and `close()`, including
  thread start and join, on the default scheduler

`hooks + flag` adds
`-XX:+UnlockExperimentalVMOptions -XX:-DoJVMTIVirtualThreadTransitions`. Cells
are the median of rounds with the minimum and maximum in parentheses, one per
fork, in ns per operation:

| Variant | events | hooks | hooks + flag |
| --- | ---: | ---: | ---: |
| `baseline` | 70.6 (69.2–79.4), 70.1 (69.7–72.0), 70.0 (69.8–71.4) | 69.6 (69.3–70.4), 68.7 (68.4–69.5), 69.4 (68.0–70.8) | 72.2 (69.5–76.7), 73.3 (70.9–77.1), 71.5 (70.0–76.5) |
| `initialized` | 152.6 (151.3–159.6), 150.6 (149.1–159.2), 150.4 (149.9–151.5) | 101.8 (101.0–103.6), 100.6 (99.7–101.4), 102.6 (101.1–107.4) | 74.8 (72.1–79.0), 70.8 (68.7–74.3), 72.3 (69.8–74.6) |
| `tracked` | 426.5 (425.1–431.9), 427.7 (426.0–433.9), 438.1 (436.5–446.7) | 389.9 (388.1–396.3), 386.4 (382.6–401.2), 408.9 (390.7–427.1) | 355.0 (351.4–370.2), 361.1 (357.2–377.2), 355.1 (351.5–368.2) |
| `accessor` | 138.4 (137.1–138.9), 138.2 (136.3–141.4), 137.6 (135.3–139.0) | 142.2 (137.0–153.5), 142.7 (137.7–146.9), 143.4 (138.3–148.9) | 138.0 (136.5–142.8), 137.7 (136.4–145.4), 138.0 (136.2–145.2) |
| `count` | 0.23, 0.23, 0.23 | 1.02, 1.00, 1.02 | 1.15, 1.03, 1.10 |
| `nanotime` | 11.4 (11.4–11.5), 11.5 (11.4–11.8), 11.5 (11.4–11.5) | 11.6 (11.5–12.4), 12.0 (11.5–13.0), 11.5 (11.5–11.6) | 11.5 (11.5–12.0), 11.6 (11.5–12.5), 11.5 (11.5–12.3) |
| `register` (ns/thread) | 665 (612–705), 617 (563–697), 631 (574–678) | 332 (226–408), 306 (287–429), 316 (310–453) | 281 (271–400), 297 (232–425), 252 (234–302) |

Taking the median fork, an unregistered thread pays `initialized - baseline` =
80 ns per cycle with the events, 32 ns with the hooks and 1 ns with the hooks
and the flag. A registered thread pays `tracked - initialized` = 277, 288 and
283 ns on top of that in the three variants: two reads of the carrier CPU
clock, which is a system call of about 133 ns in this VM (the monotonic vDSO
clock reads in 11 ns), and a few nanoseconds of bookkeeping. `accessor` is one
more read of that clock, so it costs about 140 ns in every variant. The hooks
halve the cost of a short-lived registered thread, because no JVMTI thread
state is created and freed under the global lock. `count` went from a plain
segment load to a method handle call, 0.2 to 1 ns.

The same loop measured with the monotonic clock instead of the carrier CPU
clock, in an earlier run of the same session, gave `tracked` of 158, 106 and
75 ns and `accessor` of 7 ns in the three variants: the CPU clock, not the
mechanism, sets the cost of a registered thread.

Not measured: Graviton and Intel; the x86_64 build (compiled and its unit tests
run under emulation only); JVMs with CDS or AOT archives of `VirtualThread`;
JVMs where another agent retransforms `VirtualThread` (the hooks survive a
retransformation from the same input and are reported as removed otherwise,
which is tested, but the cost of the class file load hook that stays enabled
for this is not measured).
