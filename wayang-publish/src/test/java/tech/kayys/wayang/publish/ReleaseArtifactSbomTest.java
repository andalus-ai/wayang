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

class ReleaseArtifactSbomTest {
    @Test void createsDeterministicCycloneDxBomFromVerifiedInputs() throws Exception {
        var lock = sampleLock();
        KeyPair signer = signer();
        var manifest = ReleaseArtifactDigestManifest.verify(signedManifest(lock, signer), lock, signer.getPublic());
        String first = ReleaseArtifactSbom.create(lock, manifest);
        String second = ReleaseArtifactSbom.create(lock, manifest);
        assertEquals(first, second);
        Map<?, ?> bom = (Map<?, ?>) Json.read(first);
        assertEquals("CycloneDX", bom.get("bomFormat"));
        assertEquals("1.5", bom.get("specVersion"));
        assertEquals(lock.artifacts().size(), ((List<?>) bom.get("components")).size());
        assertEquals(lock.artifacts().size(), ((List<?>) bom.get("dependencies")).size());
        Map<?, ?> component = (Map<?, ?>) ((List<?>) bom.get("components")).get(0);
        assertEquals("library", component.get("type"));
        assertEquals(1, ((List<?>) component.get("hashes")).size());
        assertEquals("a".repeat(64), ((Map<?, ?>) ((List<?>) component.get("properties")).get(0)).get("value"));
        assertTrue(component.get("purl").toString().startsWith("pkg:maven/tech.kayys.wayang/"));
    }

    @Test void rejectsLockAndManifestMismatch() throws Exception {
        var lock = sampleLock();
        var other = ReleaseArtifactResolver.verify(ReleaseArtifactLockfile.create(List.of("wayang.service.connector"), "1.2.3", false).json());
        KeyPair signer = signer();
        var manifest = ReleaseArtifactDigestManifest.verify(signedManifest(lock, signer), lock, signer.getPublic());
        assertThrows(IllegalArgumentException.class, () -> ReleaseArtifactSbom.create(other, manifest));
    }

    private static ReleaseArtifactResolver.VerifiedLock sampleLock() {
        return ReleaseArtifactResolver.verify(ReleaseArtifactLockfile.create(List.of("wayang.bpmn.control-flow"), "1.2.3", false).json());
    }
    private static KeyPair signer() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }
    private static String signedManifest(ReleaseArtifactResolver.VerifiedLock lock, KeyPair signer) throws Exception {
        List<Map<String, Object>> artifacts = new ArrayList<>();
        for (var artifact : lock.artifacts()) {
            var c = artifact.coordinate();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("groupId", c.groupId()); item.put("artifactId", c.artifactId());
            item.put("version", c.version()); item.put("scope", c.scope());
            item.put("pomSha256", "a".repeat(64)); item.put("jarSha256", "b".repeat(64));
            artifacts.add(item);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "wayang.artifact-digests"); document.put("formatVersion", 1);
        document.put("lockSha256", lock.sha256()); document.put("artifacts", artifacts);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signer.getPrivate());
        signature.update(Json.write(canonical(document)).getBytes(StandardCharsets.UTF_8));
        document.put("signature", Base64.getEncoder().encodeToString(signature.sign()));
        return Json.write(document);
    }
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonical(nested)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ReleaseArtifactSbomTest::canonical).toList();
        return value;
    }
}