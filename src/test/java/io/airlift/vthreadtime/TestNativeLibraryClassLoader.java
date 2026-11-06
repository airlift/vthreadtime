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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.spi.ToolProvider;

import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.assertj.core.api.Assertions.assertThat;

class TestNativeLibraryClassLoader
{
    private static final String INITIALIZATION_FAULT_ENVIRONMENT = "VTHREADTIME_TEST_INITIALIZATION_FAULT";
    private static final String LIBRARY_PATH_PROPERTY = "io.airlift.vthreadtime.library.path";
    private static final String TEMP_DIRECTORY_PROPERTY = "io.airlift.vthreadtime.tmpdir";
    private static final String MODE_PROPERTY = "io.airlift.vthreadtime.mode";
    private static final String EVENTS_MODE = "-D" + MODE_PROPERTY + "=events";
    private static final String PARALLELISM_PROPERTY = "jdk.virtualThreadScheduler.parallelism";
    // Default maximum pool size of the virtual thread scheduler; parallelism above it is rejected
    private static final int DEFAULT_MAX_POOL_SIZE = 256;
    private static final String HOOKS_REMOVED_MESSAGE = "VirtualThread was retransformed by another agent, which removed the hooks";

    // A Java agent whose retransformation environment precedes the library's. The test module
    // does not read java.instrument, so the agent is compiled from this source at test time and
    // driven reflectively by the subprocess.
    private static final String RETRANSFORM_AGENT_CLASS = "io.airlift.vthreadtime.agent.RetransformAgent";
    private static final String RETRANSFORM_AGENT_SOURCE =
            """
            package io.airlift.vthreadtime.agent;

            import java.lang.classfile.ClassFile;
            import java.lang.classfile.ClassTransform;
            import java.lang.classfile.CodeBuilder;
            import java.lang.classfile.CodeElement;
            import java.lang.classfile.CodeTransform;
            import java.lang.instrument.ClassFileTransformer;
            import java.lang.instrument.Instrumentation;
            import java.security.ProtectionDomain;

            public final class RetransformAgent
            {
                private static volatile Instrumentation instrumentation;
                private static volatile boolean modifying;

                private RetransformAgent() {}

                public static void premain(String arguments, Instrumentation instrumentation)
                {
                    RetransformAgent.instrumentation = instrumentation;
                    // Adding a retransformation-capable transformer before the library initializes
                    // creates this agent's retransformation environment ahead of the library's, so
                    // the library receives this transformer's output.
                    instrumentation.addTransformer(new MountTransformer(), true);
                }

                /// Retransforms VirtualThread, passing its bytes through or inserting a nop into mount().
                public static void retransform(boolean modify)
                        throws Exception
                {
                    modifying = modify;
                    instrumentation.retransformClasses(Class.forName("java.lang.VirtualThread"));
                }

                private static final class MountTransformer
                        implements ClassFileTransformer
                {
                    @Override
                    public byte[] transform(
                            Module module,
                            ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classFile)
                    {
                        if (!modifying || !"java/lang/VirtualThread".equals(className)) {
                            return null;
                        }
                        CodeTransform nopAtEntry = new CodeTransform()
                        {
                            @Override
                            public void atStart(CodeBuilder code)
                            {
                                code.nop();
                            }

                            @Override
                            public void accept(CodeBuilder code, CodeElement element)
                            {
                                code.with(element);
                            }
                        };
                        ClassFile api = ClassFile.of();
                        return api.transformClass(
                                api.parse(classFile),
                                ClassTransform.transformingMethodBodies(method -> method.methodName().equalsString("mount"), nopAtEntry));
                    }
                }
            }
            """;

    @Test
    void testProcessLifetimeLibraryWithDisposableClassLoaders(@TempDir Path temporaryDirectory)
            throws Exception
    {
        runSubprocess("lifecycle", temporaryDirectory, Optional.empty());
    }

    @Test
    void testProcessLifetimeLibraryWithDisposableClassLoadersInEventsMode(@TempDir Path temporaryDirectory)
            throws Exception
    {
        runSubprocess("lifecycle", temporaryDirectory, Optional.empty(), Optional.empty(), List.of(EVENTS_MODE));
    }

    @Test
    void testInvalidModeIsUnsupported(@TempDir Path temporaryDirectory)
            throws Exception
    {
        runSubprocess("invalid-mode", temporaryDirectory, Optional.empty(), Optional.empty(), List.of("-D" + MODE_PROPERTY + "=bogus"));
    }

    @Test
    void testHooksFallbackIsLatchedProcessWide(@TempDir Path temporaryDirectory)
            throws Exception
    {
        Path faultLibrary = buildTestNativeLibrary(
                temporaryDirectory.resolve("fixture"),
                "--test-initialization-faults");
        for (String fault : List.of("hook-class", "module-reads", "retransform")) {
            runSubprocess(
                    "hooks-fallback",
                    Files.createDirectory(temporaryDirectory.resolve("load-" + fault)),
                    Optional.of(faultLibrary),
                    Optional.of(fault),
                    List.of());
        }
    }

    @Test
    void testHooksSurviveRetransformationByAnotherAgent(@TempDir Path temporaryDirectory)
            throws Exception
    {
        Path agentJar = buildRetransformAgent(temporaryDirectory.resolve("agent"));
        runSubprocess(
                "foreign-retransform",
                Files.createDirectory(temporaryDirectory.resolve("native")),
                Optional.empty(),
                Optional.empty(),
                List.of("-javaagent:" + agentJar));
    }

    @Test
    void testConfiguredLibraryAndTemporaryDirectory(@TempDir Path temporaryDirectory)
            throws Exception
    {
        runSubprocess("single", temporaryDirectory, Optional.of(packagedNativeLibrary()));
    }

    @Test
    void testMountedTimeExcludesCarrierWaitingForCpu(@TempDir Path temporaryDirectory)
            throws Exception
    {
        // Regression test for issue 38: with three carriers per core, a carrier waits in the OS run
        // queue while a virtual thread is mounted on it, and that wait must not count. The scheduler
        // size is a JVM-wide startup property, so the scenario runs in its own JVM.
        int carriers = Math.min(Runtime.getRuntime().availableProcessors() * 3, DEFAULT_MAX_POOL_SIZE);
        runSubprocess(
                "carrier-cpu",
                temporaryDirectory,
                Optional.empty(),
                Optional.empty(),
                List.of(
                        "-D" + PARALLELISM_PROPERTY + "=" + carriers,
                        "-Djdk.virtualThreadScheduler.maxPoolSize=" + carriers));
    }

    @Test
    void testSubprocessOutputIsDrained(@TempDir Path temporaryDirectory)
            throws Exception
    {
        runSubprocess("large-output", temporaryDirectory, Optional.empty());
    }

    @Test
    void testFirstInvalidLibraryFailsProcessWide(@TempDir Path temporaryDirectory)
            throws Exception
    {
        Path invalidLibrary = temporaryDirectory.resolve("invalid-library");
        Files.writeString(invalidLibrary, "not a native library", StandardCharsets.UTF_8);
        runSubprocess(
                "unsupported",
                Files.createDirectory(temporaryDirectory.resolve("load")),
                Optional.of(invalidLibrary));
    }

    @Test
    void testInitializationFailuresAreLatchedProcessWide(@TempDir Path temporaryDirectory)
            throws Exception
    {
        Path faultLibrary = buildTestNativeLibrary(
                temporaryDirectory.resolve("fixture"),
                "--test-initialization-faults");
        for (String fault : List.of(
                "standard-callbacks",
                "mount-callback",
                "unmount-callback",
                "thread-end-event",
                "mount-event",
                "unmount-event")) {
            // The fault points are on the events path, which hooks mode never takes.
            runSubprocess(
                    "initialization-failure",
                    Files.createDirectory(temporaryDirectory.resolve("load-" + fault)),
                    Optional.of(faultLibrary),
                    Optional.of(fault),
                    List.of(EVENTS_MODE));
        }
    }

    private static void runSubprocess(String mode, Path temporaryDirectory, Optional<Path> configuredLibrary)
            throws Exception
    {
        runSubprocess(mode, temporaryDirectory, configuredLibrary, Optional.empty(), List.of());
    }

    private static void runSubprocess(
            String mode,
            Path temporaryDirectory,
            Optional<Path> configuredLibrary,
            Optional<String> initializationFault,
            List<String> jvmOptions)
            throws Exception
    {
        Path testClasses = Path.of(TestNativeLibraryClassLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path mainClasses = Path.of(VirtualThreadTime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String classPath = testClasses + System.getProperty("path.separator") + mainClasses;

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("--illegal-native-access=deny");
        command.add("-D" + TEMP_DIRECTORY_PROPERTY + "=" + temporaryDirectory);
        configuredLibrary.ifPresent(path -> command.add("-D" + LIBRARY_PATH_PROPERTY + "=" + path));
        command.addAll(jvmOptions);
        command.add("-cp");
        command.add(classPath);
        command.add(ClassLoaderLifecycleMain.class.getName());
        command.add(mode);
        command.add(mainClasses.toString());

        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .redirectErrorStream(true);
        initializationFault.ifPresent(fault -> processBuilder.environment().put(INITIALIZATION_FAULT_ENVIRONMENT, fault));
        Process process = processBuilder.start();
        waitForProcess(process, "subprocess");
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files.toList())
                    .describedAs("native extraction directory after subprocess exit")
                    .isEmpty();
        }
    }

    private static Path buildTestNativeLibrary(Path outputDirectory, String mode)
            throws Exception
    {
        Path buildScript = Path.of("src/main/c/build-native.sh").toAbsolutePath().normalize();
        Process process = new ProcessBuilder(buildScript.toString(), outputDirectory.toString(), mode)
                .redirectErrorStream(true)
                .start();
        String output = waitForProcess(process, "native fixture build");

        List<Path> libraries;
        try (var files = Files.walk(outputDirectory)) {
            libraries = files.filter(Files::isRegularFile).toList();
        }
        assertThat(libraries)
                .describedAs("native fixture build output:%n%s", output)
                .hasSize(1);
        return libraries.getFirst();
    }

    private static Path buildRetransformAgent(Path directory)
            throws Exception
    {
        Path source = directory.resolve("src").resolve("RetransformAgent.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, RETRANSFORM_AGENT_SOURCE, StandardCharsets.UTF_8);
        Path classes = Files.createDirectories(directory.resolve("classes"));

        ToolProvider javac = ToolProvider.findFirst("javac")
                .orElseThrow(() -> new IllegalStateException("The javac tool is not available in this JVM"));
        StringWriter output = new StringWriter();
        PrintWriter writer = new PrintWriter(output);
        int status = javac.run(writer, writer, "-d", classes.toString(), "-proc:none", source.toString());
        writer.flush();
        assertThat(status)
                .describedAs("javac output:%n%s", output)
                .isZero();

        Path jar = directory.resolve("retransform-agent.jar");
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Premain-Class", RETRANSFORM_AGENT_CLASS);
        attributes.putValue("Can-Retransform-Classes", "true");
        try (JarOutputStream jarOutput = new JarOutputStream(Files.newOutputStream(jar), manifest);
                var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                jarOutput.putNextEntry(new JarEntry(classes.relativize(file).toString()));
                Files.copy(file, jarOutput);
                jarOutput.closeEntry();
            }
        }
        return jar;
    }

    @SuppressWarnings("InterruptedExceptionSwallowed")
    private static String waitForProcess(Process process, String description)
            throws Exception
    {
        FutureTask<byte[]> output = new FutureTask<>(() -> process.getInputStream().readAllBytes());
        Thread.ofVirtual().start(output);

        boolean exited = process.waitFor(Duration.ofMinutes(1));
        if (!exited) {
            process.destroyForcibly();
            assertThat(process.waitFor(Duration.ofSeconds(10)))
                    .describedAs("%s did not terminate after timeout", description)
                    .isTrue();
        }

        String outputText = new String(output.get(), StandardCharsets.UTF_8);
        assertThat(exited)
                .describedAs("%s output:%n%s", description, outputText)
                .isTrue();
        assertThat(process.exitValue())
                .describedAs("%s output:%n%s", description, outputText)
                .isZero();
        return outputText;
    }

    private static Path packagedNativeLibrary()
            throws Exception
    {
        Path mainClasses = Path.of(VirtualThreadTime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String operatingSystem = switch (System.getProperty("os.name")) {
            case "Linux" -> "linux";
            case "Mac OS X", "macOS" -> "macos";
            default -> throw new UnsupportedOperationException("Unsupported operating system: " + System.getProperty("os.name"));
        };
        String architecture = switch (System.getProperty("os.arch")) {
            case "aarch64", "arm64" -> "aarch64";
            case "amd64", "x86_64" -> "x86_64";
            default -> throw new UnsupportedOperationException("Unsupported architecture: " + System.getProperty("os.arch"));
        };
        Path library = mainClasses.resolve("io/airlift/vthreadtime/native/%s-%s/%s"
                .formatted(operatingSystem, architecture, System.mapLibraryName("vthreadtime")));
        if (!Files.isRegularFile(library)) {
            throw new IOException("Packaged native library was not found: " + library);
        }
        return library;
    }

    @SuppressWarnings({"BanClassLoader", "InterruptedExceptionSwallowed"})
    public static final class ClassLoaderLifecycleMain
    {
        private ClassLoaderLifecycleMain() {}

        public static void main(String[] args)
                throws Exception
        {
            String mode = args[0];
            URL libraryClasses = Path.of(args[1]).toUri().toURL();
            switch (mode) {
                case "lifecycle" -> testLifecycle(libraryClasses);
                case "large-output" -> System.out.print("x".repeat(1024 * 1024));
                case "single" -> {
                    WeakReference<ClassLoader> loader = initializeAndRelease(libraryClasses);
                    awaitCollected(List.of(loader));
                    startAndJoinVirtualThread();
                    verifyPublishedLibraryCount(1);
                }
                case "unsupported" -> {
                    WeakReference<ClassLoader> first = verifyUnsupportedAndRelease(libraryClasses);
                    WeakReference<ClassLoader> second = verifyUnsupportedAndRelease(libraryClasses);
                    awaitCollected(List.of(first, second));
                    startAndJoinVirtualThreads(100);
                    verifyPublishedLibraryCount(1);
                }
                case "initialization-failure" -> testInitializationFailure(
                        libraryClasses,
                        System.getenv(INITIALIZATION_FAULT_ENVIRONMENT));
                case "hooks-fallback" -> testHooksFallback(libraryClasses);
                case "carrier-cpu" -> testCarrierCpuTime();
                case "foreign-retransform" -> testForeignRetransformation();
                case "invalid-mode" -> {
                    WeakReference<ClassLoader> loader = verifyExpectedFailureAndRelease(libraryClasses, "Unknown " + MODE_PROPERTY + ": bogus");
                    awaitCollected(List.of(loader));
                    verifyPublishedLibraryCount(1);
                }
                default -> throw new IllegalArgumentException("Unknown mode: " + mode);
            }
        }

        private static void testCarrierCpuTime()
                throws Exception
        {
            int threads = Integer.getInteger(PARALLELISM_PROPERTY);
            VirtualThreadTime.initialize();
            long spinNanos = Duration.ofSeconds(1).toNanos();
            AtomicLong mountedNanos = new AtomicLong();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Duration cpuBefore = processCpuTime();
            List<Thread> spinners = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                spinners.add(Thread.ofVirtual().start(() -> {
                    try (VirtualThreadTime time = VirtualThreadTime.register()) {
                        long start = time.mountedTimeNanos();
                        long end = System.nanoTime() + spinNanos;
                        while (System.nanoTime() < end) {
                            Thread.onSpinWait();
                        }
                        mountedNanos.addAndGet(time.mountedTimeNanos() - start);
                    }
                    catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                }));
            }
            for (Thread spinner : spinners) {
                spinner.join();
            }
            long cpuNanos = processCpuTime().minus(cpuBefore).toNanos();
            if (failure.get() != null) {
                throw new AssertionError("Spinning virtual thread failed", failure.get());
            }

            // With more carriers than cores, wall time between mount and unmount includes time
            // spent waiting for a CPU. Summed mounted time must stay within the CPU time the
            // process actually used, and the spinners account for most of that CPU time.
            if (mountedNanos.get() > cpuNanos * 1.05) {
                throw new AssertionError("Mounted time %s ns exceeds process CPU time %s ns with %s carriers on %s cores"
                        .formatted(mountedNanos.get(), cpuNanos, threads, Runtime.getRuntime().availableProcessors()));
            }
            if (mountedNanos.get() < cpuNanos / 2) {
                throw new AssertionError("Mounted time %s ns is below half the process CPU time %s ns"
                        .formatted(mountedNanos.get(), cpuNanos));
            }
        }

        private static void testForeignRetransformation()
                throws Exception
        {
            Method retransform = Class.forName(RETRANSFORM_AGENT_CLASS).getMethod("retransform", boolean.class);
            VirtualThreadTime.initialize();
            if (NativeAccess.mode() != NativeAccess.Mode.HOOKS) {
                throw new AssertionError("Expected hooks mode but got " + NativeAccess.mode());
            }
            verifyHooksInstalled(true);
            verifyMountsCounted();

            // The agent ahead of the library passes the class through, so the library receives the
            // bytes it installed the hooks on, serves the hooked bytes again, and the hooks survive.
            retransform.invoke(null, false);
            verifyHooksInstalled(true);
            verifyMountsCounted();

            // The agent changes the class. The library cannot reapply the hooks on those bytes, so
            // it reports them removed and registration fails instead of measuring nothing.
            retransform.invoke(null, true);
            verifyHooksInstalled(false);
            verifyRegistrationFails(HOOKS_REMOVED_MESSAGE);
            startAndJoinVirtualThreads(10);
            verifyPublishedLibraryCount(1);
        }

        private static void verifyHooksInstalled(boolean expected)
                throws Exception
        {
            long installed = readPublishedValue("airlift_vthreadtime_hooks_installed");
            if (installed != (expected ? 1 : 0)) {
                throw new AssertionError("Expected hooks installed to be %s but the library reports %s".formatted(expected, installed));
            }
        }

        private static void verifyMountsCounted()
                throws Exception
        {
            int parks = 10;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread.ofVirtual().start(() -> {
                try (VirtualThreadTime time = VirtualThreadTime.register()) {
                    for (int index = 0; index < parks; index++) {
                        LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
                    }
                    if (time.mountCount() <= parks || time.unmountCount() < parks) {
                        throw new AssertionError("Hooks did not count the transitions: %s mounts and %s unmounts after %s parks"
                                .formatted(time.mountCount(), time.unmountCount(), parks));
                    }
                }
                catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }).join();
            if (failure.get() != null) {
                throw new AssertionError("Registered virtual thread failed", failure.get());
            }
        }

        private static void verifyRegistrationFails(String expectedMessage)
                throws Exception
        {
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread.ofVirtual().start(() -> {
                try (VirtualThreadTime time = VirtualThreadTime.register()) {
                    outcome.set(new AssertionError("Registration succeeded after the hooks were removed with %s mounts".formatted(time.mountCount())));
                }
                catch (Throwable throwable) {
                    outcome.set(throwable);
                }
            }).join();
            if (!(outcome.get() instanceof IllegalStateException exception) || !exception.getMessage().contains(expectedMessage)) {
                throw new AssertionError("Expected registration to fail with '%s'".formatted(expectedMessage), outcome.get());
            }
        }

        private static Duration processCpuTime()
        {
            return ProcessHandle.current().info().totalCpuDuration()
                    .orElseThrow(() -> new IllegalStateException("Process CPU time is unavailable"));
        }

        private static void testLifecycle(URL libraryClasses)
                throws Exception
        {
            List<WeakReference<ClassLoader>> concurrentLoaders = initializeConcurrently(libraryClasses, 8);
            awaitCollected(concurrentLoaders);
            verifyPublishedLibraryCount(1);

            // Every loader must have settled on the configured mechanism, and the hooks must be
            // installed exactly when that mechanism is hooks.
            String expectedMode = System.getProperty(MODE_PROPERTY, "hooks").toUpperCase(Locale.ROOT);
            ModeSnapshot modeSnapshot = readModeOnce(libraryClasses);
            awaitCollected(List.of(modeSnapshot.loader()));
            if (!modeSnapshot.mode().equals(expectedMode) || modeSnapshot.hooksFailure() != 0) {
                throw new AssertionError("Expected mode %s without hooks failure but got %s with status %s"
                        .formatted(expectedMode, modeSnapshot.mode(), modeSnapshot.hooksFailure()));
            }
            long hooksInstalled = readPublishedValue("airlift_vthreadtime_hooks_installed");
            if (hooksInstalled != (expectedMode.equals("HOOKS") ? 1 : 0)) {
                throw new AssertionError("Unexpected hooks installation state in mode %s: %s".formatted(expectedMode, hooksInstalled));
            }

            for (int index = 0; index < 10; index++) {
                WeakReference<ClassLoader> loader = initializeAndRelease(libraryClasses);
                awaitCollected(List.of(loader));
                startAndJoinVirtualThread();
            }

            ActiveRegistration registration = startActiveRegistration(libraryClasses);
            awaitCollected(List.of(registration.loader()));
            if (readActiveRegistrations(libraryClasses) != 1) {
                throw new AssertionError("Native registration was not shared across class loaders");
            }
            startAndJoinVirtualThread();
            registration.release().countDown();
            registration.thread().join();
            assertEventuallyNoActiveRegistrations(libraryClasses);
            verifyPublishedLibraryCount(1);
        }

        private static void testInitializationFailure(URL libraryClasses, String fault)
                throws Exception
        {
            String expectedFailure = switch (fault) {
                case "standard-callbacks", "mount-callback", "unmount-callback" -> "Native initialization failed: callback installation failed";
                case "thread-end-event", "mount-event", "unmount-event" -> "Native initialization failed: event enable failed";
                default -> throw new IllegalArgumentException("Unknown initialization fault: " + fault);
            };

            long callbackCountAfterFailure;
            TransitionStorm transitionStorm = startTransitionStorm(4);
            try {
                WeakReference<ClassLoader> first = verifyExpectedFailureAndRelease(libraryClasses, expectedFailure);
                long jvmtiStatus = readPublishedValue("airlift_vthreadtime_test_jvmti_get_phase_status");
                if (jvmtiStatus != 0) {
                    throw new AssertionError("Failed initialization did not retain a valid JVMTI environment: " + jvmtiStatus);
                }
                callbackCountAfterFailure = readPublishedValue("airlift_vthreadtime_test_callback_count");
                if ((fault.equals("mount-event") || fault.equals("unmount-event")) && callbackCountAfterFailure == 0) {
                    throw new AssertionError("Late initialization failure did not overlap an event callback");
                }

                WeakReference<ClassLoader> second = verifyExpectedFailureAndRelease(libraryClasses, expectedFailure);
                awaitCollected(List.of(first, second));
                startAndJoinVirtualThreads(100);
                Thread.sleep(Duration.ofMillis(100));
                if (readPublishedValue("airlift_vthreadtime_test_callback_count") != callbackCountAfterFailure) {
                    throw new AssertionError("Failed initialization left callbacks enabled");
                }
            }
            finally {
                transitionStorm.stop();
            }

            if (readPublishedValue("airlift_vthreadtime_test_callback_count") != callbackCountAfterFailure) {
                throw new AssertionError("Failed initialization left virtual-thread-end callbacks enabled");
            }
            if (readPublishedValue("airlift_vthreadtime_active_registrations") != 0) {
                throw new AssertionError("Failed initialization left an active registration");
            }
            verifyPublishedLibraryCount(1);
        }

        private static void testHooksFallback(URL libraryClasses)
                throws Exception
        {
            // The injected fault breaks hooks installation once; the library must fall back to the
            // events for the lifetime of the process, even though a later attempt would succeed.
            WeakReference<ClassLoader> first = verifyFallbackAndRelease(libraryClasses);
            WeakReference<ClassLoader> second = verifyFallbackAndRelease(libraryClasses);
            awaitCollected(List.of(first, second));
            if (readPublishedValue("airlift_vthreadtime_hooks_installed") != 0) {
                throw new AssertionError("Hooks were installed after the events fallback");
            }

            ActiveRegistration registration = startActiveRegistration(libraryClasses);
            awaitCollected(List.of(registration.loader()));
            if (readActiveRegistrations(libraryClasses) != 1) {
                throw new AssertionError("Registration after the fallback was not counted");
            }
            registration.release().countDown();
            registration.thread().join();
            assertEventuallyNoActiveRegistrations(libraryClasses);
            verifyPublishedLibraryCount(1);
        }

        private static WeakReference<ClassLoader> verifyFallbackAndRelease(URL libraryClasses)
                throws Exception
        {
            URLClassLoader loader = new URLClassLoader(new URL[] {libraryClasses}, ClassLoader.getPlatformClassLoader());
            try (loader) {
                Class<?> api = Class.forName("io.airlift.vthreadtime.VirtualThreadTime", true, loader);
                if (!(boolean) api.getMethod("isSupported").invoke(null)) {
                    throw new AssertionError("Hooks failure was not recovered by the events fallback");
                }
                ModeSnapshot snapshot = readMode(loader);
                if (!snapshot.mode().equals("EVENTS") || snapshot.hooksFailure() == 0) {
                    throw new AssertionError("Expected events fallback with a hooks failure but got %s with status %s"
                            .formatted(snapshot.mode(), snapshot.hooksFailure()));
                }
                return new WeakReference<>(loader);
            }
        }

        private static ModeSnapshot readModeOnce(URL libraryClasses)
                throws Exception
        {
            try (URLClassLoader loader = new URLClassLoader(new URL[] {libraryClasses}, ClassLoader.getPlatformClassLoader())) {
                return readMode(loader);
            }
        }

        private static ModeSnapshot readMode(URLClassLoader loader)
                throws Exception
        {
            Class<?> nativeAccess = Class.forName("io.airlift.vthreadtime.NativeAccess", true, loader);
            Method mode = nativeAccess.getDeclaredMethod("mode");
            mode.setAccessible(true);
            Method hooksFailure = nativeAccess.getDeclaredMethod("hooksFailure");
            hooksFailure.setAccessible(true);
            Object value = mode.invoke(null);
            return new ModeSnapshot(
                    value == null ? "null" : ((Enum<?>) value).name(),
                    (int) hooksFailure.invoke(null),
                    new WeakReference<>(loader));
        }

        private static TransitionStorm startTransitionStorm(int threadCount)
                throws InterruptedException
        {
            AtomicBoolean running = new AtomicBoolean(true);
            CountDownLatch started = new CountDownLatch(threadCount);
            List<Thread> threads = new ArrayList<>();
            for (int index = 0; index < threadCount; index++) {
                threads.add(Thread.ofVirtual().start(() -> {
                    started.countDown();
                    while (running.get()) {
                        LockSupport.parkNanos(1);
                    }
                }));
            }
            started.await();
            return new TransitionStorm(running, List.copyOf(threads));
        }

        private static List<WeakReference<ClassLoader>> initializeConcurrently(URL libraryClasses, int count)
                throws Exception
        {
            CountDownLatch start = new CountDownLatch(1);
            List<WeakReference<ClassLoader>> loaders = Collections.synchronizedList(new ArrayList<>());
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> threads = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        loaders.add(initializeAndRelease(libraryClasses));
                    }
                    catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                }));
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join();
            }
            if (failure.get() != null) {
                throw new AssertionError("Concurrent class-loader initialization failed", failure.get());
            }
            return List.copyOf(loaders);
        }

        private static WeakReference<ClassLoader> initializeAndRelease(URL libraryClasses)
                throws Exception
        {
            URLClassLoader loader = new URLClassLoader(new URL[] {libraryClasses}, ClassLoader.getPlatformClassLoader());
            try (loader) {
                Class<?> api = Class.forName("io.airlift.vthreadtime.VirtualThreadTime", true, loader);
                if (!(boolean) api.getMethod("isSupported").invoke(null)) {
                    throw new AssertionError("Native library is not supported");
                }
                api.getMethod("initialize").invoke(null);
                return new WeakReference<>(loader);
            }
        }

        private static WeakReference<ClassLoader> verifyUnsupportedAndRelease(URL libraryClasses)
                throws Exception
        {
            URLClassLoader loader = new URLClassLoader(new URL[] {libraryClasses}, ClassLoader.getPlatformClassLoader());
            try (loader) {
                Class<?> api = Class.forName("io.airlift.vthreadtime.VirtualThreadTime", true, loader);
                if ((boolean) api.getMethod("isSupported").invoke(null)) {
                    throw new AssertionError("Invalid native library was accepted");
                }
                return new WeakReference<>(loader);
            }
        }

        private static WeakReference<ClassLoader> verifyExpectedFailureAndRelease(URL libraryClasses, String expectedFailure)
                throws Exception
        {
            URLClassLoader loader = new URLClassLoader(new URL[] {libraryClasses}, ClassLoader.getPlatformClassLoader());
            try (loader) {
                Class<?> api = Class.forName("io.airlift.vthreadtime.VirtualThreadTime", true, loader);
                if ((boolean) api.getMethod("isSupported").invoke(null)) {
                    throw new AssertionError("Native library failure was not retained");
                }
                try {
                    api.getMethod("initialize").invoke(null);
                    throw new AssertionError("Native initialization unexpectedly succeeded");
                }
                catch (InvocationTargetException exception) {
                    if (!causeChainContains(exception.getCause(), expectedFailure)) {
                        throw new AssertionError(
                                "Expected native failure containing '%s' but got %s".formatted(expectedFailure, exception.getCause()),
                                exception);
                    }
                }
                return new WeakReference<>(loader);
            }
        }

        private static boolean causeChainContains(Throwable throwable, String expected)
        {
            for (Throwable current = throwable; current != null; current = current.getCause()) {
                if (current.getMessage() != null && current.getMessage().contains(expected)) {
                    return true;
                }
            }
            return false;
        }

        private static ActiveRegistration startActiveRegistration(URL libraryClasses)
                throws Exception
        {
            URLClassLoader loader = new URLClassLoader(new URL[] {libraryClasses}, ClassLoader.getPlatformClassLoader());
            WeakReference<ClassLoader> loaderReference = new WeakReference<>(loader);
            AtomicReference<Method> register = new AtomicReference<>(
                    Class.forName("io.airlift.vthreadtime.VirtualThreadTime", true, loader).getMethod("register"));
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = Thread.ofVirtual().start(() -> {
                try {
                    // Release the reflective Method before the disposable loader is checked for collection.
                    register.getAndSet(null).invoke(null);
                    registered.countDown();
                    release.await();
                }
                catch (Throwable throwable) {
                    failure.set(throwable);
                    registered.countDown();
                }
            });
            registered.await();
            loader.close();
            if (failure.get() != null) {
                throw new AssertionError("Could not create active registration", failure.get());
            }
            return new ActiveRegistration(loaderReference, thread, release);
        }

        private static long readActiveRegistrations(URL libraryClasses)
                throws Exception
        {
            RegistrationSnapshot snapshot = readActiveRegistrationsOnce(libraryClasses);
            awaitCollected(List.of(snapshot.loader()));
            return snapshot.registrations();
        }

        private static RegistrationSnapshot readActiveRegistrationsOnce(URL libraryClasses)
                throws Exception
        {
            try (URLClassLoader loader = new URLClassLoader(new URL[] {libraryClasses}, ClassLoader.getPlatformClassLoader())) {
                Class<?> nativeAccess = Class.forName("io.airlift.vthreadtime.NativeAccess", true, loader);
                Method method = nativeAccess.getDeclaredMethod("activeRegistrations");
                method.setAccessible(true);
                return new RegistrationSnapshot(
                        (long) method.invoke(null),
                        new WeakReference<>(loader));
            }
        }

        private static void assertEventuallyNoActiveRegistrations(URL libraryClasses)
                throws Exception
        {
            RegistrationPoll poll = pollActiveRegistrations(libraryClasses);
            awaitCollected(List.of(poll.loader()));
            if (!poll.cleared()) {
                throw new AssertionError("Native registration was not cleaned at thread end");
            }
        }

        private static RegistrationPoll pollActiveRegistrations(URL libraryClasses)
                throws Exception
        {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            boolean cleared = false;
            try (URLClassLoader loader = new URLClassLoader(new URL[] {libraryClasses}, ClassLoader.getPlatformClassLoader())) {
                WeakReference<ClassLoader> loaderReference = new WeakReference<>(loader);
                Class<?> nativeAccess = Class.forName("io.airlift.vthreadtime.NativeAccess", true, loader);
                Method method = nativeAccess.getDeclaredMethod("activeRegistrations");
                method.setAccessible(true);
                do {
                    if ((long) method.invoke(null) == 0) {
                        cleared = true;
                        break;
                    }
                    Thread.sleep(Duration.ofMillis(10));
                }
                while (System.nanoTime() < deadline);
                return new RegistrationPoll(cleared, loaderReference);
            }
        }

        private static void awaitCollected(List<WeakReference<ClassLoader>> loaders)
                throws Exception
        {
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            do {
                System.gc();
                if (loaders.stream().allMatch(reference -> reference.get() == null)) {
                    return;
                }
                Thread.sleep(Duration.ofMillis(10));
            }
            while (System.nanoTime() < deadline);
            throw new AssertionError("Disposable class loader was not reclaimed");
        }

        private static void startAndJoinVirtualThread()
                throws InterruptedException
        {
            Thread.ofVirtual().start(() -> {}).join();
        }

        private static void startAndJoinVirtualThreads(int count)
                throws InterruptedException
        {
            for (int index = 0; index < count; index++) {
                startAndJoinVirtualThread();
            }
        }

        private static long readPublishedValue(String symbol)
                throws Exception
        {
            Path temporaryDirectory = Path.of(System.getProperty(TEMP_DIRECTORY_PROPERTY));
            Path library;
            try (var files = Files.walk(temporaryDirectory)) {
                library = files.filter(Files::isRegularFile)
                        .findFirst()
                        .orElseThrow(() -> new IOException("Published native library was not found"));
            }

            Linker linker = Linker.nativeLinker();
            SymbolLookup symbols = SymbolLookup.libraryLookup(library, Arena.global());
            var handle = linker.downcallHandle(
                    symbols.findOrThrow(symbol),
                    FunctionDescriptor.of(JAVA_LONG));
            try {
                return (long) handle.invokeExact();
            }
            catch (Throwable throwable) {
                throw new IllegalStateException("Could not read native value: " + symbol, throwable);
            }
        }

        private static void verifyPublishedLibraryCount(long expected)
                throws IOException
        {
            Path temporaryDirectory = Path.of(System.getProperty(TEMP_DIRECTORY_PROPERTY));
            try (var files = Files.walk(temporaryDirectory)) {
                long count = files.filter(Files::isRegularFile).count();
                if (count != expected) {
                    throw new AssertionError("Expected %s published native file but found %s".formatted(expected, count));
                }
            }
        }

        private record ActiveRegistration(
                WeakReference<ClassLoader> loader,
                Thread thread,
                CountDownLatch release) {}

        private record RegistrationSnapshot(
                long registrations,
                WeakReference<ClassLoader> loader) {}

        private record ModeSnapshot(
                String mode,
                int hooksFailure,
                WeakReference<ClassLoader> loader) {}

        private record RegistrationPoll(
                boolean cleared,
                WeakReference<ClassLoader> loader) {}

        private record TransitionStorm(
                AtomicBoolean running,
                List<Thread> threads)
        {
            public void stop()
                    throws InterruptedException
            {
                running.set(false);
                for (Thread thread : threads) {
                    thread.join();
                }
            }
        }
    }
}
