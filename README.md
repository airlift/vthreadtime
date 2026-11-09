# Virtual Thread Time

Virtual Thread Time measures virtual thread mounted time and scheduling activity
on the JVM.

## Usage

Check support and initialize the library once during application startup:

```java
if (VirtualThreadTime.isSupported()) {
    VirtualThreadTime.initialize();
}
```

Report whether the hooks are measuring, since trackers read as zero in an AOT
cache training run and freeze if another agent removes the hooks:

```java
if (VirtualThreadTime.isSupported()) {
    log.info("Virtual thread time status: %s", VirtualThreadTime.status());
}
```

Register and use a tracker from the virtual thread being measured:

```java
try (VirtualThreadTime time = VirtualThreadTime.register()) {
    long mountedStart = time.mountedTimeNanos();
    long mountsStart = time.mountCount();

    // Run virtual-thread work.

    long mountedNanos = time.mountedTimeNanos() - mountedStart;
    long mounts = time.mountCount() - mountsStart;
}
```

Mounted time is the CPU time of the carrier thread while the virtual thread is
mounted on it. Time spent unmounted while sleeping, parking, or waiting for I/O
is excluded. So is time the carrier spends off the CPU while the virtual thread
is mounted: waiting in the OS run queue when the scheduler has more carriers
than cores, or blocked while the virtual thread is pinned. Kernel time spent on
behalf of the virtual thread is included, and so is steal time a hypervisor
reports, on Linux carriers that read the perf task-clock event described below.
A tracker is confined to the virtual thread that registered it.

## Mechanism

At initialization the library creates a JVMTI environment and uses it once to
retransform `java.lang.VirtualThread`, injecting a call into its `mount()` and
`unmount()` methods. The call is guarded by an inline null
check of the thread's thread-local map, so a virtual thread that has never used
a thread local pays only that check, and a registered thread finds its tracker
through a thread local and stamps it with the carrier's CPU clock. The hook
class is defined into the bootstrap class loader, which is the only loader
`VirtualThread` can call into. No per-thread JVMTI state is created.

The only JVMTI event left enabled is the class file load hook, which costs a
name comparison per class loaded afterwards, and 3.7–6.2 µs for each class
loaded from a CDS archive after `initialize()` (AppCDS, or an AOT cache with
class linking off), because HotSpot re-reads the archived bytes to post the
event: roughly 110–185 ms for 30,000 classes. With the AOT cache's default
class linking it costs nothing, since those classes are loaded before `main`.
The event stays on because HotSpot rebuilds a retransformed class from its
original bytes and consults only the environments with that event enabled: the
library serves the hooked bytes again whenever another agent later retransforms
`VirtualThread` from the same input, and the hooks survive. If the input
differs, because another agent redefined the class or an agent registered
before the library now changes it, the hooks cannot be reapplied; the library
then reports `Status.HOOKS_REMOVED`, and trackers keep the totals of the
intervals that were closed by then and count nothing new.

The retransformation depends on the structure of `VirtualThread` in JDK 25.
When it cannot be applied, `initialize()` throws `UnsupportedOperationException`
whose cause names the step that failed, and `isSupported()` returns `false`;
the failure holds for the process.

A JVM recording an AOT cache (`-XX:AOTCacheOutput` or `-XX:AOTMode=record`)
does not get the hooks: retransforming `VirtualThread` would exclude it from
the cache as redefined, HotSpot refuses to write a cache without that critical
class, and the training run would exit with a failure while a production run
with `-XX:AOTMode=auto` would silently start without any class sharing. In such
a run `initialize()` succeeds, `status()` reports `AOT_TRAINING` and trackers
count nothing. A cache trained this way works with the hooks at run time.

Creating any JVMTI environment, this library's included, makes HotSpot take a
slower path at every virtual-thread transition for the rest of the process.
APM agents, profilers and debuggers pay the same path, so a JVM that already
runs one adds nothing here. The experimental flag
`-XX:+UnlockExperimentalVMOptions -XX:-DoJVMTIVirtualThreadTransitions`
removes it, at the price of JVMTI no longer reporting virtual threads to any
agent or debugger in that JVM; the hooks do not need those events and then
measure at close to the cost of a bare transition.

### Carrier CPU clock on Linux

On Linux, reading a thread's CPU clock is a system call, not a vDSO read. Each
carrier therefore opens a perf task-clock event for itself the first time it
reads its clock, maps the event's page, and from then on reads the clock from
that page without a system call. The event counts the time the carrier is
scheduled on a CPU, including kernel time and, where the hypervisor reports it,
steal time, which the system call leaves out. Each such carrier holds one perf
file descriptor and one page charged to the user's perf locked-memory limit
until its thread exits.

A carrier can use the perf task-clock event only when:

- it may open the event: `perf_event_paranoid` at 2 or lower, as on Amazon
  Linux 2023, `CAP_PERFMON` on Ubuntu 24.04, or `CAP_SYS_ADMIN` on the tested
  Debian 12 kernel;
- the kernel publishes timing data for user-space extrapolation
  (`cap_user_time`). Graviton does; x86 KVM guests using kvm-clock, the EC2
  Intel default, do not; Intel with a native scheduler clock, on bare metal or
  booted with `no-kvmclock`, can;
- no seccomp filter applies to it, by default. Some filters kill the process
  instead of failing the call, such as systemd's `SystemCallFilter=` without
  `SystemCallErrorNumber=`, so a carrier tries the event only when its
  `/proc/thread-self/status` reports no seccomp filter. With
  `io.airlift.vthreadtime.perf.clock=on`, a filter that allows
  `perf_event_open` also works, such as Docker's default profile with
  `--cap-add PERFMON`.

After the first carrier fails to set up the event, no later carrier tries.
Carriers without an event read the thread CPU clock with a system call. The
property `io.airlift.vthreadtime.perf.clock`, described under
[Dependency](#dependency), selects whether carriers try the event.

The two clocks do not agree exactly. Measured on AWS Graviton4, the perf
task-clock event differed from the thread CPU clock by about 0.2–0.3 µs per
time-slice switch, and read up to about 0.85 µs per switch lower under
frequent wakeup preemption: 0.8% of the CPU time of a thread sharing its CPU
with a task that woke every 50 µs. If native code on a carrier disables its
perf events with `prctl(PR_TASK_PERF_EVENTS_DISABLE)`, later readings on that
carrier also count time off the CPU, which the library cannot detect.

## Runtime overhead

After the first successful initialization:

- **All virtual threads:** Every mount and unmount runs HotSpot's JVMTI
  transition path and the injected null check. Measured on EC2 in November 2026
  with a zero-work `Thread.yield()` loop, a JVMTI agent attached (a server that
  monitors thread usage already runs one) and an OpenTelemetry context current
  so the measured thread has thread locals and the null check does not skip the
  call, the hooks add per mount/unmount cycle:

  | JDK 25 / 27 | Graviton5 (c9g) | Graviton4 (c8g) | Intel (c7i) | Intel Xeon 6 (c8i) |
  | --- | ---: | ---: | ---: | ---: |
  | Hooks | +7 / +7 ns | +9 / +6 ns | +14 / +7 ns | +9 / +10 ns |
  | JVMTI mount and unmount events (release 1.1) | +69 / +92 ns | +92 / +119 ns | +121 / +117 ns | +132 / +126 ns |

  HotSpot's JVMTI slow path itself costs 46–90 ns per cycle on these hosts,
  which any agent pays. There is no useful universal percentage: the cost is
  fixed per transition, so its relative weight shrinks as useful work between
  transitions grows, and it is most visible in transition-only loops. Merely
  adding the library to the class path or module path has no effect.

- **Memory:** No native memory per virtual thread. A virtual thread that uses
  thread locals but is not registered gets one empty thread-local entry of
  about 40 bytes on its first mount. On Linux, each carrier that reads the perf
  task-clock event also holds a small allocation, a file descriptor and a
  locked page until its thread exits.

- **Registered threads:** The hooks read the carrier thread's CPU clock at
  each mount and unmount, and `mountedTimeNanos()` reads it once more. On hosts
  where carriers read the perf task-clock event, such as AWS Graviton, this
  costs a fraction of the system call. On Graviton5 in November 2026, against
  the same build with the event turned off:

  | Graviton5 (c9g) | Perf task-clock event | System call |
  | --- | ---: | ---: |
  | Tracking, per mount/unmount cycle | 56 ns | 327 ns |
  | `mountedTimeNanos()` call | 31 ns | 167 ns |
  | Tracking, per park in the blocked-handoff benchmark | not measurable | 0.1 µs |

  Where carriers read the thread CPU clock with a system call, as on AWS Intel
  with kvm-clock, tracking costs about 340–400 ns per cycle and a
  `mountedTimeNanos()` call about 150 ns. On EC2, registering and closing a
  short-lived virtual thread costs 0.5–0.9 µs of CPU, against about 3 µs with
  the events of release 1.1.

- **Pathological transition rates:** The problematic cases remain loops that
  repeatedly call `Thread.yield()` or `parkNanos()` with almost no useful work
  between transitions, where the fixed cost per transition dominates. The
  library exposes `mountCount()` and `unmountCount()` so integrations can detect
  this pattern.

All of these measurements are described in
[Measuring runtime overhead](docs/measuring-overhead.md), including the
November 2026 perf task-clock measurements on AWS Graviton5, Graviton4 and
Intel and the release 1.1 numbers for the JVMTI events the hooks replaced.

## Requirements

Virtual Thread Time requires a HotSpot-based JVM, Java 25 or newer; the build
verifies 25, 26, 27 and 28-ea. The hooks depend on the structure of
`java.lang.VirtualThread`; on a JVM where the retransformation does not apply,
`initialize()` throws and `isSupported()` returns `false`. A runtime image
built with `jlink` must include the `jdk.management` module, which the library
reads the AOT options through.

Applications using the class path must enable native access for unnamed
modules:

```text
--enable-native-access=ALL-UNNAMED
```

Applications using the module path can grant native access specifically to
Virtual Thread Time:

```text
--enable-native-access=io.airlift.vthreadtime
```

Linux and macOS 15 or newer are supported on `aarch64` and `x86_64`. Linux
artifacts require glibc 2.17 or later and dynamically link the host's
standard C runtime libraries; musl-based distributions such as Alpine Linux
are not supported. Other operating systems and architectures are unsupported.
On those platforms, or when the native library cannot be loaded,
`VirtualThreadTime.isSupported()` returns `false`.

The Java API has no additional Maven runtime dependencies. The native host
runtime requirements above still apply.

## Dependency

Virtual Thread Time is published as `io.airlift:vthreadtime`.

```xml
<dependency>
    <groupId>io.airlift</groupId>
    <artifactId>vthreadtime</artifactId>
    <version>${vthreadtime.version}</version>
</dependency>
```

Release artifacts contain the native libraries for all supported platforms.
The appropriate library is extracted and loaded when Virtual Thread Time is
initialized. All class loaders in a JVM use the same extracted library, which
remains loaded until the JVM exits. Native initialization is also process-wide:
its first success or failure is retained until JVM exit, and later class
loaders do not retry. No `-agentpath` JVM option is required.

The system property `io.airlift.vthreadtime.library.path` can specify an
explicit native library instead of loading the packaged resource. The system
property `io.airlift.vthreadtime.tmpdir` can select the extraction directory
when the default `java.io.tmpdir` does not permit executable mappings. The
system property `io.airlift.vthreadtime.perf.clock` controls the perf
task-clock event on Linux: `auto`, the default, tries it only on carriers with
no seccomp filter; `on` also tries it under a seccomp filter, such as Docker's
default profile with `--cap-add PERFMON`, and must not be used where a filter
kills the process on `perf_event_open`; `off` never opens a perf event. With
`on`, a failure to open the event still falls back to the system call. Any other
value makes initialization fail on every platform, so
`VirtualThreadTime.isSupported()` returns `false`. These properties must be
configured before the first use of Virtual Thread Time and must not be changed
afterward; this includes `java.io.tmpdir` when no custom extraction directory
is configured.

All class loaders rendezvous through a predictable process-specific directory
inside the configured extraction root. The loader rejects a precreated
rendezvous directory owned by another user, so simple predictable-name
squatting fails closed instead of loading attacker-supplied code. On a shared
host, however, another local user who can create entries in the extraction root
can cause initialization to fail for the lifetime of the JVM. Applications that
do not trust other local users must configure `io.airlift.vthreadtime.tmpdir` to
a directory owned by the application user with `0700` permissions.

## Building

A C23 compiler, a Java 25 JDK, and on Linux, kernel headers 5.9 or later are
required:

```bash
./mvnw clean install
```

A library built this way requires the build host's glibc or newer. Release
builds compile the Linux libraries with the gcc of PyPA's manylinux2014 image,
which links against glibc 2.17, using the `perf_event.h` of Linux 6.12 because
the image's kernel headers are older. CI checks that each library loads on
CentOS 7; see `.github/workflows/native-build.yml`.
