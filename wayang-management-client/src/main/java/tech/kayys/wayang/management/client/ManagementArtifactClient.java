package tech.kayys.wayang.management.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Downloads Console-hosted deployment artifacts without allowing the registration bearer
 * credential to leave the configured Console origin.
 *
 * <p>This is a transport and byte-integrity boundary only. The returned signature and key id
 * must still be checked against a locally trusted publisher key before the artifact is installed.</p>
 */
public final class ManagementArtifactClient {
    private final URI baseUri;
    private final Duration requestTimeout;
    private final HttpClient http;

    public record DownloadedArtifact(Path file, String artifactDigest, String manifestDigest,
            String signature, String signingKeyId, long sizeBytes) {}

    public ManagementArtifactClient(URI baseUri, Duration connectTimeout, Duration requestTimeout) {
        this.baseUri = validateBaseUri(baseUri);
        this.requestTimeout = positive(requestTimeout, "requestTimeout");
        this.http = HttpClient.newBuilder().connectTimeout(positive(connectTimeout, "connectTimeout")).build();
    }

    public DownloadedArtifact download(String registrationToken, URI artifactUri,
            String expectedArtifactDigest, String expectedManifestDigest, Path destination,
            long maxBytes) throws IOException {
        if (registrationToken == null || registrationToken.isBlank()) {
            throw new IllegalArgumentException("registrationToken must not be blank");
        }
        requireDigest(expectedArtifactDigest, "expectedArtifactDigest");
        requireDigest(expectedManifestDigest, "expectedManifestDigest");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        Objects.requireNonNull(destination, "destination");

        URI endpoint = resolveSameOrigin(artifactUri);
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new IOException("Artifact destination parent must be an existing non-symlink directory");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Artifact destination already exists");
        }

        Path temporary = Files.createTempFile(parent, ".wayang-artifact-", ".part");
        boolean published = false;
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Accept", "application/octet-stream")
                    .header("Authorization", "Bearer " + registrationToken)
                    .GET().build();
            final HttpResponse<InputStream> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while downloading deployment artifact", interrupted);
            }

            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("Console artifact download failed with HTTP " + response.statusCode());
                }
                long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (declaredLength > maxBytes) throw new IOException("Artifact exceeds configured size limit");

                String responseDigest = requiredHeader(response, "X-Artifact-Digest");
                String responseManifest = requiredHeader(response, "X-Manifest-Digest");
                String signature = requiredHeader(response, "X-Artifact-Signature");
                String signingKeyId = requiredHeader(response, "X-Signing-Key-Id");
                requireDigest(responseDigest, "X-Artifact-Digest");
                requireDigest(responseManifest, "X-Manifest-Digest");
                if (!responseDigest.equalsIgnoreCase(expectedArtifactDigest)
                        || !responseManifest.equalsIgnoreCase(expectedManifestDigest)) {
                    throw new IOException("Console artifact metadata does not match the deployment command");
                }

                MessageDigest digest = sha256();
                long size = 0;
                byte[] buffer = new byte[16 * 1024];
                try (var output = Files.newOutputStream(temporary, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    int read;
                    while ((read = body.read(buffer)) != -1) {
                        size = Math.addExact(size, read);
                        if (size > maxBytes) throw new IOException("Artifact exceeds configured size limit");
                        digest.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                } catch (ArithmeticException overflow) {
                    throw new IOException("Artifact size overflow", overflow);
                }

                String actual = "sha256:" + HexFormat.of().formatHex(digest.digest());
                if (!actual.equalsIgnoreCase(expectedArtifactDigest)) {
                    throw new IOException("Downloaded artifact SHA-256 does not match the deployment command");
                }
                if (declaredLength >= 0 && declaredLength != size) {
                    throw new IOException("Downloaded artifact length does not match Content-Length");
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    throw new IOException("Artifact filesystem does not support atomic publication", unsupported);
                }
                published = true;
                return new DownloadedArtifact(target, actual, responseManifest.toLowerCase(java.util.Locale.ROOT),
                        signature, signingKeyId, size);
            }
        } finally {
            if (!published) Files.deleteIfExists(temporary);
        }
    }

    private URI resolveSameOrigin(URI supplied) {
        Objects.requireNonNull(supplied, "artifactUri");
        URI resolved = baseUri.resolve(supplied);
        if (!resolved.isAbsolute() || resolved.getHost() == null || resolved.getUserInfo() != null
                || resolved.getQuery() != null || resolved.getFragment() != null
                || !sameOrigin(baseUri, resolved)
                || !resolved.getPath().startsWith("/api/v1/runtime/artifacts/")) {
            throw new IllegalArgumentException("artifactUri must be a Console runtime-artifact URI on the configured origin");
        }
        return resolved;
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String requiredHeader(HttpResponse<?> response, String name) throws IOException {
        return response.headers().firstValue(name)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IOException("Console artifact response is missing " + name));
    }

    private static void requireDigest(String digest, String field) {
        if (digest == null || !digest.matches("(?i)sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be sha256:<64 hex>");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static URI validateBaseUri(URI uri) {
        Objects.requireNonNull(uri, "baseUri");
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("baseUri must be an absolute HTTP(S) URI without user-info, query or fragment");
        }
        String value = uri.toString();
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
