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
    // JNI DefineClass rejected a hook class, typically because the bootstrap loader already has
    // a class of that name or the bytes are not a well-formed class file.
    HOOK_CLASS_DEFINE_FAILED = 14,
    // java.base could not be made to read the hook classes' module, so the calls injected into
    // java.lang.VirtualThread would fail their access check when linked.
    MODULE_READS_FAILED = 15,
    // RetransformClasses failed outright or rejected the transformed java.lang.VirtualThread.
    RETRANSFORM_FAILED = 16,
    // The transformer was never asked for java.lang.VirtualThread, declined it or returned no
    // class bytes.
    TRANSFORM_FAILED = 17,
    // Retransformation was requested before the classes the injected calls target were defined.
    HOOK_CLASSES_NOT_DEFINED = 18,
    // The events mode is already live in this process, so a later class loader must not install
    // hooks on top of it.
    EVENTS_ALREADY_ENABLED = 19,
    // java.lang.VirtualThread was retransformed or redefined from class bytes other than the ones
    // the hooks were applied to, so the hooks are no longer in the class.
    HOOKS_REMOVED = 20,
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

// NativeAccess reads the counts directly through a memory segment; keep both layouts in sync.
_Static_assert(offsetof(ThreadState, mountedNanos) == 0, "ThreadState layout");
_Static_assert(offsetof(ThreadState, mountStarted) == 8, "ThreadState layout");
_Static_assert(offsetof(ThreadState, mountCount) == 16, "ThreadState layout");
_Static_assert(offsetof(ThreadState, unmountCount) == 24, "ThreadState layout");
_Static_assert(offsetof(ThreadState, mounted) == 32, "ThreadState layout");
_Static_assert(offsetof(ThreadState, mountStartedValid) == 33, "ThreadState layout");
_Static_assert(sizeof(ThreadState) == 40, "ThreadState layout");

static const char VIRTUAL_THREAD_CLASS_NAME[] = "java/lang/VirtualThread";

// The JVMTI environment and the mutex are shared by both modes: the events mode, which
// is tracked by initialized and initializationFailure, and the hooks mode, which is set up in two
// steps tracked by hookClassesDefined and hooksInstalled with a latched failure for each. A failed
// step stays failed for the life of the process, as retrying could define a hook class twice.
// hooksRemoved latches when a later retransformation drops the hooks from the class.
static JavaVM* javaVm = NULL;
static jvmtiEnv* jvmti = NULL;
static _Atomic bool initialized = false;
static pthread_mutex_t initializationMutex = PTHREAD_MUTEX_INITIALIZER;
static int initializationFailure = SUCCESS;
static bool hookClassesDefined = false;
static int hookClassesFailure = SUCCESS;
static _Atomic bool hooksInstalled = false;
static int hooksInstallationFailure = SUCCESS;
static _Atomic bool hooksRemoved = false;
static _Atomic uint64_t activeRegistrations = 0;

static _Thread_local ThreadState* currentState = NULL;

typedef int32_t (*ClassTransformer)(const uint8_t* classData, int32_t length, uint8_t** newData, int32_t* newLength);

// VirtualThread is retransformed twice. The first pass only captures the class bytes the JVM
// presents for it, the transformer then runs in the native frame of the downcall, and the second
// pass installs its output. Calling the transformer from inside the ClassFileLoadHook instead
// crashes on macOS aarch64: JVMTI posts the event with the thread's JIT memory still in write
// mode, and an FFM upcall stub, unlike a JNI method call, is entered without switching it back.
typedef enum
{
    HOOK_PHASE_IDLE,
    HOOK_PHASE_CAPTURE,
    HOOK_PHASE_APPLY,
} HookPhase;

// HotSpot posts the ClassFileLoadHook for a retransformed class on the requesting thread and
// serializes retransformations of one class, so at most one callback for VirtualThread runs at a
// time; the callback rejects every other class by name before reading anything else. The phase is
// set by the installing thread and read by whichever thread retransforms VirtualThread, which is
// another agent's once the hooks are installed.
static _Atomic HookPhase hookPhase = HOOK_PHASE_IDLE;
static unsigned char* capturedClassData = NULL;
static jint capturedClassDataLength = 0;
static bool classDataApplied = false;
static bool classDataAllocationFailed = false;

// The class bytes the hooks were applied to and the bytes they produced, kept for the life of the
// process once the hooks are installed. HotSpot rebuilds a retransformed class from its original
// bytes, which it caches as the input of the first environment that changed the class, and
// consults only the environments with the ClassFileLoadHook enabled. The event therefore stays
// enabled and the callback serves installedClassData again whenever another agent retransforms
// VirtualThread from the input the applying pass saw; the hooks then survive. Any other input
// comes from a redefinition or from an agent ahead of this environment that now changes the
// class. The hooks cannot be reapplied there, because the transformer runs in Java and cannot be
// called from inside the callback, so they are reported as removed instead.
static unsigned char* installedInputClassData = NULL;
static jint installedInputClassDataLength = 0;
static unsigned char* installedClassData = NULL;
static jint installedClassDataLength = 0;

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

// Mounted time is the CPU time of the carrier thread, not wall time. A carrier that waits in the
// OS run queue while a virtual thread is mounted on it must not charge that wait to the virtual
// thread. Every caller runs on the carrier, so each start and end pair reads the same thread clock.
static bool carrierCpuTime(uint64_t* result)
{
    struct timespec value = {0};
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &value) != 0) {
        return false;
    }
    *result = ((uint64_t) value.tv_sec * 1000000000ULL) + (uint64_t) value.tv_nsec;
    return true;
}

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

    state->mountStartedValid = carrierCpuTime(&state->mountStarted);
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
    if (state->mountStartedValid && carrierCpuTime(&now) && now >= state->mountStarted) {
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
    (void) thread;
    ThreadState* state;
    if (getState(jvmti, &state) == JVMTI_ERROR_NONE) {
        (void) clearState(jvmti, state);
    }
}

// The JVM frees the new bytes with Deallocate, so they must be a fresh JVMTI allocation.
static bool serveClassData(jvmtiEnv* jvmti, const unsigned char* data, jint length, jint* newClassDataLength, unsigned char** newClassData)
{
    unsigned char* copy = NULL;
    if ((*jvmti)->Allocate(jvmti, length, &copy) != JVMTI_ERROR_NONE || copy == NULL) {
        return false;
    }
    memcpy(copy, data, (size_t) length);
    *newClassData = copy;
    *newClassDataLength = length;
    return true;
}

static void JNICALL onClassFileLoadHook(
        jvmtiEnv* jvmti,
        JNIEnv* jni,
        jclass classBeingRedefined,
        jobject loader,
        const char* name,
        jobject protectionDomain,
        jint classDataLength,
        const unsigned char* classData,
        jint* newClassDataLength,
        unsigned char** newClassData)
{
    (void) jni;
    (void) classBeingRedefined;
    (void) loader;
    (void) protectionDomain;
    // Every class loaded anywhere in the process while the event is enabled arrives here, so
    // nothing beyond the name comparison runs for the others.
    if (name == NULL || strcmp(name, VIRTUAL_THREAD_CLASS_NAME) != 0) {
        return;
    }
    HookPhase phase = atomic_load_explicit(&hookPhase, memory_order_acquire);
    if (phase == HOOK_PHASE_CAPTURE) {
        if (capturedClassData != NULL || classData == NULL || classDataLength <= 0) {
            return;
        }
        unsigned char* copy = malloc((size_t) classDataLength);
        if (copy == NULL) {
            classDataAllocationFailed = true;
            return;
        }
        memcpy(copy, classData, (size_t) classDataLength);
        capturedClassData = copy;
        capturedClassDataLength = classDataLength;
    }
    else if (phase == HOOK_PHASE_APPLY) {
        if (classData == NULL || classDataLength <= 0) {
            return;
        }
        unsigned char* input = malloc((size_t) classDataLength);
        if (input == NULL || !serveClassData(jvmti, installedClassData, installedClassDataLength, newClassDataLength, newClassData)) {
            free(input);
            classDataAllocationFailed = true;
            return;
        }
        memcpy(input, classData, (size_t) classDataLength);
        installedInputClassData = input;
        installedInputClassDataLength = classDataLength;
        classDataApplied = true;
    }
    else if (atomic_load_explicit(&hooksInstalled, memory_order_acquire)) {
        // Another agent is retransforming or redefining VirtualThread.
        if (classData == NULL ||
                classDataLength != installedInputClassDataLength ||
                memcmp(classData, installedInputClassData, (size_t) classDataLength) != 0 ||
                !serveClassData(jvmti, installedClassData, installedClassDataLength, newClassDataLength, newClassData)) {
            atomic_store_explicit(&hooksRemoved, true, memory_order_release);
        }
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

// Shared by both modes. The environment is created at most once, as every GetEnv for a JVMTI
// version creates another one, and it then lives until JVM exit.
static int ensureEnvironment(void)
{
    if (jvmti != NULL) {
        return SUCCESS;
    }
    JavaVM* vm = findJavaVm();
    if (vm == NULL) {
        return JAVA_VM_UNAVAILABLE;
    }
    jvmtiEnv* environment = NULL;
    if ((*vm)->GetEnv(vm, (void**) &environment, JVMTI_VERSION_21) != JNI_OK || environment == NULL) {
        return JVMTI_UNAVAILABLE;
    }
    javaVm = vm;
    jvmti = environment;
    return SUCCESS;
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

// SetEventCallbacks replaces the whole table, and either mode may be set up before the other in
// the same process, so both install the same table. A callback whose event is disabled is inert.
static jvmtiError installEventCallbacks(jvmtiEnv* jvmti)
{
    jvmtiEventCallbacks callbacks = {0};
    callbacks.VirtualThreadEnd = onVirtualThreadEnd;
    callbacks.ClassFileLoadHook = onClassFileLoadHook;
    return (*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks));
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

    int status = ensureEnvironment();
    if (status != SUCCESS) {
        initializationFailure = status;
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

    if (installEventCallbacks(jvmti) != JVMTI_ERROR_NONE) {
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

static bool clearPendingException(JNIEnv* jni)
{
    if (!(*jni)->ExceptionCheck(jni)) {
        return false;
    }
    (*jni)->ExceptionClear(jni);
    return true;
}

// The calling thread entered native code through a foreign downcall rather than a JNI method, so
// no local reference frame is scoped to the call and references created here would otherwise
// outlive it. Every successful push is paired with PopLocalFrame by the caller.
static int pushJniFrame(JNIEnv** jni)
{
    *jni = NULL;
    JNIEnv* environment = NULL;
    if ((*javaVm)->GetEnv(javaVm, (void**) &environment, JNI_VERSION_21) != JNI_OK || environment == NULL) {
        return CURRENT_THREAD_UNAVAILABLE;
    }
    if ((*environment)->PushLocalFrame(environment, 16) != JNI_OK) {
        (void) clearPendingException(environment);
        return OUT_OF_MEMORY;
    }
    *jni = environment;
    return SUCCESS;
}

// Both capabilities are in HotSpot's always-available set, so a live-phase environment may add
// them. They are never relinquished: the environment outlives the retransformed class anyway.
static int addRetransformCapabilities(jvmtiEnv* jvmti)
{
    jvmtiCapabilities capabilities = {0};
    capabilities.can_retransform_classes = 1;
    capabilities.can_retransform_any_class = 1;
    if ((*jvmti)->AddCapabilities(jvmti, &capabilities) != JVMTI_ERROR_NONE) {
        return VIRTUAL_THREAD_CAPABILITY_UNAVAILABLE;
    }
    return SUCCESS;
}

// Loader NULL is the bootstrap loader, the only one whose classes java.lang.VirtualThread can call.
static jclass defineHookClass(JNIEnv* jni, const char* name, const uint8_t* classData, int32_t length)
{
    if (name == NULL || classData == NULL || length <= 0) {
        return NULL;
    }
    jclass hookClass = (*jni)->DefineClass(jni, name, NULL, (const jbyte*) classData, length);
    if (clearPendingException(jni)) {
        return NULL;
    }
    return hookClass;
}

// The bootstrap loader places the hook classes in its unnamed module, which java.base does not
// read, so without this the calls injected into java.lang.VirtualThread would fail their access
// check when linked.
static int addModuleReads(JNIEnv* jni, jvmtiEnv* jvmti, jclass hooksClass)
{
    jclass classClass = (*jni)->FindClass(jni, "java/lang/Class");
    if (clearPendingException(jni) || classClass == NULL) {
        return MODULE_READS_FAILED;
    }
    jmethodID getModule = (*jni)->GetMethodID(jni, classClass, "getModule", "()Ljava/lang/Module;");
    if (clearPendingException(jni) || getModule == NULL) {
        return MODULE_READS_FAILED;
    }
    jclass objectClass = (*jni)->FindClass(jni, "java/lang/Object");
    if (clearPendingException(jni) || objectClass == NULL) {
        return MODULE_READS_FAILED;
    }
    jobject javaBase = (*jni)->CallObjectMethod(jni, objectClass, getModule);
    if (clearPendingException(jni) || javaBase == NULL) {
        return MODULE_READS_FAILED;
    }
    jobject hooksModule = (*jni)->CallObjectMethod(jni, hooksClass, getModule);
    if (clearPendingException(jni) || hooksModule == NULL) {
        return MODULE_READS_FAILED;
    }
    if ((*jvmti)->AddModuleReads(jvmti, javaBase, hooksModule) != JVMTI_ERROR_NONE) {
        return MODULE_READS_FAILED;
    }
    if (injectInitializationFault("module-reads")) {
        return MODULE_READS_FAILED;
    }
    return SUCCESS;
}

static int defineHookClasses(
        JNIEnv* jni,
        jvmtiEnv* jvmti,
        const char* firstClassName,
        const uint8_t* firstClass,
        int32_t firstLength,
        const char* secondClassName,
        const uint8_t* secondClass,
        int32_t secondLength)
{
    // The second class may reference the first, so the order is the caller's dependency order.
    if (defineHookClass(jni, firstClassName, firstClass, firstLength) == NULL) {
        return HOOK_CLASS_DEFINE_FAILED;
    }
    jclass hooksClass = defineHookClass(jni, secondClassName, secondClass, secondLength);
    if (hooksClass == NULL) {
        return HOOK_CLASS_DEFINE_FAILED;
    }
    if (injectInitializationFault("hook-class")) {
        return HOOK_CLASS_DEFINE_FAILED;
    }
    return addModuleReads(jni, jvmti, hooksClass);
}

static int defineHookClassesLocked(
        const char* firstClassName,
        const uint8_t* firstClass,
        int32_t firstLength,
        const char* secondClassName,
        const uint8_t* secondClass,
        int32_t secondLength)
{
    if (hookClassesDefined) {
        return SUCCESS;
    }
    if (hookClassesFailure != SUCCESS) {
        return hookClassesFailure;
    }

    int status = ensureEnvironment();
    if (status == SUCCESS) {
        status = addRetransformCapabilities(jvmti);
    }
    JNIEnv* jni = NULL;
    if (status == SUCCESS) {
        status = pushJniFrame(&jni);
    }
    if (status == SUCCESS) {
        status = defineHookClasses(jni, jvmti, firstClassName, firstClass, firstLength, secondClassName, secondClass, secondLength);
        (void) (*jni)->PopLocalFrame(jni, NULL);
    }
    if (status != SUCCESS) {
        hookClassesFailure = status;
        return status;
    }
    hookClassesDefined = true;
    return SUCCESS;
}

static int retransformPass(jvmtiEnv* jvmti, jclass virtualThreadClass, HookPhase phase)
{
    atomic_store_explicit(&hookPhase, phase, memory_order_release);
    jvmtiError error = (*jvmti)->RetransformClasses(jvmti, 1, &virtualThreadClass);
    atomic_store_explicit(&hookPhase, HOOK_PHASE_IDLE, memory_order_release);
    if (error != JVMTI_ERROR_NONE) {
        return RETRANSFORM_FAILED;
    }
    return classDataAllocationFailed ? OUT_OF_MEMORY : SUCCESS;
}

static int retransformVirtualThread(JNIEnv* jni, jvmtiEnv* jvmti, ClassTransformer transformer)
{
    if (installEventCallbacks(jvmti) != JVMTI_ERROR_NONE) {
        return CALLBACK_INSTALL_FAILED;
    }
    jclass virtualThreadClass = (*jni)->FindClass(jni, VIRTUAL_THREAD_CLASS_NAME);
    if (clearPendingException(jni) || virtualThreadClass == NULL) {
        return RETRANSFORM_FAILED;
    }
    // From here on every class loaded anywhere in the process posts the event, at the cost of the
    // name comparison in the callback. It stays enabled once the hooks are installed so that they
    // survive later retransformations by other agents; see installedClassData.
    if ((*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL) != JVMTI_ERROR_NONE) {
        return EVENT_ENABLE_FAILED;
    }

    capturedClassData = NULL;
    capturedClassDataLength = 0;
    classDataApplied = false;
    classDataAllocationFailed = false;
    int status = retransformPass(jvmti, virtualThreadClass, HOOK_PHASE_CAPTURE);
    if (status == SUCCESS && capturedClassData == NULL) {
        status = TRANSFORM_FAILED;
    }

    // The transformer's input and output are valid only until this function returns, so the
    // output is copied before the second pass applies it.
    uint8_t* transformed = NULL;
    int32_t transformedLength = 0;
    if (status == SUCCESS &&
            (transformer(capturedClassData, capturedClassDataLength, &transformed, &transformedLength) != 0 ||
                    transformed == NULL ||
                    transformedLength <= 0)) {
        status = TRANSFORM_FAILED;
    }
    if (status == SUCCESS) {
        installedClassData = malloc((size_t) transformedLength);
        if (installedClassData == NULL) {
            status = OUT_OF_MEMORY;
        }
        else {
            memcpy(installedClassData, transformed, (size_t) transformedLength);
            installedClassDataLength = transformedLength;
        }
    }

    free(capturedClassData);
    capturedClassData = NULL;
    capturedClassDataLength = 0;

    if (status == SUCCESS) {
        status = retransformPass(jvmti, virtualThreadClass, HOOK_PHASE_APPLY);
    }
    if (status == SUCCESS && !classDataApplied) {
        status = TRANSFORM_FAILED;
    }
    if (status == SUCCESS && injectInitializationFault("retransform")) {
        status = RETRANSFORM_FAILED;
    }

    if (status != SUCCESS) {
        (void) (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_DISABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL);
        free(installedInputClassData);
        installedInputClassData = NULL;
        installedInputClassDataLength = 0;
        free(installedClassData);
        installedClassData = NULL;
        installedClassDataLength = 0;
    }
    return status;
}

static int retransformVirtualThreadLocked(ClassTransformer transformer)
{
    if (atomic_load_explicit(&hooksInstalled, memory_order_acquire)) {
        return atomic_load_explicit(&hooksRemoved, memory_order_acquire) ? HOOKS_REMOVED : SUCCESS;
    }
    // Once a class loader has fallen back to events, a later loader must not add hooks on top of
    // the live events, or both would account for the same mounts.
    if (atomic_load_explicit(&initialized, memory_order_acquire)) {
        return EVENTS_ALREADY_ENABLED;
    }
    if (!hookClassesDefined) {
        return HOOK_CLASSES_NOT_DEFINED;
    }
    if (hooksInstallationFailure != SUCCESS) {
        return hooksInstallationFailure;
    }
    if (transformer == NULL) {
        return TRANSFORM_FAILED;
    }

    JNIEnv* jni = NULL;
    int status = pushJniFrame(&jni);
    if (status == SUCCESS) {
        status = retransformVirtualThread(jni, jvmti, transformer);
        (void) (*jni)->PopLocalFrame(jni, NULL);
    }
    if (status != SUCCESS) {
        hooksInstallationFailure = status;
        return status;
    }
    atomic_store_explicit(&hooksInstalled, true, memory_order_release);
    return SUCCESS;
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
    state->mountStartedValid = carrierCpuTime(&state->mountStarted);
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
// is stable and the open interval ends on this carrier's clock.
VTHREADTIME_EXPORT int64_t airlift_vthreadtime_mounted_nanos(int64_t stateAddress)
{
    const ThreadState* state = (const ThreadState*) (intptr_t) stateAddress;
    uint64_t nanos = state->mountedNanos;
    uint64_t now;
    if (state->mounted && state->mountStartedValid && carrierCpuTime(&now) && now >= state->mountStarted) {
        nanos += now - state->mountStarted;
    }
    return (int64_t) nanos;
}

VTHREADTIME_EXPORT int airlift_vthreadtime_unregister(void)
{
    return unregisterCurrentThread();
}

VTHREADTIME_EXPORT int64_t airlift_vthreadtime_active_registrations(void)
{
    return (int64_t) atomic_load_explicit(&activeRegistrations, memory_order_relaxed);
}

VTHREADTIME_EXPORT int airlift_vthreadtime_define_hook_classes(
        const char* firstClassName,
        const uint8_t* firstClass,
        int32_t firstLength,
        const char* secondClassName,
        const uint8_t* secondClass,
        int32_t secondLength)
{
    (void) pthread_mutex_lock(&initializationMutex);
    int status = defineHookClassesLocked(firstClassName, firstClass, firstLength, secondClassName, secondClass, secondLength);
    (void) pthread_mutex_unlock(&initializationMutex);
    return status;
}

// The transformer runs on the calling thread while this mutex is held; it must therefore not call
// back into this library.
VTHREADTIME_EXPORT int airlift_vthreadtime_retransform_virtual_thread(
        int32_t (*transformer)(const uint8_t* classData, int32_t length, uint8_t** newData, int32_t* newLength))
{
    (void) pthread_mutex_lock(&initializationMutex);
    int status = retransformVirtualThreadLocked(transformer);
    (void) pthread_mutex_unlock(&initializationMutex);
    return status;
}

// Whether the hooks are in java.lang.VirtualThread right now: installed and not removed since by
// another agent's retransformation. Read through a critical downcall on every registration.
VTHREADTIME_EXPORT int64_t airlift_vthreadtime_hooks_installed(void)
{
    return atomic_load_explicit(&hooksInstalled, memory_order_acquire) &&
                    !atomic_load_explicit(&hooksRemoved, memory_order_acquire)
            ? 1
            : 0;
}

// The hook classes' clock, called through a critical downcall on every mount and unmount of a
// registered thread, so it must neither block nor fail.
VTHREADTIME_EXPORT int64_t airlift_vthreadtime_carrier_cpu_nanos(void)
{
    uint64_t nanos;
    if (!carrierCpuTime(&nanos)) {
        return 0;
    }
    return (int64_t) nanos;
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
