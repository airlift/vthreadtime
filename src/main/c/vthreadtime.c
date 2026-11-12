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

typedef enum
{
    SUCCESS = 0,
    // No JavaVM, no JVMTI or JNI environment for this thread, or a JVMTI call failed.
    JVMTI_UNAVAILABLE = 1,
    OUT_OF_MEMORY = 2,
    // JNI DefineClass rejected the hook class, typically because the bootstrap loader already has
    // a class of that name or the bytes are not a well-formed class file, or its initialization
    // failed.
    HOOK_CLASS_DEFINE_FAILED = 3,
    // AddModuleReads failed; see addModuleReads.
    MODULE_READS_FAILED = 4,
    // The transformer was never asked for java.lang.VirtualThread, declined it or returned no
    // class bytes.
    TRANSFORM_FAILED = 5,
    // RetransformClasses failed outright or rejected the transformed java.lang.VirtualThread.
    RETRANSFORM_FAILED = 6,
} Status;

// The hook class to define into the bootstrap loader.
typedef struct
{
    const char* name;
    const uint8_t* classData;
    int32_t length;
} ClassDefinition;

static const char VIRTUAL_THREAD_CLASS_NAME[] = "java/lang/VirtualThread";

// The installation is tracked by hooksInstalled with a latched failure. A failed installation
// stays failed for the life of the process, as retrying could define the hook class twice.
// hooksRemoved latches when a later retransformation drops the hooks from the class.
static JavaVM* javaVm = NULL;
static jvmtiEnv* jvmti = NULL;
static pthread_mutex_t initializationMutex = PTHREAD_MUTEX_INITIALIZER;
static _Atomic bool hooksInstalled = false;
static int hooksInstallationFailure = SUCCESS;
static _Atomic bool hooksRemoved = false;

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
//
// installedInputClassData is the signal that there are bytes to serve. It is written by the
// applying callback itself, which HotSpot's serialization orders before any other agent's
// callback, whereas hooksInstalled is stored only after the applying pass returns: an agent
// retransforming in that window would otherwise be served nothing and the hooks would be gone
// with hooksInstalled still reporting them. The buffers are never freed, as a callback of another
// agent may be reading them.
static _Atomic(unsigned char*) installedInputClassData = NULL;
static jint installedInputClassDataLength = 0;
static unsigned char* installedClassData = NULL;
static jint installedClassDataLength = 0;

#if defined(VTHREADTIME_TEST_INITIALIZATION_FAULTS)
static _Atomic bool initializationFaultInjected = false;

static bool injectInitializationFault(const char* point)
{
    const char* configuredPoint = getenv("VTHREADTIME_TEST_INITIALIZATION_FAULT");
    return configuredPoint != NULL &&
            strcmp(configuredPoint, point) == 0 &&
            !atomic_exchange(&initializationFaultInjected, true);
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
        installedInputClassDataLength = classDataLength;
        atomic_store_explicit(&installedInputClassData, input, memory_order_release);
        classDataApplied = true;
    }
    else {
        // Another agent is retransforming or redefining VirtualThread. Once the hooks are gone
        // nothing is served again, since trackers then count nothing new.
        const unsigned char* installedInput = atomic_load_explicit(&installedInputClassData, memory_order_acquire);
        if (installedInput == NULL || atomic_load_explicit(&hooksRemoved, memory_order_acquire)) {
            return;
        }
        if (classData == NULL ||
                classDataLength != installedInputClassDataLength ||
                memcmp(classData, installedInput, (size_t) classDataLength) != 0 ||
                !serveClassData(jvmti, installedClassData, installedClassDataLength, newClassDataLength, newClassData)) {
            atomic_store_explicit(&hooksRemoved, true, memory_order_release);
        }
    }
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

// The environment is created at most once, as every GetEnv for a JVMTI
// version creates another one, and it then lives until JVM exit.
static int ensureEnvironment(void)
{
    if (jvmti != NULL) {
        return SUCCESS;
    }
    JavaVM* vm = findJavaVm();
    if (vm == NULL) {
        return JVMTI_UNAVAILABLE;
    }
    jvmtiEnv* environment = NULL;
    if ((*vm)->GetEnv(vm, (void**) &environment, JVMTI_VERSION_21) != JNI_OK || environment == NULL) {
        return JVMTI_UNAVAILABLE;
    }
    javaVm = vm;
    jvmti = environment;
    return SUCCESS;
}

static jvmtiError installEventCallbacks(jvmtiEnv* jvmti)
{
    jvmtiEventCallbacks callbacks = {0};
    callbacks.ClassFileLoadHook = onClassFileLoadHook;
    return (*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks));
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
        return JVMTI_UNAVAILABLE;
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
        return JVMTI_UNAVAILABLE;
    }
    return SUCCESS;
}

// java.base does not read the bootstrap loader's unnamed module, where the hook classes land, so
// without this the calls injected into java.lang.VirtualThread fail their access check when linked.
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

// Defines the hook class and initializes it here, on the installing thread, so that no virtual
// thread triggers the initialization from inside mount(). GetStaticMethodID initializes the class.
static int defineHookClass(JNIEnv* jni, jvmtiEnv* jvmti, ClassDefinition hooks)
{
    if (hooks.name == NULL || hooks.classData == NULL || hooks.length <= 0) {
        return HOOK_CLASS_DEFINE_FAILED;
    }
    // Loader NULL is the bootstrap loader, the only one whose classes java.lang.VirtualThread can call.
    jclass hooksClass = (*jni)->DefineClass(jni, hooks.name, NULL, (const jbyte*) hooks.classData, hooks.length);
    if (clearPendingException(jni) || hooksClass == NULL) {
        return HOOK_CLASS_DEFINE_FAILED;
    }
    jmethodID registerMethod = (*jni)->GetStaticMethodID(jni, hooksClass, "register", "()[J");
    if (clearPendingException(jni) || registerMethod == NULL) {
        return HOOK_CLASS_DEFINE_FAILED;
    }
    if (injectInitializationFault("hook-class")) {
        return HOOK_CLASS_DEFINE_FAILED;
    }
    return addModuleReads(jni, jvmti, hooksClass);
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
        return JVMTI_UNAVAILABLE;
    }
    jclass virtualThreadClass = (*jni)->FindClass(jni, VIRTUAL_THREAD_CLASS_NAME);
    if (clearPendingException(jni) || virtualThreadClass == NULL) {
        return RETRANSFORM_FAILED;
    }
    // From here on every class loaded anywhere in the process posts the event, at the cost of the
    // name comparison in the callback. It stays enabled once the hooks are installed so that they
    // survive later retransformations by other agents; see installedClassData.
    if ((*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL) != JVMTI_ERROR_NONE) {
        return JVMTI_UNAVAILABLE;
    }

    // capturedClassData is reset by every earlier attempt on its way out
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
    // The fault hands the transformer a truncated class file, which it must reject
    int32_t inputLength = injectInitializationFault("transform") ? capturedClassDataLength / 2 : capturedClassDataLength;
    if (status == SUCCESS &&
            (transformer(capturedClassData, inputLength, &transformed, &transformedLength) != 0 ||
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
        // The buffers stay allocated: a failure after the applying callback ran may be racing
        // another agent's callback that is reading them. The failure is latched by the caller,
        // and the hooks are reported as removed in case the class did end up with them.
        (void) (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_DISABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL);
        atomic_store_explicit(&hooksRemoved, true, memory_order_release);
    }
    return status;
}

static int installHooksLocked(ClassDefinition hooks, ClassTransformer transformer)
{
    // Installed once is installed for the process, whether or not another agent has removed the
    // hooks since: every class loader then reads the same removal through hooks_installed.
    if (atomic_load_explicit(&hooksInstalled, memory_order_acquire)) {
        return SUCCESS;
    }
    // A later class loader gets the failure that was latched, not a consequence of it.
    if (hooksInstallationFailure != SUCCESS) {
        return hooksInstallationFailure;
    }
    if (transformer == NULL) {
        return TRANSFORM_FAILED;
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
        status = defineHookClass(jni, jvmti, hooks);
        if (status == SUCCESS) {
            status = retransformVirtualThread(jni, jvmti, transformer);
        }
        (void) (*jni)->PopLocalFrame(jni, NULL);
    }
    if (status != SUCCESS) {
        hooksInstallationFailure = status;
        return status;
    }
    atomic_store_explicit(&hooksInstalled, true, memory_order_release);
    return SUCCESS;
}

// Defines the hook class into the bootstrap loader and retransforms java.lang.VirtualThread to
// call it, once per process. The transformer runs on the calling thread while the mutex is held;
// it must therefore not call back into this library.
VTHREADTIME_EXPORT int airlift_vthreadtime_install_hooks(
        const char* hooksClassName,
        const uint8_t* hooksClass,
        int32_t hooksClassLength,
        int32_t (*transformer)(const uint8_t* classData, int32_t length, uint8_t** newData, int32_t* newLength))
{
    ClassDefinition hooks = {hooksClassName, hooksClass, hooksClassLength};
    (void) pthread_mutex_lock(&initializationMutex);
    int status = installHooksLocked(hooks, transformer);
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
