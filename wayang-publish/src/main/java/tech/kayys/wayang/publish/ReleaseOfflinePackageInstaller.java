package tech.kayys.wayang.publish;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs a verified offline release into a new directory using an isolated staging tree.
 *
 * <p>Nothing is executed. The destination becomes visible only after every entry has been
 * extracted and checked. Existing destinations are never intentionally overwritten.</p>
 */
public final class ReleaseOfflinePackageInstaller {
    private static final int MAX_ENTRIES = 100_000;
    private static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

    public record InstallationResult(Path installationDirectory, String packageSha256,
            String lockSha256, int artifactCount, Map<String, String> artifactDigests) {
        public InstallationResult {
            artifactDigests = Map.copyOf(artifactDigests);
        }
    }

    private ReleaseOfflinePackageInstaller() {}

    /**
     * Verifies and installs an offline package.
     *
     * @param packageFile verified ZIP package
     * @param trustedReleaseSigner externally trusted release-manifest key
     * @param destination new installation directory; it must not already exist
     * @throws IOException if verification, extraction, integrity checks, or publication fails
     */
    public static InstallationResult install(Path packageFile, PublicKey trustedReleaseSigner,
            Path destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        var verified = ReleaseOfflinePackageVerifier.verify(packageFile, trustedReleaseSigner);
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new IOException("Installation parent must be an existing, non-symlink directory");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Installation destination already exists: " + target);
        }
        if (!target.startsWith(parent) || target.equals(parent)) {
            throw new IOException("Invalid installation destination");
        }

        // Detect package replacement between verification and extraction.
        if (!MessageDigest.isEqual(digest(packageFile).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                verified.packageSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IOException("Offline package changed after verification");
        }

        Path staging = Files.createTempDirectory(parent, ".wayang-install-");
        boolean published = false;
        try {
            Map<String, String> extractedDigests = extractAndVerify(packageFile, staging, verified.artifactDigests());
            String packageAfter = digest(packageFile);
            if (!MessageDigest.isEqual(packageAfter.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    verified.packageSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new IOException("Offline package changed during installation");
            }
            if (!extractedDigests.equals(new TreeMap<>(verified.artifactDigests()))) {
                throw new IOException("Extracted artifact inventory does not match verified package inventory");
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                throw new IOException("Filesystem does not support atomic installation publication", e);
            }
            published = true;
            return new InstallationResult(target, verified.packageSha256(), verified.lockSha256(),
                    verified.artifactCount(), extractedDigests);
        } finally {
            if (!published) deleteTree(staging);
        }
    }

    private static Map<String, String> extractAndVerify(Path packageFile, Path staging,
            Map<String, String> expectedArtifacts) throws IOException {
        Map<String, String> actualArtifacts = new TreeMap<>();
        long totalBytes = 0;
        int count = 0;
        try (ZipFile zip = new ZipFile(packageFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) throw new IOException("Offline package contains too many entries");
                String name = entry.getName();
                validateEntryName(name);
                if (entry.isDirectory()) throw new IOException("Directory entries are not permitted: " + name);
                if (entry.getSize() > MAX_ENTRY_BYTES) throw new IOException("Offline package entry is too large: " + name);

                Path output = staging.resolve(name).normalize();
                if (!output.startsWith(staging) || output.equals(staging)) {
                    throw new IOException("Archive entry escapes installation staging directory: " + name);
                }
                Path relativeParent = staging.relativize(output.getParent());
                Path cursor = staging;
                for (Path segment : relativeParent) {
                    cursor = cursor.resolve(segment);
                    Files.createDirectories(cursor);
                    if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Unsafe extraction parent: " + cursor);
                    }
                }
                if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Duplicate extraction target: " + name);
                }

                MessageDigest digest = newDigest();
                long entryBytes = 0;
                try (InputStream input = zip.getInputStream(entry);
                        OutputStream outputStream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        entryBytes += read;
                        totalBytes += read;
                        if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                            throw new IOException("Offline package exceeds extraction size limits");
                        }
                        digest.update(buffer, 0, read);
                        outputStream.write(buffer, 0, read);
                    }
                }
                String actual = HexFormat.of().formatHex(digest.digest());
                String expected = expectedArtifacts.get(name);
                if (expected != null) {
                    if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                            expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                        throw new IOException("Extracted artifact digest mismatch: " + name);
                    }
                    actualArtifacts.put(name, actual);
                } else if (name.startsWith("repository/")) {
                    throw new IOException("Unlisted artifact in verified package: " + name);
                }
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException("Offline package became invalid during extraction", e);
        }
        return actualArtifacts;
    }

    private static void validateEntryName(String name) throws IOException {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("Unsafe ZIP entry path: " + name);
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("Unsafe ZIP entry path: " + name);
            }
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String digest(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (DirectoryNotEmptyException e) {
                    throw new IOException("Unable to clean incomplete installation staging directory: " + path, e);
                }
            }
        }
    }
}
