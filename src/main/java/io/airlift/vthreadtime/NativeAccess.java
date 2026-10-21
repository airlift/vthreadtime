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

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.Linker.Option.critical;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

final class NativeAccess
{
    // Layout of ThreadState in vthreadtime.c
    private static final long MOUNTED_NANOS_OFFSET = 0;
    private static final long MOUNT_STARTED_OFFSET = 8;
    private static final long MOUNT_COUNT_OFFSET = 16;
    private static final long UNMOUNT_COUNT_OFFSET = 24;
    private static final long MOUNTED_OFFSET = 32;
    private static final long MOUNT_STARTED_VALID_OFFSET = 33;
    private static final long STATE_SIZE = 40;

    private static final MethodHandle REGISTER;
    private static final MethodHandle STATE_ADDRESS;
    private static final MethodHandle UNREGISTER;
    private static final MethodHandle ACTIVE_REGISTRATIONS;
    private static final Throwable INITIALIZATION_FAILURE;

    static {
        MethodHandle register = null;
        MethodHandle stateAddress = null;
        MethodHandle unregister = null;
        MethodHandle activeRegistrations = null;
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
            unregister = linker.downcallHandle(
                    symbols.findOrThrow("airlift_vthreadtime_unregister"),
                    FunctionDescriptor.of(JAVA_INT));
            activeRegistrations = linker.downcallHandle(
                    symbols.findOrThrow("airlift_vthreadtime_active_registrations"),
                    FunctionDescriptor.of(JAVA_LONG));

            int status = (int) initialize.invokeExact();
            if (status != 0) {
                throw new UnsupportedOperationException("Native initialization failed: " + statusMessage(status));
            }
        }
        catch (Throwable throwable) {
            initializationFailure = throwable;
        }
        REGISTER = register;
        STATE_ADDRESS = stateAddress;
        UNREGISTER = unregister;
        ACTIVE_REGISTRATIONS = activeRegistrations;
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

    /// Registers the current thread and returns its native state. The segment is valid until
    /// the thread is unregistered or terminates and must only be read by that thread.
    static MemorySegment register()
    {
        checkStatus(invokeInt(REGISTER), "register");
        long address = invokeLong(STATE_ADDRESS);
        if (address == 0) {
            throw new IllegalStateException("Could not register: native state is unavailable");
        }
        return MemorySegment.ofAddress(address).reinterpret(STATE_SIZE);
    }

    /// Mounted time uses the clock behind [System#nanoTime()]; the thread is mounted while it runs.
    static long mountedNanos(MemorySegment state)
    {
        long mountedNanos = state.get(JAVA_LONG, MOUNTED_NANOS_OFFSET);
        if (state.get(JAVA_BYTE, MOUNTED_OFFSET) != 0 && state.get(JAVA_BYTE, MOUNT_STARTED_VALID_OFFSET) != 0) {
            long elapsed = System.nanoTime() - state.get(JAVA_LONG, MOUNT_STARTED_OFFSET);
            if (elapsed >= 0) {
                mountedNanos += elapsed;
            }
        }
        return mountedNanos;
    }

    static long mountCount(MemorySegment state)
    {
        return state.get(JAVA_LONG, MOUNT_COUNT_OFFSET);
    }

    static long unmountCount(MemorySegment state)
    {
        return state.get(JAVA_LONG, UNMOUNT_COUNT_OFFSET);
    }

    static void unregister()
    {
        checkStatus(invokeInt(UNREGISTER), "unregister");
    }

    static long activeRegistrations()
    {
        ensureSupported();
        return invokeLong(ACTIVE_REGISTRATIONS);
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
            default -> "unknown native status " + status;
        };
    }
}
