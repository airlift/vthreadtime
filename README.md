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
behalf of the virtual thread is included. A tracker is confined to the virtual
thread that registered it.

## Mechanism

At initialization the library creates a JVMTI environment and uses it once to
retransform `java.lang.VirtualThread`, injecting a call into its `mount()`,
`unmount()` and `run(Runnable)` methods. The call is guarded by an inline null
check of the thread's thread-local map, so a virtual thread that has never used
a thread local pays only that check, and a registered thread finds its tracker
through a thread local and stamps it with the carrier's CPU clock. The hook
classes are defined into the bootstrap class
loader, which is the only loader `VirtualThread` can call into. No per-thread
JVMTI state is created. The only JVMTI event left enabled is the class file
load hook, which costs a name comparison per class loaded afterwards: HotSpot
rebuilds a retransformed class from its original bytes and consults only the
environments with that event enabled, so the library serves the hooked bytes
again whenever another agent later retransforms `VirtualThread` from the same
input, and the hooks survive. If the input differs, because another agent
redefined the class or an agent registered before the library now changes it,
the hooks cannot be reapplied; the library then reports them removed and
`register()` throws `IllegalStateException` rather than measuring nothing.

The retransformation depends on the structure of `VirtualThread` in JDK 25.
When it cannot be applied, the library falls back to the JVMTI virtual-thread
mount and unmount events, which measure the same thing at a higher cost per
transition. The system property `io.airlift.vthreadtime.mode` selects `hooks`
(the default) or `events` explicitly. The first initialization fixes the mode
for the process.

Creating any JVMTI environment, this library's included, makes HotSpot take a
slower path at every virtual-thread transition for the rest of the process.
APM agents, profilers and debuggers pay the same path, so a JVM that already
runs one adds nothing here. The experimental flag
`-XX:+UnlockExperimentalVMOptions -XX:-DoJVMTIVirtualThreadTransitions`
removes it, at the price of JVMTI no longer reporting virtual threads to any
agent or debugger in that JVM; the hooks do not need those events and then
measure at close to the cost of a bare transition.

## Runtime overhead

After the first successful initialization, in the default hooks mode:

- **All virtual threads:** Every mount and unmount runs HotSpot's JVMTI
  transition path and the injected null check. In a deliberately zero-work
  `Thread.yield()` loop on a Linux aarch64 virtual machine this added about
  32 ns to a 70 ns mount/unmount cycle, against about 80 ns for the events
  mode in the same run, and about 1 ns with the transitions flag above. There
  is no useful universal percentage: the cost is fixed per transition, so its
  relative weight shrinks as useful work between transitions grows, and it is
  most visible in transition-only loops. Merely adding the library to the class
  path or module path has no effect.

- **Memory:** No native memory per thread. A virtual thread that uses thread
  locals but is not registered gets one empty thread-local entry of about 40
  bytes on its first mount.

- **Registered threads:** The hooks read the carrier thread's CPU clock at
  each mount and unmount, and `mountedTimeNanos()` reads it once more. On Linux
  that clock is not served from the vDSO, so each read is a system call, about
  130 ns in the same virtual machine against 11 ns for the monotonic clock.
  A registered thread therefore pays about 290 ns more per cycle than an
  unregistered one, in both modes, a `mountedTimeNanos()` call costs about
  140 ns, and registering and closing a short-lived thread about 316 ns
  including its start and join, against about 631 ns with the events.

- **Pathological transition rates:** The problematic cases remain loops that
  repeatedly call `Thread.yield()` or `parkNanos()` with almost no useful work
  between transitions, where the fixed cost per transition dominates. The
  library exposes `mountCount()` and `unmountCount()` so integrations can detect
  this pattern.

In the events mode, used as the fallback or when configured, mount, unmount and
thread-end callbacks are enabled for every virtual thread until the JVM exits,
HotSpot delivers each event through a JNI handle block and a native transition,
and it allocates about 266 bytes of native memory for every live virtual thread
at its first event. Measured with the monotonic clock before release 1.2, on
Corretto 25.0.4.1 and AWS Graviton5, Graviton4 and Intel hosts, that cost an
unregistered thread 75–160 ns per cycle on a JVM that already ran a JVMTI
agent and 150–240 ns on one that did not, a registered thread 45–70 ns per
cycle more, and a `mountedTimeNanos()` begin/end pair 46–68 ns. In a
one-carrier blocked-handoff benchmark it added about 0.13 us of process CPU
per park on Graviton and was within noise on Intel:

  | Host | Park CPU without the library | Added with tracking |
  | --- | ---: | ---: |
  | AWS Graviton5 | 4.5 us | 0.13 us |
  | AWS Graviton4 | 5.9 us | 0.14 us |
  | AWS Intel | 7.2 us | within noise |

The events are enabled globally because enabling them per registered thread
reduced throughput by about 95% in short-lived workloads: JDK 25 synchronizes
every notification change across all virtual-thread transitions.

All of these measurements are described in
[Measuring runtime overhead](docs/measuring-overhead.md), including the
November 2026 hooks measurements on a Linux aarch64 virtual machine. Graviton
and Intel have not been re-measured with the hooks or the carrier CPU clock.

## Requirements

Virtual Thread Time requires a HotSpot-based Java 25 JVM. The hooks depend on
the structure of `java.lang.VirtualThread` in JDK 25; a JVM where the
retransformation does not apply must expose the JVMTI virtual-thread mount
events for the fallback.

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
artifacts target glibc-based distributions and dynamically link the host's
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
when the default `java.io.tmpdir` does not permit executable mappings. Native
library and temporary-directory properties must be configured before the first
use of Virtual Thread Time and must not be changed afterward; this includes
`java.io.tmpdir` when no custom extraction directory is configured.

All class loaders rendezvous through a predictable process-specific directory
inside the configured extraction root. The loader rejects a precreated
rendezvous directory owned by another user, so simple predictable-name
squatting fails closed instead of loading attacker-supplied code. On a shared
host, however, another local user who can create entries in the extraction root
can cause initialization to fail for the lifetime of the JVM. Applications that
do not trust other local users must configure `io.airlift.vthreadtime.tmpdir` to
a directory owned by the application user with `0700` permissions.

## Building

A C23 compiler and a Java 25 JDK are required:

```bash
./mvnw clean install
```
