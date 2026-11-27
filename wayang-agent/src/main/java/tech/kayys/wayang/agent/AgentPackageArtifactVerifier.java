package tech.kayys.wayang.agent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Verifies an Agent Package archive before it is passed to installation.
 *
 * <p>The archive is inspected in memory and is never extracted or executed. The detached Ed25519
 * signature is checked against operator-configured publisher trust, and every declared resource
 * is re-hashed against the manifest.</p>
 */
public final class AgentPackageArtifactVerifier {
    public static final String YAML_MANIFEST = "agent-package.yaml";
    public static final String JSON_MANIFEST = "agent-package.json";

    private static final int MAX_ARCHIVE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_RESOURCE_BYTES = 16 * 1024 * 1024;
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;

    private final AgentPackageReader reader;
    private final AgentPackageTrustStore trustStore;
    private final Clock clock;

    public AgentPackageArtifactVerifier(AgentPackageTrustStore trustStore) {
        this(new AgentPackageReader(), trustStore, Clock.systemUTC());
    }

    public AgentPackageArtifactVerifier(AgentPackageReader reader, AgentPackageTrustStore trustStore, Clock clock) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public VerifiedArtifact verify(byte[] archive, AgentPackageSignature signature) {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(signature, "signature");
        if (archive.length == 0 || archive.length > MAX_ARCHIVE_BYTES) {
            throw invalid("Agent Package archive is empty or exceeds the 64 MiB limit");
        }

        String archiveSha256 = sha256(archive);
        if (!MessageDigest.isEqual(archiveSha256.getBytes(StandardCharsets.US_ASCII),
                signature.artifactSha256().getBytes(StandardCharsets.US_ASCII))) {
            throw invalid("Agent Package archive digest does not match its signature envelope");
        }

        AgentPackageTrustStore.TrustedPublisherKey trusted = trustStore
                .find(signature.publisher(), signature.keyId())
                .orElseThrow(() -> invalid("Publisher signing key is not trusted"));
        Instant now = clock.instant();
        if (!trusted.validAt(now)) throw invalid("Publisher signing key is revoked or outside its validity interval");
        if (!trusted.permits(signature.packageId())) throw invalid("Publisher key is not authorized for this package namespace");

        verifySignature(signature, trusted);

        Map<String, byte[]> entries = readArchive(archive);
        byte[] yaml = entries.get(YAML_MANIFEST);
        byte[] json = entries.get(JSON_MANIFEST);
        if ((yaml == null) == (json == null)) {
            throw invalid("Archive must contain exactly one agent-package.yaml or agent-package.json manifest");
        }
        byte[] manifestBytes = yaml == null ? json : yaml;
        if (manifestBytes.length > MAX_MANIFEST_BYTES) throw invalid("Agent Package manifest exceeds 1 MiB");
        AgentPackageReader.AgentPackage agentPackage = reader.read(utf8(manifestBytes));

        if (!agentPackage.metadata().id().equals(signature.packageId())
                || !agentPackage.metadata().version().equals(signature.version())
                || !agentPackage.metadata().publisher().equals(signature.publisher())) {
            throw invalid("Signature envelope identity does not match the Agent Package manifest");
        }

        Map<String, String> resourceDigests = verifyResources(agentPackage, entries);
        String manifestSha256 = sha256(manifestBytes);
        return new VerifiedArtifact(agentPackage, archiveSha256, manifestSha256,
                signature.publisher(), signature.keyId(), resourceDigests);
    }

    private static void verifySignature(AgentPackageSignature envelope,
            AgentPackageTrustStore.TrustedPublisherKey trusted) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(trusted.publicKey());
            verifier.update(envelope.signedPayload());
            byte[] signatureBytes = java.util.Base64.getDecoder().decode(envelope.signatureBase64());
            if (!verifier.verify(signatureBytes)) throw invalid("Agent Package signature verification failed");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            if (e instanceof VerificationException ve) throw ve;
            throw invalid("Agent Package signature verification failed");
        }
    }

    private static Map<String, byte[]> readArchive(byte[] archive) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, String> foldedNames = new HashMap<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (++count > MAX_ENTRIES) throw invalid("Agent Package contains too many ZIP entries");
                String name = entry.getName();
                validatePath(name);
                if (entry.isDirectory()) throw invalid("Directory entries are not permitted in Agent Packages");
                String folded = name.toLowerCase(Locale.ROOT);
                String previous = foldedNames.putIfAbsent(folded, name);
                if (previous != null) throw invalid("Duplicate or case-colliding ZIP entry: " + name);
                int limit = name.equals(YAML_MANIFEST) || name.equals(JSON_MANIFEST)
                        ? MAX_MANIFEST_BYTES : MAX_RESOURCE_BYTES;
                byte[] content = readBounded(zip, limit);
                total += content.length;
                if (total > MAX_TOTAL_UNCOMPRESSED_BYTES) throw invalid("Agent Package expands beyond the 64 MiB limit");
                zip.closeEntry();
                long compressed = entry.getCompressedSize();
                if (compressed > 0 && content.length > 1024 && content.length / (double) compressed > 200.0) {
                    throw invalid("Agent Package entry compression ratio exceeds the safety limit");
                }
                if (files.putIfAbsent(name, content) != null) throw invalid("Duplicate ZIP entry: " + name);
            }
        } catch (IOException e) {
            throw new VerificationException("Agent Package is not a valid bounded ZIP archive", e);
        }
        if (files.isEmpty()) throw invalid("Agent Package ZIP archive is empty");
        return files;
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (out.size() + read > maxBytes) throw invalid("Agent Package ZIP entry exceeds its size limit");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void validatePath(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
            throw invalid("Unsafe Agent Package ZIP path");
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw invalid("Unsafe Agent Package ZIP path");
            }
        }
    }

    private static Map<String, String> verifyResources(AgentPackageReader.AgentPackage pkg,
            Map<String, byte[]> entries) {
        Map<String, String> digests = new LinkedHashMap<>();
        java.util.Set<String> expectedPaths = new java.util.HashSet<>();
        expectedPaths.add(entries.containsKey(YAML_MANIFEST) ? YAML_MANIFEST : JSON_MANIFEST);
        for (AgentPackageReader.Resource resource : pkg.resources()) {
            String path = resource.path();
            if (!expectedPaths.add(path)) throw invalid("Manifest resource path collides with the package manifest");
            byte[] content = entries.get(path);
            if (content == null) throw invalid("Declared Agent Package resource is missing: " + path);
            if (resource.sha256() == null || resource.sha256().isBlank()) {
                throw invalid("Every packaged resource must declare a SHA-256 digest: " + path);
            }
            String actual = sha256(content);
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                    resource.sha256().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
                throw invalid("Agent Package resource digest mismatch: " + path);
            }
            digests.put(path, actual);
        }
        String icon = pkg.metadata().icon();
        if (icon != null && !icon.startsWith("https://") && !expectedPaths.contains(icon)) {
            throw invalid("Relative package icon must be declared as a verified resource");
        }
        if (!entries.keySet().equals(expectedPaths)) {
            java.util.Set<String> unexpected = new java.util.HashSet<>(entries.keySet());
            unexpected.removeAll(expectedPaths);
            throw invalid("Archive contains undeclared package resources: " + unexpected);
        }
        return Map.copyOf(digests);
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new VerificationException("Agent Package manifest is not valid UTF-8", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static VerificationException invalid(String message) {
        return new VerificationException(message);
    }

    /** Result can only be created by this verifier; callers cannot manufacture a trusted artifact instance. */
    public static final class VerifiedArtifact {
        private final AgentPackageReader.AgentPackage agentPackage;
        private final String archiveSha256;
        private final String manifestSha256;
        private final String publisher;
        private final String keyId;
        private final Map<String, String> resourceSha256;

        private VerifiedArtifact(AgentPackageReader.AgentPackage agentPackage, String archiveSha256,
                String manifestSha256, String publisher, String keyId, Map<String, String> resourceSha256) {
            this.agentPackage = agentPackage;
            this.archiveSha256 = archiveSha256;
            this.manifestSha256 = manifestSha256;
            this.publisher = publisher;
            this.keyId = keyId;
            this.resourceSha256 = Map.copyOf(resourceSha256);
        }

        public AgentPackageReader.AgentPackage agentPackage() { return agentPackage; }
        public String archiveSha256() { return archiveSha256; }
        public String manifestSha256() { return manifestSha256; }
        public String publisher() { return publisher; }
        public String keyId() { return keyId; }
        public Map<String, String> resourceSha256() { return resourceSha256; }

        AgentDependencyResolver.PackageArtifact resolverArtifact() {
            return new AgentDependencyResolver.PackageArtifact(agentPackage, archiveSha256);
        }
    }

    public static final class VerificationException extends IllegalArgumentException {
        public VerificationException(String message) { super(message); }
        public VerificationException(String message, Throwable cause) { super(message, cause); }
    }
}
