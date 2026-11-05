/**
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

#define _POSIX_C_SOURCE 200809L

#include <jni.h>
#include <jvmti.h>

#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#if defined(__APPLE__)
#include <mach/mach_time.h>
#elif defined(__aarch64__) && defined(__linux__)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#ifndef HWCAP2_ECV
#define HWCAP2_ECV (1 << 19)
#endif
#elif defined(__x86_64__) && defined(__linux__)
#include <cpuid.h>
#include <stdio.h>
#include <x86intrin.h>
#endif

#define VTHREADTIME_EXPORT __attribute__((visibility("default")))

static const char MOUNT_EVENT_NAME[] = "com.sun.hotspot.events.VirtualThreadMount";
static const char UNMOUNT_EVENT_NAME[] = "com.sun.hotspot.events.VirtualThreadUnmount";

#define NANOS_PER_SECOND 1000000000ULL

typedef enum
{
    SUCCESS = 0,
    JAVA_VM_UNAVAILABLE = 1,
    JVMTI_UNAVAILABLE = 2,
    VIRTUAL_THREAD_CAPABILITY_UNAVAILABLE = 3,
    EXTENSION_EVENTS_UNAVAILABLE = 4,
    MOUNT_EVENTS_UNAVAILABLE = 5,
    CALLBACK_INSTALL_FAILED = 6,
    EVENT_ENABLE_FAILED = 7,
    NOT_INITIALIZED = 8,
    CURRENT_THREAD_UNAVAILABLE = 9,
    ALREADY_REGISTERED = 10,
    OUT_OF_MEMORY = 11,
    NOT_REGISTERED = 12,
    CLOCK_UNAVAILABLE = 13,
} Status;

// Timestamps are raw ticks of the clock selected at initialization and are converted to
// nanoseconds only when mounted time is read, so the mount and unmount callbacks never pay for
// the conversion and accumulated time never loses tick fractions.
typedef struct
{
    uint64_t mountedTicks;
    uint64_t mountStarted;
    uint64_t mountCount;
    uint64_t unmountCount;
    bool mounted;
    bool mountStartedValid;
} ThreadState;

// NativeAccess reads the counts directly through a memory segment; keep both layouts in sync.
_Static_assert(offsetof(ThreadState, mountedTicks) == 0, "ThreadState layout");
_Static_assert(offsetof(ThreadState, mountStarted) == 8, "ThreadState layout");
_Static_assert(offsetof(ThreadState, mountCount) == 16, "ThreadState layout");
_Static_assert(offsetof(ThreadState, unmountCount) == 24, "ThreadState layout");
_Static_assert(offsetof(ThreadState, mounted) == 32, "ThreadState layout");
_Static_assert(offsetof(ThreadState, mountStartedValid) == 33, "ThreadState layout");
_Static_assert(sizeof(ThreadState) == 40, "ThreadState layout");

__extension__ typedef unsigned __int128 uint128_t;

static jvmtiEnv* jvmti = NULL;
static _Atomic bool initialized = false;
static pthread_mutex_t initializationMutex = PTHREAD_MUTEX_INITIALIZER;
static int initializationFailure = SUCCESS;
static _Atomic uint64_t activeRegistrations = 0;

static _Thread_local ThreadState* currentState = NULL;

// nanos = (ticks * ticksToNanosMultiplier) >> ticksToNanosShift, evaluated in 128 bits.
static uint64_t ticksToNanosMultiplier = 0;
static unsigned ticksToNanosShift = 0;

#if defined(VTHREADTIME_TEST_INITIALIZATION_FAULTS)
static _Atomic bool initializationFaultInjected = false;
static _Atomic uint64_t testCallbackCount = 0;

static void waitForTestCallback(void)
{
    const struct timespec delay = {.tv_nsec = 1000000};
    for (int attempt = 0; attempt < 1000; attempt++) {
        if (atomic_load_explicit(&testCallbackCount, memory_order_relaxed) > 0) {
            return;
        }
        (void) nanosleep(&delay, NULL);
    }
}

static bool injectInitializationFault(const char* point)
{
    const char* configuredPoint = getenv("VTHREADTIME_TEST_INITIALIZATION_FAULT");
    bool inject = configuredPoint != NULL &&
            strcmp(configuredPoint, point) == 0 &&
            !atomic_exchange(&initializationFaultInjected, true);
    if (inject && (strcmp(point, "mount-event") == 0 || strcmp(point, "unmount-event") == 0)) {
        waitForTestCallback();
    }
    return inject;
}
#else
static bool injectInitializationFault(const char* point)
{
    (void) point;
    return false;
}
#endif

// Mounted time is a duration, so the clock only needs to be monotonic and to run at a known rate
// across carriers. That allows the cheapest counter each platform offers instead of the system
// clock behind System.nanoTime(): the architected counter on aarch64 Linux, whose rate is the
// counter frequency register, the time stamp counter on x86_64 Linux when the kernel itself
// trusts it, and mach_absolute_time on macOS. Each is what the kernel derives its monotonic
// clock from, so durations agree with System.nanoTime() up to NTP rate adjustments. Elsewhere
// CLOCK_MONOTONIC is used and a tick is a nanosecond.
//
// Neither counter read is ordered with respect to preceding instructions, so a stamp may be
// taken a few instructions early. A mount or unmount is bracketed by far more than that in
// HotSpot's transition code, so a stamp is never taken out of order with the transition it
// describes, and the error is bounded by the pipeline depth. This matches how Abseil and DPDK
// read the counters, and avoids a barrier that costs as much as the system clock call it
// replaces. aarch64 uses the self-synchronized counter where FEAT_ECV provides one.

#if !defined(__APPLE__)
static bool readPosixTicks(uint64_t* ticks)
{
    struct timespec value = {0};
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) {
        return false;
    }
    *ticks = ((uint64_t) value.tv_sec * NANOS_PER_SECOND) + (uint64_t) value.tv_nsec;
    return true;
}
#endif

// Chooses the largest shift whose multiplier fits in 63 bits, so the conversion is exact to
// well under a nanosecond over any realistic uptime.
static bool initializeTicksToNanos(uint64_t nanosNumerator, uint64_t nanosDenominator)
{
    if (nanosNumerator == 0 || nanosDenominator == 0) {
        return false;
    }
    unsigned shift = 63;
    uint128_t multiplier;
    while (true) {
        multiplier = (((uint128_t) nanosNumerator) << shift) / nanosDenominator;
        if (multiplier < ((uint128_t) 1 << 63) || shift == 0) {
            break;
        }
        shift--;
    }
    if (multiplier == 0 || multiplier >= ((uint128_t) 1 << 63)) {
        return false;
    }
    ticksToNanosMultiplier = (uint64_t) multiplier;
    ticksToNanosShift = shift;
    return true;
}

static uint64_t ticksToNanos(uint64_t ticks)
{
    return (uint64_t) (((uint128_t) ticks * ticksToNanosMultiplier) >> ticksToNanosShift);
}

#if defined(__APPLE__)

static bool readTicks(uint64_t* ticks)
{
    *ticks = mach_absolute_time();
    return true;
}

static bool initializeClock(void)
{
    mach_timebase_info_data_t timebase = {0};
    if (mach_timebase_info(&timebase) != KERN_SUCCESS) {
        return false;
    }
    return initializeTicksToNanos(timebase.numer, timebase.denom);
}

#elif defined(__aarch64__) && defined(__linux__)

typedef enum
{
    COUNTER_SELF_SYNCHRONIZED,
    COUNTER_UNORDERED,
    COUNTER_UNAVAILABLE,
} ArchitectedCounter;

static ArchitectedCounter architectedCounter = COUNTER_UNAVAILABLE;

static bool readTicks(uint64_t* ticks)
{
    switch (architectedCounter) {
        case COUNTER_SELF_SYNCHRONIZED:
            // CNTVCTSS_EL0, which older assemblers do not know by name.
            __asm__ volatile("mrs %0, s3_3_c14_c0_6" : "=r"(*ticks));
            return true;
        case COUNTER_UNORDERED:
            __asm__ volatile("mrs %0, cntvct_el0" : "=r"(*ticks));
            return true;
        default:
            return readPosixTicks(ticks);
    }
}

static bool initializeClock(void)
{
    uint64_t frequency = 0;
    __asm__ volatile("mrs %0, cntfrq_el0" : "=r"(frequency));
    if (frequency == 0) {
        // Firmware did not program the frequency register; the kernel then takes the rate from
        // the device tree instead, which is not visible here.
        architectedCounter = COUNTER_UNAVAILABLE;
        return initializeTicksToNanos(1, 1);
    }
    architectedCounter = (getauxval(AT_HWCAP2) & HWCAP2_ECV) != 0 ? COUNTER_SELF_SYNCHRONIZED : COUNTER_UNORDERED;
    return initializeTicksToNanos(NANOS_PER_SECOND, frequency);
}

#elif defined(__x86_64__) && defined(__linux__)

static bool useTimeStampCounter = false;

static bool readTicks(uint64_t* ticks)
{
    if (useTimeStampCounter) {
        *ticks = __rdtsc();
        return true;
    }
    return readPosixTicks(ticks);
}

static bool hasInvariantTimeStampCounter(void)
{
    unsigned eax = 0;
    unsigned ebx = 0;
    unsigned ecx = 0;
    unsigned edx = 0;
    if (__get_cpuid_max(0x80000000, NULL) < 0x80000007 ||
            !__get_cpuid(0x80000007, &eax, &ebx, &ecx, &edx)) {
        return false;
    }
    return (edx & (1u << 8)) != 0;
}

// The kernel selects the TSC clocksource only after verifying that the counter is constant and
// synchronized across CPUs, so defer to its judgment instead of repeating those checks.
static bool kernelUsesTimeStampCounter(void)
{
    FILE* file = fopen("/sys/devices/system/clocksource/clocksource0/current_clocksource", "r");
    if (file == NULL) {
        return false;
    }
    char name[16] = {0};
    bool result = fgets(name, sizeof(name), file) != NULL && strcmp(name, "tsc\n") == 0;
    (void) fclose(file);
    return result;
}

// Pairs the counter with CLOCK_MONOTONIC_RAW, which the kernel derives from the same counter
// with its own calibration, keeping the tightest of several attempts so a preemption between
// the two reads does not skew the sample.
static bool sampleTimeStampCounter(uint64_t* ticks, uint64_t* nanos)
{
    uint64_t bestSpread = UINT64_MAX;
    for (int attempt = 0; attempt < 8; attempt++) {
        uint64_t before = __rdtsc();
        struct timespec value = {0};
        if (clock_gettime(CLOCK_MONOTONIC_RAW, &value) != 0) {
            return false;
        }
        uint64_t after = __rdtsc();
        if (after < before || after - before >= bestSpread) {
            continue;
        }
        bestSpread = after - before;
        *ticks = before + bestSpread / 2;
        *nanos = ((uint64_t) value.tv_sec * NANOS_PER_SECOND) + (uint64_t) value.tv_nsec;
    }
    return bestSpread != UINT64_MAX;
}

static bool initializeClock(void)
{
    useTimeStampCounter = false;
    if (hasInvariantTimeStampCounter() && kernelUsesTimeStampCounter()) {
        uint64_t startTicks = 0;
        uint64_t startNanos = 0;
        uint64_t endTicks = 0;
        uint64_t endNanos = 0;
        const struct timespec window = {.tv_nsec = 10000000};
        if (sampleTimeStampCounter(&startTicks, &startNanos)) {
            (void) nanosleep(&window, NULL);
            if (sampleTimeStampCounter(&endTicks, &endNanos) &&
                    endTicks > startTicks &&
                    endNanos > startNanos + 1000000) {
                uint64_t ticks = endTicks - startTicks;
                uint64_t nanos = endNanos - startNanos;
                // Accept rates between 100 MHz and 100 GHz; anything else is a broken reading.
                if (ticks >= nanos / 10 && ticks <= nanos * 100 && initializeTicksToNanos(nanos, ticks)) {
                    useTimeStampCounter = true;
                    return true;
                }
            }
        }
    }
    return initializeTicksToNanos(1, 1);
}

#else

static bool readTicks(uint64_t* ticks)
{
    return readPosixTicks(ticks);
}

static bool initializeClock(void)
{
    return initializeTicksToNanos(1, 1);
}

#endif

// Thread-local storage is only ever accessed for the current thread. Passing NULL takes the
// HotSpot fast path, which avoids the VM transition, the VTMS transition disabler and the
// thread-list handle that resolving a jthread requires. Inside the mount and thread-end
// callbacks the current thread is the virtual thread the event is about.
static jvmtiError getState(jvmtiEnv* jvmti, ThreadState** state)
{
    *state = NULL;
    void* value = NULL;
    jvmtiError error = (*jvmti)->GetThreadLocalStorage(jvmti, NULL, &value);
    if (error == JVMTI_ERROR_NONE) {
        *state = (ThreadState*) value;
    }
    return error;
}

static jvmtiError clearState(jvmtiEnv* jvmti, ThreadState* state)
{
    if (state == NULL) {
        return JVMTI_ERROR_NONE;
    }
    jvmtiError error = (*jvmti)->SetThreadLocalStorage(jvmti, NULL, NULL);
    if (error != JVMTI_ERROR_NONE) {
        return error;
    }
    if (currentState == state) {
        currentState = NULL;
    }
    free(state);
    atomic_fetch_sub_explicit(&activeRegistrations, 1, memory_order_relaxed);
    return JVMTI_ERROR_NONE;
}

static void JNICALL onMount(jvmtiEnv* jvmti, ...)
{
#if defined(VTHREADTIME_TEST_INITIALIZATION_FAULTS)
    atomic_fetch_add_explicit(&testCallbackCount, 1, memory_order_relaxed);
#endif
    // With no registrations there is no state to find, so skip the JVMTI lookup that every
    // other mount pays. A thread's own registration is ordered before its later mounts by the
    // scheduler handoff, so a registered thread never observes a stale zero here.
    if (atomic_load_explicit(&activeRegistrations, memory_order_relaxed) == 0) {
        currentState = NULL;
        return;
    }

    ThreadState* state;
    (void) getState(jvmti, &state);
    currentState = state;
    if (state == NULL) {
        return;
    }

    state->mountStartedValid = readTicks(&state->mountStarted);
    state->mountCount++;
    state->mounted = true;
}

static void JNICALL onUnmount(jvmtiEnv* jvmti, ...)
{
#if defined(VTHREADTIME_TEST_INITIALIZATION_FAULTS)
    atomic_fetch_add_explicit(&testCallbackCount, 1, memory_order_relaxed);
#endif
    (void) jvmti;

    ThreadState* state = currentState;
    currentState = NULL;
    if (state == NULL) {
        return;
    }

    uint64_t now;
    if (state->mountStartedValid && readTicks(&now) && now >= state->mountStarted) {
        state->mountedTicks += now - state->mountStarted;
    }
    state->unmountCount++;
    state->mounted = false;
    state->mountStartedValid = false;
}

static void JNICALL onVirtualThreadEnd(jvmtiEnv* jvmti, JNIEnv* jni, jthread thread)
{
#if defined(VTHREADTIME_TEST_INITIALIZATION_FAULTS)
    atomic_fetch_add_explicit(&testCallbackCount, 1, memory_order_relaxed);
#endif
    (void) jni;
    (void) thread;
    ThreadState* state;
    if (getState(jvmti, &state) == JVMTI_ERROR_NONE) {
        (void) clearState(jvmti, state);
    }
}

static void deallocateExtensionEvents(jvmtiEnv* jvmti, jint count, jvmtiExtensionEventInfo* events)
{
    if (events == NULL) {
        return;
    }
    for (jint index = 0; index < count; index++) {
        if (events[index].id != NULL) {
            (void) (*jvmti)->Deallocate(jvmti, (unsigned char*) events[index].id);
        }
        if (events[index].short_description != NULL) {
            (void) (*jvmti)->Deallocate(jvmti, (unsigned char*) events[index].short_description);
        }
        for (jint parameter = 0; parameter < events[index].param_count; parameter++) {
            if (events[index].params[parameter].name != NULL) {
                (void) (*jvmti)->Deallocate(jvmti, (unsigned char*) events[index].params[parameter].name);
            }
        }
        if (events[index].params != NULL) {
            (void) (*jvmti)->Deallocate(jvmti, (unsigned char*) events[index].params);
        }
    }
    (void) (*jvmti)->Deallocate(jvmti, (unsigned char*) events);
}

static JavaVM* findJavaVm(void)
{
    JavaVM* vm = NULL;
    jsize count = 0;
    if (JNI_GetCreatedJavaVMs(&vm, 1, &count) != JNI_OK || count != 1 || vm == NULL) {
        return NULL;
    }
    return vm;
}

static bool hasExpectedVirtualThreadEventParameters(const jvmtiExtensionEventInfo* event)
{
    if (event->param_count != 2 || event->params == NULL) {
        return false;
    }
    const jvmtiParamInfo* jni = &event->params[0];
    const jvmtiParamInfo* thread = &event->params[1];
    return jni->kind == JVMTI_KIND_IN_PTR &&
            jni->base_type == JVMTI_TYPE_JNIENV &&
            thread->kind == JVMTI_KIND_IN &&
            thread->base_type == JVMTI_TYPE_JTHREAD;
}

static void deactivateInitialization(jvmtiEnv* jvmti, jint mountIndex, jint unmountIndex)
{
    if (unmountIndex >= 0) {
        (void) (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_DISABLE, (jvmtiEvent) unmountIndex, NULL);
    }
    if (mountIndex >= 0) {
        (void) (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_DISABLE, (jvmtiEvent) mountIndex, NULL);
    }
    (void) (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_DISABLE, JVMTI_EVENT_VIRTUAL_THREAD_END, NULL);

    if (unmountIndex >= 0) {
        (void) (*jvmti)->SetExtensionEventCallback(jvmti, unmountIndex, NULL);
    }
    if (mountIndex >= 0) {
        (void) (*jvmti)->SetExtensionEventCallback(jvmti, mountIndex, NULL);
    }
    (void) (*jvmti)->SetEventCallbacks(jvmti, NULL, 0);

    jvmtiCapabilities capabilities = {0};
    capabilities.can_support_virtual_threads = 1;
    (void) (*jvmti)->RelinquishCapabilities(jvmti, &capabilities);
}

static int failInitialization(jvmtiEnv* jvmti, jint mountIndex, jint unmountIndex, Status status)
{
    // Initialization is one-shot and the native image remains loaded until JVM exit. Deactivate
    // callbacks best-effort, but retain the JVMTI environment because handlers already in flight
    // continue after notification removal and must not observe a disposed environment.
    deactivateInitialization(jvmti, mountIndex, unmountIndex);
    initializationFailure = status;
    return initializationFailure;
}

static int initializeLocked(void)
{
    if (atomic_load_explicit(&initialized, memory_order_acquire)) {
        return SUCCESS;
    }
    if (initializationFailure != SUCCESS) {
        return initializationFailure;
    }

    // Select the clock before touching the JVM, so a failure here leaves no JVMTI state behind.
    if (!initializeClock()) {
        initializationFailure = CLOCK_UNAVAILABLE;
        return initializationFailure;
    }

    JavaVM* vm = findJavaVm();
    if (vm == NULL) {
        initializationFailure = JAVA_VM_UNAVAILABLE;
        return initializationFailure;
    }
    if ((*vm)->GetEnv(vm, (void**) &jvmti, JVMTI_VERSION_21) != JNI_OK || jvmti == NULL) {
        initializationFailure = JVMTI_UNAVAILABLE;
        return initializationFailure;
    }
    jint mountIndex = -1;
    jint unmountIndex = -1;

    jvmtiCapabilities capabilities = {0};
    capabilities.can_support_virtual_threads = 1;
    if ((*jvmti)->AddCapabilities(jvmti, &capabilities) != JVMTI_ERROR_NONE) {
        return failInitialization(jvmti, mountIndex, unmountIndex, VIRTUAL_THREAD_CAPABILITY_UNAVAILABLE);
    }

    jint eventCount = 0;
    jvmtiExtensionEventInfo* events = NULL;
    if ((*jvmti)->GetExtensionEvents(jvmti, &eventCount, &events) != JVMTI_ERROR_NONE) {
        return failInitialization(jvmti, mountIndex, unmountIndex, EXTENSION_EVENTS_UNAVAILABLE);
    }
    for (jint index = 0; index < eventCount; index++) {
        if (events[index].id == NULL || !hasExpectedVirtualThreadEventParameters(&events[index])) {
            continue;
        }
        if (strcmp(events[index].id, MOUNT_EVENT_NAME) == 0) {
            mountIndex = events[index].extension_event_index;
        }
        else if (strcmp(events[index].id, UNMOUNT_EVENT_NAME) == 0) {
            unmountIndex = events[index].extension_event_index;
        }
    }
    deallocateExtensionEvents(jvmti, eventCount, events);
    if (mountIndex < 0 || unmountIndex < 0) {
        return failInitialization(jvmti, mountIndex, unmountIndex, MOUNT_EVENTS_UNAVAILABLE);
    }

    jvmtiEventCallbacks callbacks = {0};
    callbacks.VirtualThreadEnd = onVirtualThreadEnd;
    if ((*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE) {
        return failInitialization(jvmti, mountIndex, unmountIndex, CALLBACK_INSTALL_FAILED);
    }
    if (injectInitializationFault("standard-callbacks")) {
        return failInitialization(jvmti, mountIndex, unmountIndex, CALLBACK_INSTALL_FAILED);
    }
    if ((*jvmti)->SetExtensionEventCallback(jvmti, mountIndex, onMount) != JVMTI_ERROR_NONE) {
        return failInitialization(jvmti, mountIndex, unmountIndex, CALLBACK_INSTALL_FAILED);
    }
    if (injectInitializationFault("mount-callback")) {
        return failInitialization(jvmti, mountIndex, unmountIndex, CALLBACK_INSTALL_FAILED);
    }
    if ((*jvmti)->SetExtensionEventCallback(jvmti, unmountIndex, onUnmount) != JVMTI_ERROR_NONE) {
        return failInitialization(jvmti, mountIndex, unmountIndex, CALLBACK_INSTALL_FAILED);
    }
    if (injectInitializationFault("unmount-callback")) {
        return failInitialization(jvmti, mountIndex, unmountIndex, CALLBACK_INSTALL_FAILED);
    }

    // Enable notifications globally once. On HotSpot JDK 25, changing them for each registered
    // thread synchronizes all virtual-thread transitions and makes short-lived registrations
    // prohibitively expensive. Each callback rejects unregistered threads through JVMTI TLS.
    if ((*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_END, NULL) != JVMTI_ERROR_NONE) {
        return failInitialization(jvmti, mountIndex, unmountIndex, EVENT_ENABLE_FAILED);
    }
    if (injectInitializationFault("thread-end-event")) {
        return failInitialization(jvmti, mountIndex, unmountIndex, EVENT_ENABLE_FAILED);
    }
    if ((*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, (jvmtiEvent) mountIndex, NULL) != JVMTI_ERROR_NONE) {
        return failInitialization(jvmti, mountIndex, unmountIndex, EVENT_ENABLE_FAILED);
    }
    if (injectInitializationFault("mount-event")) {
        return failInitialization(jvmti, mountIndex, unmountIndex, EVENT_ENABLE_FAILED);
    }
    if ((*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, (jvmtiEvent) unmountIndex, NULL) != JVMTI_ERROR_NONE) {
        return failInitialization(jvmti, mountIndex, unmountIndex, EVENT_ENABLE_FAILED);
    }
    if (injectInitializationFault("unmount-event")) {
        return failInitialization(jvmti, mountIndex, unmountIndex, EVENT_ENABLE_FAILED);
    }

    atomic_store_explicit(&initialized, true, memory_order_release);
    return SUCCESS;
}

static int initialize(void)
{
    (void) pthread_mutex_lock(&initializationMutex);
    int status = initializeLocked();
    (void) pthread_mutex_unlock(&initializationMutex);
    return status;
}

static int registerCurrentThread(void)
{
    if (!atomic_load_explicit(&initialized, memory_order_acquire)) {
        return NOT_INITIALIZED;
    }

    ThreadState* existingState;
    jvmtiError error = getState(jvmti, &existingState);
    if (error != JVMTI_ERROR_NONE) {
        return (int) error + 1000;
    }
    if (existingState != NULL) {
        return ALREADY_REGISTERED;
    }

    ThreadState* state = malloc(sizeof(*state));
    if (state == NULL) {
        return OUT_OF_MEMORY;
    }
    *state = (ThreadState) {0};
    state->mountStartedValid = readTicks(&state->mountStarted);
    state->mountCount = 1;
    state->mounted = true;

    error = (*jvmti)->SetThreadLocalStorage(jvmti, NULL, state);
    if (error != JVMTI_ERROR_NONE) {
        free(state);
        return (int) error + 1000;
    }

    currentState = state;
    atomic_fetch_add_explicit(&activeRegistrations, 1, memory_order_relaxed);
    return SUCCESS;
}

static int unregisterCurrentThread(void)
{
    if (!atomic_load_explicit(&initialized, memory_order_acquire)) {
        return NOT_INITIALIZED;
    }

    ThreadState* state;
    jvmtiError error = getState(jvmti, &state);
    if (error != JVMTI_ERROR_NONE) {
        return (int) error + 1000;
    }
    if (state == NULL) {
        return NOT_REGISTERED;
    }

    error = clearState(jvmti, state);
    if (error != JVMTI_ERROR_NONE) {
        return (int) error + 1000;
    }
    return SUCCESS;
}

VTHREADTIME_EXPORT int airlift_vthreadtime_initialize(void)
{
    return initialize();
}

VTHREADTIME_EXPORT int airlift_vthreadtime_register(void)
{
    return registerCurrentThread();
}

VTHREADTIME_EXPORT int64_t airlift_vthreadtime_state_address(void)
{
    return (int64_t) (intptr_t) currentState;
}

// Called by the registered thread through a critical downcall while it is mounted, so the state
// is stable and the open interval ends now.
VTHREADTIME_EXPORT int64_t airlift_vthreadtime_mounted_nanos(int64_t stateAddress)
{
    const ThreadState* state = (const ThreadState*) (intptr_t) stateAddress;
    uint64_t ticks = state->mountedTicks;
    uint64_t now;
    if (state->mounted && state->mountStartedValid && readTicks(&now) && now >= state->mountStarted) {
        ticks += now - state->mountStarted;
    }
    return (int64_t) ticksToNanos(ticks);
}

VTHREADTIME_EXPORT int airlift_vthreadtime_unregister(void)
{
    return unregisterCurrentThread();
}

VTHREADTIME_EXPORT int64_t airlift_vthreadtime_active_registrations(void)
{
    return (int64_t) atomic_load_explicit(&activeRegistrations, memory_order_relaxed);
}

#if defined(VTHREADTIME_TEST_INITIALIZATION_FAULTS)
VTHREADTIME_EXPORT int64_t airlift_vthreadtime_test_jvmti_get_phase_status(void)
{
    if (jvmti == NULL) {
        return JVMTI_ERROR_INVALID_ENVIRONMENT;
    }
    jvmtiPhase phase;
    return (int64_t) (*jvmti)->GetPhase(jvmti, &phase);
}

VTHREADTIME_EXPORT int64_t airlift_vthreadtime_test_callback_count(void)
{
    return (int64_t) atomic_load_explicit(&testCallbackCount, memory_order_relaxed);
}
#endif
