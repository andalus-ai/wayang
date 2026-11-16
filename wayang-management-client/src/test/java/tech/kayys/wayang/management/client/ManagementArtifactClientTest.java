package tech.kayys.wayang.management.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagementArtifactClientTest {
    private static final byte[] PACKAGE = "verified-release-package".getBytes(StandardCharsets.UTF_8);
    private static final String MANIFEST = "sha256:" + "b".repeat(64);
    private HttpServer server;
    private ManagementArtifactClient client;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> responseDigest = new AtomicReference<>();

    @TempDir Path directory;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/runtime/artifacts/", this::download);
        server.start();
        client = new ManagementArtifactClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(2), Duration.ofSeconds(3));
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void downloadsToAtomicDestinationAndReturnsUntrustedSignatureMetadata() throws Exception {
        String digest = digest(PACKAGE);
        Path target = directory.resolve("release.zip");

        var result = client.download("registration-secret",
                URI.create("/api/v1/runtime/artifacts/" + digest), digest, MANIFEST,
                target, 1024);

        assertEquals(target, result.file());
        assertArrayEquals(PACKAGE, Files.readAllBytes(target));
        assertEquals(digest, result.artifactDigest());
        assertEquals(MANIFEST, result.manifestDigest());
        assertEquals("detached-signature", result.signature());
        assertEquals("publisher-key-01", result.signingKeyId());
        assertEquals(PACKAGE.length, result.sizeBytes());
        assertEquals("Bearer registration-secret", authorization.get());
    }

    @Test
    void rejectsBytesThatDoNotMatchTheCommandDigestAndCleansTemporaryFile() throws Exception {
        String wrongDigest = "sha256:" + "0".repeat(64);
        responseDigest.set(wrongDigest);
        Path target = directory.resolve("bad.zip");

        assertThrows(IOException.class, () -> client.download("token",
                URI.create("/api/v1/runtime/artifacts/" + wrongDigest), wrongDigest, MANIFEST,
                target, 1024));

        assertFalse(Files.exists(target));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void rejectsArtifactUriOnAnotherOriginBeforeSendingCredentials() {
        assertThrows(IllegalArgumentException.class, () -> client.download("secret",
                URI.create("https://attacker.example/api/v1/runtime/artifacts/" + digest(PACKAGE)),
                digest(PACKAGE), MANIFEST, directory.resolve("escape.zip"), 1024));
        assertNull(authorization.get());
    }

    @Test
    void enforcesMaximumSizeAndDoesNotPublishPartialArtifact() throws Exception {
        Path target = directory.resolve("oversized.zip");
        assertThrows(IOException.class, () -> client.download("token",
                URI.create("/api/v1/runtime/artifacts/" + digest(PACKAGE)), digest(PACKAGE), MANIFEST,
                target, 4));
        assertFalse(Files.exists(target));
    }

    @Test
    void rejectsManifestMetadataMismatch() throws Exception {
        Path target = directory.resolve("manifest.zip");
        assertThrows(IOException.class, () -> client.download("token",
                URI.create("/api/v1/runtime/artifacts/" + digest(PACKAGE)), digest(PACKAGE),
                "sha256:" + "c".repeat(64), target, 1024));
        assertFalse(Files.exists(target));
    }

    private void download(HttpExchange exchange) throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("X-Artifact-Digest", digest(PACKAGE));
        exchange.getResponseHeaders().set("X-Manifest-Digest", MANIFEST);
        exchange.getResponseHeaders().set("X-Artifact-Signature", "detached-signature");
        exchange.getResponseHeaders().set("X-Signing-Key-Id", "publisher-key-01");
        exchange.sendResponseHeaders(200, PACKAGE.length);
        try (var output = exchange.getResponseBody()) {
            output.write(PACKAGE);
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
