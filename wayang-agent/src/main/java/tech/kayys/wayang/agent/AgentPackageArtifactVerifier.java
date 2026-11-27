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

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

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
        // Snapshot caller-owned bytes so the digest and ZIP inspection cannot observe different content.
        archive = archive.clone();

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

        String manifestSha256 = sha256(manifestBytes);
        Map<String, String> resourceDigests = verifyResources(agentPackage, entries);
        String keyFingerprint = sha256(trusted.publicKey().getEncoded());
        return new VerifiedArtifact(agentPackage, archiveSha256, manifestSha256,
                signature.publisher(), signature.keyId(), keyFingerprint, now, resourceDigests);
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
            if ("sbom".equalsIgnoreCase(resource.role())) {
                if (!"application/vnd.cyclonedx+json".equalsIgnoreCase(resource.mediaType())
                        && !"application/json".equalsIgnoreCase(resource.mediaType())) {
                    throw invalid("CycloneDX SBOM must use a JSON media type: " + path);
                }
                verifyCycloneDx(content, path);
            } else if ("provenance".equalsIgnoreCase(resource.role())) {
                if (!"application/vnd.in-toto+json".equalsIgnoreCase(resource.mediaType())
                        && !"application/json".equalsIgnoreCase(resource.mediaType())) {
                    throw invalid("In-toto provenance must use a JSON media type: " + path);
                }
                verifyInTotoProvenance(content, path, pkg.metadata().id());
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


    private static void verifyCycloneDx(byte[] content, String path) {
        Map<?, ?> bom = structuredObject(content, path);
        if (!"CycloneDX".equals(bom.get("bomFormat"))
                || !java.util.Set.of("1.4", "1.5", "1.6").contains(String.valueOf(bom.get("specVersion")))
                || !(bom.get("serialNumber") instanceof String serial) || !serial.startsWith("urn:uuid:")
                || !(bom.get("components") instanceof java.util.List<?> components)) {
            throw invalid("SBOM resource is not a supported CycloneDX document: " + path);
        }
        java.util.Set<String> refs = new java.util.HashSet<>();
        for (Object item : components) {
            if (!(item instanceof Map<?, ?> component)
                    || !(component.get("name") instanceof String name) || name.isBlank()
                    || !(component.get("type") instanceof String type) || type.isBlank()) {
                throw invalid("SBOM contains an invalid component entry: " + path);
            }
            Object ref = component.get("bom-ref");
            if (ref instanceof String value && !refs.add(value)) {
                throw invalid("SBOM contains duplicate bom-ref values: " + path);
            }
        }
    }

    private static void verifyInTotoProvenance(byte[] content, String path, String packageId) {
        Map<?, ?> statement = structuredObject(content, path);
        if (!"https://in-toto.io/Statement/v1".equals(statement.get("_type"))
                || !(statement.get("predicateType") instanceof String predicateType) || predicateType.isBlank()
                || !(statement.get("predicate") instanceof Map<?, ?>)
                || !(statement.get("subject") instanceof java.util.List<?> subjects)) {
            throw invalid("Provenance resource is not an in-toto Statement v1: " + path);
        }
        boolean bindsPackageSubject = false;
        for (Object item : subjects) {
            if (!(item instanceof Map<?, ?> subject)
                    || !(subject.get("name") instanceof String name)
                    || !(subject.get("digest") instanceof Map<?, ?> digest)) {
                throw invalid("Provenance contains an invalid subject: " + path);
            }
            Object sha = digest.get("sha256");
            if (packageId.equals(name) && sha instanceof String value && value.matches("(?i)[0-9a-f]{64}")) {
                bindsPackageSubject = true;
            }
        }
        if (!bindsPackageSubject) {
            throw invalid("Provenance subject does not identify this package with a SHA-256 digest: " + path);
        }
    }

    private static Map<?, ?> structuredObject(byte[] content, String path) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(5);
            options.setCodePointLimit(MAX_RESOURCE_BYTES);
            Object parsed = new Yaml(new SafeConstructor(options)).load(utf8(content));
            if (!(parsed instanceof Map<?, ?> map)) throw invalid("Evidence resource must contain a JSON object: " + path);
            return map;
        } catch (RuntimeException e) {
            if (e instanceof VerificationException ve) throw ve;
            throw new VerificationException("Evidence resource is malformed: " + path, e);
        }
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
        private final String signingKeyFingerprint;
        private final Instant verifiedAt;
        private final Map<String, String> resourceSha256;

        private VerifiedArtifact(AgentPackageReader.AgentPackage agentPackage, String archiveSha256,
                String manifestSha256, String publisher, String keyId, String signingKeyFingerprint,
                Instant verifiedAt, Map<String, String> resourceSha256) {
            this.agentPackage = agentPackage;
            this.archiveSha256 = archiveSha256;
            this.manifestSha256 = manifestSha256;
            this.publisher = publisher;
            this.keyId = keyId;
            this.signingKeyFingerprint = signingKeyFingerprint;
            this.verifiedAt = verifiedAt;
            this.resourceSha256 = Map.copyOf(resourceSha256);
        }

        public AgentPackageReader.AgentPackage agentPackage() { return agentPackage; }
        public String archiveSha256() { return archiveSha256; }
        public String manifestSha256() { return manifestSha256; }
        public String publisher() { return publisher; }
        public String keyId() { return keyId; }
        public String signingKeyFingerprint() { return signingKeyFingerprint; }
        public Instant verifiedAt() { return verifiedAt; }
        public Map<String, String> resourceSha256() { return resourceSha256; }

        public InstalledAgent.VerificationReceipt verificationReceipt() {
            return new InstalledAgent.VerificationReceipt(publisher, keyId, signingKeyFingerprint,
                    manifestSha256, verifiedAt);
        }

        AgentDependencyResolver.PackageArtifact resolverArtifact() {
            return new AgentDependencyResolver.PackageArtifact(agentPackage, archiveSha256);
        }
    }

    public static final class VerificationException extends IllegalArgumentException {
        public VerificationException(String message) { super(message); }
        public VerificationException(String message, Throwable cause) { super(message, cause); }
    }
}
