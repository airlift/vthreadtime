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
#include <stdarg.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define VTHREADTIME_EXPORT __attribute__((visibility("default")))

static const char MOUNT_EVENT_NAME[] = "com.sun.hotspot.events.VirtualThreadMount";
static const char UNMOUNT_EVENT_NAME[] = "com.sun.hotspot.events.VirtualThreadUnmount";

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
} Status;

typedef struct
{
    uint64_t mountedNanos;
    uint64_t mountStarted;
    uint64_t mountCount;
    uint64_t unmountCount;
    bool mounted;
    bool mountStartedValid;
} ThreadState;

static JavaVM* javaVm = NULL;
static jvmtiEnv* jvmti = NULL;
static _Atomic bool initialized = false;
static pthread_mutex_t initializationMutex = PTHREAD_MUTEX_INITIALIZER;
static int initializationFailure = SUCCESS;
static _Atomic uint64_t activeRegistrations = 0;

static _Thread_local ThreadState* currentState = NULL;

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

static bool monotonicTime(uint64_t* result)
{
    struct timespec value = {0};
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) {
        return false;
    }
    *result = ((uint64_t) value.tv_sec * 1000000000ULL) + (uint64_t) value.tv_nsec;
    return true;
}

static jvmtiError getState(jvmtiEnv* jvmti, jthread thread, ThreadState** state)
{
    *state = NULL;
    void* value = NULL;
    jvmtiError error = (*jvmti)->GetThreadLocalStorage(jvmti, thread, &value);
    if (error == JVMTI_ERROR_NONE) {
        *state = (ThreadState*) value;
    }
    return error;
}

static jvmtiError clearState(jvmtiEnv* jvmti, jthread thread, ThreadState* state)
{
    if (state == NULL) {
        return JVMTI_ERROR_NONE;
    }
    jvmtiError error = (*jvmti)->SetThreadLocalStorage(jvmti, thread, NULL);
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
    va_list arguments;
    va_start(arguments, jvmti);
    (void) va_arg(arguments, JNIEnv*);
    jthread thread = va_arg(arguments, jthread);
    va_end(arguments);

    currentState = NULL;
    ThreadState* state;
    if (getState(jvmti, thread, &state) != JVMTI_ERROR_NONE) {
        return;
    }
    currentState = state;
    if (state == NULL) {
        return;
    }

    state->mountStartedValid = monotonicTime(&state->mountStarted);
    state->mountCount++;
    state->mounted = true;
}

static void JNICALL onUnmount(jvmtiEnv* jvmti, ...)
{
#if defined(VTHREADTIME_TEST_INITIALIZATION_FAULTS)
    atomic_fetch_add_explicit(&testCallbackCount, 1, memory_order_relaxed);
#endif
    va_list arguments;
    va_start(arguments, jvmti);
    (void) va_arg(arguments, JNIEnv*);
    (void) va_arg(arguments, jthread);
    va_end(arguments);
    (void) jvmti;

    ThreadState* state = currentState;
    currentState = NULL;
    if (state == NULL) {
        return;
    }

    uint64_t now;
    if (state->mountStartedValid && monotonicTime(&now) && now >= state->mountStarted) {
        state->mountedNanos += now - state->mountStarted;
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
    ThreadState* state;
    if (getState(jvmti, thread, &state) == JVMTI_ERROR_NONE) {
        (void) clearState(jvmti, thread, state);
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

    javaVm = vm;
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

static void deleteLocalReference(jthread thread)
{
    JNIEnv* jni = NULL;
    if (thread != NULL &&
            (*javaVm)->GetEnv(javaVm, (void**) &jni, JNI_VERSION_10) == JNI_OK &&
            jni != NULL) {
        (*jni)->DeleteLocalRef(jni, thread);
    }
}

static int registerCurrentThread(void)
{
    if (!atomic_load_explicit(&initialized, memory_order_acquire)) {
        return NOT_INITIALIZED;
    }

    jthread thread = NULL;
    jvmtiError error = (*jvmti)->GetCurrentThread(jvmti, &thread);
    if (error != JVMTI_ERROR_NONE || thread == NULL) {
        return CURRENT_THREAD_UNAVAILABLE;
    }
    ThreadState* existingState;
    error = getState(jvmti, thread, &existingState);
    if (error != JVMTI_ERROR_NONE) {
        deleteLocalReference(thread);
        return (int) error + 1000;
    }
    if (existingState != NULL) {
        deleteLocalReference(thread);
        return ALREADY_REGISTERED;
    }

    ThreadState* state = malloc(sizeof(*state));
    if (state == NULL) {
        deleteLocalReference(thread);
        return OUT_OF_MEMORY;
    }
    *state = (ThreadState) {0};
    state->mountStartedValid = monotonicTime(&state->mountStarted);
    state->mountCount = 1;
    state->mounted = true;

    error = (*jvmti)->SetThreadLocalStorage(jvmti, thread, state);
    deleteLocalReference(thread);
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

    jthread thread = NULL;
    jvmtiError error = (*jvmti)->GetCurrentThread(jvmti, &thread);
    if (error != JVMTI_ERROR_NONE || thread == NULL) {
        return CURRENT_THREAD_UNAVAILABLE;
    }
    ThreadState* state;
    error = getState(jvmti, thread, &state);
    if (error != JVMTI_ERROR_NONE) {
        deleteLocalReference(thread);
        return (int) error + 1000;
    }
    if (state == NULL) {
        deleteLocalReference(thread);
        return NOT_REGISTERED;
    }

    error = clearState(jvmti, thread, state);
    deleteLocalReference(thread);
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

VTHREADTIME_EXPORT int64_t airlift_vthreadtime_mounted_nanos(void)
{
    ThreadState* state = currentState;
    if (state == NULL) {
        return -NOT_REGISTERED;
    }
    uint64_t mountedNanos = state->mountedNanos;
    uint64_t now;
    if (state->mounted && state->mountStartedValid && monotonicTime(&now) && now >= state->mountStarted) {
        mountedNanos += now - state->mountStarted;
    }
    return (int64_t) mountedNanos;
}

VTHREADTIME_EXPORT int64_t airlift_vthreadtime_mount_count(void)
{
    ThreadState* state = currentState;
    if (state == NULL) {
        return -NOT_REGISTERED;
    }
    return (int64_t) state->mountCount;
}

VTHREADTIME_EXPORT int64_t airlift_vthreadtime_unmount_count(void)
{
    ThreadState* state = currentState;
    if (state == NULL) {
        return -NOT_REGISTERED;
    }
    return (int64_t) state->unmountCount;
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
