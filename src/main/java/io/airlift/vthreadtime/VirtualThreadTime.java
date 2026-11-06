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

/// Measures the time a virtual thread is mounted on carrier threads.
///
/// A tracker is bound to the virtual thread that registers it. All methods,
/// including [#close()], must be invoked by that thread.
public final class VirtualThreadTime
        implements AutoCloseable
{
    private final Thread owner;
    private final Tracker tracker;
    private boolean closed;

    private VirtualThreadTime(Thread owner, Tracker tracker)
    {
        this.owner = owner;
        this.tracker = tracker;
    }

    /// Returns whether the current JVM and platform support mounted-time tracking.
    /// This method initializes and loads the native library on its first call.
    public static boolean isSupported()
    {
        return NativeAccess.isSupported();
    }

    /// Loads the process-lifetime native library and installs the virtual-thread hooks, or the
    /// virtual-thread lifecycle callbacks when the hooks cannot be installed.
    ///
    /// @throws UnsupportedOperationException if the JVM or platform is unsupported
    public static void initialize()
    {
        NativeAccess.ensureSupported();
    }

    /// Registers the current virtual thread and begins measuring mounted time.
    public static VirtualThreadTime register()
    {
        Thread thread = Thread.currentThread();
        if (!thread.isVirtual()) {
            throw new IllegalStateException("Current thread is not virtual");
        }
        return new VirtualThreadTime(thread, NativeAccess.register());
    }

    /// Returns cumulative mounted time since registration, in nanoseconds.
    ///
    /// Mounted time is the CPU time of the carrier thread while this virtual thread is mounted
    /// on it. Time the carrier spends off the CPU, such as waiting in the OS run queue, is not
    /// counted.
    public long mountedTimeNanos()
    {
        checkOwner();
        return tracker.mountedTimeNanos();
    }

    /// Returns the number of mounted intervals since registration.
    /// The interval active at registration is included.
    public long mountCount()
    {
        checkOwner();
        return tracker.mountCount();
    }

    /// Returns the number of unmounts since registration.
    public long unmountCount()
    {
        checkOwner();
        return tracker.unmountCount();
    }

    /// Unregisters the current virtual thread.
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
