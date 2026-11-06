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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.invoke.MethodType.methodType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestVirtualThreadHooks
{
    private static final String HOOK_PACKAGE = "io.airlift.vthreadtime.hook.";
    private static final long TOLERANCE_NANOS = Duration.ofMillis(1).toNanos();

    @Test
    void testRegisterStartsMountedInterval()
            throws Throwable
    {
        onVirtualThread(() -> {
            MountedTime tracker = VirtualThreadHooks.register();
            try {
                assertThat(tracker.mountCount()).isEqualTo(1);
                assertThat(tracker.unmountCount()).isEqualTo(0);
                assertThat(tracker.isClosed()).isFalse();

                long initial = tracker.mountedTimeNanos();
                spin(Duration.ofMillis(5));
                assertThat(tracker.mountedTimeNanos() - initial)
                        .isGreaterThanOrEqualTo(Duration.ofMillis(5).toNanos());
            }
            finally {
                VirtualThreadHooks.unregister(tracker);
            }
            return null;
        });
    }

    @Test
    void testExplicitMountAndUnmountAccounting()
            throws Throwable
    {
        onVirtualThread(() -> {
            long wallStart = System.nanoTime();
            MountedTime tracker = VirtualThreadHooks.register();
            try {
                spin(Duration.ofMillis(10));
                VirtualThreadHooks.unmounted();
                long wallElapsed = System.nanoTime() - wallStart;

                long frozen = tracker.mountedTimeNanos();
                assertThat(tracker.unmountCount()).isEqualTo(1);
                assertThat(frozen).isBetween(Duration.ofMillis(10).toNanos(), wallElapsed);

                spin(Duration.ofMillis(2));
                assertThat(tracker.mountedTimeNanos()).isEqualTo(frozen);

                VirtualThreadHooks.mounted();
                assertThat(tracker.mountCount()).isEqualTo(2);
                spin(Duration.ofMillis(2));
                assertThat(tracker.mountedTimeNanos() - frozen)
                        .isGreaterThanOrEqualTo(Duration.ofMillis(2).toNanos());
            }
            finally {
                VirtualThreadHooks.unregister(tracker);
            }
            return null;
        });
    }

    @Test
    void testMountedTimeBoundedByWallTime()
            throws Throwable
    {
        onVirtualThread(() -> {
            MountedTime tracker = VirtualThreadHooks.register();
            try {
                long wallStart = System.nanoTime();
                long mountedStart = tracker.mountedTimeNanos();
                spin(Duration.ofMillis(20));
                long mountedElapsed = tracker.mountedTimeNanos() - mountedStart;
                long wallElapsed = System.nanoTime() - wallStart;

                assertThat(mountedElapsed).isBetween(wallElapsed - TOLERANCE_NANOS, wallElapsed);
                assertThat(mountedElapsed).isGreaterThanOrEqualTo(Duration.ofMillis(20).toNanos() - TOLERANCE_NANOS);
            }
            finally {
                VirtualThreadHooks.unregister(tracker);
            }
            return null;
        });
    }

    @Test
    void testDoubleRegistrationRejected()
            throws Throwable
    {
        onVirtualThread(() -> {
            MountedTime tracker = VirtualThreadHooks.register();
            try {
                assertThatThrownBy(VirtualThreadHooks::register)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("current thread is already registered");
            }
            finally {
                VirtualThreadHooks.unregister(tracker);
            }
            return null;
        });
    }

    @Test
    void testUnregisterClearsRegistration()
            throws Throwable
    {
        onVirtualThread(() -> {
            long registrations = VirtualThreadHooks.activeRegistrations();
            MountedTime first = VirtualThreadHooks.register();
            assertThat(VirtualThreadHooks.activeRegistrations()).isEqualTo(registrations + 1);

            VirtualThreadHooks.unregister(first);
            assertThat(first.isClosed()).isTrue();
            assertThat(VirtualThreadHooks.activeRegistrations()).isEqualTo(registrations);

            long frozen = first.mountedTimeNanos();
            spin(Duration.ofMillis(1));
            assertThat(first.mountedTimeNanos()).isEqualTo(frozen);

            VirtualThreadHooks.unregister(first);
            assertThat(VirtualThreadHooks.activeRegistrations()).isEqualTo(registrations);

            MountedTime second = VirtualThreadHooks.register();
            assertThat(second).isNotSameAs(first);
            assertThat(second.mountCount()).isEqualTo(1);
            assertThat(VirtualThreadHooks.activeRegistrations()).isEqualTo(registrations + 1);

            VirtualThreadHooks.unregister(second);
            assertThat(VirtualThreadHooks.activeRegistrations()).isEqualTo(registrations);
            return null;
        });
    }

    @Test
    void testUnregisterFromOtherThreadIsIgnored()
            throws Throwable
    {
        onVirtualThread(() -> {
            MountedTime tracker = VirtualThreadHooks.register();
            try {
                long registrations = VirtualThreadHooks.activeRegistrations();
                onVirtualThread(() -> {
                    VirtualThreadHooks.unregister(tracker);
                    return null;
                });
                assertThat(tracker.isClosed()).isFalse();
                assertThat(VirtualThreadHooks.activeRegistrations()).isEqualTo(registrations);
            }
            finally {
                VirtualThreadHooks.unregister(tracker);
            }
            assertThat(tracker.isClosed()).isTrue();
            return null;
        });
    }

    @Test
    void testEndedDropsRegistration()
            throws Throwable
    {
        onVirtualThread(() -> {
            long registrations = VirtualThreadHooks.activeRegistrations();
            MountedTime tracker = VirtualThreadHooks.register();
            spin(Duration.ofMillis(1));

            VirtualThreadHooks.ended();
            assertThat(tracker.isClosed()).isTrue();
            assertThat(tracker.mountedTimeNanos()).isGreaterThanOrEqualTo(Duration.ofMillis(1).toNanos());
            assertThat(VirtualThreadHooks.activeRegistrations()).isEqualTo(registrations);

            VirtualThreadHooks.unregister(tracker);
            VirtualThreadHooks.ended();
            assertThat(VirtualThreadHooks.activeRegistrations()).isEqualTo(registrations);

            MountedTime next = VirtualThreadHooks.register();
            assertThat(next).isNotSameAs(tracker);
            assertThat(VirtualThreadHooks.activeRegistrations()).isEqualTo(registrations + 1);
            VirtualThreadHooks.unregister(next);
            assertThat(VirtualThreadHooks.activeRegistrations()).isEqualTo(registrations);
            return null;
        });
    }

    @Test
    void testHooksAreNoopsOnUnregisteredThread()
            throws Throwable
    {
        long registrations = VirtualThreadHooks.activeRegistrations();
        VirtualThreadHooks.mounted();
        VirtualThreadHooks.unmounted();
        VirtualThreadHooks.ended();
        VirtualThreadHooks.unregister(null);
        onVirtualThread(() -> {
            VirtualThreadHooks.mounted();
            VirtualThreadHooks.unmounted();
            VirtualThreadHooks.ended();
            return null;
        });
        assertThat(VirtualThreadHooks.activeRegistrations()).isEqualTo(registrations);
    }

    @Test
    void testFirstClockWins()
            throws Throwable
    {
        IsolatedHookLoader loader = new IsolatedHookLoader();
        Class<?> hooks = loader.loadClass(VirtualThreadHooks.class.getName());
        Class<?> mountedTime = loader.loadClass(MountedTime.class.getName());
        assertThat(hooks).isNotSameAs(VirtualThreadHooks.class);
        assertThat(hooks.getClassLoader()).isSameAs(loader);
        assertThat(mountedTime.getClassLoader()).isSameAs(loader);

        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle setClock = lookup.findStatic(hooks, "setClock", methodType(boolean.class, MethodHandle.class));
        MethodHandle register = lookup.findStatic(hooks, "register", methodType(mountedTime));
        MethodHandle mounted = lookup.findStatic(hooks, "mounted", methodType(void.class));
        MethodHandle unmounted = lookup.findStatic(hooks, "unmounted", methodType(void.class));
        MethodHandle unregister = lookup.findStatic(hooks, "unregister", methodType(void.class, mountedTime));
        MethodHandle mountedTimeNanos = lookup.findVirtual(mountedTime, "mountedTimeNanos", methodType(long.class));
        MethodHandle mountCount = lookup.findVirtual(mountedTime, "mountCount", methodType(long.class));
        MethodHandle unmountCount = lookup.findVirtual(mountedTime, "unmountCount", methodType(long.class));
        MethodHandle isClosed = lookup.findVirtual(mountedTime, "isClosed", methodType(boolean.class));

        AtomicLong ticks = new AtomicLong(1_000_000);
        MethodHandle clock = MethodHandles.lookup()
                .findVirtual(AtomicLong.class, "get", methodType(long.class))
                .bindTo(ticks);

        assertThatThrownBy(() -> setClock.invoke(MethodHandles.constant(int.class, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("()long");
        assertThat((boolean) setClock.invoke(clock)).isTrue();
        assertThat((boolean) setClock.invoke(MethodHandles.constant(long.class, 999_999_999L))).isFalse();

        onVirtualThread(() -> {
            Object tracker = register.invoke();
            assertThat((long) mountCount.invoke(tracker)).isEqualTo(1);
            assertThat((long) mountedTimeNanos.invoke(tracker)).isEqualTo(0);

            ticks.addAndGet(500);
            assertThat((long) mountedTimeNanos.invoke(tracker)).isEqualTo(500);

            unmounted.invoke();
            assertThat((long) unmountCount.invoke(tracker)).isEqualTo(1);
            ticks.addAndGet(300);
            assertThat((long) mountedTimeNanos.invoke(tracker)).isEqualTo(500);

            mounted.invoke();
            assertThat((long) mountCount.invoke(tracker)).isEqualTo(2);
            ticks.addAndGet(200);
            assertThat((long) mountedTimeNanos.invoke(tracker)).isEqualTo(700);

            unregister.invoke(tracker);
            assertThat((boolean) isClosed.invoke(tracker)).isTrue();
            ticks.addAndGet(1_000);
            assertThat((long) mountedTimeNanos.invoke(tracker)).isEqualTo(700);
            return null;
        });
    }

    private static void spin(Duration duration)
    {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static <T> T onVirtualThread(ThrowingSupplier<T> task)
            throws Throwable
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
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T>
    {
        T get()
                throws Throwable;
    }

    /// Loads a private copy of the hook classes with the bootstrap loader as parent, so that
    /// the clock test owns its call site and the classes prove to link against `java.base` alone.
    private static final class IsolatedHookLoader
            extends ClassLoader
    {
        IsolatedHookLoader()
        {
            super(null);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException
        {
            if (!name.startsWith(HOOK_PACKAGE)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        @Override
        @SuppressWarnings("BanClassLoader")
        protected Class<?> findClass(String name)
                throws ClassNotFoundException
        {
            String resource = "/" + name.replace('.', '/') + ".class";
            try (InputStream stream = TestVirtualThreadHooks.class.getResourceAsStream(resource)) {
                if (stream == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = stream.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            }
            catch (IOException exception) {
                throw new ClassNotFoundException(name, exception);
            }
        }
    }
}
