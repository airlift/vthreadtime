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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestVirtualThreadTime
{
    @Test
    void testSupported()
    {
        assertThat(VirtualThreadTime.isSupported()).isTrue();
        VirtualThreadTime.initialize();
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
        long mountedNanos = onVirtualThread(() -> {
            try (VirtualThreadTime time = VirtualThreadTime.register()) {
                long start = time.mountedTimeNanos();
                long end = System.nanoTime() + Duration.ofMillis(10).toNanos();
                while (System.nanoTime() < end) {
                    Thread.onSpinWait();
                }
                return time.mountedTimeNanos() - start;
            }
        });

        assertThat(mountedNanos).isGreaterThan(Duration.ofMillis(5).toNanos());
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
    void testRegistrationCleanedAtThreadEnd()
            throws Exception
    {
        long registrations = NativeAccess.activeRegistrations();
        onVirtualThread(() -> {
            VirtualThreadTime.register();
            return null;
        });
        assertEventually(
                () -> assertThat(NativeAccess.activeRegistrations()).isEqualTo(registrations),
                Duration.ofSeconds(10));
    }

    @Test
    void testConcurrentTrackers()
            throws InterruptedException
    {
        long registrations = NativeAccess.activeRegistrations();
        List<Thread> threads = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int index = 0; index < 1_000; index++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try (VirtualThreadTime time = VirtualThreadTime.register()) {
                    LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
                    assertThat(time.mountCount()).isGreaterThanOrEqualTo(2);
                }
                catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(failure.get()).isNull();
        assertThat(NativeAccess.activeRegistrations()).isEqualTo(registrations);
    }

    private static <T> T onVirtualThread(ThrowingSupplier<T> task)
            throws Exception
    {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                result.set(task.get());
            }
            catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        thread.join();
        if (failure.get() instanceof Exception exception) {
            throw exception;
        }
        if (failure.get() instanceof Error error) {
            throw error;
        }
        return result.get();
    }

    private static void assertEventually(ThrowingRunnable assertion, Duration timeout)
            throws Exception
    {
        long deadline = System.nanoTime() + timeout.toNanos();
        AssertionError lastFailure;
        do {
            try {
                assertion.run();
                return;
            }
            catch (AssertionError failure) {
                lastFailure = failure;
            }
            Thread.sleep(Duration.ofMillis(10));
        }
        while (System.nanoTime() < deadline);
        throw lastFailure;
    }

    private record Measurement(long wallNanos, long mountedNanos, long mounts, long unmounts) {}

    @FunctionalInterface
    private interface ThrowingSupplier<T>
    {
        T get()
                throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingRunnable
    {
        void run()
                throws Exception;
    }
}
