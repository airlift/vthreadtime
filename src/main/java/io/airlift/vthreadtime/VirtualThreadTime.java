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
package io.airlift.vthreadtime;

import static java.util.Objects.requireNonNull;

/// Measures the time a virtual thread is mounted on carrier threads.
///
/// A tracker is bound to the virtual thread that registers it. All methods,
/// including [#close()], must be invoked by that thread.
public final class VirtualThreadTime
        implements AutoCloseable
{
    /// Whether the hooks retransformed into `java.lang.VirtualThread` are measuring. Trackers
    /// register and read in every status; in the two inactive ones they count nothing new.
    public enum Status
    {
        /// The hooks are in `VirtualThread` and every registered thread is measured.
        ACTIVE,
        /// This JVM is recording an AOT cache (`-XX:AOTCacheOutput` or `-XX:AOTMode=record`).
        /// Retransforming `VirtualThread` would exclude it from the cache and make the training run
        /// fail, so the hooks are not installed and trackers report zero.
        AOT_TRAINING,
        /// Another agent retransformed or redefined `VirtualThread` from other class bytes after the
        /// hooks were installed, which removed them. Trackers keep the totals of the intervals that
        /// were closed by then and count nothing new.
        HOOKS_REMOVED,
    }

    private final Thread owner;
    private final NativeAccess.Tracker tracker;
    private boolean closed;

    private VirtualThreadTime(Thread owner, NativeAccess.Tracker tracker)
    {
        this.owner = requireNonNull(owner, "owner is null");
        this.tracker = requireNonNull(tracker, "tracker is null");
    }

    /// Returns whether the current JVM and platform support mounted-time tracking.
    /// This method initializes and loads the native library on its first call.
    public static boolean isSupported()
    {
        return NativeAccess.isSupported();
    }

    /// Loads the process-lifetime native library and installs the hooks into
    /// `java.lang.VirtualThread`, unless this JVM is recording an AOT cache; see [Status].
    ///
    /// @throws UnsupportedOperationException if the JVM or platform is unsupported, or the hooks
    ///         could not be installed; the cause names the step that failed
    public static void initialize()
    {
        NativeAccess.ensureSupported();
    }

    /// Returns what the hooks are doing right now: measuring, skipped for an AOT training run, or
    /// removed by another agent. The value is a point-in-time reading that can move from
    /// [Status#ACTIVE] to [Status#HOOKS_REMOVED] and never back. Worth logging at startup or
    /// exporting as a metric: in the two inactive statuses every tracker reads as zero or frozen.
    ///
    /// @throws UnsupportedOperationException if the JVM or platform is unsupported
    public static Status status()
    {
        return NativeAccess.status();
    }

    /// Registers the current virtual thread and begins measuring mounted time. The interval the
    /// thread is in right now is the first one counted. In the two inactive statuses the returned
    /// tracker counts nothing, without throwing.
    ///
    /// @throws IllegalStateException if the current thread is not a virtual thread
    /// @throws UnsupportedOperationException if the JVM or platform is unsupported
    public static VirtualThreadTime register()
    {
        Thread thread = Thread.currentThread();
        if (!thread.isVirtual()) {
            throw new IllegalStateException("Current thread is not virtual");
        }
        return new VirtualThreadTime(thread, NativeAccess.register());
    }

    /// Returns cumulative mounted time since registration, in nanoseconds, including the
    /// interval that is still open.
    ///
    /// Mounted time is the CPU time of the carrier thread while this virtual thread is mounted
    /// on it. Time the carrier spends off the CPU, such as waiting in the OS run queue, is not
    /// counted. The total stops growing once the hooks are removed; see [Status#HOOKS_REMOVED].
    ///
    /// @throws IllegalStateException if called from another thread or after [#close()]
    public long mountedTimeNanos()
    {
        checkOwner();
        return tracker.mountedTimeNanos();
    }

    /// Returns the number of mounted intervals since registration.
    /// The interval active at registration is included.
    ///
    /// @throws IllegalStateException if called from another thread or after [#close()]
    public long mountCount()
    {
        checkOwner();
        return tracker.mountCount();
    }

    /// Returns the number of unmounts since registration.
    ///
    /// @throws IllegalStateException if called from another thread or after [#close()]
    public long unmountCount()
    {
        checkOwner();
        return tracker.unmountCount();
    }

    /// Unregisters the owning virtual thread and freezes the totals; the accessors throw from
    /// here on. Calling it again does nothing.
    ///
    /// @throws IllegalStateException if called from another thread
    @Override
    public void close()
    {
        checkOwnerThread();
        if (closed) {
            return;
        }
        tracker.close();
        closed = true;
    }

    private void checkOwner()
    {
        checkOwnerThread();
        if (closed) {
            throw new IllegalStateException("Tracker is closed");
        }
    }

    private void checkOwnerThread()
    {
        if (!owner.equals(Thread.currentThread())) {
            throw new IllegalStateException("Tracker can only be used by its registered virtual thread");
        }
    }
}
