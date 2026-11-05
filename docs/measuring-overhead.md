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
- `mountedTimeNanos()` agrees with a busy-spin of known length measured with
  `System.nanoTime()`, which also checks the tick-to-nanosecond conversion of
  the hardware counter. `TestVirtualThreadTime` asserts this within 1%.
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

## November 2026: hardware counters instead of the system clock

Release 1.2 replaced the two `clock_gettime` calls per mount/unmount cycle with
a raw counter read (`CNTVCTSS_EL0` or `CNTVCT_EL0` on aarch64, `rdtsc` on
x86_64 when the kernel's clocksource is `tsc`), converted to nanoseconds only
when `mountedTimeNanos()` is read, which became a single critical downcall. The
mount callback also returns at once while no thread is registered.

The change was measured on a Linux aarch64 virtual machine, not on Graviton:

- Apple M5 Max host, macOS, OrbStack VM with 4 vCPUs, kernel
  7.2.12-orbstack, clocksource `arch_sys_counter`, FEAT_ECV present,
  `CNTFRQ_EL0` 24 MHz as seen by the guest
- Ubuntu 26.04 container, Temurin 25.0.5, gcc 15.2,
  `-Xms1g -Xmx1g -XX:+AlwaysPreTouch`
- The yield loop above with one carrier, 10 warmup and 20 measured rounds of
  1,000,000 yields, three forks per variant run back to back, no pinning
- `accessor` is one registered thread calling `mountedTimeNanos()` in a loop,
  `count` the same for `mountCount()`, `nanotime` a `System.nanoTime()` loop as
  a reference, all 1,000,000 calls per round
- `register` is 100,000 short-lived virtual threads per round, each calling
  `register()` and `close()`, including thread start and join, on the default
  scheduler; its "before" run had a harness assertion that failed in some
  threads, so compare it loosely

Each cell is the median of the measured rounds, with the minimum and maximum
rounds in parentheses, one row per fork:

| Variant | Before (release 1.1) | After (release 1.2) |
| --- | ---: | ---: |
| `baseline` | 71.4 (68.8–73.1), 70.7 (69.2–76.1), 77.2 (72.2–83.8) | 81.2 (77.2–86.8), 73.9 (70.6–78.2), 72.6 (70.0–76.9) |
| `initialized` | 164.4 (161.8–174.5), 159.8 (155.6–173.2), 170.8 (163.6–181.6) | 158.8 (153.9–168.1), 162.5 (154.6–170.5), 160.4 (155.5–168.5) |
| `tracked` | 199.4 (192.1–207.0), 189.3 (185.9–202.6), 193.8 (186.3–205.6) | 178.3 (171.8–186.2), 178.0 (170.8–184.5), 178.5 (172.3–188.1) |
| `accessor` | 13.4 (12.1–13.9), 13.2 (12.1–13.9), 12.3 (12.0–13.2) | 13.1 (12.1–14.3), 13.2 (12.3–18.9), 13.2 (12.2–17.2) |
| `count` | 0.25, 0.25, 0.24 | 0.27, 0.28, 0.29 |
| `nanotime` | 11.7 (11.6–12.1), 12.1 (11.6–15.0), 11.9 (11.4–12.6) | 13.5 (12.0–15.6), 14.1 (12.0–15.4), 13.2 (11.5–14.4) |
| `register` (ns/thread) | 448 (420–545), 474 (403–674), 475 (401–583) | 590 (453–627), 468 (412–601), 465 (427–571) |

All values are nanoseconds per operation. Taking the median fork, tracking a
registered thread cost `tracked - initialized` = 29 ns per cycle before and
18 ns after; the `initialized` cycle, which is what every unregistered thread
pays, moved from 164 to 160 ns, within noise but in the direction expected from
skipping the lookup while nothing is registered. `accessor` did not change:
on this VM the critical downcall costs about what `System.nanoTime()` did, so
the cheaper counter read is cancelled out. `baseline`, `nanotime` and
`register` do not touch the changed code; their differences show the noise
level of this setup, about 10%.

The clock primitives themselves, 20,000,000 back-to-back calls in C in the same
VM, three rounds:

| Read | ns/call |
| --- | ---: |
| `clock_gettime(CLOCK_MONOTONIC)` (vDSO) | 11.7–11.9 |
| `clock_gettime(CLOCK_MONOTONIC_RAW)` | 12.2–12.7 |
| `mrs cntvct_el0` | 0.26–0.28 |
| `mrs cntvctss_el0` | 3.8–4.0 |
| `isb; mrs cntvct_el0` | 9.3–9.6 |

A harness that includes `vthreadtime.c` and calls its static clock functions
measured the library's `readTicks` at 3.9 ns and
`airlift_vthreadtime_mounted_nanos` at 6.1 ns in the VM, and its
tick-to-nanosecond conversion within 0.5 ppm of `CLOCK_MONOTONIC_RAW` over one
second.

The x86_64 path was compiled and run only under emulation on the same host.
There the kernel clocksource is `arch_sys_counter`, so the library correctly
declined the time stamp counter and used `clock_gettime`; forcing the
calibration in the harness converted within 0.4 ppm of `CLOCK_MONOTONIC_RAW`.
No x86_64 timing was taken, because emulated `rdtsc` costs are meaningless.
Re-measure on Graviton and Intel with the procedure above before quoting
per-cycle figures for those hosts.
