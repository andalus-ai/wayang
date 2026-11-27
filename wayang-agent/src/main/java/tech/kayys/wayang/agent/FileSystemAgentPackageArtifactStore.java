package tech.kayys.wayang.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Local content-addressed repository. The configured root must be writable only by the Wayang service account.
 * Coordinate index paths are hashes, never caller-provided path segments.
 */
public final class FileSystemAgentPackageArtifactStore implements AgentPackageArtifactStore {
    public static final long MAX_ARCHIVE_BYTES = 64L * 1024 * 1024;

    private final Path objects;
    private final Path coordinates;
    private final Path locks;

    public FileSystemAgentPackageArtifactStore(Path root) {
        if (root == null) throw new IllegalArgumentException("Artifact repository root is required");
        Path normalized = root.toAbsolutePath().normalize();
        this.objects = normalized.resolve("objects");
        this.coordinates = normalized.resolve("coordinates");
        this.locks = normalized.resolve("locks");
    }

    /** Uses WAYANG_AGENT_ARTIFACTS_DIR, or WAYANG_STATE_DIR/agent-artifacts; null when unconfigured. */
    public static FileSystemAgentPackageArtifactStore fromEnvironment() {
        String configured = System.getenv("WAYANG_AGENT_ARTIFACTS_DIR");
        String state = System.getenv("WAYANG_STATE_DIR");
        Path path = configured != null && !configured.isBlank() ? Path.of(configured)
                : state != null && !state.isBlank() ? Path.of(state).resolve("agent-artifacts") : null;
        return path == null ? null : new FileSystemAgentPackageArtifactStore(path);
    }

    @Override
    public ArtifactReference put(AgentPackageArtifactVerifier.VerifiedArtifact verified, byte[] archive) {
        if (verified == null) throw new IllegalArgumentException("Verified artifact is required");
        if (archive == null || archive.length == 0 || archive.length > MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("Agent Package archive is empty or exceeds 64 MiB");
        }
        byte[] snapshot = archive.clone();
        String actual = sha256(snapshot);
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                verified.archiveSha256().getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Archive bytes do not match the verified artifact digest");
        }
        AgentPackageReader.Metadata metadata = verified.agentPackage().metadata();
        return putVerified(metadata.id(), metadata.version(), actual, snapshot);
    }

    // Package-private seam lets storage contract tests avoid constructing cryptographic fixtures.
    ArtifactReference putVerified(String packageId, String version, String digest, byte[] archive) {
        ArtifactReference reference = new ArtifactReference(packageId, version, digest);
        if (archive == null || archive.length == 0 || archive.length > MAX_ARCHIVE_BYTES || !sha256(archive).equals(digest)) {
            throw new IllegalArgumentException("Artifact bytes do not match the declared SHA-256 digest or size limit");
        }
        Path object = objectPath(digest);
        Path coordinate = coordinatePath(packageId, version);
        Path lock = locks.resolve(coordinateKey(packageId, version) + ".lock");
        try {
            Files.createDirectories(object.getParent());
            Files.createDirectories(coordinates);
            Files.createDirectories(locks);
            Optional<ArtifactReference> existing = find(packageId, version);
            if (existing.isPresent()) {
                if (!existing.get().sha256().equals(digest)) throw conflict();
                verifyObject(object, digest);
                return reference;
            }
            try (FileChannel ignored = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                existing = find(packageId, version);
                if (existing.isPresent()) {
                    if (!existing.get().sha256().equals(digest)) throw conflict();
                    verifyObject(object, digest);
                    return reference;
                }
                if (!Files.exists(object)) writeAtomically(object, archive);
                else verifyObject(object, digest);
                writeAtomically(coordinate, (digest + "\n").getBytes(StandardCharsets.US_ASCII));
                return reference;
            } catch (FileAlreadyExistsException busy) {
                throw new IllegalStateException("Another writer is publishing this coordinate; retry the operation", busy);
            } finally {
                Files.deleteIfExists(lock);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Agent Package artifact could not be stored", e);
        }
    }

    @Override
    public Optional<StoredArtifact> get(String digest) {
        if (!validDigest(digest)) return Optional.empty();
        Path object = objectPath(digest);
        try {
            if (!Files.isRegularFile(object)) return Optional.empty();
            long size = Files.size(object);
            if (size <= 0 || size > MAX_ARCHIVE_BYTES) throw new IllegalStateException("Stored artifact has an invalid size");
            byte[] bytes = Files.readAllBytes(object);
            if (!sha256(bytes).equals(digest)) throw new IllegalStateException("Stored artifact failed its SHA-256 integrity check");
            return Optional.of(new StoredArtifact(new ArtifactReference("local:content", "0.0.0", digest), bytes));
        } catch (IOException e) {
            throw new IllegalStateException("Agent Package artifact could not be read", e);
        }
    }

    @Override
    public Optional<ArtifactReference> find(String packageId, String version) {
        if (packageId == null || packageId.isBlank() || version == null || version.isBlank()) return Optional.empty();
        Path coordinate = coordinatePath(packageId, version);
        try {
            if (!Files.isRegularFile(coordinate)) return Optional.empty();
            String digest = Files.readString(coordinate, StandardCharsets.US_ASCII).strip();
            if (!validDigest(digest)) throw new IllegalStateException("Agent Package coordinate index is corrupt");
            return Optional.of(new ArtifactReference(packageId, version, digest));
        } catch (IOException e) {
            throw new IllegalStateException("Agent Package coordinate could not be read", e);
        }
    }

    private Path objectPath(String digest) {
        if (!validDigest(digest)) throw new IllegalArgumentException("Invalid SHA-256 digest");
        return objects.resolve(digest.substring(0, 2)).resolve(digest + ".zip");
    }
    private Path coordinatePath(String id, String version) {
        return coordinates.resolve(coordinateKey(id, version) + ".ref");
    }
    private static String coordinateKey(String id, String version) {
        return sha256((id + "\n" + version).getBytes(StandardCharsets.UTF_8));
    }
    private static boolean validDigest(String digest) {
        return digest != null && digest.matches("[0-9a-f]{64}");
    }
    private static IllegalStateException conflict() {
        return new IllegalStateException("Agent Package coordinates are immutable and already reference different bytes");
    }
    private static void verifyObject(Path object, String digest) throws IOException {
        if (!Files.isRegularFile(object) || !sha256(Files.readAllBytes(object)).equals(digest)) {
            throw new IllegalStateException("Existing content-addressed artifact is missing or corrupt");
        }
    }
    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".agent-package-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Artifact repository filesystem does not support atomic moves", unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
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
