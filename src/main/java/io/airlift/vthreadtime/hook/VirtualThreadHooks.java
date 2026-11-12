/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.vthreadtime.hook;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;

/// Entry points called by the bytecode injected into `java.lang.VirtualThread`, and the trackers
/// they update.
///
/// [#mounted()] and [#unmounted()] run on every mount and unmount of every virtual thread in
/// the JVM. They are allocation-free for registered threads and only touch a tracker when the
/// current thread has registered one through [#register()]. They never throw:
/// `ThreadLocal.get()` on a miss inserts an entry into the map of a thread that has thread locals
/// but no tracker, and an `OutOfMemoryError` escaping from inside `VirtualThread.mount()` or
/// `unmount()` would strand the virtual thread with its JVMTI transition open.
///
/// A tracker is a `long[]` laid out by the index constants below rather than an object of its own,
/// so that this is the only class defined into the bootstrap loader. Only the owning thread writes
/// it, on whichever carrier it is mounted; the scheduler handoff orders carrier changes, so no
/// volatile access is needed. The methods on the mount and unmount path are kept tiny so that the
/// JIT inlines them into `VirtualThread.mount()` and `unmount()`.
public final class VirtualThreadHooks
{
    private static final int MOUNTED_NANOS = 0; // total of the closed intervals
    private static final int MOUNT_STARTED_NANOS = 1; // stamp of the open interval
    private static final int MOUNT_COUNT = 2;
    private static final int UNMOUNT_COUNT = 3;
    private static final int INTERVAL_OPEN = 4; // 1 while mounted
    private static final int TRACKER_SIZE = 5;

    private static final MethodType CLOCK_TYPE = methodType(long.class);
    private static final ThreadLocal<long[]> TRACKER = new ThreadLocal<>();
    private static final MutableCallSite CLOCK_SITE = new MutableCallSite(systemNanoTime());
    private static final MethodHandle CLOCK = CLOCK_SITE.dynamicInvoker();
    private static final Object CLOCK_LOCK = new Object();
    private static boolean clockInstalled;

    // Set once by the first registration and never cleared. Until then no thread has a tracker,
    // so the hooks skip the thread-local lookup, which on a miss would add an entry to the map of
    // every virtual thread that uses thread locals. A volatile read is a plain load on x86 and
    // one acquiring load on aarch64, next to nothing on the hook path.
    private static volatile boolean anyThreadRegistered;

    private VirtualThreadHooks() {}

    /// Called on the virtual thread right after it has been mounted on a carrier.
    public static void mounted()
    {
        if (!anyThreadRegistered) {
            return;
        }
        try {
            long[] tracker = TRACKER.get();
            if (tracker != null) {
                tracker[MOUNT_STARTED_NANOS] = now();
                tracker[MOUNT_COUNT]++;
                tracker[INTERVAL_OPEN] = 1;
            }
        }
        catch (Throwable ignored) {
            // see the class comment
        }
    }

    /// Called on the virtual thread right before it is unmounted from its carrier.
    public static void unmounted()
    {
        if (!anyThreadRegistered) {
            return;
        }
        try {
            long[] tracker = TRACKER.get();
            if (tracker != null) {
                tracker[MOUNTED_NANOS] += openIntervalNanos(tracker);
                tracker[UNMOUNT_COUNT]++;
                tracker[INTERVAL_OPEN] = 0;
            }
        }
        catch (Throwable ignored) {
            // see the class comment
        }
    }

    /// Registers the current thread and starts measuring its mounted time.
    /// The thread is mounted while it runs this method, so the returned tracker
    /// already counts one open mounted interval.
    ///
    /// @throws IllegalStateException if the current thread is already registered
    public static long[] register()
    {
        if (TRACKER.get() != null) {
            throw new IllegalStateException("current thread is already registered");
        }
        anyThreadRegistered = true;
        long[] tracker = new long[TRACKER_SIZE];
        tracker[MOUNT_STARTED_NANOS] = now();
        tracker[MOUNT_COUNT] = 1;
        tracker[INTERVAL_OPEN] = 1;
        TRACKER.set(tracker);
        return tracker;
    }

    /// Unregisters the current thread if `tracker` is the one it registered, ending the open
    /// interval and freezing the totals. Any other call, including a repeated one or a call from
    /// a thread that does not own the tracker, does nothing.
    @SuppressWarnings("ReferenceEquality") // the registered array itself, not an equal one
    public static void unregister(long[] tracker)
    {
        if (tracker == null || TRACKER.get() != tracker) {
            return;
        }
        // Keeping the entry, now null, means the thread's later transitions find nothing to do
        // without allocating a new entry on the miss.
        TRACKER.set(null);
        if (tracker[INTERVAL_OPEN] != 0) {
            tracker[MOUNTED_NANOS] += openIntervalNanos(tracker);
            tracker[INTERVAL_OPEN] = 0;
        }
    }

    /// Discards the open mounted interval of `tracker`, if any. Called by the owning thread once
    /// the hooks have been removed from `VirtualThread`: the unmount that would end the interval
    /// will never be stamped, and closing it on whichever carrier runs the thread now would charge
    /// that carrier's clock, so the interval is dropped and the totals stay where they were.
    public static void dropOpenInterval(long[] tracker)
    {
        tracker[INTERVAL_OPEN] = 0;
    }

    /// Returns the cumulative mounted time of `tracker` since registration, in nanoseconds,
    /// including the mounted interval that is still open.
    public static long mountedTimeNanos(long[] tracker)
    {
        if (tracker[INTERVAL_OPEN] == 0) {
            return tracker[MOUNTED_NANOS];
        }
        return tracker[MOUNTED_NANOS] + openIntervalNanos(tracker);
    }

    /// Returns the number of mounted intervals of `tracker` since registration.
    /// The interval active at registration is included.
    public static long mountCount(long[] tracker)
    {
        return tracker[MOUNT_COUNT];
    }

    /// Returns the number of unmounts of `tracker` since registration.
    public static long unmountCount(long[] tracker)
    {
        return tracker[UNMOUNT_COUNT];
    }

    /// Installs the clock that stamps mounts and unmounts. The handle must take no arguments
    /// and return the current time in nanoseconds. The first call wins: it installs the
    /// clock and returns `true`; every later call is ignored and returns `false`. The default
    /// clock is [System#nanoTime()].
    ///
    /// Call this before any thread registers: trackers stamp intervals with whichever clock
    /// is installed at the time, and readings from different clocks cannot be combined.
    ///
    /// @throws IllegalArgumentException if the handle does not have type `()long`
    public static boolean setClock(MethodHandle clock)
    {
        requireNonNull(clock, "clock is null");
        if (!clock.type().equals(CLOCK_TYPE)) {
            throw new IllegalArgumentException("clock must have type " + CLOCK_TYPE + " but has " + clock.type());
        }
        // Under one lock so that a loser returns only after the winner's clock is in place, and
        // its first registration cannot stamp with the default clock
        synchronized (CLOCK_LOCK) {
            if (clockInstalled) {
                return false;
            }
            clockInstalled = true;
            CLOCK_SITE.setTarget(clock);
            MutableCallSite.syncAll(new MutableCallSite[] {CLOCK_SITE});
            return true;
        }
    }

    /// The length of the open interval on the installed clock. A zero stamp is a mount at which
    /// the clock failed, so there is no interval to measure, and a clock that fails now or runs
    /// backwards contributes nothing rather than the carrier's whole CPU time.
    private static long openIntervalNanos(long[] tracker)
    {
        long started = tracker[MOUNT_STARTED_NANOS];
        if (started == 0) {
            return 0;
        }
        return Math.max(0, now() - started);
    }

    /// Reads the installed clock. Never throws: the call site is constant, so the JIT inlines
    /// the clock into the hooks, and a failing clock reads as zero instead of unwinding
    /// through `VirtualThread.mount()`.
    static long now()
    {
        try {
            return (long) CLOCK.invokeExact();
        }
        catch (Throwable ignored) {
            return 0;
        }
    }

    private static MethodHandle systemNanoTime()
    {
        try {
            return MethodHandles.lookup().findStatic(System.class, "nanoTime", CLOCK_TYPE);
        }
        catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
