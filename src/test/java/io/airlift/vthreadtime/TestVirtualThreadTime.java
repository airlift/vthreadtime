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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static io.airlift.vthreadtime.VirtualThreads.onVirtualThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestVirtualThreadTime
{
    // Set on hosts known to allow user-space reads of a perf task-clock event, which run the tests
    // that need one with ./mvnw verify -Dio.airlift.vthreadtime.test.perf.clock.supported=true. The
    // host must let the user open the event (perf_event_paranoid or a capability), must offer
    // cap_user_time, and must run the JVM with no seccomp filter, as the default auto mode requires;
    // a default Docker container has one.
    private static final String PERF_CLOCK_SUPPORTED_PROPERTY = "io.airlift.vthreadtime.test.perf.clock.supported";

    @Test
    void testSupported()
    {
        assertThat(VirtualThreadTime.isSupported()).isTrue();
        VirtualThreadTime.initialize();
    }

    @Test
    void testHooksAreActive()
    {
        assertThat(VirtualThreadTime.status()).isEqualTo(VirtualThreadTime.Status.ACTIVE);
    }

    @Test
    void testPlatformThreadCannotRegister()
    {
        assertThatThrownBy(VirtualThreadTime::register)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Current thread is not virtual");
    }

    @Test
    void testMountedTimeAndMountCount()
            throws Exception
    {
        Measurement measurement = onVirtualThread(() -> {
            try (VirtualThreadTime time = VirtualThreadTime.register()) {
                long initialMountedTime = time.mountedTimeNanos();
                long initialMountCount = time.mountCount();
                long initialUnmountCount = time.unmountCount();
                long wallStart = System.nanoTime();

                long parkDeadline = wallStart + Duration.ofMillis(250).toNanos();
                long remaining;
                while ((remaining = parkDeadline - System.nanoTime()) > 0) {
                    LockSupport.parkNanos(remaining);
                }

                return new Measurement(
                        System.nanoTime() - wallStart,
                        time.mountedTimeNanos() - initialMountedTime,
                        time.mountCount() - initialMountCount,
                        time.unmountCount() - initialUnmountCount);
            }
        });

        assertThat(measurement.wallNanos() - measurement.mountedNanos())
                .isGreaterThan(Duration.ofMillis(100).toNanos());
        assertThat(measurement.mounts()).isGreaterThanOrEqualTo(1);
        assertThat(measurement.unmounts()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void testMountedTimeIncreasesWhileRunning()
            throws Exception
    {
        long threshold = Duration.ofMillis(5).toNanos();
        long mountedNanos = onVirtualThread(() -> {
            try (VirtualThreadTime time = VirtualThreadTime.register()) {
                long start = time.mountedTimeNanos();
                // Mounted time is carrier CPU time, so spin until enough of it accumulates
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (time.mountedTimeNanos() - start < threshold && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                return time.mountedTimeNanos() - start;
            }
        });

        assertThat(mountedNanos).isGreaterThan(threshold);
    }

    @Test
    void testMountedTimeIsBoundedByWallTime()
            throws Exception
    {
        // A busy spin never unmounts and cannot use more CPU time than wall time passes.
        Measurement measurement = onVirtualThread(() -> {
            try (VirtualThreadTime time = VirtualThreadTime.register()) {
                long initialMountedTime = time.mountedTimeNanos();
                long wallStart = System.nanoTime();
                long end = wallStart + Duration.ofMillis(50).toNanos();
                while (System.nanoTime() < end) {
                    Thread.onSpinWait();
                }
                long wallNanos = System.nanoTime() - wallStart;
                long mountedNanos = time.mountedTimeNanos() - initialMountedTime;
                return new Measurement(wallNanos, mountedNanos, time.mountCount(), time.unmountCount());
            }
        });

        assertThat(measurement.mountedNanos())
                .isLessThanOrEqualTo(measurement.wallNanos() + Duration.ofMillis(1).toNanos());
    }

    @Test
    @EnabledIfSystemProperty(named = PERF_CLOCK_SUPPORTED_PROPERTY, matches = "true")
    void testCarrierUsesPerfClock()
            throws Exception
    {
        boolean usesPerfClock = onVirtualThread(() -> {
            try (VirtualThreadTime _ = VirtualThreadTime.register()) {
                return NativeAccess.usesPerfClock();
            }
        });

        assertThat(usesPerfClock).isTrue();
    }

    @Test
    void testDoubleRegistrationRejected()
            throws Exception
    {
        onVirtualThread(() -> {
            try (VirtualThreadTime time = VirtualThreadTime.register()) {
                assertThat(time.mountCount()).isEqualTo(1);
                assertThatThrownBy(VirtualThreadTime::register)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("already registered");
            }
            return null;
        });
    }

    @Test
    void testTrackerIsThreadConfined()
            throws Exception
    {
        onVirtualThread(() -> {
            try (VirtualThreadTime time = VirtualThreadTime.register()) {
                IllegalStateException failure = onVirtualThread(() -> {
                    try {
                        time.mountedTimeNanos();
                        return null;
                    }
                    catch (IllegalStateException exception) {
                        return exception;
                    }
                });
                assertThat(failure)
                        .hasMessage("Tracker can only be used by its registered virtual thread");
            }
            return null;
        });
    }

    @Test
    void testCloseIsIdempotent()
            throws Exception
    {
        onVirtualThread(() -> {
            VirtualThreadTime time = VirtualThreadTime.register();
            time.close();
            time.close();
            assertThatThrownBy(time::mountedTimeNanos)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Tracker is closed");
            return null;
        });
    }

    @Test
    void testClosedTrackerRemainsThreadConfined()
            throws Exception
    {
        VirtualThreadTime time = onVirtualThread(() -> {
            VirtualThreadTime tracker = VirtualThreadTime.register();
            tracker.close();
            tracker.close();
            return tracker;
        });

        assertThatThrownBy(() -> onVirtualThread(() -> {
            time.close();
            return null;
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tracker can only be used by its registered virtual thread");
    }

    @Test
    void testConcurrentTrackers()
            throws InterruptedException
    {
        List<Thread> threads = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int index = 0; index < 1_000; index++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try (VirtualThreadTime time = VirtualThreadTime.register()) {
                    LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
                    assertThat(time.mountCount()).isGreaterThanOrEqualTo(2);
                    assertThat(time.unmountCount()).isGreaterThanOrEqualTo(1);
                }
                catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            }));
        }
        for (Thread thread : threads) {
            assertThat(thread.join(Duration.ofSeconds(30))).describedAs("thread finished").isTrue();
        }

        assertThat(failure.get()).isNull();
    }

    private record Measurement(long wallNanos, long mountedNanos, long mounts, long unmounts) {}
}
