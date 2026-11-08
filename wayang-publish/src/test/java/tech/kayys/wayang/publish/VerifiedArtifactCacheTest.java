package tech.kayys.wayang.publish;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedArtifactCacheTest {

    @TempDir
    java.nio.file.Path temp;

    private static final URI ARTIFACT = URI.create(
            "https://repo.example/maven/tech/kayys/wayang/wayang-runtime/1.0/wayang-runtime-1.0.jar");

    @Test
    void downloadsVerifiesAndCachesTrustedBytes() throws Exception {
        byte[] bytes = "verified artifact".getBytes(StandardCharsets.UTF_8);
        String digest = sha256(bytes);
        AtomicInteger requests = new AtomicInteger();
        var cache = cache((uri, timeout) -> {
            requests.incrementAndGet();
            assertEquals(ARTIFACT, uri);
            return response(200, bytes);
        });

        var first = cache.fetch(ARTIFACT, digest);
        var second = cache.fetch(ARTIFACT, digest);

        assertEquals(first, second);
        assertArrayEquals(bytes, Files.readAllBytes(first));
        assertEquals(1, requests.get());
        assertTrue(first.toString().contains(digest));
    }

    @Test
    void rejectsDigestMismatchAndDoesNotPublishPartialCacheEntry() {
        byte[] bytes = "tampered".getBytes(StandardCharsets.UTF_8);
        var cache = cache((uri, timeout) -> response(200, bytes));
        assertThrows(IOException.class, () -> cache.fetch(ARTIFACT, "0".repeat(64)));
        try (var files = Files.list(temp.resolve("cache/sha256/00"))) {\n            assertEquals(0, files.count());\n        }
    }

    @Test
    void rejectsRedirectAndOtherNonSuccessStatuses() {
        var cache = cache((uri, timeout) -> response(302, new byte[0]));
        assertThrows(IOException.class, () -> cache.fetch(ARTIFACT, "0".repeat(64)));
    }

    @Test
    void rejectsOversizedResponse() {
        var cache = new VerifiedArtifactCache(policy(), temp.resolve("cache"), 4, Duration.ofSeconds(2),
                (uri, timeout) -> response(200, "12345".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> cache.fetch(ARTIFACT, sha256("12345".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void rejectsUrlsOutsideRepositoryPolicyBeforeTransport() {
        AtomicInteger requests = new AtomicInteger();
        var cache = cache((uri, timeout) -> {
            requests.incrementAndGet();
            return response(200, new byte[0]);
        });
        assertThrows(IllegalArgumentException.class,
                () -> cache.fetch(URI.create("https://evil.example/artifact.jar"), "0".repeat(64)));
        assertEquals(0, requests.get());
    }

    @Test
    void rejectsInvalidExpectedDigest() {
        var cache = cache((uri, timeout) -> response(200, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> cache.fetch(ARTIFACT, "sha256:abc"));
    }

    private VerifiedArtifactCache cache(VerifiedArtifactCache.Transport transport) {
        return new VerifiedArtifactCache(policy(), temp.resolve("cache"), 1024, Duration.ofSeconds(2), transport);
    }

    private static RepositoryPolicy policy() {
        return new RepositoryPolicy(List.of(
                new RepositoryPolicy.Repository("releases", URI.create("https://repo.example/maven"))));
    }

    private static VerifiedArtifactCache.Response response(int status, byte[] body) {
        return new VerifiedArtifactCache.Response(status, new ByteArrayInputStream(body));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
