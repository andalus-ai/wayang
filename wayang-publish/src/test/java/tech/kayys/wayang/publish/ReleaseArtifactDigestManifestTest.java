package tech.kayys.wayang.publish;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.json.Json;

class ReleaseArtifactDigestManifestTest {

    @Test
    void verifiesSignedManifestAndBindsEveryLockedCoordinate() throws Exception {
        var lock = ReleaseArtifactResolver.verify(
                ReleaseArtifactLockfile.create(List.of("wayang.bpmn.control-flow"), "1.2.3", false).json());
        KeyPair signer = signer();
        String json = signedManifest(lock, signer, false);

        var verified = ReleaseArtifactDigestManifest.verify(json, lock, signer.getPublic());

        assertEquals(lock.sha256(), verified.lockSha256());
        assertEquals(lock.artifacts().size(), verified.digests().size());
        for (var artifact : lock.artifacts()) {
            assertEquals("a".repeat(64), verified.pomSha256(artifact.coordinate()));
            assertEquals("b".repeat(64), verified.jarSha256(artifact.coordinate()));
        }
    }

    @Test
    void rejectsManifestSignedByAnUntrustedKey() throws Exception {
        var lock = sampleLock();
        String json = signedManifest(lock, signer(), false);
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseArtifactDigestManifest.verify(json, lock, signer().getPublic()));
    }

    @Test
    void rejectsManifestBoundToAnotherLock() throws Exception {
        var lock = sampleLock();
        var otherLock = ReleaseArtifactResolver.verify(
                ReleaseArtifactLockfile.create(List.of("wayang.service.connector"), "1.2.3", false).json());
        String json = signedManifest(lock, signer(), false);
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseArtifactDigestManifest.verify(json, otherLock, signer().getPublic()));
    }

    @Test
    void rejectsMissingArtifactEvenWithValidSignature() throws Exception {
        var lock = sampleLock();
        KeyPair signer = signer();
        String json = signedManifest(lock, signer, true);
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseArtifactDigestManifest.verify(json, lock, signer.getPublic()));
    }

    private static ReleaseArtifactResolver.VerifiedLock sampleLock() {
        return ReleaseArtifactResolver.verify(
                ReleaseArtifactLockfile.create(List.of("wayang.bpmn.control-flow"), "1.2.3", false).json());
    }

    private static KeyPair signer() throws Exception {
        var generator = KeyPairGenerator.getInstance("Ed25519");
        return generator.generateKeyPair();
    }

    private static String signedManifest(ReleaseArtifactResolver.VerifiedLock lock, KeyPair signer,
            boolean omitFirst) throws Exception {
        List<Map<String, Object>> artifacts = new ArrayList<>();
        for (var artifact : lock.artifacts()) {
            if (omitFirst && artifacts.isEmpty()) continue;
            var c = artifact.coordinate();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("groupId", c.groupId());
            item.put("artifactId", c.artifactId());
            item.put("version", c.version());
            item.put("scope", c.scope());
            item.put("pomSha256", "a".repeat(64));
            item.put("jarSha256", "b".repeat(64));
            artifacts.add(item);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "wayang.artifact-digests");
        document.put("formatVersion", 1);
        document.put("lockSha256", lock.sha256());
        document.put("artifacts", artifacts);
        byte[] payload = Json.write(canonical(document)).getBytes(StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signer.getPrivate());
        signature.update(payload);
        document.put("signature", Base64.getEncoder().encodeToString(signature.sign()));
        return Json.write(document);
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonical(nested)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ReleaseArtifactDigestManifestTest::canonical).toList();
        return value;
    }
}
