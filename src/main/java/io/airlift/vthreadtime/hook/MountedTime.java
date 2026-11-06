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

/// Mounted-time accounting of one registered virtual thread.
///
/// The fields are plain because there is a single writer at any time: the carrier running the
/// owning virtual thread stamps mounts and unmounts, and the owning thread reads the totals.
/// The scheduler handoff orders carrier changes, so no volatile access is needed. The methods
/// on the mount and unmount path are kept tiny so that the JIT inlines them into
/// `VirtualThread.mount()` and `unmount()`.
public final class MountedTime
{
    private long mountedNanos;
    private long mountStarted;
    private long mountCount;
    private long unmountCount;
    private boolean mounted;
    private boolean closed;

    MountedTime() {}

    /// Records that the owning thread has just been mounted.
    void mounted()
    {
        mountStarted = VirtualThreadHooks.now();
        mountCount++;
        mounted = true;
    }

    /// Records that the owning thread is about to be unmounted.
    void unmounted()
    {
        long now = VirtualThreadHooks.now();
        if (now >= mountStarted) {
            mountedNanos += now - mountStarted;
        }
        unmountCount++;
        mounted = false;
    }

    /// Ends the open mounted interval, if any, and freezes the totals.
    void close()
    {
        if (mounted) {
            long now = VirtualThreadHooks.now();
            if (now >= mountStarted) {
                mountedNanos += now - mountStarted;
            }
            mounted = false;
        }
        closed = true;
    }

    /// Returns cumulative mounted time since registration, in nanoseconds,
    /// including the mounted interval that is still open.
    public long mountedTimeNanos()
    {
        if (!mounted) {
            return mountedNanos;
        }
        long now = VirtualThreadHooks.now();
        if (now < mountStarted) {
            return mountedNanos;
        }
        return mountedNanos + (now - mountStarted);
    }

    /// Returns the number of mounted intervals since registration.
    /// The interval active at registration is included.
    public long mountCount()
    {
        return mountCount;
    }

    /// Returns the number of unmounts since registration.
    public long unmountCount()
    {
        return unmountCount;
    }

    /// Returns whether the tracker has been unregistered or its thread has ended.
    /// A closed tracker is no longer updated and reports frozen totals.
    public boolean isClosed()
    {
        return closed;
    }
}
