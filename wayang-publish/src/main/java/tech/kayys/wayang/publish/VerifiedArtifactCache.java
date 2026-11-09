package tech.kayys.wayang.publish;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Downloads a single artifact into a content-addressed cache only after checking
 * its bytes against a digest supplied by a trusted release manifest.
 *
 * <p>Redirects are never followed. Repository-hosted checksum sidecars are not
 * accepted as trust anchors. Callers must obtain expectedSha256 from a
 * separately authenticated lock/manifest.</p>
 */
public final class VerifiedArtifactCache {

    @FunctionalInterface
    public interface Transport {
        Response get(URI uri, Duration requestTimeout) throws IOException, InterruptedException;
    }

    public record Response(int statusCode, InputStream body) implements AutoCloseable {
        public Response {
            Objects.requireNonNull(body, "body");
        }
        @Override public void close() throws IOException { body.close(); }
    }

    private final RepositoryPolicy policy;
    private final Path cacheRoot;
    private final long maxArtifactBytes;
    private final Duration requestTimeout;
    private final Transport transport;

    public VerifiedArtifactCache(RepositoryPolicy policy, Path cacheRoot, long maxArtifactBytes,
            Duration requestTimeout) {
        this(policy, cacheRoot, maxArtifactBytes, requestTimeout, jdkTransport());
    }

    VerifiedArtifactCache(RepositoryPolicy policy, Path cacheRoot, long maxArtifactBytes,
            Duration requestTimeout, Transport transport) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot").toAbsolutePath().normalize();
        if (maxArtifactBytes < 1) throw new IllegalArgumentException("maxArtifactBytes must be positive");
        this.maxArtifactBytes = maxArtifactBytes;
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * Fetches and verifies bytes. Existing cache entries are re-hashed before
     * reuse; a failed or interrupted download never becomes a visible cache entry.
     */
    public Path fetch(URI artifactUri, String expectedSha256) throws IOException, InterruptedException {
        policy.requireAllowed(artifactUri);
        String digest = requireDigest(expectedSha256);
        Path target = cachePath(digest);
        Path shard = target.getParent();
        Files.createDirectories(shard);
        rejectSymlink(cacheRoot);
        rejectSymlink(cacheRoot.resolve("sha256"));
        rejectSymlink(shard);

        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Artifact cache entry is not a regular file: " + target);
            }
            if (digest(target).equals(digest)) return target;
            Files.delete(target);
        }

        Path temporary = Files.createTempFile(shard, ".wayang-artifact-", ".part");
        boolean published = false;
        try {
            long size;
            MessageDigest sha = sha256();
            try (Response response = transport.get(artifactUri, requestTimeout)) {
                if (response.statusCode() != 200) {
                    throw new IOException("Artifact repository returned HTTP " + response.statusCode());
                }
                try (var output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[8192];
                    size = 0;
                    int read;
                    while ((read = response.body().read(buffer)) != -1) {
                        size += read;
                        if (size > maxArtifactBytes) {
                            throw new IOException("Artifact exceeds configured size limit of " + maxArtifactBytes + " bytes");
                        }
                        sha.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                }
            }
            String actual = HexFormat.of().formatHex(sha.digest());
            if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    digest.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new IOException("Downloaded artifact SHA-256 does not match trusted expected digest");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target);
            }
            published = true;
            return target;
        } finally {
            if (!published) Files.deleteIfExists(temporary);
        }
    }

    private Path cachePath(String digest) {
        return cacheRoot.resolve("sha256").resolve(digest.substring(0, 2)).resolve(digest);
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Artifact cache path must not be a symbolic link: " + path);
    }

    private static String digest(Path file) throws IOException {
        MessageDigest sha = sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) sha.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static String requireDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A trusted lowercase SHA-256 digest is required");
        }
        return digest;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static Transport jdkTransport() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return (uri, timeout) -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept-Encoding", "identity")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new Response(response.statusCode(), response.body());
        };
    }
}
