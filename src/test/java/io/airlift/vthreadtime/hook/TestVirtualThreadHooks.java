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
import java.lang.invoke.MethodType;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static io.airlift.vthreadtime.VirtualThreads.onVirtualThread;
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
            long[] tracker = VirtualThreadHooks.register();
            try {
                assertThat(VirtualThreadHooks.mountCount(tracker)).isEqualTo(1);
                assertThat(VirtualThreadHooks.unmountCount(tracker)).isEqualTo(0);

                long initial = VirtualThreadHooks.mountedTimeNanos(tracker);
                spin(Duration.ofMillis(5));
                assertThat(VirtualThreadHooks.mountedTimeNanos(tracker) - initial)
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
            long[] tracker = VirtualThreadHooks.register();
            try {
                spin(Duration.ofMillis(10));
                VirtualThreadHooks.unmounted();
                long wallElapsed = System.nanoTime() - wallStart;

                long frozen = VirtualThreadHooks.mountedTimeNanos(tracker);
                assertThat(VirtualThreadHooks.unmountCount(tracker)).isEqualTo(1);
                assertThat(frozen).isBetween(Duration.ofMillis(10).toNanos(), wallElapsed);

                spin(Duration.ofMillis(2));
                assertThat(VirtualThreadHooks.mountedTimeNanos(tracker)).isEqualTo(frozen);

                VirtualThreadHooks.mounted();
                assertThat(VirtualThreadHooks.mountCount(tracker)).isEqualTo(2);
                spin(Duration.ofMillis(2));
                assertThat(VirtualThreadHooks.mountedTimeNanos(tracker) - frozen)
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
            long[] tracker = VirtualThreadHooks.register();
            try {
                long wallStart = System.nanoTime();
                long mountedStart = VirtualThreadHooks.mountedTimeNanos(tracker);
                spin(Duration.ofMillis(20));
                long mountedElapsed = VirtualThreadHooks.mountedTimeNanos(tracker) - mountedStart;
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
            long[] tracker = VirtualThreadHooks.register();
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
            long[] first = VirtualThreadHooks.register();
            VirtualThreadHooks.unregister(first);

            long frozen = VirtualThreadHooks.mountedTimeNanos(first);
            spin(Duration.ofMillis(1));
            assertThat(VirtualThreadHooks.mountedTimeNanos(first)).isEqualTo(frozen);

            VirtualThreadHooks.unregister(first);
            assertThat(VirtualThreadHooks.mountedTimeNanos(first)).isEqualTo(frozen);

            long[] second = VirtualThreadHooks.register();
            assertThat(second).isNotSameAs(first);
            assertThat(VirtualThreadHooks.mountCount(second)).isEqualTo(1);
            VirtualThreadHooks.unregister(second);
            assertThat(VirtualThreadHooks.mountedTimeNanos(first)).isEqualTo(frozen);
            return null;
        });
    }

    @Test
    void testUnregisterFromOtherThreadIsIgnored()
            throws Throwable
    {
        onVirtualThread(() -> {
            long[] tracker = VirtualThreadHooks.register();
            try {
                onVirtualThread(() -> {
                    VirtualThreadHooks.unregister(tracker);
                    return null;
                });
                // Still registered: the open interval keeps growing
                long before = VirtualThreadHooks.mountedTimeNanos(tracker);
                spin(Duration.ofMillis(1));
                assertThat(VirtualThreadHooks.mountedTimeNanos(tracker) - before).isGreaterThanOrEqualTo(Duration.ofMillis(1).toNanos());
            }
            finally {
                VirtualThreadHooks.unregister(tracker);
            }
            long frozen = VirtualThreadHooks.mountedTimeNanos(tracker);
            spin(Duration.ofMillis(1));
            assertThat(VirtualThreadHooks.mountedTimeNanos(tracker)).isEqualTo(frozen);
            return null;
        });
    }

    @Test
    void testHooksAreNoopsOnUnregisteredThread()
            throws Throwable
    {
        VirtualThreadHooks.mounted();
        VirtualThreadHooks.unmounted();
        VirtualThreadHooks.unregister(null);
        onVirtualThread(() -> {
            VirtualThreadHooks.mounted();
            VirtualThreadHooks.unmounted();
            return null;
        });
    }

    @Test
    void testFirstClockWins()
            throws Throwable
    {
        IsolatedHooks hooks = IsolatedHooks.load();
        AtomicLong ticks = new AtomicLong(1_000_000);

        assertThatThrownBy(() -> hooks.setClock().invoke(MethodHandles.constant(int.class, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("()long");
        assertThat((boolean) hooks.setClock().invoke(clockOf(ticks))).isTrue();
        assertThat((boolean) hooks.setClock().invoke(MethodHandles.constant(long.class, 999_999_999L))).isFalse();

        onVirtualThread(() -> {
            Object tracker = hooks.register().invoke();
            assertThat((long) hooks.mountCount().invoke(tracker)).isEqualTo(1);
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(0);

            ticks.addAndGet(500);
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(500);

            hooks.unmounted().invoke();
            assertThat((long) hooks.unmountCount().invoke(tracker)).isEqualTo(1);
            ticks.addAndGet(300);
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(500);

            hooks.mounted().invoke();
            assertThat((long) hooks.mountCount().invoke(tracker)).isEqualTo(2);
            ticks.addAndGet(200);
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(700);

            hooks.unregister().invoke(tracker);
            ticks.addAndGet(1_000);
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(700);
            return null;
        });
    }

    @Test
    void testZeroStampIsNoOpenInterval()
            throws Throwable
    {
        // The installed clock reads as zero when it fails. A mount stamped with zero must not turn
        // the next reading into the clock's whole value, and a zero reading at unmount or on a
        // read must not count either.
        IsolatedHooks hooks = IsolatedHooks.load();
        AtomicLong ticks = new AtomicLong(0);
        assertThat((boolean) hooks.setClock().invoke(clockOf(ticks))).isTrue();

        onVirtualThread(() -> {
            Object tracker = hooks.register().invoke();
            assertThat((long) hooks.mountCount().invoke(tracker)).isEqualTo(1);
            ticks.set(1_000);
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(0);
            hooks.unmounted().invoke();
            assertThat((long) hooks.unmountCount().invoke(tracker)).isEqualTo(1);
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(0);

            hooks.mounted().invoke();
            ticks.set(1_300);
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(300);
            ticks.set(0);
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(0);
            hooks.unmounted().invoke();
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(0);

            ticks.set(2_000);
            hooks.mounted().invoke();
            ticks.set(2_400);
            hooks.unmounted().invoke();
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(400);

            ticks.set(0);
            hooks.mounted().invoke();
            ticks.set(9_000);
            hooks.unregister().invoke(tracker);
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(400);
            return null;
        });
    }

    @Test
    void testDropOpenIntervalKeepsClosedTotals()
            throws Throwable
    {
        IsolatedHooks hooks = IsolatedHooks.load();
        AtomicLong ticks = new AtomicLong(1_000);
        assertThat((boolean) hooks.setClock().invoke(clockOf(ticks))).isTrue();

        onVirtualThread(() -> {
            Object tracker = hooks.register().invoke();
            ticks.set(1_400);
            hooks.unmounted().invoke();
            ticks.set(2_000);
            hooks.mounted().invoke();
            ticks.set(2_300);
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(700);

            hooks.dropOpenInterval().invoke(tracker);
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(400);
            ticks.set(9_000);
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(400);

            hooks.unregister().invoke(tracker);
            assertThat((long) hooks.mountedTimeNanos().invoke(tracker)).isEqualTo(400);
            return null;
        });
    }

    private static MethodHandle clockOf(AtomicLong ticks)
            throws ReflectiveOperationException
    {
        return MethodHandles.lookup()
                .findVirtual(AtomicLong.class, "get", methodType(long.class))
                .bindTo(ticks);
    }

    private static void spin(Duration duration)
    {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    /// A private copy of the hook class with its own clock call site, and the handles to drive it.
    private record IsolatedHooks(
            MethodHandle setClock,
            MethodHandle register,
            MethodHandle mounted,
            MethodHandle unmounted,
            MethodHandle unregister,
            MethodHandle dropOpenInterval,
            MethodHandle mountedTimeNanos,
            MethodHandle mountCount,
            MethodHandle unmountCount)
    {
        static IsolatedHooks load()
                throws ReflectiveOperationException
        {
            IsolatedHookLoader loader = new IsolatedHookLoader();
            Class<?> hooks = loader.loadClass(VirtualThreadHooks.class.getName());
            assertThat(hooks).isNotSameAs(VirtualThreadHooks.class);
            assertThat(hooks.getClassLoader()).isSameAs(loader);

            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodType reader = methodType(long.class, long[].class);
            return new IsolatedHooks(
                    lookup.findStatic(hooks, "setClock", methodType(boolean.class, MethodHandle.class)),
                    lookup.findStatic(hooks, "register", methodType(long[].class)),
                    lookup.findStatic(hooks, "mounted", methodType(void.class)),
                    lookup.findStatic(hooks, "unmounted", methodType(void.class)),
                    lookup.findStatic(hooks, "unregister", methodType(void.class, long[].class)),
                    lookup.findStatic(hooks, "dropOpenInterval", methodType(void.class, long[].class)),
                    lookup.findStatic(hooks, "mountedTimeNanos", reader),
                    lookup.findStatic(hooks, "mountCount", reader),
                    lookup.findStatic(hooks, "unmountCount", reader));
        }
    }

    /// Loads a private copy of the hook class with the bootstrap loader as parent, so that
    /// the clock test owns its call site and the class proves to link against `java.base` alone.
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
