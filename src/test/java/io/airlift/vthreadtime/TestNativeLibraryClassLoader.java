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

import java.io.File;
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
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
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
    private static final String PARALLELISM_PROPERTY = "jdk.virtualThreadScheduler.parallelism";
    private static final List<String> NATIVE_ACCESS_OPTIONS = List.of("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny");
    // Default maximum pool size of the virtual thread scheduler; parallelism above it is rejected
    private static final int DEFAULT_MAX_POOL_SIZE = 256;

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
    void testHooksFailureIsLatchedProcessWide(@TempDir Path temporaryDirectory)
            throws Exception
    {
        Path faultLibrary = buildTestNativeLibrary(
                temporaryDirectory.resolve("fixture"),
                "--test-initialization-faults");
        for (String fault : List.of("hook-class", "module-reads", "transform", "retransform")) {
            runSubprocess(
                    "hooks-failure",
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
    void testHooksAreNotInstalledInAotTrainingRun(@TempDir Path temporaryDirectory)
            throws Exception
    {
        // Retransforming VirtualThread in a training run excludes it from the cache, and HotSpot
        // refuses to write a cache without that critical class. The run must succeed and produce
        // the cache with the library initialized and a thread registered. The runs get no
        // --illegal-native-access: HotSpot treats that option as incompatible with the archived
        // module graph, and with AOT class linking on by default (JDK 28) the cache assembly then
        // fails. A training run makes no native calls anyway, which the lifecycle main verifies.
        Path cache = temporaryDirectory.resolve("app.aot");
        // A training run rejects directories on the class path, and the cache records the jars
        // it was trained with, so both runs share them
        Path jars = Files.createDirectory(temporaryDirectory.resolve("jars"));
        runSubprocess(
                "aot-training",
                Files.createDirectory(temporaryDirectory.resolve("native")),
                Optional.empty(),
                Optional.empty(),
                List.of("--enable-native-access=ALL-UNNAMED", "-XX:AOTCacheOutput=" + cache),
                Optional.of(jars));
        assertThat(cache)
                .describedAs("AOT cache written by the training run")
                .isRegularFile();

        // The hooks apply on top of the cache; -XX:AOTMode=on fails the run unless the cache loads
        runSubprocess(
                "aot-production",
                Files.createDirectory(temporaryDirectory.resolve("native-production")),
                Optional.empty(),
                Optional.empty(),
                List.of("--enable-native-access=ALL-UNNAMED", "-XX:AOTMode=on", "-XX:AOTCache=" + cache),
                Optional.of(jars));

        // The two-step workflow records a configuration first; that run is a training run as well
        Path configuration = temporaryDirectory.resolve("app.aotconfig");
        runSubprocess(
                "aot-training",
                Files.createDirectory(temporaryDirectory.resolve("native-record")),
                Optional.empty(),
                Optional.empty(),
                List.of("--enable-native-access=ALL-UNNAMED", "-XX:AOTMode=record", "-XX:AOTConfiguration=" + configuration),
                Optional.of(jars));
        assertThat(configuration)
                .describedAs("AOT configuration written by the recording run")
                .isRegularFile();
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
        List<String> options = new ArrayList<>(NATIVE_ACCESS_OPTIONS);
        options.addAll(jvmOptions);
        runSubprocess(mode, temporaryDirectory, configuredLibrary, initializationFault, options, Optional.empty());
    }

    /// Runs the lifecycle main in its own JVM with exactly `jvmOptions`, with the test and main
    /// classes on the class path as the directories they were compiled into or, given
    /// `jarDirectory`, packed into jars there.
    private static void runSubprocess(
            String mode,
            Path temporaryDirectory,
            Optional<Path> configuredLibrary,
            Optional<String> initializationFault,
            List<String> jvmOptions,
            Optional<Path> jarDirectory)
            throws Exception
    {
        Path testClasses = Path.of(TestNativeLibraryClassLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path mainClasses = Path.of(VirtualThreadTime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path testEntry = testClasses;
        Path mainEntry = mainClasses;
        if (jarDirectory.isPresent()) {
            testEntry = jarDirectory.orElseThrow().resolve("test-classes.jar");
            mainEntry = jarDirectory.orElseThrow().resolve("classes.jar");
            // Written once: a cache records the jars it was trained with, so a later run reuses them
            if (Files.notExists(mainEntry)) {
                writeJar(testClasses, testEntry, Optional.empty());
                writeJar(mainClasses, mainEntry, Optional.empty());
            }
        }
        String classPath = testEntry + System.getProperty("path.separator") + mainEntry;

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
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

        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Premain-Class", RETRANSFORM_AGENT_CLASS);
        attributes.putValue("Can-Retransform-Classes", "true");
        return writeJar(classes, directory.resolve("retransform-agent.jar"), Optional.of(manifest));
    }

    private static Path writeJar(Path classes, Path jar, Optional<Manifest> manifest)
            throws IOException
    {
        try (JarOutputStream jarOutput = manifest.isPresent()
                ? new JarOutputStream(Files.newOutputStream(jar), manifest.orElseThrow())
                : new JarOutputStream(Files.newOutputStream(jar));
                var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                jarOutput.putNextEntry(new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')));
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
                case "hooks-failure" -> testHooksFailure(libraryClasses, System.getenv(INITIALIZATION_FAULT_ENVIRONMENT));
                case "carrier-cpu" -> testCarrierCpuTime();
                case "foreign-retransform" -> testForeignRetransformation(libraryClasses);
                case "aot-training" -> testAotTraining();
                case "aot-production" -> testAotProduction();
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

        private static void testForeignRetransformation(URL libraryClasses)
                throws Exception
        {
            Method retransform = Class.forName(RETRANSFORM_AGENT_CLASS).getMethod("retransform", boolean.class);
            VirtualThreadTime.initialize();
            verifyStatus(VirtualThreadTime.Status.ACTIVE);
            verifyHooksInstalled(true);
            verifyMountsCounted();

            // The agent ahead of the library passes the class through, so the library receives the
            // bytes it installed the hooks on, serves the hooked bytes again, and the hooks survive.
            retransform.invoke(null, false);
            verifyHooksInstalled(true);
            verifyStatus(VirtualThreadTime.Status.ACTIVE);
            verifyMountsCounted();

            // The agent changes the class. The library cannot reapply the hooks on those bytes, so
            // it reports them removed, and trackers freeze at their closed intervals instead of
            // charging whichever carrier runs them. A thread that is mounted at that moment never
            // sees its unmount hook again, so its tracker must drop the open interval.
            verifyTotalFreezesWhenHooksAreRemoved(() -> retransform.invoke(null, true));
            verifyHooksInstalled(false);
            verifyStatus(VirtualThreadTime.Status.HOOKS_REMOVED);
            verifyNothingCounted();
            // A class loader initializing only now sees the same removal, not a failure
            WeakReference<ClassLoader> later = verifyStatusAndRelease(libraryClasses, "HOOKS_REMOVED");
            awaitCollected(List.of(later));
            startAndJoinVirtualThreads(10);
            verifyPublishedLibraryCount(1);
        }

        private static void testAotTraining()
                throws Exception
        {
            VirtualThreadTime.initialize();
            verifyStatus(VirtualThreadTime.Status.AOT_TRAINING);
            verifyNothingCounted();
            // The native library is not even extracted
            verifyPublishedLibraryCount(0);
        }

        private static void testAotProduction()
                throws Exception
        {
            VirtualThreadTime.initialize();
            verifyStatus(VirtualThreadTime.Status.ACTIVE);
            verifyHooksInstalled(true);
            verifyMountsCounted();
            verifyPublishedLibraryCount(1);
        }

        private static void verifyStatus(VirtualThreadTime.Status expected)
        {
            if (VirtualThreadTime.status() != expected) {
                throw new AssertionError("Expected status %s but got %s".formatted(expected, VirtualThreadTime.status()));
            }
        }

        /// A tracker registered while the hooks are not measuring reads zero throughout.
        private static void verifyNothingCounted()
                throws Exception
        {
            int parks = 10;
            runOnVirtualThread("Registered virtual thread failed", () -> {
                try (VirtualThreadTime time = VirtualThreadTime.register()) {
                    spin(Duration.ofMillis(20));
                    for (int index = 0; index < parks; index++) {
                        LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
                    }
                    if (time.mountCount() != 0 || time.unmountCount() != 0 || time.mountedTimeNanos() != 0) {
                        throw new AssertionError("Tracker counted while the hooks are not measuring: %s mounts, %s unmounts, %s ns"
                                .formatted(time.mountCount(), time.unmountCount(), time.mountedTimeNanos()));
                    }
                }
            });
        }

        /// Runs `body` on a new virtual thread, waits for it, and reports what it threw.
        private static void runOnVirtualThread(String failureMessage, ThrowingRunnable body)
                throws InterruptedException
        {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread.ofVirtual().start(() -> {
                try {
                    body.run();
                }
                catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }).join();
            if (failure.get() != null) {
                throw new AssertionError(failureMessage, failure.get());
            }
        }

        private static void spin(Duration duration)
        {
            long end = System.nanoTime() + duration.toNanos();
            while (System.nanoTime() < end) {
                Thread.onSpinWait();
            }
        }

        private static void verifyTotalFreezesWhenHooksAreRemoved(Callable<?> removeHooks)
                throws Exception
        {
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch removed = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = Thread.ofVirtual().start(() -> {
                try (VirtualThreadTime time = VirtualThreadTime.register()) {
                    registered.countDown();
                    // Spinning keeps the thread mounted on one carrier across the removal
                    while (removed.getCount() > 0) {
                        Thread.onSpinWait();
                    }
                    long frozen = time.mountedTimeNanos();
                    long mounts = time.mountCount();
                    long unmounts = time.unmountCount();
                    spin(Duration.ofMillis(50));
                    LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
                    if (time.mountedTimeNanos() != frozen || time.mountCount() != mounts || time.unmountCount() != unmounts) {
                        throw new AssertionError("Tracker moved from %s ns, %s mounts, %s unmounts to %s ns, %s mounts, %s unmounts after the hooks were removed"
                                .formatted(frozen, mounts, unmounts, time.mountedTimeNanos(), time.mountCount(), time.unmountCount()));
                    }
                    // The close drops the open interval as well, and must not fail
                }
                catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            registered.await();
            try {
                removeHooks.call();
            }
            finally {
                removed.countDown();
            }
            thread.join();
            if (failure.get() != null) {
                throw new AssertionError("Mounted thread failed across the hooks removal", failure.get());
            }
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
            runOnVirtualThread("Registered virtual thread failed", () -> {
                try (VirtualThreadTime time = VirtualThreadTime.register()) {
                    for (int index = 0; index < parks; index++) {
                        LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
                    }
                    if (time.mountCount() <= parks || time.unmountCount() < parks) {
                        throw new AssertionError("Hooks did not count the transitions: %s mounts and %s unmounts after %s parks"
                                .formatted(time.mountCount(), time.unmountCount(), parks));
                    }
                }
            });
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

            VirtualThreadTime.initialize();
            verifyStatus(VirtualThreadTime.Status.ACTIVE);
            verifyHooksInstalled(true);

            for (int index = 0; index < 10; index++) {
                WeakReference<ClassLoader> loader = initializeAndRelease(libraryClasses);
                awaitCollected(List.of(loader));
                startAndJoinVirtualThread();
            }

            verifyRegistrationSharedAcrossLoaders(libraryClasses);
            verifyPublishedLibraryCount(1);
        }

        /// Every class loader's copy of the library drives the one set of hook classes in the
        /// boot loader, so a thread registered through one copy is already registered to another.
        private static void verifyRegistrationSharedAcrossLoaders(URL libraryClasses)
                throws Exception
        {
            runOnVirtualThread("Shared registration check failed", () -> {
                VirtualThreadTime time = VirtualThreadTime.register();
                try (URLClassLoader loader = newLibraryLoader(libraryClasses)) {
                    apiClass(loader).getMethod("register").invoke(null);
                    throw new AssertionError("Registration was not shared across class loaders");
                }
                catch (InvocationTargetException exception) {
                    if (!causeChainContains(exception.getCause(), "already registered")) {
                        throw new AssertionError("Unexpected registration failure", exception.getCause());
                    }
                }
                finally {
                    time.close();
                }
            });
        }

        private static void testHooksFailure(URL libraryClasses, String fault)
                throws Exception
        {
            String expectedFailure = "Hooks installation failed: " + switch (fault) {
                case "hook-class" -> "hook class definition failed";
                case "module-reads" -> "module readability update failed";
                // The transformer is handed a truncated class file and its parse failure is the cause
                case "transform" -> "VirtualThread transformation failed";
                case "retransform" -> "VirtualThread retransformation failed";
                default -> throw new IllegalArgumentException("Unknown initialization fault: " + fault);
            };
            boolean expectTransformerCause = fault.equals("transform");

            // The injected fault breaks hooks installation once. The failure must hold for the
            // lifetime of the process, even though a later attempt would succeed, and a later class
            // loader must see the failure that was latched rather than a consequence of it. Only the
            // loader whose transformer ran has that transformer's exception as the cause.
            WeakReference<ClassLoader> first = verifyExpectedFailureAndRelease(libraryClasses, expectedFailure, expectTransformerCause);
            WeakReference<ClassLoader> second = verifyExpectedFailureAndRelease(libraryClasses, expectedFailure, false);
            awaitCollected(List.of(first, second));
            if (readPublishedValue("airlift_vthreadtime_hooks_installed") != 0) {
                throw new AssertionError("Hooks were installed after a latched failure");
            }
            startAndJoinVirtualThreads(100);
            verifyPublishedLibraryCount(1);
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

        /// A disposable class loader with its own copy of the library's classes.
        private static URLClassLoader newLibraryLoader(URL libraryClasses)
        {
            return new URLClassLoader(new URL[] {libraryClasses}, ClassLoader.getPlatformClassLoader());
        }

        private static Class<?> apiClass(ClassLoader loader)
                throws ClassNotFoundException
        {
            return Class.forName("io.airlift.vthreadtime.VirtualThreadTime", true, loader);
        }

        private static boolean isSupported(Class<?> api)
                throws Exception
        {
            return (boolean) api.getMethod("isSupported").invoke(null);
        }

        private static WeakReference<ClassLoader> initializeAndRelease(URL libraryClasses)
                throws Exception
        {
            try (URLClassLoader loader = newLibraryLoader(libraryClasses)) {
                Class<?> api = apiClass(loader);
                if (!isSupported(api)) {
                    throw new AssertionError("Native library is not supported");
                }
                api.getMethod("initialize").invoke(null);
                return new WeakReference<>(loader);
            }
        }

        private static WeakReference<ClassLoader> verifyStatusAndRelease(URL libraryClasses, String expectedStatus)
                throws Exception
        {
            try (URLClassLoader loader = newLibraryLoader(libraryClasses)) {
                Class<?> api = apiClass(loader);
                String status = api.getMethod("status").invoke(null).toString();
                if (!status.equals(expectedStatus)) {
                    throw new AssertionError("Expected status %s in a later class loader but got %s".formatted(expectedStatus, status));
                }
                return new WeakReference<>(loader);
            }
        }

        private static WeakReference<ClassLoader> verifyUnsupportedAndRelease(URL libraryClasses)
                throws Exception
        {
            try (URLClassLoader loader = newLibraryLoader(libraryClasses)) {
                Class<?> api = apiClass(loader);
                if (isSupported(api)) {
                    throw new AssertionError("Invalid native library was accepted");
                }
                verifyUnsupportedApiThrows(api);
                return new WeakReference<>(loader);
            }
        }

        private static WeakReference<ClassLoader> verifyExpectedFailureAndRelease(URL libraryClasses, String expectedFailure, boolean expectTransformerCause)
                throws Exception
        {
            try (URLClassLoader loader = newLibraryLoader(libraryClasses)) {
                Class<?> api = apiClass(loader);
                if (isSupported(api)) {
                    throw new AssertionError("Native library failure was not retained");
                }
                Throwable failure = verifyUnsupportedApiThrows(api);
                if (!causeChainContains(failure, expectedFailure)) {
                    throw new AssertionError("Expected native failure containing '%s' but got %s".formatted(expectedFailure, failure), failure);
                }
                // initialize() wraps the installation failure, whose cause is the transformer's own exception
                Throwable transformerFailure = failure.getCause() == null ? null : failure.getCause().getCause();
                if (expectTransformerCause && transformerFailure == null) {
                    throw new AssertionError("The transformer's exception is not the cause of the failure", failure);
                }
                return new WeakReference<>(loader);
            }
        }

        /// Every entry point but `isSupported()` throws `UnsupportedOperationException` on an
        /// unsupported library; returns the one `initialize()` threw.
        private static Throwable verifyUnsupportedApiThrows(Class<?> api)
                throws Exception
        {
            Throwable initializeFailure = expectUnsupported(api, "initialize");
            expectUnsupported(api, "status");
            runOnVirtualThread("Unsupported registration check failed", () -> expectUnsupported(api, "register"));
            return initializeFailure;
        }

        private static Throwable expectUnsupported(Class<?> api, String method)
                throws Exception
        {
            try {
                api.getMethod(method).invoke(null);
            }
            catch (InvocationTargetException exception) {
                if (exception.getCause() instanceof UnsupportedOperationException unsupported) {
                    return unsupported;
                }
                throw new AssertionError("%s() failed with something other than UnsupportedOperationException".formatted(method), exception.getCause());
            }
            throw new AssertionError("%s() unexpectedly succeeded on an unsupported library".formatted(method));
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

        @FunctionalInterface
        private interface ThrowingRunnable
        {
            void run()
                    throws Throwable;
        }
    }
}
