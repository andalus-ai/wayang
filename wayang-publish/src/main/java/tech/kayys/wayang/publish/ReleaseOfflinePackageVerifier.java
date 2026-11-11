package tech.kayys.wayang.publish;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import tech.kayys.wayang.runtime.json.Json;

/**
 * Verifies an offline release ZIP without extracting or executing any packaged content.
 *
 * <p>The embedded lock and digest manifest are verified against an externally trusted
 * Ed25519 key. Every archive entry must belong to the exact package layout and every
 * packaged POM/JAR is re-hashed against the signed manifest.</p>
 */
public final class ReleaseOfflinePackageVerifier {
    private static final int MAX_ENTRIES = 100_000;
    private static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 16 * 1024 * 1024;

    public record VerifiedPackage(Path packageFile, String packageSha256, String lockSha256,
            String manifestSha256, String sbomSha256, int artifactCount, Map<String, String> artifactDigests) {
        public VerifiedPackage {
            artifactDigests = Map.copyOf(artifactDigests);
        }
    }

    private ReleaseOfflinePackageVerifier() {}

    /**
     * Validates the ZIP in place. No filesystem extraction occurs.
     *
     * @throws IOException for malformed archives, unsafe paths, missing/extra entries,
     *         oversized content, or artifact digest mismatches
     * @throws IllegalArgumentException when the embedded lock or signature is invalid
     */
    public static VerifiedPackage verify(Path packageFile, PublicKey trustedReleaseSigner) throws IOException {
        Objects.requireNonNull(packageFile, "packageFile");
        Objects.requireNonNull(trustedReleaseSigner, "trustedReleaseSigner");
        if (Files.isSymbolicLink(packageFile)
                || !Files.isRegularFile(packageFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Offline package must be a regular non-symlink file");
        }

        Path normalized = packageFile.toAbsolutePath().normalize();
        try (ZipFile zip = new ZipFile(normalized.toFile())) {
            Map<String, ZipEntry> entries = enumerate(zip);
            String lockJson = readText(zip, required(entries, "release/lock.json"), MAX_METADATA_BYTES);
            String manifestJson = readText(zip, required(entries, "release/artifact-digests.json"), MAX_METADATA_BYTES);
            String sbomJson = readText(zip, required(entries, "release/bom.cdx.json"), MAX_METADATA_BYTES);

            var lock = ReleaseArtifactResolver.verify(lockJson);
            var manifest = ReleaseArtifactDigestManifest.verify(manifestJson, lock, trustedReleaseSigner);
            String expectedSbom = ReleaseArtifactSbom.create(lock, manifest);
            if (!expectedSbom.equals(sbomJson)) {
                throw new IOException("Offline package SBOM does not match the verified lock and manifest");
            }

            Map<String, String> expected = new TreeMap<>();
            expected.put("release/lock.json", sha256(lockJson.getBytes(StandardCharsets.UTF_8)));
            expected.put("release/artifact-digests.json", sha256(manifestJson.getBytes(StandardCharsets.UTF_8)));
            expected.put("release/bom.cdx.json", sha256(sbomJson.getBytes(StandardCharsets.UTF_8)));
            for (var artifact : lock.artifacts()) {
                var coordinate = artifact.coordinate();
                String base = "repository/" + safeGroupPath(coordinate.groupId()) + "/"
                        + safeSegment(coordinate.artifactId()) + "/" + safeSegment(coordinate.version()) + "/";
                String stem = safeSegment(coordinate.artifactId()) + "-" + safeSegment(coordinate.version());
                var digest = manifest.artifact(coordinate);
                expected.put(base + stem + ".pom", digest.pomSha256());
                expected.put(base + stem + ".jar", digest.jarSha256());
            }
            if (!entries.keySet().equals(expected.keySet())) {
                var missing = new java.util.TreeSet<>(expected.keySet());
                missing.removeAll(entries.keySet());
                var extra = new java.util.TreeSet<>(entries.keySet());
                extra.removeAll(expected.keySet());
                throw new IOException("Offline package entry set mismatch; missing=" + missing + ", extra=" + extra);
            }

            Map<String, String> artifactDigests = new LinkedHashMap<>();
            long total = 0;
            for (var item : expected.entrySet()) {
                ZipEntry entry = entries.get(item.getKey());
                long limit = item.getKey().startsWith("release/") ? MAX_METADATA_BYTES : MAX_ENTRY_BYTES;
                String actual;
                try (InputStream input = zip.getInputStream(entry)) {
                    DigestResult result = digest(input, limit);
                    actual = result.sha256();
                    total = Math.addExact(total, result.bytes());
                } catch (ArithmeticException e) {
                    throw new IOException("Offline package uncompressed size overflow", e);
                }
                if (total > MAX_TOTAL_BYTES) throw new IOException("Offline package exceeds maximum uncompressed size");
                if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                        item.getValue().getBytes(StandardCharsets.US_ASCII))) {
                    throw new IOException("Offline package entry digest mismatch: " + item.getKey());
                }
                if (item.getKey().startsWith("repository/")) artifactDigests.put(item.getKey(), actual);
            }

            return new VerifiedPackage(normalized, digestFile(normalized), lock.sha256(),
                    sha256(manifestJson.getBytes(StandardCharsets.UTF_8)),
                    sha256(sbomJson.getBytes(StandardCharsets.UTF_8)), lock.artifacts().size(), artifactDigests);
        } catch (java.util.zip.ZipException e) {
            throw new IOException("Offline package is not a valid ZIP archive", e);
        }
    }

    private static Map<String, ZipEntry> enumerate(ZipFile zip) throws IOException {
        Map<String, ZipEntry> result = new LinkedHashMap<>();
        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        int count = 0;
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            if (++count > MAX_ENTRIES) throw new IOException("Offline package contains too many entries");
            String name = entry.getName();
            validateArchivePath(name);
            if (entry.isDirectory()) throw new IOException("Directory entries are not permitted: " + name);
            if (entry.getSize() > MAX_ENTRY_BYTES) throw new IOException("Offline package entry is too large: " + name);
            if (result.putIfAbsent(name, entry) != null) throw new IOException("Duplicate ZIP entry: " + name);
        }
        return result;
    }

    private static void validateArchivePath(String name) throws IOException {
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

    private static String safeGroupPath(String groupId) throws IOException {
        String[] segments = groupId.split("\\.", -1);
        java.util.List<String> safe = new java.util.ArrayList<>();
        for (String segment : segments) safe.add(safeSegment(segment));
        return String.join("/", safe);
    }

    private static String safeSegment(String value) throws IOException {
        if (value == null || value.isBlank() || value.equals(".") || value.equals("..")
                || !value.matches("[A-Za-z0-9_+.-]+")) {
            throw new IOException("Unsafe Maven coordinate path segment: " + value);
        }
        return value;
    }

    private static ZipEntry required(Map<String, ZipEntry> entries, String name) throws IOException {
        ZipEntry entry = entries.get(name);
        if (entry == null) throw new IOException("Offline package is missing " + name);
        return entry;
    }

    private static String readText(ZipFile zip, ZipEntry entry, int maxBytes) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            DigestResult ignored = digest(input, maxBytes);
        }
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("Offline package metadata is too large: " + entry.getName());
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private record DigestResult(String sha256, long bytes) {}

    private static DigestResult digest(InputStream input, long maxBytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("Offline package entry exceeds size limit");
                digest.update(buffer, 0, read);
            }
            return new DigestResult(HexFormat.of().formatHex(digest.digest()), total);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String digestFile(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return digest(input, Long.MAX_VALUE).sha256();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
