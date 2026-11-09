# Measuring runtime overhead

This describes how the numbers in the README's "Runtime overhead" section were
measured, so the measurement can be repeated when the JVM or the library
changes. The scripts were throwaway and are not checked in; this document is
the part worth keeping.

The sections up to "Pitfalls" describe the October 2026 measurement of the
JVMTI mount and unmount events that release 1.2 replaced; they are kept as the
method and the baseline. The current mechanism, hooks retransformed into
`VirtualThread`, is measured in the last section.

## Question

How much does the library add to each virtual-thread unmount/mount cycle, and
how much of that is HotSpot's JVMTI machinery rather than the library's own
callback? Then, how large is that cost next to real operations that unmount?

## Environment

The October 2026 measurements, of the JVMTI mount and unmount events that
release 1.2 replaced with hooks, used:

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
into `java.lang.VirtualThread.mount()` and `unmount()`, guarded by an inline
null check of the thread's thread-local map, and made mounted time
the carrier's CPU time, read with `CLOCK_THREAD_CPUTIME_ID` at each mount and
unmount of a registered thread. The JVMTI environment remains and with it
HotSpot's transition slow path. The events are gone: a JVM where the
retransformation does not apply fails `initialize()`.

### EC2

Measured on EC2 during the review of the change, on JDK 25 and 27, with the
yield loop above, a JVMTI agent attached, because a server that monitors thread
usage already runs one, and an OpenTelemetry context made current on the
measured thread, so that it has thread locals and the hooks do not skip at the
null check. The cost of turning JVMTI on at all is measured separately. Added
per mount/unmount cycle for an unregistered thread, JDK 25 / JDK 27:

| | Graviton5 (c9g) | Graviton4 (c8g) | Intel (c7i) | Intel Xeon 6 (c8i) |
| --- | ---: | ---: | ---: | ---: |
| Events | +69 / +92 ns | +92 / +119 ns | +121 / +117 ns | +132 / +126 ns |
| Hooks | +7 / +7 ns | +9 / +6 ns | +14 / +7 ns | +9 / +10 ns |

HotSpot's JVMTI slow path costs 46–90 ns per cycle on these hosts. Register
plus close drops from about 3 µs of CPU per thread with the events to
0.5–0.9 µs with the hooks. The class file load hook that stays enabled costs
3.7–6.2 µs per class loaded from a CDS archive after `initialize()` (AppCDS, or
an AOT cache with class linking off), roughly 110–185 ms for 30,000 classes,
and nothing with the AOT cache's default class linking, since those classes are
loaded before `main`. Alternatives ruled out in the same runs: a startup agent
cannot add a field to `VirtualThread`, which is loaded before `premain`, and a
scoped-value check would save 3–4 ns but no public `ScopedValue` lookup gives
the right answer at the hook points.

### Linux aarch64 virtual machine

Measured during development in a Linux aarch64 virtual machine, with the events
still present as a mode for comparison:

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

Not measured in the virtual machine: the x86_64 build (compiled and its unit
tests run under emulation only); the registered-thread numbers on EC2, which
the next section measures on Graviton and Intel; JVMs where another agent
retransforms `VirtualThread` (the hooks survive a retransformation from the
same input and are reported removed otherwise, which is tested, but not
timed).

## November 2026: perf task-clock event on AWS Graviton and Intel

On Linux the carrier CPU clock was a system call at every mount, unmount and
`mountedTimeNanos()` of a registered thread. Each carrier now reads it from a
perf task-clock event mapped into its memory, where the kernel publishes
`cap_user_time`, and falls back to the system call elsewhere. The README's
figures for registered threads are the Graviton5 and Intel ones.

Measured on 2026-11-11:

- AWS c8g.4xlarge (Graviton4, clocksource `arch_sys_counter`, so
  `cap_user_time`) and c8i.4xlarge (Xeon 6975P-C, kvm-clock, no
  `cap_user_time`, so the system call), 16 vCPUs each, us-west-2a
- Amazon Linux 2023, kernel 6.18.56, glibc 2.34, `perf_event_paranoid=2`
- Corretto 25.0.5.0.10, `-Xms1g -Xmx1g -XX:+AlwaysPreTouch
  --enable-native-access=ALL-UNNAMED`
- The perf version built with clang 22.1.8 targeting glibc 2.17; release
  builds now use the gcc 10 of the manylinux2014 image instead. The baseline,
  PR #40's head with the system call, built with gcc14 14.2.1. A gcc14 build
  of the perf version differed from the clang build by 2.5 ns per cycle and
  0.7 ns per query.
- Pinned with `taskset` to CPUs 2–5, on distinct physical cores; one carrier
  (`-Djdk.virtualThreadScheduler.parallelism=1
  -Djdk.virtualThreadScheduler.maxPoolSize=1`); six forks per variant, the
  order reversed in alternate blocks, statistics as above

The harness is one class using only the public API, `Bench`, kept with the
benchmark notes (`perf-task-clock-on-pr40-2026-11-11/harness/Bench.java`) and
not checked in. Each JVM printed the perf clock mode before measuring. The
workloads:

- `Bench yield initialized|tracked 10 20 1000000`: the yield loop above,
  process CPU over the measured rounds divided by yields.
- `Bench mnanos 5 15 1000000`: one registered thread calling
  `mountedTimeNanos()`, the median of per-round wall time per call.
- `Bench handoff tracked|initialized 5 10`: the blocked handoff above, 1 s
  rounds, process CPU per park, with a tracker open or with none.
- `Bench drift <threads> 3`: a registered virtual thread warms both clocks,
  reads `CLOCK_THREAD_CPUTIME_ID` through FFM and `mountedTimeNanos()`, spins
  for 3 s and reads both again, reporting mounted time minus carrier CPU time
  with the carrier's context switches from `/proc/thread-self/status`. Alone
  on CPU 2; four carriers on CPU 2 (time-slice preemption); sharing CPU 2 with
  a `python3` loop sleeping 50 µs (frequent wakeup preemption).

Before trusting a run, also confirm that `strace -f -c` over 100,000 tracked
yields shows about 11 `clock_gettime` calls and one `perf_event_open` with the
perf clock and about 200,000 `clock_gettime` calls without it, and that the
spin and three-carriers-per-core checks above pass.

Yield cycle, process CPU ns per yield, median of forks (min–max). `off` is the
same build with `-Dio.airlift.vthreadtime.perf.clock=off`:

| Host | Build | unregistered | registered | registered − unregistered |
| --- | --- | ---: | ---: | ---: |
| c8g | PR #40, system call | 275.8 (273.0–277.0) | 726.5 (716.5–732.5) | +450.8 |
| c8g | perf task-clock event | 275.0 (273.0–277.0) | 350.8 (348.0–353.0) | +75.8 |
| c8g | `off` | 278.0 (277.0–278.5) | 734.8 (731.5–758.0) | +456.8 |
| c8i | PR #40, system call | 357.5 (355.5–358.5) | 701.2 (679.0–737.0) | +343.8 |
| c8i | perf build, falls back | 363.0 (358.0–386.5) | 741.0 (724.0–787.5) | +378.0 |
| c8i | `off` | 359.5 (356.5–365.0) | 760.0 (719.5–785.5) | +400.5 |

The c8i registered forks stepped between levels, as before, so its builds are
not distinguishable.

`mountedTimeNanos()`, wall ns per call, median of forks: c8g 195.7 with the
system call, 34.3 with the perf task-clock event, 197.5 with `off`; c8i 148.4,
152.1 and 151.0.

Blocked handoff on c8g, process CPU µs per park: tracking added +0.801
[+0.515, +1.069] with the system call, +0.779 with `off`, and +0.008
[−0.101, +0.138] with the perf task-clock event, against the same build with
no tracker open. On c8i a park with no tracker cost about 1.1–1.5 µs more than
one with a tracker in every build, most likely a wakeup-timing effect of the
workload on that host rather than a cost of the library.

Drift on c8g, mounted time minus carrier CPU time over a 3 s spin: `off`
reads the same clock at both ends, so its few microseconds are the
measurement floor. With the perf task-clock event the difference was about
+0.26 to +0.29 µs per context switch alone on the CPU, about −0.2 to −0.3 µs
per switch net of the floor under time-slice preemption, and −0.85 to
−0.87 µs per switch under wakeup preemption: 28,500 switches, −24 ms, 0.8% of
the thread's CPU time. No reading was negative or above wall time.

### Graviton5

Measured on 2026-11-11 after the change was rebased onto PR #40's final head,
on AWS c9g.4xlarge (Graviton5, 16 vCPUs, clocksource `arch_sys_counter`, so
`cap_user_time`), and on a c8g.4xlarge just before it for comparison, one
instance each. Everything else is as above: the region, Amazon Linux 2023
kernel 6.18.56, Corretto, the clang build, pinning, the harness, six forks and
the statistics. Only the perf build was measured, with its default `auto` and
with `off`, since `off` had matched PR #40's system call within noise. Each
host passed the test suite with the perf tests enabled, and `strace` showed one
`perf_event_open` and 10 `clock_gettime` calls per 100,000 tracked yields with
`auto` against about 200,000 with `off`.

Yield cycle, process CPU ns per yield, and `mountedTimeNanos()`, wall ns per
call, median of forks (min–max); tracking is registered minus unregistered:

| Metric | `auto` c9g | `auto` c8g | `off` c9g | `off` c8g |
| --- | ---: | ---: | ---: | ---: |
| unregistered | 226.2 (224.5–227.5) | 275.0 (274.0–277.0) | 229.5 (227.5–231.0) | 278.8 (276.5–279.5) |
| registered | 282.5 (280.0–284.5) | 344.8 (342.5–348.0) | 556.0 (550.0–562.5) | 729.8 (722.0–757.0) |
| tracking | +56.2 | +69.8 | +326.5 | +451.0 |
| `mountedTimeNanos()` | 30.5 (30.5–30.5) | 32.3 (32.3–32.3) | 166.6 (165.8–167.0) | 196.8 (196.4–197.0) |

Blocked handoff, process CPU µs per park, tracking added against the same
build with no tracker open: on c9g −0.035 [−0.147, +0.200] with `auto` and
+0.110 [+0.012, +0.280] with `off`; on c8g −0.004 [−0.172, +0.100] and +0.710
[+0.415, +0.938].

Graviton5 makes the system call cheaper, so the event saves less there than on
Graviton4, 274 ns per cycle and 136 ns per `mountedTimeNanos()` against 385 and
165 ns, while the page read itself barely changes, 30.5 against 32.3 ns. The
handoff gap between the hosts comes from one instance each and is indicative
only.
