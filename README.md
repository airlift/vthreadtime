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

Mounted time includes only intervals when the virtual thread is mounted on a
carrier. Time spent unmounted while sleeping, parking, or waiting for I/O is
excluded. A tracker is confined to the virtual thread that registered it.

## Runtime overhead

After the first successful initialization:

- **Unregistered threads:** Mount, unmount, and thread-end callbacks are enabled
  for every virtual thread until the JVM exits. An unregistered thread performs
  a fast lookup and returns. In a deliberately zero-work `Thread.yield()` loop,
  this adds about 75–160 ns per mount/unmount cycle on a JVM that already runs
  a JVMTI agent, as most monitored production JVMs do: APM agents, profilers,
  and jvmkill in Trino's Docker image all load one. On a JVM with no agent it
  adds about 150–240 ns, because the first JVMTI environment also moves
  HotSpot's virtual-thread transitions to a slower path. There is no useful
  universal percentage: it was indistinguishable from noise in
  production-shaped tiny-query tests, and its relative cost shrinks as useful
  work between transitions grows. It is most visible in transition-only loops.
  Merely adding the library to the class path or module path has no effect.

- **Memory:** On HotSpot JDK 25, assume about 266 bytes of additional native
  memory for every live virtual thread after initialization, whether or not it
  is registered. The memory is released when the thread ends. This is a
  measured JVM-specific value, not a guaranteed size.

- **Registered threads:** The cost has two parts:
  - A `mountedTimeNanos()` begin/end pair adds about 46–68 ns per measured
    operation, about the same as two `System.nanoTime()` calls. A
    platform-thread CPU-time begin/end pair adds about 322–399 ns per
    operation, but does not add work at parks.
  - Virtual tracking also adds about 45–70 ns at each mount/unmount cycle.
    In a synthetic one-carrier blocked-handoff benchmark, where a platform
    thread wakes a virtual thread, the library with tracking added about
    0.13 us of process CPU per park on Graviton and was within noise on Intel.
    That run had no agent, so it includes the slower JVM path.

  The park cost must be compared with its baseline. In that same benchmark:

  | Host | Park CPU without the library | Added with tracking |
  | --- | ---: | ---: |
  | AWS Graviton5 | 4.5 us | 0.13 us |
  | AWS Graviton4 | 5.9 us | 0.14 us |
  | AWS Intel | 7.2 us | within noise |

  This deliberately transition-heavy workload performed almost no useful work
  between parks, so the library added only about 2–3% to process CPU. These
  are measured workload-specific costs, not intrinsic `park()` constants.

- **Pathological transition rates:** The problematic cases are loops that
  repeatedly call `Thread.yield()` or `parkNanos()` with almost no useful work
  between transitions. In a stress test where two virtual threads did nothing
  but wake each other on one carrier, the library with tracking doubled to
  tripled the CPU per operation. The library exposes `mountCount()` and
  `unmountCount()` so integrations can detect this pattern. The tested Trino
  prototype stopped detailed timing after 50 unmounts with less than 2 ms of
  mounted time and fell back to wall time. That policy is not automatic or
  part of this library; the JVM-wide callbacks remain enabled.

These effects were measured with Corretto 25.0.4.1 on AWS Graviton5,
Graviton4, and Intel machines, as described in
[Measuring runtime overhead](docs/measuring-overhead.md).

The global callbacks are a deliberate JDK 25 tradeoff. Enabling and disabling
notifications per registered thread reduced throughput by about 95% in
short-lived one-shot-split and tiny-query workloads because JDK 25 globally
synchronizes every notification change. The one-shot drivers did not unmount
after registration, so the collapse came from registration itself rather than
callback processing. We accept the smaller JVM-wide cost and should re-evaluate
this choice if a future JVM implements per-thread notifications differently.

## Requirements

Virtual Thread Time requires a HotSpot-based Java 25 JVM exposing the JVMTI
virtual-thread mount events.

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
