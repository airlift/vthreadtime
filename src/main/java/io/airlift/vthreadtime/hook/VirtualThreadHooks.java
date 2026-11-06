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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;

/// Entry points called by the bytecode injected into `java.lang.VirtualThread`.
///
/// [#mounted()] and [#unmounted()] run on every mount and unmount of every virtual thread in
/// the JVM, so they are allocation-free for registered threads, never throw, and only touch a
/// [MountedTime] when the current thread has registered one through [#register()].
public final class VirtualThreadHooks
{
    private static final MethodType CLOCK_TYPE = methodType(long.class);
    private static final ThreadLocal<MountedTime> TRACKER = new ThreadLocal<>();
    private static final MutableCallSite CLOCK_SITE = new MutableCallSite(systemNanoTime());
    private static final MethodHandle CLOCK = CLOCK_SITE.dynamicInvoker();
    private static final AtomicBoolean CLOCK_INSTALLED = new AtomicBoolean();
    private static final AtomicLong ACTIVE_REGISTRATIONS = new AtomicLong();

    private VirtualThreadHooks() {}

    /// Called on the virtual thread right after it has been mounted on a carrier.
    public static void mounted()
    {
        MountedTime tracker = TRACKER.get();
        if (tracker != null) {
            tracker.mounted();
        }
    }

    /// Called on the virtual thread right before it is unmounted from its carrier.
    public static void unmounted()
    {
        MountedTime tracker = TRACKER.get();
        if (tracker != null) {
            tracker.unmounted();
        }
    }

    /// Called on the virtual thread when its task has finished, normally or by exception.
    /// Drops the registration of the thread, if any, so that a tracker that was never
    /// unregistered does not stay counted as active.
    public static void ended()
    {
        MountedTime tracker = TRACKER.get();
        if (tracker != null) {
            TRACKER.remove();
            close(tracker);
        }
    }

    /// Registers the current thread and starts measuring its mounted time.
    /// The thread is mounted while it runs this method, so the returned tracker
    /// already counts one open mounted interval.
    ///
    /// @throws IllegalStateException if the current thread is already registered
    public static MountedTime register()
    {
        if (TRACKER.get() != null) {
            throw new IllegalStateException("current thread is already registered");
        }
        MountedTime tracker = new MountedTime();
        tracker.mounted();
        TRACKER.set(tracker);
        ACTIVE_REGISTRATIONS.incrementAndGet();
        return tracker;
    }

    /// Unregisters the current thread if `tracker` is the one it registered, closing the
    /// tracker and freezing its totals. Any other call, including a repeated one, a call
    /// after [#ended()], or a call from a thread that does not own the tracker, does nothing.
    public static void unregister(MountedTime tracker)
    {
        if (tracker == null || TRACKER.get() != tracker) {
            return;
        }
        TRACKER.remove();
        close(tracker);
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
        if (!CLOCK_INSTALLED.compareAndSet(false, true)) {
            return false;
        }
        CLOCK_SITE.setTarget(clock);
        MutableCallSite.syncAll(new MutableCallSite[] {CLOCK_SITE});
        return true;
    }

    /// Returns the number of registered threads that have neither unregistered nor ended.
    public static long activeRegistrations()
    {
        return ACTIVE_REGISTRATIONS.get();
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

    private static void close(MountedTime tracker)
    {
        tracker.close();
        ACTIVE_REGISTRATIONS.decrementAndGet();
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
