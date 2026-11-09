package tech.kayys.wayang.publish;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import tech.kayys.wayang.runtime.json.Json;

/**
 * Verifies a detached Ed25519-signed manifest that binds artifact byte digests
 * to one already-verified Wayang coordinate lock.
 *
 * <p>The manifest signature is the trust boundary. Repository checksum sidecars
 * must never be used as a substitute for this verification.</p>
 */
public final class ReleaseArtifactDigestManifest {

    public record ArtifactDigest(ReleaseArtifactResolver.Coordinate coordinate, String pomSha256, String jarSha256) {
        public ArtifactDigest {
            Objects.requireNonNull(coordinate, "coordinate");
            requireDigest(pomSha256, "POM");
            requireDigest(jarSha256, "JAR");
        }
    }

    public record VerifiedManifest(String lockSha256, Map<String, ArtifactDigest> artifacts) {
        public VerifiedManifest {
            artifacts = Map.copyOf(artifacts);
        }

        public ArtifactDigest artifact(ReleaseArtifactResolver.Coordinate coordinate) {
            ArtifactDigest digest = artifacts.get(key(coordinate));
            if (digest == null) throw new IllegalArgumentException("Artifact is not covered by verified manifest: " + key(coordinate));
            return digest;
        }

        public String pomSha256(ReleaseArtifactResolver.Coordinate coordinate) {
            return artifact(coordinate).pomSha256();
        }

        public String jarSha256(ReleaseArtifactResolver.Coordinate coordinate) {
            return artifact(coordinate).jarSha256();
        }
    }

    private ReleaseArtifactDigestManifest() {}

    /**
     * Manifest JSON fields: format, formatVersion, lockSha256, artifacts,
     * signature. The Ed25519 signature covers canonical JSON with signature omitted.
     */
    public static VerifiedManifest verify(String json, ReleaseArtifactResolver.VerifiedLock lock,
            PublicKey trustedSigner) {
        Objects.requireNonNull(lock, "lock");
        Objects.requireNonNull(trustedSigner, "trustedSigner");
        if (json == null || json.isBlank()) throw new IllegalArgumentException("Artifact digest manifest is empty");
        Object parsed = Json.read(json);
        if (!(parsed instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Artifact digest manifest must be a JSON object");
        Map<String, Object> document = stringMap(raw);
        if (!"wayang.artifact-digests".equals(document.get("format"))) {
            throw new IllegalArgumentException("Unsupported artifact digest manifest format");
        }
        if (!(document.get("formatVersion") instanceof Number version)
                || version.intValue() != 1 || version.doubleValue() != 1.0d) {
            throw new IllegalArgumentException("Unsupported artifact digest manifest formatVersion");
        }
        String lockDigest = requiredString(document.get("lockSha256"), "lockSha256");
        if (!lockDigest.equals(lock.sha256())) {
            throw new IllegalArgumentException("Artifact digest manifest does not match verified lock");
        }
        String encodedSignature = requiredString(document.get("signature"), "signature");
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(encodedSignature);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Artifact digest manifest signature is not valid Base64", e);
        }
        Map<String, Object> unsigned = new LinkedHashMap<>(document);
        unsigned.remove("signature");
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(trustedSigner);
            verifier.update(Json.write(canonical(unsigned)).getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(signatureBytes)) {
                throw new IllegalArgumentException("Artifact digest manifest signature verification failed");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Unable to verify artifact digest manifest signature", e);
        }

        Object rawArtifacts = document.get("artifacts");
        if (!(rawArtifacts instanceof List<?> entries) || entries.isEmpty()) {
            throw new IllegalArgumentException("Artifact digest manifest must contain artifacts");
        }
        Map<String, ReleaseArtifactResolver.Coordinate> locked = new HashMap<>();
        for (ReleaseArtifactResolver.Artifact artifact : lock.artifacts()) {
            var c = artifact.coordinate();
            locked.put(key(c), c);
        }
        Map<String, ArtifactDigest> digests = new HashMap<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> rawEntry)) throw new IllegalArgumentException("Malformed artifact digest entry");
            Map<String, Object> item = stringMap(rawEntry);
            var coordinate = new ReleaseArtifactResolver.Coordinate(
                    requiredString(item.get("groupId"), "artifact.groupId"),
                    requiredString(item.get("artifactId"), "artifact.artifactId"),
                    requiredString(item.get("version"), "artifact.version"),
                    requiredString(item.get("scope"), "artifact.scope"));
            if (!"compile".equals(coordinate.scope())) throw new IllegalArgumentException("Only compile artifacts may be manifested");
            String key = key(coordinate);
            if (!locked.containsKey(key)) throw new IllegalArgumentException("Manifest contains an artifact outside the verified lock: " + key);
            String pomDigest = requiredString(item.get("pomSha256"), "artifact.pomSha256");
            String jarDigest = requiredString(item.get("jarSha256"), "artifact.jarSha256");
            ArtifactDigest digest = new ArtifactDigest(coordinate, pomDigest, jarDigest);
            if (digests.putIfAbsent(key, digest) != null) throw new IllegalArgumentException("Duplicate manifest artifact: " + key);
        }
        if (!digests.keySet().equals(locked.keySet())) {
            var missing = new HashSet<>(locked.keySet());
            missing.removeAll(digests.keySet());
            throw new IllegalArgumentException("Manifest is missing locked artifacts: " + missing);
        }
        return new VerifiedManifest(lockDigest, digests);
    }

    private static void requireDigest(String digest, String kind) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(kind + " SHA-256 must be 64 lowercase hexadecimal characters");
        }
    }

    private static String key(ReleaseArtifactResolver.Coordinate c) {
        return c.groupId() + ":" + c.artifactId() + ":" + c.version();
    }

    private static String requiredString(Object value, String field) {
        if (!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException("Missing or invalid " + field);
        return s;
    }

    private static Map<String, Object> stringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (!(key instanceof String s)) throw new IllegalArgumentException("Manifest object keys must be strings");
            result.put(s, value);
        });
        return result;
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonical(nested)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ReleaseArtifactDigestManifest::canonical).toList();
        return value;
    }
}
