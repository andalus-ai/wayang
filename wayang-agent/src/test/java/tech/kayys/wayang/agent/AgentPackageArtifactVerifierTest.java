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
        var generator = KeyPairGenerator.getInstance("Ed25519");
        keyPair = generator.generateKeyPair();
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

        var wrongIdentity = sign(archive, "acme:other", "1.2.0", "acme:publisher");
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class,
                () -> verifier.verify(archive, wrongIdentity));
    }

    @Test
    void rejectsUnknownRevokedExpiredAndOutOfNamespacePublisherKeys() throws Exception {
        byte[] archive = archive(Map.of(AgentPackageArtifactVerifier.JSON_MANIFEST,
                MINIMAL.getBytes(StandardCharsets.UTF_8)));
        var signature = sign(archive, "acme:support", "1.2.0", "acme:publisher");

        var empty = new AgentPackageArtifactVerifier(new AgentPackageTrustStore.InMemory(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class, () -> empty.verify(archive, signature));

        trustStore.revoke("acme:publisher", "key-1");
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class, () -> verifier.verify(archive, signature));

        trustStore.trust(new AgentPackageTrustStore.TrustedPublisherKey("acme:publisher", "key-1",
                keyPair.getPublic(), List.of("other:"), NOW.minusSeconds(60), NOW.plusSeconds(3600), false));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class, () -> verifier.verify(archive, signature));

        trustStore.trust(new AgentPackageTrustStore.TrustedPublisherKey("acme:publisher", "key-1",
                keyPair.getPublic(), List.of("acme:"), NOW.minusSeconds(3600), NOW, false));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class, () -> verifier.verify(archive, signature));
    }

    @Test
    void verifiesDeclaredResourceDigestAndRejectsUndeclaredOrMissingResources() throws Exception {
        byte[] resource = "hello resource".getBytes(StandardCharsets.UTF_8);
        String manifest = MINIMAL.replace("\"requestedPermissions\":[]",
                "\"requestedPermissions\":[],\"resources\":[{\"path\":\"resources/prompt.txt\","
                + "\"mediaType\":\"text/plain\",\"sha256\":\"" + sha256(resource) + "\"}]");
        byte[] valid = archive(Map.of(
                AgentPackageArtifactVerifier.JSON_MANIFEST, manifest.getBytes(StandardCharsets.UTF_8),
                "resources/prompt.txt", resource));
        var verified = verifier.verify(valid, sign(valid, "acme:support", "1.2.0", "acme:publisher"));
        assertEquals(sha256(resource), verified.resourceSha256().get("resources/prompt.txt"));

        byte[] missing = archive(Map.of(AgentPackageArtifactVerifier.JSON_MANIFEST, manifest.getBytes(StandardCharsets.UTF_8)));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class,
                () -> verifier.verify(missing, sign(missing, "acme:support", "1.2.0", "acme:publisher")));

        byte[] undeclared = archive(Map.of(
                AgentPackageArtifactVerifier.JSON_MANIFEST, MINIMAL.getBytes(StandardCharsets.UTF_8),
                "payload.bin", new byte[] {1, 2, 3}));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class,
                () -> verifier.verify(undeclared, sign(undeclared, "acme:support", "1.2.0", "acme:publisher")));
    }

    @Test
    void rejectsTraversalPathsAndOversizedArchives() throws Exception {
        byte[] traversal = archive(Map.of(
                AgentPackageArtifactVerifier.JSON_MANIFEST, MINIMAL.getBytes(StandardCharsets.UTF_8),
                "../outside.txt", new byte[] {1}));
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class,
                () -> verifier.verify(traversal, sign(traversal, "acme:support", "1.2.0", "acme:publisher")));

        byte[] tooLarge = new byte[64 * 1024 * 1024 + 1];
        var signature = sign(tooLarge, "acme:support", "1.2.0", "acme:publisher");
        assertThrows(AgentPackageArtifactVerifier.VerificationException.class, () -> verifier.verify(tooLarge, signature));
    }

    @Test
    void verifiedArtifactIsRequiredBySecureInstallerPath() throws Exception {
        byte[] archive = archive(Map.of(AgentPackageArtifactVerifier.JSON_MANIFEST,
                MINIMAL.getBytes(StandardCharsets.UTF_8)));
        var verified = verifier.verify(archive, sign(archive, "acme:support", "1.2.0", "acme:publisher"));
        var installed = new AgentPackageInstaller().installVerified("tenant", "project", "support",
                verified, List.of(), Map.of(), NOW);
        assertEquals(verified.archiveSha256(), installed.lock().artifactSha256());
        assertEquals("acme:support", installed.lock().packageId());
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
}
