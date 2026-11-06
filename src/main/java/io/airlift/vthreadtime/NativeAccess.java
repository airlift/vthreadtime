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

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.foreign.Linker.Option.critical;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.invoke.MethodType.methodType;

final class NativeAccess
{
    /// How mount and unmount transitions reach the library. Hooks retransformed into
    /// `java.lang.VirtualThread` cost a few nanoseconds per transition; the JVMTI mount and unmount
    /// events cost HotSpot's event delivery on every virtual thread. Hooks are the default and the
    /// events the fallback when they cannot be installed.
    enum Mode
    {
        HOOKS,
        EVENTS,
    }

    static final String MODE_PROPERTY = "io.airlift.vthreadtime.mode";

    // The hook classes are defined into the bootstrap loader at runtime, so this class must only
    // ever reach them by name through that loader, never as compile-time references.
    private static final String HOOKS_CLASS = "io.airlift.vthreadtime.hook.VirtualThreadHooks";
    private static final String MOUNTED_TIME_CLASS = "io.airlift.vthreadtime.hook.MountedTime";

    // Layout of ThreadState in vthreadtime.c, used by the events mode. Mounted time is only read
    // through airlift_vthreadtime_mounted_nanos, which closes the open interval on the carrier's
    // CPU clock.
    private static final long MOUNT_COUNT_OFFSET = 16;
    private static final long UNMOUNT_COUNT_OFFSET = 24;
    private static final long STATE_SIZE = 40;

    private static final MethodHandle REGISTER;
    private static final MethodHandle STATE_ADDRESS;
    private static final MethodHandle MOUNTED_NANOS;
    private static final MethodHandle UNREGISTER;
    private static final MethodHandle ACTIVE_REGISTRATIONS;

    private static final MethodHandle HOOKS_INSTALLED;
    private static final MethodHandle HOOK_REGISTER;
    private static final MethodHandle HOOK_UNREGISTER;
    private static final MethodHandle HOOK_ACTIVE_REGISTRATIONS;
    private static final MethodHandle HOOK_MOUNTED_TIME_NANOS;
    private static final MethodHandle HOOK_MOUNT_COUNT;
    private static final MethodHandle HOOK_UNMOUNT_COUNT;

    private static final Mode MODE;
    private static final int HOOKS_FAILURE;
    private static final Throwable HOOKS_FAILURE_CAUSE;
    private static final Throwable INITIALIZATION_FAILURE;

    // The transformed class bytes must stay reachable until the native retransformation has
    // copied them, which happens on this thread before the retransform downcall returns; the
    // arena of the last attempt remains referenced after it is closed, which is harmless because
    // the transformer only runs during that downcall.
    private static Arena transformArena;
    private static final AtomicReference<Throwable> TRANSFORM_FAILURE = new AtomicReference<>();

    static {
        MethodHandle register = null;
        MethodHandle stateAddress = null;
        MethodHandle mountedNanos = null;
        MethodHandle unregister = null;
        MethodHandle activeRegistrations = null;
        MethodHandle hooksInstalled = null;
        MethodHandle hookRegister = null;
        MethodHandle hookUnregister = null;
        MethodHandle hookActiveRegistrations = null;
        MethodHandle hookMountedTimeNanos = null;
        MethodHandle hookMountCount = null;
        MethodHandle hookUnmountCount = null;
        Mode mode = null;
        int hooksFailure = 0;
        Throwable hooksFailureCause = null;
        Throwable initializationFailure = null;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup symbols = NativeLibrary.load();

            MethodHandle initialize = linker.downcallHandle(
                    symbols.findOrThrow("airlift_vthreadtime_initialize"),
                    FunctionDescriptor.of(JAVA_INT));
            register = linker.downcallHandle(
                    symbols.findOrThrow("airlift_vthreadtime_register"),
                    FunctionDescriptor.of(JAVA_INT));
            stateAddress = linker.downcallHandle(
                    symbols.findOrThrow("airlift_vthreadtime_state_address"),
                    FunctionDescriptor.of(JAVA_LONG),
                    critical(false));
            mountedNanos = linker.downcallHandle(
                    symbols.findOrThrow("airlift_vthreadtime_mounted_nanos"),
                    FunctionDescriptor.of(JAVA_LONG, JAVA_LONG),
                    critical(false));
            unregister = linker.downcallHandle(
                    symbols.findOrThrow("airlift_vthreadtime_unregister"),
                    FunctionDescriptor.of(JAVA_INT));
            activeRegistrations = linker.downcallHandle(
                    symbols.findOrThrow("airlift_vthreadtime_active_registrations"),
                    FunctionDescriptor.of(JAVA_LONG));
            MethodHandle carrierCpuNanos = linker.downcallHandle(
                    symbols.findOrThrow("airlift_vthreadtime_carrier_cpu_nanos"),
                    FunctionDescriptor.of(JAVA_LONG),
                    critical(false));

            mode = configuredMode();
            if (mode == Mode.HOOKS) {
                hooksInstalled = linker.downcallHandle(
                        symbols.findOrThrow("airlift_vthreadtime_hooks_installed"),
                        FunctionDescriptor.of(JAVA_LONG),
                        critical(false));
                hooksFailure = installHooks(linker, symbols);
                if (hooksFailure != 0) {
                    hooksFailureCause = TRANSFORM_FAILURE.get();
                    mode = Mode.EVENTS;
                }
            }
            if (mode == Mode.EVENTS) {
                int status = (int) initialize.invokeExact();
                if (status != 0) {
                    throw new UnsupportedOperationException("Native initialization failed: " + statusMessage(status));
                }
            }
            else {
                HookHandles hooks = resolveHooks(carrierCpuNanos);
                hookRegister = hooks.register();
                hookUnregister = hooks.unregister();
                hookActiveRegistrations = hooks.activeRegistrations();
                hookMountedTimeNanos = hooks.mountedTimeNanos();
                hookMountCount = hooks.mountCount();
                hookUnmountCount = hooks.unmountCount();
            }
        }
        catch (Throwable throwable) {
            initializationFailure = throwable;
            mode = null;
        }
        REGISTER = register;
        STATE_ADDRESS = stateAddress;
        MOUNTED_NANOS = mountedNanos;
        UNREGISTER = unregister;
        ACTIVE_REGISTRATIONS = activeRegistrations;
        HOOKS_INSTALLED = hooksInstalled;
        HOOK_REGISTER = hookRegister;
        HOOK_UNREGISTER = hookUnregister;
        HOOK_ACTIVE_REGISTRATIONS = hookActiveRegistrations;
        HOOK_MOUNTED_TIME_NANOS = hookMountedTimeNanos;
        HOOK_MOUNT_COUNT = hookMountCount;
        HOOK_UNMOUNT_COUNT = hookUnmountCount;
        MODE = mode;
        HOOKS_FAILURE = hooksFailure;
        HOOKS_FAILURE_CAUSE = hooksFailureCause;
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

    /// The active mechanism, or `null` when unsupported.
    static Mode mode()
    {
        return MODE;
    }

    /// The native status that made hooks installation fall back to events, zero otherwise.
    static int hooksFailure()
    {
        return HOOKS_FAILURE;
    }

    /// The transformer failure behind [#hooksFailure()], if the transformer was what failed.
    static Throwable hooksFailureCause()
    {
        return HOOKS_FAILURE_CAUSE;
    }

    /// Registers the current thread and returns its tracker.
    ///
    /// @throws IllegalStateException in hooks mode if another agent has since retransformed
    ///         `VirtualThread` from other class bytes, which removed the hooks
    static Tracker register()
    {
        ensureSupported();
        if (MODE == Mode.HOOKS) {
            if (invokeLong(HOOKS_INSTALLED) == 0) {
                throw new IllegalStateException("Could not register: " + statusMessage(20));
            }
            try {
                return new HookTracker((Object) HOOK_REGISTER.invokeExact());
            }
            catch (RuntimeException exception) {
                throw exception;
            }
            catch (Throwable throwable) {
                throw new IllegalStateException("Hook registration failed", throwable);
            }
        }
        checkStatus(invokeInt(REGISTER), "register");
        long address = invokeLong(STATE_ADDRESS);
        if (address == 0) {
            throw new IllegalStateException("Could not register: native state is unavailable");
        }
        return new NativeTracker(MemorySegment.ofAddress(address).reinterpret(STATE_SIZE));
    }

    static long activeRegistrations()
    {
        ensureSupported();
        if (MODE == Mode.HOOKS) {
            try {
                return (long) HOOK_ACTIVE_REGISTRATIONS.invokeExact();
            }
            catch (Throwable throwable) {
                throw new IllegalStateException("Hook call failed", throwable);
            }
        }
        return invokeLong(ACTIVE_REGISTRATIONS);
    }

    private static Mode configuredMode()
    {
        String value = System.getProperty(MODE_PROPERTY, "hooks");
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "hooks" -> Mode.HOOKS;
            case "events" -> Mode.EVENTS;
            default -> throw new IllegalArgumentException("Unknown %s: %s".formatted(MODE_PROPERTY, value));
        };
    }

    /// Defines the hook classes into the bootstrap loader, initializes them, and then retransforms
    /// `VirtualThread` to call them. Initialization happens here, on the installing thread, so no
    /// virtual thread can trigger it from inside `mount()`. Another class loader may have done all
    /// of this already; the boot-loaded classes are then reused. Returns the native status.
    private static int installHooks(Linker linker, SymbolLookup symbols)
            throws Throwable
    {
        if (!isBootClassDefined(HOOKS_CLASS)) {
            MethodHandle defineHookClasses = linker.downcallHandle(
                    symbols.findOrThrow("airlift_vthreadtime_define_hook_classes"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            byte[] mountedTime = classBytes(MOUNTED_TIME_CLASS);
            byte[] hooks = classBytes(HOOKS_CLASS);
            try (Arena arena = Arena.ofConfined()) {
                int status = (int) defineHookClasses.invokeExact(
                        arena.allocateFrom(internalName(MOUNTED_TIME_CLASS)),
                        arena.allocateFrom(JAVA_BYTE, mountedTime),
                        mountedTime.length,
                        arena.allocateFrom(internalName(HOOKS_CLASS)),
                        arena.allocateFrom(JAVA_BYTE, hooks),
                        hooks.length);
                if (status != 0) {
                    return status;
                }
            }
        }
        Class.forName(MOUNTED_TIME_CLASS, true, null);
        Class.forName(HOOKS_CLASS, true, null);

        MethodHandle retransform = linker.downcallHandle(
                symbols.findOrThrow("airlift_vthreadtime_retransform_virtual_thread"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        MethodHandle transform = MethodHandles.lookup().findStatic(
                NativeAccess.class,
                "transform",
                methodType(int.class, MemorySegment.class, int.class, MemorySegment.class, MemorySegment.class));
        try (Arena arena = Arena.ofConfined()) {
            transformArena = arena;
            MemorySegment transformer = linker.upcallStub(
                    transform,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                    arena);
            return (int) retransform.invokeExact(transformer);
        }
    }

    /// Called by the native retransformation, on the installing thread, with the original class
    /// bytes of `VirtualThread`. Writes the transformed bytes and their length through the two
    /// out-parameters and returns zero, or returns one when the transformation fails.
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
        Class<?> mountedTime = Class.forName(MOUNTED_TIME_CLASS, true, null);
        // Resolve through a lookup whose class is in the bootstrap loader. On the module path this
        // module contains its own copy of the hook package, and a lookup from this class would
        // then see two different MountedTime types in the signatures and fail the loader
        // constraint check. The hook members are public in an unconditionally exported package,
        // which is all the public lookup needs.
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();

        // The clock must be in place before the first registration; later class loaders calling
        // this again are ignored by the hooks.
        MethodHandle setClock = lookup.findStatic(hooks, "setClock", methodType(boolean.class, MethodHandle.class))
                .asType(methodType(void.class, MethodHandle.class));
        setClock.invokeExact(carrierCpuNanos);

        return new HookHandles(
                lookup.findStatic(hooks, "register", methodType(mountedTime))
                        .asType(methodType(Object.class)),
                lookup.findStatic(hooks, "unregister", methodType(void.class, mountedTime))
                        .asType(methodType(void.class, Object.class)),
                lookup.findStatic(hooks, "activeRegistrations", methodType(long.class)),
                lookup.findVirtual(mountedTime, "mountedTimeNanos", methodType(long.class))
                        .asType(methodType(long.class, Object.class)),
                lookup.findVirtual(mountedTime, "mountCount", methodType(long.class))
                        .asType(methodType(long.class, Object.class)),
                lookup.findVirtual(mountedTime, "unmountCount", methodType(long.class))
                        .asType(methodType(long.class, Object.class)));
    }

    private static boolean isBootClassDefined(String name)
    {
        try {
            Class.forName(name, false, null);
            return true;
        }
        catch (ClassNotFoundException ignored) {
            return false;
        }
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

    private static int invokeInt(MethodHandle handle)
    {
        ensureSupported();
        try {
            return (int) handle.invokeExact();
        }
        catch (Throwable throwable) {
            throw new IllegalStateException("Native call failed", throwable);
        }
    }

    private static long invokeLong(MethodHandle handle)
    {
        ensureSupported();
        try {
            return (long) handle.invokeExact();
        }
        catch (Throwable throwable) {
            throw new IllegalStateException("Native call failed", throwable);
        }
    }

    private static void checkStatus(int status, String operation)
    {
        if (status != 0) {
            throw new IllegalStateException("Could not %s: %s".formatted(operation, statusMessage(status)));
        }
    }

    private static String statusMessage(int status)
    {
        if (status >= 1_000) {
            return "JVMTI error " + (status - 1_000);
        }
        return switch (status) {
            case 0 -> "success";
            case 1 -> "Java VM is unavailable";
            case 2 -> "JVMTI is unavailable";
            case 3 -> "virtual thread capability is unavailable";
            case 4 -> "JVMTI extension events are unavailable";
            case 5 -> "virtual thread mount events are unavailable";
            case 6 -> "callback installation failed";
            case 7 -> "event enable failed";
            case 8 -> "native library is not initialized";
            case 9 -> "current thread is unavailable";
            case 10 -> "current thread is already registered";
            case 11 -> "native allocation failed";
            case 12 -> "current thread is not registered";
            case 14 -> "hook class definition failed";
            case 15 -> "module readability update failed";
            case 16 -> "VirtualThread retransformation failed";
            case 17 -> "VirtualThread transformation failed";
            case 18 -> "hook classes are not defined";
            case 19 -> "virtual thread events are already enabled";
            case 20 -> "VirtualThread was retransformed by another agent, which removed the hooks";
            default -> "unknown native status " + status;
        };
    }

    /// Mounted time measured by the JVMTI callbacks. The segment is valid until the thread is
    /// unregistered or terminates and must only be read by that thread.
    private static final class NativeTracker
            implements Tracker
    {
        private final MemorySegment state;

        private NativeTracker(MemorySegment state)
        {
            this.state = state;
        }

        /// Closes the open mounted interval natively on the carrier's CPU clock, which Java cannot
        /// read for the carrier of a virtual thread.
        @Override
        public long mountedTimeNanos()
        {
            try {
                return (long) MOUNTED_NANOS.invokeExact(state.address());
            }
            catch (Throwable throwable) {
                throw new IllegalStateException("Native call failed", throwable);
            }
        }

        @Override
        public long mountCount()
        {
            return state.get(JAVA_LONG, MOUNT_COUNT_OFFSET);
        }

        @Override
        public long unmountCount()
        {
            return state.get(JAVA_LONG, UNMOUNT_COUNT_OFFSET);
        }

        @Override
        public void close()
        {
            checkStatus(invokeInt(UNREGISTER), "unregister");
        }
    }

    /// Mounted time measured by the hooks; wraps the boot-loaded `MountedTime` object, which this
    /// class cannot name, behind exact-typed handles the JIT inlines.
    private static final class HookTracker
            implements Tracker
    {
        private final Object mountedTime;

        private HookTracker(Object mountedTime)
        {
            this.mountedTime = mountedTime;
        }

        @Override
        public long mountedTimeNanos()
        {
            try {
                return (long) HOOK_MOUNTED_TIME_NANOS.invokeExact(mountedTime);
            }
            catch (Throwable throwable) {
                throw new IllegalStateException("Hook call failed", throwable);
            }
        }

        @Override
        public long mountCount()
        {
            try {
                return (long) HOOK_MOUNT_COUNT.invokeExact(mountedTime);
            }
            catch (Throwable throwable) {
                throw new IllegalStateException("Hook call failed", throwable);
            }
        }

        @Override
        public long unmountCount()
        {
            try {
                return (long) HOOK_UNMOUNT_COUNT.invokeExact(mountedTime);
            }
            catch (Throwable throwable) {
                throw new IllegalStateException("Hook call failed", throwable);
            }
        }

        @Override
        public void close()
        {
            try {
                HOOK_UNREGISTER.invokeExact(mountedTime);
            }
            catch (Throwable throwable) {
                throw new IllegalStateException("Hook call failed", throwable);
            }
        }
    }

    private record HookHandles(
            MethodHandle register,
            MethodHandle unregister,
            MethodHandle activeRegistrations,
            MethodHandle mountedTimeNanos,
            MethodHandle mountCount,
            MethodHandle unmountCount) {}
}
