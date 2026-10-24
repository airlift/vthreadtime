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
- `mountedTimeNanos()` agrees with a busy-spin of known length, and the native
  clock matches `System.nanoTime()`.
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
