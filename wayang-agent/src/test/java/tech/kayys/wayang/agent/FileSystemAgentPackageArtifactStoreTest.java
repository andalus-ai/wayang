package tech.kayys.wayang.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class FileSystemAgentPackageArtifactStoreTest {

    @Test
    void storesAndRetrievesByContentDigest() throws Exception {
        FileSystemAgentPackageArtifactStore store = new FileSystemAgentPackageArtifactStore(
                Files.createTempDirectory("agent-artifacts"));
        byte[] bytes = "immutable package archive".getBytes(StandardCharsets.UTF_8);
        String digest = digest(bytes);

        var reference = store.putVerified("acme:triage", "1.2.3", digest, bytes);

        assertEquals("acme:triage", reference.packageId());
        assertEquals("1.2.3", reference.version());
        assertEquals(digest, reference.sha256());
        assertEquals(Optional.of(reference), store.find("acme:triage", "1.2.3"));
        assertEquals(digest, store.get(digest).orElseThrow().sha256());
        assertArrayEquals(bytes, store.get(digest).orElseThrow().archive());
    }

    @Test
    void duplicateUploadIsIdempotentAndCoordinateCannotBeRebound() throws Exception {
        FileSystemAgentPackageArtifactStore store = new FileSystemAgentPackageArtifactStore(
                Files.createTempDirectory("agent-artifacts"));
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        String firstDigest = digest(first);
        var original = store.putVerified("acme:triage", "1.0.0", firstDigest, first);

        assertEquals(original, store.putVerified("acme:triage", "1.0.0", firstDigest, first));
        byte[] replacement = "replacement".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class,
                () -> store.putVerified("acme:triage", "1.0.0", digest(replacement), replacement));
    }

    @Test
    void rejectsMismatchedBytesAndUnsafeDigests() throws Exception {
        FileSystemAgentPackageArtifactStore store = new FileSystemAgentPackageArtifactStore(
                Files.createTempDirectory("agent-artifacts"));
        byte[] bytes = "package".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> store.putVerified("acme:triage", "1.0.0", "0".repeat(64), bytes));
        assertTrue(store.get("../../etc/passwd").isEmpty());
        assertTrue(store.find("../../etc/passwd", "1.0.0").isEmpty());
    }

    @Test
    void verifiesContentAgainWhenReadingAndDetectsCorruption() throws Exception {
        Path root = Files.createTempDirectory("agent-artifacts");
        FileSystemAgentPackageArtifactStore store = new FileSystemAgentPackageArtifactStore(root);
        byte[] bytes = "package".getBytes(StandardCharsets.UTF_8);
        String digest = digest(bytes);
        store.putVerified("acme:triage", "1.0.0", digest, bytes);
        Path object = root.resolve("objects").resolve(digest.substring(0, 2)).resolve(digest + ".zip");
        Files.writeString(object, "corrupt", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> store.get(digest));
    }

    @Test
    void returnedArchiveBytesAreDefensiveCopies() throws Exception {
        FileSystemAgentPackageArtifactStore store = new FileSystemAgentPackageArtifactStore(
                Files.createTempDirectory("agent-artifacts"));
        byte[] bytes = "package".getBytes(StandardCharsets.UTF_8);
        String digest = digest(bytes);
        store.putVerified("acme:triage", "1.0.0", digest, bytes);
        var stored = store.get(digest).orElseThrow();
        byte[] exposed = stored.archive();
        exposed[0] = 0;
        assertArrayEquals(bytes, stored.archive());
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
