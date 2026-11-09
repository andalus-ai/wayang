package tech.kayys.wayang.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.json.Json;

class ReleaseArtifactResolverTest {

    @Test
    void verifiesAndResolvesTheGeneratedArtifactLock() {
        String lock = ReleaseArtifactLockfile.create(
                List.of("wayang.bpmn.control-flow", "wayang.service.connector"), "1.2.3", false).json();

        var verified = ReleaseArtifactResolver.verify(lock);

        assertEquals(ReleaseArtifactLockfile.create(
                List.of("wayang.bpmn.control-flow", "wayang.service.connector"), "1.2.3", false).sha256(),
                verified.sha256());
        assertTrue(verified.roots().contains("wayang-embed"));
        assertTrue(verified.artifacts().stream().anyMatch(a ->
                a.coordinate().artifactId().equals("wayang-connectors")));
    }

    @Test
    void rejectsTamperedLockContents() {
        Map<String, Object> lock = readLock();
        lock.put("versionPolicy", "mutable-version");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ReleaseArtifactResolver.verify(Json.write(lock))).getMessage().contains("digest mismatch"));
    }

    @Test
    void rejectsAnArtifactMissingFromTheDependencyClosure() {
        Map<String, Object> lock = readLock();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) lock.get("artifacts");
        artifacts.removeIf(a -> "wayang-expression".equals(a.get("artifactId")));
        resign(lock);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ReleaseArtifactResolver.verify(Json.write(lock))).getMessage().contains("not locked"));
    }

    @Test
    void rejectsAnUnreachableArtifactEvenWhenItsDigestIsValid() {
        Map<String, Object> lock = readLock();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) lock.get("artifacts");
        artifacts.add(new LinkedHashMap<>(Map.of(
                "groupId", ReleaseArtifactCatalog.GROUP_ID,
                "artifactId", "wayang-orphan",
                "version", "1.2.3",
                "scope", "compile",
                "dependencies", List.of())));
        resign(lock);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ReleaseArtifactResolver.verify(Json.write(lock))).getMessage().contains("unreachable"));
    }

    private static Map<String, Object> readLock() {
        return new LinkedHashMap<>((Map<String, Object>) Json.read(
                ReleaseArtifactLockfile.create(List.of("wayang.bpmn.control-flow"), "1.2.3", false).json()));
    }

    private static void resign(Map<String, Object> lock) {
        lock.remove("sha256");
        String canonical = Json.write(canonical(lock));
        try {
            lock.put("sha256", java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonical(nested)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ReleaseArtifactResolverTest::canonical).toList();
        return value;
    }
}
