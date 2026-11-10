package tech.kayys.wayang.publish;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a deterministic offline ZIP from a verified lock and signed artifact manifest. */
public final class ReleaseOfflinePackageAssembler {
    public record Result(Path packageFile, String sha256, long size, int artifactCount) {}
    private record Payload(String archivePath, Path source, String expectedSha256) {}

    private ReleaseOfflinePackageAssembler() {}

    /**
     * Re-verifies the lock and Ed25519 manifest, validates the exact artifact set and bytes,
     * then atomically publishes an offline package. The ZIP contains the original lock and
     * signed manifest, a generated SBOM, and Maven-layout POM/JAR files.
     */
    public static Result assemble(String lockJson, String signedManifestJson, PublicKey trustedSigner,
            Map<ReleaseArtifactResolver.Coordinate, VerifiedArtifactFetcher.CachedArtifact> artifacts,
            Path output) throws IOException {
        Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(output, "output");
        var lock = ReleaseArtifactResolver.verify(lockJson);
        var manifest = ReleaseArtifactDigestManifest.verify(signedManifestJson, lock, trustedSigner);
        Map<String, VerifiedArtifactFetcher.CachedArtifact> supplied = new TreeMap<>();
        artifacts.forEach((coordinate, cached) -> {
            Objects.requireNonNull(coordinate, "artifact coordinate");
            Objects.requireNonNull(cached, "cached artifact");
            if (!coordinate.equals(cached.coordinate())) throw new IllegalArgumentException("Cached artifact coordinate does not match map key");
            if (supplied.putIfAbsent(key(coordinate), cached) != null) throw new IllegalArgumentException("Duplicate cached artifact: " + key(coordinate));
        });
        Map<String, ReleaseArtifactResolver.Coordinate> required = new TreeMap<>();
        lock.artifacts().forEach(a -> required.put(key(a.coordinate()), a.coordinate()));
        if (!supplied.keySet().equals(required.keySet())) {
            throw new IllegalArgumentException("Offline package artifact set must exactly match the verified lock; missing="
                    + difference(required.keySet(), supplied.keySet()) + ", extra=" + difference(supplied.keySet(), required.keySet()));
        }

        List<Payload> payloads = new ArrayList<>();
        for (var entry : required.entrySet()) {
            var coordinate = entry.getValue();
            var cached = supplied.get(entry.getKey());
            var digest = manifest.artifact(coordinate);
            String base = "repository/" + coordinate.groupId().replace('.', '/') + "/"
                    + coordinate.artifactId() + "/" + coordinate.version() + "/";
            String stem = coordinate.artifactId() + "-" + coordinate.version();
            verifyFile(cached.pom(), digest.pomSha256());
            verifyFile(cached.jar(), digest.jarSha256());
            payloads.add(new Payload(base + stem + ".pom", cached.pom(), digest.pomSha256()));
            payloads.add(new Payload(base + stem + ".jar", cached.jar(), digest.jarSha256()));
        }
        payloads.sort(Comparator.comparing(Payload::archivePath));

        Path target = output.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Offline package output must have a parent directory");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".wayang-release-", ".part");
        boolean published = false;
        try {
            try (OutputStream file = Files.newOutputStream(temporary); ZipOutputStream zip = new ZipOutputStream(file)) {
                addText(zip, "release/lock.json", lockJson);
                addText(zip, "release/artifact-digests.json", signedManifestJson);
                addText(zip, "release/bom.cdx.json", ReleaseArtifactSbom.create(lock, manifest));
                for (Payload payload : payloads) addFile(zip, payload);
            }
            String packageDigest = digest(temporary);
            long size = Files.size(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            published = true;
            return new Result(target, packageDigest, size, required.size());
        } finally {
            if (!published) Files.deleteIfExists(temporary);
        }
    }

    private static void addText(ZipOutputStream zip, String name, String value) throws IOException {
        ZipEntry entry = new ZipEntry(name); entry.setTime(0L); zip.putNextEntry(entry);
        zip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
    }

    private static void addFile(ZipOutputStream zip, Payload payload) throws IOException {
        ZipEntry entry = new ZipEntry(payload.archivePath()); entry.setTime(0L); zip.putNextEntry(entry);
        try (InputStream input = Files.newInputStream(payload.source())) { input.transferTo(zip); }
        zip.closeEntry();
    }

    private static void verifyFile(Path file, String expected) throws IOException {
        if (file == null || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Offline package input must be a regular non-symlink file: " + file);
        String actual = digest(file);
        if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new IOException("Offline package input digest mismatch: " + file);
    }

    private static String digest(Path file) throws IOException {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) != -1) sha.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    private static String key(ReleaseArtifactResolver.Coordinate c) {
        return c.groupId() + ":" + c.artifactId() + ":" + c.version() + ":" + c.scope();
    }
    private static List<String> difference(java.util.Set<String> left, java.util.Set<String> right) {
        return left.stream().filter(v -> !right.contains(v)).toList();
    }
}