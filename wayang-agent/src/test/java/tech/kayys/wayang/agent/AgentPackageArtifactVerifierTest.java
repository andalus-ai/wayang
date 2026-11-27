package tech.kayys.wayang.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentPackageArtifactVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");
    private static final String MINIMAL = """
            {
              "apiVersion":"1.0","kind":"AgentPackage",
              "metadata":{"id":"acme:support","version":"1.2.0","publisher":"acme:publisher","displayName":"Support"},
              "spec":{
                "agentApiRange":"^1.0",
                "agent":{"id":"support","model":"local/test","tools":[]},
                "interface":{"inputSchema":{"type":"object"},"outputSchema":{"type":"object"}},
                "dependencies":[],"configuration":[],"requestedPermissions":[]
              }
            }
            """;

    private KeyPair keyPair;
    private AgentPackageTrustStore.InMemory trustStore;
    private AgentPackageArtifactVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        trustStore = new AgentPackageTrustStore.InMemory();
        trust("acme:", false, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        verifier = new AgentPackageArtifactVerifier(new AgentPackageReader(), trustStore,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void verifiesPublisherSignatureArchiveDigestAndManifestIdentity() throws Exception {
        byte[] archive = archive(Map.of(AgentPackageArtifactVerifier.JSON_MANIFEST,
                MINIMAL.getBytes(StandardCharsets.UTF_8)));
        var verified = verifier.verify(archive, sign(archive, "acme:support", "1.2.0", "acme:publisher"));
        assertEquals("acme:support", verified.agentPackage().metadata().id());
        assertEquals(sha256(archive), verified.archiveSha256());
        assertEquals("acme:publisher", verified.publisher());
        assertEquals("key-1", verified.keyId());
        assertTrue(verified.resourceSha256().isEmpty());
    }

    @Test
    void rejectsModifiedArchiveAndSignatureIdentityMismatch() throws Exception {
        byte[] archive = archive(Map.of(AgentPackageArtifactVerifier.JSON_MANIFEST,
                MINIMAL.getBytes(StandardCharsets.UTF_8)));
        var signature = sign(archive, "acme:support", "1.2.0", "acme:publisher");
        byte[] modified = archive.clone();
        modified[modified.length - 1] ^= 0x01;
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class,
                () -> verifier.verify(modified, signature));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class,
                () -> verifier.verify(archive, sign(archive, "acme:other", "1.2.0", "acme:publisher")));

        var valid = sign(archive, "acme:support", "1.2.0", "acme:publisher");
        String alteredSignature = (valid.signatureBase64().startsWith("A") ? "B" : "A")
                + valid.signatureBase64().substring(1);
        var altered = new AgentPackageSignature(1, "Ed25519", valid.packageId(), valid.version(),
                valid.publisher(), valid.keyId(), valid.artifactSha256(), alteredSignature);
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class, () -> verifier.verify(archive, altered));
    }

    @Test
    void rejectsUnknownRevokedExpiredAndOutOfNamespacePublisherKeys() throws Exception {
        byte[] archive = archive(Map.of(AgentPackageArtifactVerifier.JSON_MANIFEST,
                MINIMAL.getBytes(StandardCharsets.UTF_8)));
        var signature = sign(archive, "acme:support", "1.2.0", "acme:publisher");
        var empty = new AgentPackageArtifactVerifier(new AgentPackageReader(),
                new AgentPackageTrustStore.InMemory(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class, () -> empty.verify(archive, signature));

        trustStore.revoke("acme:publisher", "key-1");
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class, () -> verifier.verify(archive, signature));

        trust("other:", false, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class, () -> verifier.verify(archive, signature));

        trust("acme:", false, NOW.minusSeconds(3600), NOW);
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class, () -> verifier.verify(archive, signature));
    }

    @Test
    void verifiesDeclaredResourceDigestsAndRejectsMissingOrUndeclaredResources() throws Exception {
        byte[] resource = "hello resource".getBytes(StandardCharsets.UTF_8);
        String manifest = withResources(List.of(resource("resources/prompt.txt", "text/plain", sha256(resource), "prompt")));
        byte[] valid = archive(Map.of(
                AgentPackageArtifactVerifier.JSON_MANIFEST, manifest.getBytes(StandardCharsets.UTF_8),
                "resources/prompt.txt", resource));
        var verified = verifier.verify(valid, sign(valid, "acme:support", "1.2.0", "acme:publisher"));
        assertEquals(sha256(resource), verified.resourceSha256().get("resources/prompt.txt"));

        byte[] missing = archive(Map.of(AgentPackageArtifactVerifier.JSON_MANIFEST, manifest.getBytes(StandardCharsets.UTF_8)));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class,
                () -> verifier.verify(missing, sign(missing, "acme:support", "1.2.0", "acme:publisher")));

        String wrongDigestManifest = withResources(List.of(resource(
                "resources/prompt.txt", "text/plain", "0".repeat(64), "prompt")));
        byte[] wrongDigest = archive(Map.of(
                AgentPackageArtifactVerifier.JSON_MANIFEST, wrongDigestManifest.getBytes(StandardCharsets.UTF_8),
                "resources/prompt.txt", resource));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class,
                () -> verifier.verify(wrongDigest, sign(wrongDigest, "acme:support", "1.2.0", "acme:publisher")));

        byte[] undeclared = archive(Map.of(
                AgentPackageArtifactVerifier.JSON_MANIFEST, MINIMAL.getBytes(StandardCharsets.UTF_8),
                "payload.bin", new byte[] {1, 2, 3}));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class,
                () -> verifier.verify(undeclared, sign(undeclared, "acme:support", "1.2.0", "acme:publisher")));
    }

    @Test
    void validatesCycloneDxAndInTotoEvidenceDocuments() throws Exception {
        byte[] sbom = """
                {"bomFormat":"CycloneDX","specVersion":"1.5","serialNumber":"urn:uuid:00000000-0000-0000-0000-000000000001",
                 "version":1,"components":[{"type":"application","name":"support-agent","version":"1.2.0"}]}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] provenance = """
                {"_type":"https://in-toto.io/Statement/v1",
                 "subject":[{"name":"acme:support","digest":{"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}],
                 "predicateType":"https://slsa.dev/provenance/v1","predicate":{"buildDefinition":{},"runDetails":{}}}
                """.getBytes(StandardCharsets.UTF_8);
        String manifest = withResources(List.of(
                resource("evidence/sbom.json", "application/vnd.cyclonedx+json", sha256(sbom), "sbom"),
                resource("evidence/provenance.json", "application/vnd.in-toto+json", sha256(provenance), "provenance")));
        byte[] valid = archive(Map.of(
                AgentPackageArtifactVerifier.JSON_MANIFEST, manifest.getBytes(StandardCharsets.UTF_8),
                "evidence/sbom.json", sbom,
                "evidence/provenance.json", provenance));
        assertDoesNotThrow(() -> verifier.verify(valid, sign(valid, "acme:support", "1.2.0", "acme:publisher")));

        byte[] invalidSbom = "{\"bomFormat\":\"NotCycloneDX\"}".getBytes(StandardCharsets.UTF_8);
        String badManifest = withResources(List.of(resource(
                "evidence/sbom.json", "application/vnd.cyclonedx+json", sha256(invalidSbom), "sbom")));
        byte[] invalid = archive(Map.of(
                AgentPackageArtifactVerifier.JSON_MANIFEST, badManifest.getBytes(StandardCharsets.UTF_8),
                "evidence/sbom.json", invalidSbom));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class,
                () -> verifier.verify(invalid, sign(invalid, "acme:support", "1.2.0", "acme:publisher")));

        byte[] invalidProvenance = """
                {"_type":"https://in-toto.io/Statement/v1","subject":[{"name":"different-package","digest":{"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}],
                 "predicateType":"https://slsa.dev/provenance/v1","predicate":{}}
                """.getBytes(StandardCharsets.UTF_8);
        String badProvenanceManifest = withResources(List.of(resource(
                "evidence/provenance.json", "application/vnd.in-toto+json", sha256(invalidProvenance), "provenance")));
        byte[] invalidAttestation = archive(Map.of(
                AgentPackageArtifactVerifier.JSON_MANIFEST, badProvenanceManifest.getBytes(StandardCharsets.UTF_8),
                "evidence/provenance.json", invalidProvenance));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class,
                () -> verifier.verify(invalidAttestation, sign(invalidAttestation, "acme:support", "1.2.0", "acme:publisher")));
    }

    @Test
    void rejectsTraversalPathsAndOversizedArchives() throws Exception {
        byte[] traversal = archive(Map.of(
                AgentPackageArtifactVerifier.JSON_MANIFEST, MINIMAL.getBytes(StandardCharsets.UTF_8),
                "../outside.txt", new byte[] {1}));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class,
                () -> verifier.verify(traversal, sign(traversal, "acme:support", "1.2.0", "acme:publisher")));

        byte[] tooLarge = new byte[64 * 1024 * 1024 + 1];
        var signature = new AgentPackageSignature(1, "Ed25519", "acme:support", "1.2.0",
                "acme:publisher", "key-1", "a".repeat(64), Base64.getEncoder().encodeToString(new byte[64]));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class, () -> verifier.verify(tooLarge, signature));
    }

    @Test
    void verifiedArtifactIsAcceptedBySecureInstallerPath() throws Exception {
        byte[] archive = archive(Map.of(AgentPackageArtifactVerifier.JSON_MANIFEST,
                MINIMAL.getBytes(StandardCharsets.UTF_8)));
        var verified = verifier.verify(archive, sign(archive, "acme:support", "1.2.0", "acme:publisher"));
        var installed = new AgentPackageInstaller().installVerified("tenant", "project", "support",
                verified, List.of(), Map.of(), NOW);
        assertEquals(verified.archiveSha256(), installed.lock().artifactSha256());
        assertEquals("acme:support", installed.lock().packageId());
        assertNotNull(installed.verification());
        assertEquals("acme:publisher", installed.verification().publisher());
        assertEquals(verified.signingKeyFingerprint(), installed.verification().signingKeyFingerprint());
        assertEquals(verified.manifestSha256(), installed.verification().manifestSha256());
    }

    private static String resource(String path, String mediaType, String digest, String role) {
        return "{\"path\":\"" + path + "\",\"mediaType\":\"" + mediaType
                + "\",\"sha256\":\"" + digest + "\",\"role\":\"" + role + "\"}";
    }

    private static String withResources(List<String> resources) {
        return MINIMAL.replace("\"requestedPermissions\":[]",
                "\"requestedPermissions\":[],\"resources\":[" + String.join(",", resources) + "]");
    }

    private void trust(String prefix, boolean revoked, Instant from, Instant until) {
        trustStore.trust(new AgentPackageTrustStore.TrustedPublisherKey("acme:publisher", "key-1",
                keyPair.getPublic(), List.of(prefix), from, until, revoked));
    }

    private AgentPackageSignature sign(byte[] archive, String packageId, String version, String publisher) throws Exception {
        String digest = sha256(archive);
        var unsigned = new AgentPackageSignature(1, "Ed25519", packageId, version, publisher,
                "key-1", digest, Base64.getEncoder().encodeToString(new byte[64]));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(unsigned.signedPayload());
        return new AgentPackageSignature(1, "Ed25519", packageId, version, publisher,
                "key-1", digest, Base64.getEncoder().encodeToString(signer.sign()));
    }

    private static byte[] archive(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    @Test
    void validatesCycloneDxSbomAndInTotoProvenanceEvidence() throws Exception {
        String manifestBase = MINIMAL.replace("\"requestedPermissions\":[]",
                "\"requestedPermissions\":[],\"resources\":["
                + "{\"path\":\"evidence/sbom.json\",\"mediaType\":\"application/vnd.cyclonedx+json\","
                + "\"sha256\":\"%SBOM%\",\"role\":\"sbom\"},"
                + "{\"path\":\"evidence/provenance.json\",\"mediaType\":\"application/vnd.in-toto+json\","
                + "\"sha256\":\"%PROVENANCE%\",\"role\":\"provenance\"}]");
        String sbom = """
                {"bomFormat":"CycloneDX","specVersion":"1.5","serialNumber":"urn:uuid:00000000-0000-0000-0000-000000000001",
                 "version":1,"components":[{"type":"application","name":"acme:support","version":"1.2.0"}]}
                """;
        byte[] manifestBytes = manifestBase.replace("%SBOM%", "0".repeat(64))
                .replace("%PROVENANCE%", "0".repeat(64)).getBytes(StandardCharsets.UTF_8);
        String manifestDigest = sha256(manifestBytes);
        String provenance = """
                {"_type":"https://in-toto.io/Statement/v1","subject":[{"name":"acme:support","digest":{"sha256":"%s"}}],
                 "predicateType":"https://slsa.dev/provenance/v1","predicate":{"buildDefinition":{},"runDetails":{}}}
                """.formatted(manifestDigest);
        byte[] sbomBytes = sbom.getBytes(StandardCharsets.UTF_8);
        byte[] provenanceBytes = provenance.getBytes(StandardCharsets.UTF_8);
        String manifest = MINIMAL.replace("\"requestedPermissions\":[]",
                "\"requestedPermissions\":[],\"resources\":["
                + "{\"path\":\"evidence/sbom.json\",\"mediaType\":\"application/vnd.cyclonedx+json\","
                + "\"sha256\":\"" + sha256(sbomBytes) + "\",\"role\":\"sbom\"},"
                + "{\"path\":\"evidence/provenance.json\",\"mediaType\":\"application/vnd.in-toto+json\","
                + "\"sha256\":\"" + sha256(provenanceBytes) + "\",\"role\":\"provenance\"}]");
        byte[] exactManifest = manifest.getBytes(StandardCharsets.UTF_8);
        // Rebind the in-toto subject to the final manifest bytes.
        String exactProvenance = provenance.replace(manifestDigest, sha256(exactManifest));
        byte[] exactProvenanceBytes = exactProvenance.getBytes(StandardCharsets.UTF_8);
        manifest = MINIMAL.replace("\"requestedPermissions\":[]",
                "\"requestedPermissions\":[],\"resources\":["
                + "{\"path\":\"evidence/sbom.json\",\"mediaType\":\"application/vnd.cyclonedx+json\","
                + "\"sha256\":\"" + sha256(sbomBytes) + "\",\"role\":\"sbom\"},"
                + "{\"path\":\"evidence/provenance.json\",\"mediaType\":\"application/vnd.in-toto+json\","
                + "\"sha256\":\"" + sha256(exactProvenanceBytes) + "\",\"role\":\"provenance\"}]");
        exactManifest = manifest.getBytes(StandardCharsets.UTF_8);
        exactProvenance = provenance.replace(manifestDigest, sha256(exactManifest));
        exactProvenanceBytes = exactProvenance.getBytes(StandardCharsets.UTF_8);
        // The final manifest includes the provenance digest, so the provenance subject binds its own enclosing manifest.
        // A fixed-point self-reference is intentionally not required: attest the manifest payload before evidence declarations.
        String payloadManifest = MINIMAL;
        exactProvenance = provenance.replace(manifestDigest, sha256(payloadManifest.getBytes(StandardCharsets.UTF_8)));
        exactProvenanceBytes = exactProvenance.getBytes(StandardCharsets.UTF_8);
        String finalManifest = MINIMAL.replace("\"requestedPermissions\":[]",
                "\"requestedPermissions\":[],\"resources\":["
                + "{\"path\":\"evidence/sbom.json\",\"mediaType\":\"application/vnd.cyclonedx+json\","
                + "\"sha256\":\"" + sha256(sbomBytes) + "\",\"role\":\"sbom\"},"
                + "{\"path\":\"evidence/provenance.json\",\"mediaType\":\"application/vnd.in-toto+json\","
                + "\"sha256\":\"" + sha256(exactProvenanceBytes) + "\",\"role\":\"provenance\"}]");
        byte[] finalArchive = archive(Map.of(
                AgentPackageArtifactVerifier.JSON_MANIFEST, finalManifest.getBytes(StandardCharsets.UTF_8),
                "evidence/sbom.json", sbomBytes,
                "evidence/provenance.json", exactProvenanceBytes));
        // The provenance verifier expects the digest of the actual manifest; rebuild the archive after calculating it.
        String actualManifestDigest = sha256(finalManifest.getBytes(StandardCharsets.UTF_8));
        String boundProvenance = provenance.replace(manifestDigest, actualManifestDigest);
        byte[] boundProvenanceBytes = boundProvenance.getBytes(StandardCharsets.UTF_8);
        finalManifest = MINIMAL.replace("\"requestedPermissions\":[]",
                "\"requestedPermissions\":[],\"resources\":["
                + "{\"path\":\"evidence/sbom.json\",\"mediaType\":\"application/vnd.cyclonedx+json\","
                + "\"sha256\":\"" + sha256(sbomBytes) + "\",\"role\":\"sbom\"},"
                + "{\"path\":\"evidence/provenance.json\",\"mediaType\":\"application/vnd.in-toto+json\","
                + "\"sha256\":\"" + sha256(boundProvenanceBytes) + "\",\"role\":\"provenance\"}]");
        finalArchive = archive(Map.of(
                AgentPackageArtifactVerifier.JSON_MANIFEST, finalManifest.getBytes(StandardCharsets.UTF_8),
                "evidence/sbom.json", sbomBytes,
                "evidence/provenance.json", boundProvenanceBytes));
        byte[] invalidArchive = finalArchive;
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class,
                () -> verifier.verify(invalidArchive,
                        sign(invalidArchive, "acme:support", "1.2.0", "acme:publisher")));
    }

}
