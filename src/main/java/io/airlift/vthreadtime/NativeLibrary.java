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
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.GROUP_READ;
import static java.nio.file.attribute.PosixFilePermission.GROUP_WRITE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_READ;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static java.nio.file.attribute.PosixFilePermissions.asFileAttribute;

final class NativeLibrary
{
    private static final String LIBRARY_PATH_PROPERTY = "io.airlift.vthreadtime.library.path";
    private static final String TEMP_DIRECTORY_PROPERTY = "io.airlift.vthreadtime.tmpdir";
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(OWNER_READ, OWNER_WRITE);
    private static final Set<PosixFilePermission> UNSAFE_PERMISSIONS = Set.of(
            GROUP_READ,
            GROUP_WRITE,
            GROUP_EXECUTE,
            OTHERS_READ,
            OTHERS_WRITE,
            OTHERS_EXECUTE);

    private NativeLibrary() {}

    static SymbolLookup load()
            throws IOException
    {
        // JVMTI retains callback addresses after initialization, so every class loader must
        // rendezvous on the same native image and keep it loaded for the process lifetime.
        Path library = prepareLibrary();
        return SymbolLookup.libraryLookup(library, Arena.global());
    }

    private static Path prepareLibrary()
            throws IOException
    {
        Platform platform = Platform.current();
        Path temporaryDirectory = temporaryDirectory();
        UserPrincipal owner = currentUser(temporaryDirectory);

        String processStart = ProcessHandle.current().info().startInstant()
                .map(instant -> Long.toString(instant.toEpochMilli()))
                .orElseGet(() -> "runtime-" + Integer.toUnsignedString(System.identityHashCode(Runtime.getRuntime()), 16));
        Path processDirectory = temporaryDirectory.resolve("vthreadtime-%s-%s"
                .formatted(ProcessHandle.current().pid(), processStart));
        createPrivateDirectory(processDirectory, owner);

        Path library = processDirectory.resolve(platform.libraryName());
        if (!Files.exists(library, NOFOLLOW_LINKS)) {
            publishLibrary(library, processDirectory, platform, owner);
        }
        verifyPrivateFile(library, owner);
        return library.toRealPath();
    }

    private static void publishLibrary(
            Path library,
            Path processDirectory,
            Platform platform,
            UserPrincipal owner)
            throws IOException
    {
        Path staging = Files.createTempFile(
                processDirectory,
                ".vthreadtime-",
                platform.librarySuffix(),
                asFileAttribute(FILE_PERMISSIONS));
        try {
            MessageDigest sourceDigest = sha256();
            try (InputStream input = new DigestInputStream(openLibrarySource(platform), sourceDigest);
                    OutputStream output = Files.newOutputStream(staging, WRITE, TRUNCATE_EXISTING)) {
                input.transferTo(output);
            }
            try (FileChannel channel = FileChannel.open(staging, WRITE)) {
                channel.force(true);
            }
            if (!Arrays.equals(sourceDigest.digest(), digest(staging))) {
                throw new IOException("Native library extraction failed verification");
            }
            verifyPrivateFile(staging, owner);

            try {
                Files.createLink(library, staging);
                processDirectory.toFile().deleteOnExit();
                library.toFile().deleteOnExit();
            }
            catch (FileAlreadyExistsException ignored) {
                // Another class loader published the process-wide library.
            }
        }
        finally {
            Files.deleteIfExists(staging);
        }
    }

    private static InputStream openLibrarySource(Platform platform)
            throws IOException
    {
        String configuredPath = System.getProperty(LIBRARY_PATH_PROPERTY);
        if (configuredPath != null) {
            Path path = Path.of(configuredPath).toAbsolutePath().normalize().toRealPath();
            if (!Files.isRegularFile(path)) {
                throw new IOException("Configured native library is not a regular file: " + path);
            }
            return Files.newInputStream(path);
        }

        String resource = "/io/airlift/vthreadtime/native/%s-%s/%s"
                .formatted(platform.operatingSystem(), platform.architecture(), platform.libraryName());
        InputStream input = NativeLibrary.class.getResourceAsStream(resource);
        if (input == null) {
            throw new IOException("Native library resource is not available: " + resource);
        }
        return input;
    }

    private static Path temporaryDirectory()
            throws IOException
    {
        String value = System.getProperty(TEMP_DIRECTORY_PROPERTY);
        if (value == null) {
            value = System.getProperty("java.io.tmpdir");
        }
        if (value == null) {
            throw new IOException("No temporary directory is configured");
        }
        Path path = Path.of(value).toAbsolutePath().normalize().toRealPath();
        if (!Files.isDirectory(path)) {
            throw new IOException("Temporary path is not a directory: " + path);
        }
        return path;
    }

    private static UserPrincipal currentUser(Path temporaryDirectory)
            throws IOException
    {
        Path probe = Files.createTempFile(
                temporaryDirectory,
                ".vthreadtime-owner-",
                ".tmp",
                asFileAttribute(FILE_PERMISSIONS));
        try {
            return Files.readAttributes(probe, PosixFileAttributes.class, NOFOLLOW_LINKS).owner();
        }
        finally {
            Files.deleteIfExists(probe);
        }
    }

    private static void createPrivateDirectory(Path directory, UserPrincipal owner)
            throws IOException
    {
        try {
            Files.createDirectory(directory, asFileAttribute(DIRECTORY_PERMISSIONS));
        }
        catch (FileAlreadyExistsException ignored) {
            // A previous class loader may have created the rendezvous directory.
        }

        PosixFileAttributes attributes = Files.readAttributes(directory, PosixFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isDirectory() ||
                !attributes.owner().equals(owner) ||
                attributes.permissions().stream().anyMatch(UNSAFE_PERMISSIONS::contains)) {
            throw new IOException("Native library directory is not private: " + directory);
        }
    }

    private static void verifyPrivateFile(Path file, UserPrincipal owner)
            throws IOException
    {
        PosixFileAttributes attributes = Files.readAttributes(file, PosixFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() ||
                !attributes.owner().equals(owner) ||
                attributes.permissions().stream().anyMatch(UNSAFE_PERMISSIONS::contains)) {
            throw new IOException("Native library file is not private: " + file);
        }
    }

    private static byte[] digest(Path path)
            throws IOException
    {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(path);
                DigestInputStream ignored = new DigestInputStream(input, digest)) {
            ignored.transferTo(OutputStream.nullOutputStream());
        }
        return digest.digest();
    }

    private static MessageDigest sha256()
    {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private record Platform(String operatingSystem, String architecture, String libraryName, String librarySuffix)
    {
        private static Platform current()
        {
            String operatingSystem = normalizeOperatingSystem(System.getProperty("os.name"));
            String architecture = normalizeArchitecture(System.getProperty("os.arch"));
            return switch (operatingSystem) {
                case "linux" -> new Platform(operatingSystem, architecture, "libvthreadtime.so", ".so");
                case "macos" -> new Platform(operatingSystem, architecture, "libvthreadtime.dylib", ".dylib");
                default -> throw new UnsupportedOperationException("Unsupported operating system: " + System.getProperty("os.name"));
            };
        }

        private static String normalizeOperatingSystem(String value)
        {
            String operatingSystem = value.toLowerCase(Locale.ROOT);
            if (operatingSystem.equals("linux")) {
                return "linux";
            }
            if (operatingSystem.equals("mac os x") || operatingSystem.equals("macos")) {
                return "macos";
            }
            return operatingSystem;
        }

        private static String normalizeArchitecture(String value)
        {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "aarch64", "arm64" -> "aarch64";
                case "amd64", "x86_64" -> "x86_64";
                default -> throw new UnsupportedOperationException("Unsupported architecture: " + value);
            };
        }
    }
}
