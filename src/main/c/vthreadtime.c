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
#if defined(__linux__) && (defined(__aarch64__) || defined(__x86_64__))
#define VTHREADTIME_PERF_TASK_CLOCK
// Exposes syscall(); glibc before 2.19 reads _BSD_SOURCE instead of _DEFAULT_SOURCE.
#define _DEFAULT_SOURCE
#define _BSD_SOURCE
#endif

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

#if defined(VTHREADTIME_PERF_TASK_CLOCK)
#include <errno.h>
#include <fcntl.h>
#include <linux/perf_event.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

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

// Values of the io.airlift.vthreadtime.perf.clock system property; see configurePerfClock.
typedef enum
{
    PERF_CLOCK_AUTO = 0,
    PERF_CLOCK_ON = 1,
    PERF_CLOCK_OFF = 2,
} PerfClockMode;

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
static _Atomic uint64_t testTaskClockReleaseCount = 0;

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

static bool threadCpuTime(uint64_t* result)
{
    struct timespec value = {0};
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &value) != 0) {
        return false;
    }
    *result = ((uint64_t) value.tv_sec * 1000000000ULL) + (uint64_t) value.tv_nsec;
    return true;
}

#if defined(VTHREADTIME_PERF_TASK_CLOCK)
// On Linux the thread CPU clock is a system call, not a vDSO read. A perf task-clock event opened
// for the calling thread counts the time the thread is scheduled on a CPU, including kernel time
// and, where the hypervisor reports it, steal time that the thread CPU clock leaves out. Its mapped
// page lets the thread read it in user space. Each carrier holds one event file descriptor and one
// page charged to the user's perf locked-memory limit.
//
// The state belongs to the carrier, not to the virtual thread mounted on it, so it hangs off a
// pthread key. A C thread-local would not do: the first access to one in a library loaded at
// runtime can allocate and take a loader lock, which the critical clock must not, whereas reading
// a key never allocates or locks. The key's destructor also releases the event when the carrier's
// thread exits, for every kind of thread, without the per-thread JVMTI state a thread end event
// costs. The key's value is NULL until the carrier tries the event, then the carrier's open clock,
// or the address of taskClockUnavailable when the carrier uses the thread CPU clock.
typedef struct
{
    int fd;
    volatile struct perf_event_mmap_page* page;
} TaskClock;

static const char SECCOMP_FIELD[] = "Seccomp:";

static TaskClock taskClockUnavailable = {.fd = -1};
// Created by the first configuration, before the hooks are installed, unless the perf clock is off.
// It is never deleted: carriers of every class loader share it, and its destructor releases each
// carrier's event.
static pthread_key_t taskClockKey;
static _Atomic bool taskClockKeyCreated = false;
// Whether a carrier checks for a seccomp filter before opening the event; the on mode skips it.
static bool checkSeccomp = true;
// Set by the first setup failure, or by the off mode, so later carriers never try the event.
static _Atomic bool taskClockStopped = false;

static uint64_t readCycleCounter(void)
{
#if defined(__aarch64__)
    uint64_t value;
    __asm__ volatile("isb; mrs %0, cntvct_el0" : "=r"(value) : : "memory");
    return value;
#else
    uint32_t low;
    uint32_t high;
    __asm__ volatile("rdtsc" : "=a"(low), "=d"(high) : : "memory");
    return ((uint64_t) high << 32) | low;
#endif
}

// The kernel rewrites the page whenever the thread is switched back onto a CPU, so time_running
// is the count as of that switch, and the cycle counter extrapolates it to now. Without
// cap_user_time the page cannot be extrapolated and time_running alone can be stale by seconds.
// If native code on the carrier calls prctl(PR_TASK_PERF_EVENTS_DISABLE), the kernel stops
// rewriting the page but leaves it readable, so later reads also count time off the CPU; this
// cannot be detected.
static bool readTaskClock(volatile struct perf_event_mmap_page* page, uint64_t* result)
{
    uint32_t sequence;
    bool userTime;
    bool shortCycles;
    uint64_t running;
    uint64_t cycles = 0;
    uint64_t timeOffset = 0;
    uint64_t timeCycles = 0;
    uint64_t timeMask = 0;
    uint32_t timeMult = 0;
    uint16_t timeShift = 0;
    do {
        sequence = page->lock;
        atomic_thread_fence(memory_order_acquire);
        userTime = page->cap_user_time;
        shortCycles = page->cap_user_time_short;
        running = page->time_running;
        if (userTime) {
            cycles = readCycleCounter();
            timeOffset = page->time_offset;
            timeMult = page->time_mult;
            timeShift = page->time_shift;
            if (shortCycles) {
                timeCycles = page->time_cycles;
                timeMask = page->time_mask;
            }
        }
        atomic_thread_fence(memory_order_acquire);
    } while (page->lock != sequence);

    if (!userTime) {
        return false;
    }
    if (shortCycles) {
        cycles = timeCycles + ((cycles - timeCycles) & timeMask);
    }
    uint64_t quotient = cycles >> timeShift;
    uint64_t remainder = cycles & ((1ULL << timeShift) - 1);
    *result = running + timeOffset + (quotient * timeMult) + ((remainder * timeMult) >> timeShift);
    return true;
}

// The same count through the file descriptor, a system call.
static bool readTaskClockFile(int fd, uint64_t* result)
{
    uint64_t value;
    if (read(fd, &value, sizeof(value)) != (ssize_t) sizeof(value)) {
        return false;
    }
    *result = value;
    return true;
}

// Whether the value of the seccomp field, up to the end of its line, is exactly 0.
static bool isZeroField(const char* value, size_t length)
{
    size_t position = 0;
    while (position < length && (value[position] == ' ' || value[position] == '\t')) {
        position++;
    }
    return position + 1 == length && value[position] == '0';
}

// A seccomp filter can kill the process on perf_event_open instead of failing the call, as
// systemd's SystemCallFilter= does by default, and a filter's policy cannot be inspected. So the
// auto mode admits only a thread whose status explicitly reports no filter: an unreadable status,
// or a Seccomp field that is missing, malformed or longer than the kept prefix of its line, counts
// as filtered. The status file has no size limit, as the Groups line before the field grows with
// the supplementary groups, so it is scanned line by line; a line only needs its first bytes kept
// to be recognized.
static bool mayHaveSeccompFilter(void)
{
    int fd = open("/proc/thread-self/status", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return true;
    }
    char chunk[512];
    char line[32];
    size_t lineLength = 0;
    bool lineTruncated = false;
    bool found = false;
    bool noFilter = false;
    while (!found) {
        ssize_t count = read(fd, chunk, sizeof(chunk));
        if (count < 0 && errno == EINTR) {
            continue;
        }
        if (count <= 0) {
            break;
        }
        for (ssize_t index = 0; index < count; index++) {
            if (chunk[index] != '\n') {
                if (lineLength < sizeof(line)) {
                    line[lineLength++] = chunk[index];
                }
                else {
                    lineTruncated = true;
                }
                continue;
            }
            size_t fieldLength = sizeof(SECCOMP_FIELD) - 1;
            if (lineLength >= fieldLength && memcmp(line, SECCOMP_FIELD, fieldLength) == 0) {
                found = true;
                noFilter = !lineTruncated && isZeroField(line + fieldLength, lineLength - fieldLength);
                break;
            }
            lineLength = 0;
            lineTruncated = false;
        }
    }
    (void) close(fd);
    return !noFilter;
}

static void closeTaskClock(TaskClock* clock)
{
    if (clock->page != NULL) {
        (void) munmap((void*) clock->page, (size_t) sysconf(_SC_PAGESIZE));
        clock->page = NULL;
    }
    if (clock->fd >= 0) {
        (void) close(clock->fd);
        clock->fd = -1;
    }
}

// Fails without trying when the auto mode finds the thread may have a seccomp filter; the on
// mode skips that check, and then a filter that kills on perf_event_open kills the process. Also
// fails when the event cannot be opened (perf_event_paranoid, a seccomp filter that returns an
// error), when its page cannot be mapped, or when the first read finds the page cannot be read in
// user space (no cap_user_time, as on x86 KVM guests whose scheduler clock is kvm-clock).
static bool openTaskClock(TaskClock* clock)
{
    *clock = (TaskClock) {.fd = -1};
    if (checkSeccomp && mayHaveSeccompFilter()) {
        return false;
    }

    struct perf_event_attr attributes = {0};
    attributes.size = sizeof(attributes);
    attributes.type = PERF_TYPE_SOFTWARE;
    attributes.config = PERF_COUNT_SW_TASK_CLOCK;
    // Kernel time is still counted; excluding it only lets an unprivileged process open the event.
    attributes.exclude_kernel = 1;
    attributes.exclude_hv = 1;
    long fd = syscall(SYS_perf_event_open, &attributes, 0, -1, -1, PERF_FLAG_FD_CLOEXEC);
    if (fd < 0) {
        return false;
    }
    clock->fd = (int) fd;
    void* page = mmap(NULL, (size_t) sysconf(_SC_PAGESIZE), PROT_READ, MAP_SHARED, clock->fd, 0);
    if (page == MAP_FAILED) {
        closeTaskClock(clock);
        return false;
    }
    clock->page = page;

    // The first read checks that user space can extrapolate the page.
    uint64_t taskNow;
    if (!readTaskClock(clock->page, &taskNow)) {
        closeTaskClock(clock);
        return false;
    }
    return true;
}

// The key's destructor, run by the exiting thread with the value it held: releases the carrier's
// event and mapping, if it has them. It touches no JVM state, as the thread may already be
// detached from the JVM.
static void releaseTaskClock(void* value)
{
#if defined(VTHREADTIME_TEST_INITIALIZATION_FAULTS)
    atomic_fetch_add_explicit(&testTaskClockReleaseCount, 1, memory_order_relaxed);
#endif
    TaskClock* clock = (TaskClock*) value;
    if (clock == &taskClockUnavailable) {
        return;
    }
    closeTaskClock(clock);
    free(clock);
}

// The calling carrier's clock: NULL until the carrier tries the event, then its open clock or
// taskClockUnavailable, which every carrier gets without the key. Never allocates or locks, so
// critical downcalls may call it.
static TaskClock* currentTaskClock(void)
{
    if (!atomic_load_explicit(&taskClockKeyCreated, memory_order_acquire)) {
        return &taskClockUnavailable;
    }
    return (TaskClock*) pthread_getspecific(taskClockKey);
}

// Records that the calling carrier uses the thread CPU clock. Should the key refuse the value, the
// carrier keeps answering through this path, which then finds the stopped flag set.
static TaskClock* useThreadCpuClock(void)
{
    (void) pthread_setspecific(taskClockKey, &taskClockUnavailable);
    return &taskClockUnavailable;
}

// Sets up the calling carrier's event on its first call. The clock is attached to the key before
// any reading from it is returned, so the destructor always releases it.
static TaskClock* openCurrentTaskClock(void)
{
    TaskClock* clock = currentTaskClock();
    if (clock != NULL) {
        return clock;
    }
    if (atomic_load_explicit(&taskClockStopped, memory_order_relaxed)) {
        return useThreadCpuClock();
    }
    TaskClock opened;
    if (openTaskClock(&opened)) {
        clock = malloc(sizeof(*clock));
        if (clock != NULL) {
            *clock = opened;
            if (pthread_setspecific(taskClockKey, clock) == 0) {
                return clock;
            }
            free(clock);
        }
        closeTaskClock(&opened);
    }
    // Opening the first perf task-clock event patches kernel code on every CPU, so a failed setup,
    // which has closed any event it opened, stops later carriers from trying. Carriers that already
    // hold an event keep it, and first attempts already in flight may still finish.
    atomic_store_explicit(&taskClockStopped, true, memory_order_relaxed);
    return useThreadCpuClock();
}
#endif

// The first call fixes the mode for the process; class loaders that initialize later reuse it.
static void configurePerfClock(PerfClockMode mode)
{
#if defined(VTHREADTIME_PERF_TASK_CLOCK)
    static bool configured = false;
    (void) pthread_mutex_lock(&initializationMutex);
    if (!configured) {
        configured = true;
        checkSeccomp = mode != PERF_CLOCK_ON;
        if (mode == PERF_CLOCK_OFF) {
            atomic_store_explicit(&taskClockStopped, true, memory_order_relaxed);
        }
        else if (pthread_key_create(&taskClockKey, releaseTaskClock) == 0) {
            atomic_store_explicit(&taskClockKeyCreated, true, memory_order_release);
        }
        else {
            // Without a key no carrier can keep an event, so every carrier uses the thread CPU clock.
            atomic_store_explicit(&taskClockStopped, true, memory_order_relaxed);
        }
    }
    (void) pthread_mutex_unlock(&initializationMutex);
#else
    (void) mode;
#endif
}

typedef enum
{
    CARRIER_CLOCK_FAILED,
    CARRIER_CLOCK_READ,
    // Answering would mean setting up the carrier's event or reading its file descriptor.
    CARRIER_CLOCK_WOULD_BLOCK,
} CarrierClockRead;

// Mounted time is the CPU time of the carrier thread, not wall time. A carrier that waits in the
// OS run queue while a virtual thread is mounted on it must not charge that wait to the virtual
// thread. Every caller runs on the carrier, so each start and end pair reads one carrier clock.
//
// This read never blocks, so critical downcalls may make it: it answers only from the mapped page
// of a carrier on the task clock, or from the thread CPU clock on a carrier known to lack the event.
static CarrierClockRead carrierCpuTimeNonBlocking(uint64_t* result)
{
#if defined(VTHREADTIME_PERF_TASK_CLOCK)
    TaskClock* clock = currentTaskClock();
    if (clock == NULL) {
        return CARRIER_CLOCK_WOULD_BLOCK;
    }
    // A carrier leaves the task clock only when its thread exits, so no interval mixes the two
    // clocks, which drift apart with every context switch.
    if (clock != &taskClockUnavailable) {
        return readTaskClock(clock->page, result) ? CARRIER_CLOCK_READ : CARRIER_CLOCK_WOULD_BLOCK;
    }
#endif
    return threadCpuTime(result) ? CARRIER_CLOCK_READ : CARRIER_CLOCK_FAILED;
}

// The same clock for callers that may block: on its first read a carrier sets up the event, and
// once the kernel withdraws cap_user_time, for example when the TSC becomes unstable, a carrier on
// the task clock reads the same count through the file descriptor, which can wait on the kernel's
// perf-context lock.
static bool carrierCpuTime(uint64_t* result)
{
#if defined(VTHREADTIME_PERF_TASK_CLOCK)
    TaskClock* clock = openCurrentTaskClock();
    if (clock != &taskClockUnavailable) {
        return readTaskClock(clock->page, result) || readTaskClockFile(clock->fd, result);
    }
#endif
    return threadCpuTime(result);
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

// Called before the hooks are installed, so the mode is in place before any carrier reads its clock.
VTHREADTIME_EXPORT void airlift_vthreadtime_configure_perf_clock(int mode)
{
    configurePerfClock((PerfClockMode) mode);
}

// Whether the calling carrier reads its CPU clock from a perf task-clock event.
VTHREADTIME_EXPORT int airlift_vthreadtime_uses_perf_clock(void)
{
#if defined(VTHREADTIME_PERF_TASK_CLOCK)
    TaskClock* clock = currentTaskClock();
    return clock != NULL && clock != &taskClockUnavailable;
#else
    return 0;
#endif
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
// registered thread, so it must not block. Returns zero when the clock fails, and -1 when it cannot
// answer without blocking; the hooks then call airlift_vthreadtime_carrier_cpu_nanos_blocking.
VTHREADTIME_EXPORT int64_t airlift_vthreadtime_carrier_cpu_nanos(void)
{
    uint64_t nanos;
    switch (carrierCpuTimeNonBlocking(&nanos)) {
        case CARRIER_CLOCK_READ:
            return (int64_t) nanos;
        case CARRIER_CLOCK_WOULD_BLOCK:
            return -1;
        case CARRIER_CLOCK_FAILED:
            break;
    }
    return 0;
}

// The same clock through a regular downcall, which may block: it sets up the event of a carrier
// that has not tried it yet, or reads the event's file descriptor. Returns zero when the clock fails.
VTHREADTIME_EXPORT int64_t airlift_vthreadtime_carrier_cpu_nanos_blocking(void)
{
    uint64_t nanos;
    if (!carrierCpuTime(&nanos)) {
        return 0;
    }
    return (int64_t) nanos;
}

#if defined(VTHREADTIME_TEST_INITIALIZATION_FAULTS)
// How many exiting threads have run the destructor of the task clock key.
VTHREADTIME_EXPORT int64_t airlift_vthreadtime_test_task_clock_release_count(void)
{
    return (int64_t) atomic_load_explicit(&testTaskClockReleaseCount, memory_order_relaxed);
}
#endif
