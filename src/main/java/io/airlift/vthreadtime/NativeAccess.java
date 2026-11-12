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

import com.sun.management.HotSpotDiagnosticMXBean;
import io.airlift.vthreadtime.VirtualThreadTime.Status;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.foreign.Linker.Option.critical;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;

/// Installs the hooks into `java.lang.VirtualThread` once per process and reaches the boot-loaded
/// hook class behind exact-typed method handles.
final class NativeAccess
{
    // The hook class is defined into the bootstrap loader at runtime, so this class must only
    // ever reach it by name through that loader, never as a compile-time reference.
    private static final String HOOKS_CLASS = "io.airlift.vthreadtime.hook.VirtualThreadHooks";

    private static final MethodHandle HOOKS_INSTALLED;
    private static final HookHandles HOOKS;
    private static final boolean AOT_TRAINING;
    private static final Throwable INITIALIZATION_FAILURE;

    // The transformed class bytes must stay reachable until the native retransformation has
    // copied them, which happens on this thread before the retransform downcall returns; the
    // arena of the last attempt remains referenced after it is closed, which is harmless because
    // the transformer only runs during that downcall.
    private static Arena transformArena;
    private static final AtomicReference<Throwable> TRANSFORM_FAILURE = new AtomicReference<>();

    static {
        MethodHandle hooksInstalled = null;
        HookHandles hooks = null;
        boolean aotTraining = false;
        Throwable initializationFailure = null;
        try {
            aotTraining = isAotTrainingRun();
            if (!aotTraining) {
                Linker linker = Linker.nativeLinker();
                SymbolLookup symbols = NativeLibrary.load();
                MethodHandle carrierCpuNanos = linker.downcallHandle(
                        symbols.findOrThrow("airlift_vthreadtime_carrier_cpu_nanos"),
                        FunctionDescriptor.of(JAVA_LONG),
                        critical(false));
                hooksInstalled = linker.downcallHandle(
                        symbols.findOrThrow("airlift_vthreadtime_hooks_installed"),
                        FunctionDescriptor.of(JAVA_LONG),
                        critical(false));
                int status = installHooks(linker, symbols);
                if (status != 0) {
                    throw new UnsupportedOperationException("Hooks installation failed: " + statusMessage(status), TRANSFORM_FAILURE.get());
                }
                hooks = resolveHooks(carrierCpuNanos);
            }
        }
        catch (Throwable throwable) {
            initializationFailure = throwable;
        }
        HOOKS_INSTALLED = hooksInstalled;
        HOOKS = hooks;
        AOT_TRAINING = aotTraining;
        INITIALIZATION_FAILURE = initializationFailure;
    }

    private NativeAccess() {}

    static boolean isSupported()
    {
        return INITIALIZATION_FAILURE == null;
    }

    static void ensureSupported()
    {
        if (!isSupported()) {
            throw new UnsupportedOperationException("Virtual thread time is not supported", INITIALIZATION_FAILURE);
        }
    }

    static Status status()
    {
        ensureSupported();
        if (AOT_TRAINING) {
            return Status.AOT_TRAINING;
        }
        return hooksInstalled() ? Status.ACTIVE : Status.HOOKS_REMOVED;
    }

    /// Registers the current thread and returns its tracker. While the hooks are not measuring,
    /// in an AOT training run or after another agent removed them, the tracker counts nothing.
    static Tracker register()
    {
        ensureSupported();
        if (AOT_TRAINING || !hooksInstalled()) {
            return Tracker.COUNTING_NOTHING;
        }
        try {
            return new Tracker((Object) HOOKS.register().invokeExact());
        }
        catch (RuntimeException exception) {
            throw exception;
        }
        catch (Throwable throwable) {
            throw new IllegalStateException("Hook registration failed", throwable);
        }
    }

    /// Whether the hooks are in `VirtualThread` right now: installed and not removed since by
    /// another agent's retransformation. A critical downcall reading one flag.
    private static boolean hooksInstalled()
    {
        return invokeLong(HOOKS_INSTALLED) != 0;
    }

    /// Whether this JVM is recording an AOT cache. Retransforming `VirtualThread` would exclude it
    /// from the cache as redefined, and HotSpot refuses to write a cache without that critical
    /// class, so the training run would exit with a failure and the production run start without
    /// class sharing. The hooks are therefore not installed in a training run and trackers count
    /// nothing; a cache trained this way works with the hooks at run time.
    private static boolean isAotTrainingRun()
    {
        HotSpotDiagnosticMXBean diagnostics = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        return "record".equals(vmOption(diagnostics, "AOTMode")) || !vmOption(diagnostics, "AOTCacheOutput").isEmpty();
    }

    private static String vmOption(HotSpotDiagnosticMXBean diagnostics, String name)
    {
        try {
            String value = diagnostics.getVMOption(name).getValue();
            return value == null ? "" : value;
        }
        catch (IllegalArgumentException unknownOption) {
            return "";
        }
    }

    /// Defines the hook class into the bootstrap loader, initializes it there on the installing
    /// thread so that no virtual thread can trigger that from inside `mount()`, and retransforms
    /// `VirtualThread` to call it. Another class loader may have done all of this already: the
    /// native library then reports success and the boot class is reused, or it reports the failure
    /// it latched. Returns the native status.
    private static int installHooks(Linker linker, SymbolLookup symbols)
            throws Throwable
    {
        MethodHandle installHooks = linker.downcallHandle(
                symbols.findOrThrow("airlift_vthreadtime_install_hooks"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        MethodHandle transform = MethodHandles.lookup().findStatic(
                NativeAccess.class,
                "transform",
                methodType(int.class, MemorySegment.class, int.class, MemorySegment.class, MemorySegment.class));
        byte[] hooks = classBytes(HOOKS_CLASS);
        try (Arena arena = Arena.ofConfined()) {
            transformArena = arena;
            MemorySegment transformer = linker.upcallStub(
                    transform,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                    arena);
            return (int) installHooks.invokeExact(
                    arena.allocateFrom(internalName(HOOKS_CLASS)),
                    arena.allocateFrom(JAVA_BYTE, hooks),
                    hooks.length,
                    transformer);
        }
    }

    /// Called by the native retransformation, on the installing thread, with the original class
    /// bytes of `VirtualThread`. Writes the transformed bytes and their length through the two
    /// out-parameters and returns zero, or returns one when the transformation fails. Package
    /// visible so that the reference through the lookup above is not flagged as unused.
    static int transform(MemorySegment classData, int length, MemorySegment newData, MemorySegment newLength)
    {
        try {
            byte[] original = classData.reinterpret(length).toArray(JAVA_BYTE);
            byte[] transformed = VirtualThreadTransformer.transform(original);
            MemorySegment buffer = transformArena.allocateFrom(JAVA_BYTE, transformed);
            newData.reinterpret(ADDRESS.byteSize()).set(ADDRESS, 0, buffer);
            newLength.reinterpret(JAVA_INT.byteSize()).set(JAVA_INT, 0, transformed.length);
            return 0;
        }
        catch (Throwable throwable) {
            TRANSFORM_FAILURE.set(throwable);
            return 1;
        }
    }

    private static HookHandles resolveHooks(MethodHandle carrierCpuNanos)
            throws Throwable
    {
        Class<?> hooks = Class.forName(HOOKS_CLASS, true, null);
        // The hook members are public in an unconditionally exported package, which is all the
        // public lookup needs; trackers are plain long[] so the signatures name no hook type.
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();

        // The clock must be in place before the first registration; later class loaders calling
        // this again are ignored by the hooks.
        MethodHandle setClock = lookup.findStatic(hooks, "setClock", methodType(boolean.class, MethodHandle.class))
                .asType(methodType(void.class, MethodHandle.class));
        setClock.invokeExact(carrierCpuNanos);

        // The tracker travels as Object so that the handles share one invoker; unregister and
        // dropOpenInterval return nothing, which asType turns into a zero.
        MethodType reader = methodType(long.class, Object.class);
        return new HookHandles(
                lookup.findStatic(hooks, "register", methodType(long[].class)).asType(methodType(Object.class)),
                lookup.findStatic(hooks, "unregister", methodType(void.class, long[].class)).asType(reader),
                lookup.findStatic(hooks, "dropOpenInterval", methodType(void.class, long[].class)).asType(reader),
                lookup.findStatic(hooks, "mountedTimeNanos", methodType(long.class, long[].class)).asType(reader),
                lookup.findStatic(hooks, "mountCount", methodType(long.class, long[].class)).asType(reader),
                lookup.findStatic(hooks, "unmountCount", methodType(long.class, long[].class)).asType(reader));
    }

    private static byte[] classBytes(String name)
            throws IOException
    {
        String resource = "/" + internalName(name) + ".class";
        try (InputStream input = NativeAccess.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Hook class resource is not available: " + resource);
            }
            return input.readAllBytes();
        }
    }

    private static String internalName(String className)
    {
        return className.replace('.', '/');
    }

    private static long invokeLong(MethodHandle handle)
    {
        try {
            return (long) handle.invokeExact();
        }
        catch (Throwable throwable) {
            throw new IllegalStateException("Native call failed", throwable);
        }
    }

    /// Calls a hook member on `tracker`, a `long[]` typed as Object, through a handle of type
    /// `(Object)long`.
    private static long invokeLong(MethodHandle handle, Object tracker)
    {
        try {
            return (long) handle.invokeExact(tracker);
        }
        catch (Throwable throwable) {
            throw new IllegalStateException("Hook call failed", throwable);
        }
    }

    /// The native status codes; see the Status enum in vthreadtime.c.
    private static String statusMessage(int status)
    {
        return switch (status) {
            case 1 -> "JVMTI is unavailable";
            case 2 -> "native allocation failed";
            case 3 -> "hook class definition failed";
            case 4 -> "module readability update failed";
            case 5 -> "VirtualThread transformation failed";
            case 6 -> "VirtualThread retransformation failed";
            default -> "unknown native status " + status;
        };
    }

    /// Per-registration state behind [VirtualThreadTime]: the `long[]` the boot-loaded hook class
    /// updates, behind exact-typed handles the JIT inlines. Only the registering thread uses an
    /// instance.
    ///
    /// A thread mounted when another agent removes the hooks never sees `unmounted()` again, so
    /// its tracker would stay mounted with a stamp from that carrier and every later read would
    /// add the current carrier's clock minus it. Reads and the close therefore check that the
    /// hooks are still in place and drop the open interval once they are not. A tracker registered
    /// while the hooks are not measuring has no hook object and every count is zero.
    static final class Tracker
    {
        // Serves every registration made while the hooks are not measuring
        private static final Tracker COUNTING_NOTHING = new Tracker(new long[0]);

        private final Object mountedTime;
        private final boolean counting;

        private Tracker(Object mountedTime)
        {
            this.mountedTime = requireNonNull(mountedTime, "mountedTime is null");
            this.counting = ((long[]) mountedTime).length != 0;
        }

        long mountedTimeNanos()
        {
            if (!counting) {
                return 0;
            }
            dropOpenIntervalIfHooksRemoved();
            return invokeLong(HOOKS.mountedTimeNanos(), mountedTime);
        }

        long mountCount()
        {
            return counting ? invokeLong(HOOKS.mountCount(), mountedTime) : 0;
        }

        long unmountCount()
        {
            return counting ? invokeLong(HOOKS.unmountCount(), mountedTime) : 0;
        }

        void close()
        {
            if (!counting) {
                return;
            }
            dropOpenIntervalIfHooksRemoved();
            invokeLong(HOOKS.unregister(), mountedTime);
        }

        private void dropOpenIntervalIfHooksRemoved()
        {
            if (!hooksInstalled()) {
                invokeLong(HOOKS.dropOpenInterval(), mountedTime);
            }
        }
    }

    /// The hook members, resolved once; the record's final components let the JIT treat the
    /// handles as constants.
    private record HookHandles(
            MethodHandle register,
            MethodHandle unregister,
            MethodHandle dropOpenInterval,
            MethodHandle mountedTimeNanos,
            MethodHandle mountCount,
            MethodHandle unmountCount) {}
}
