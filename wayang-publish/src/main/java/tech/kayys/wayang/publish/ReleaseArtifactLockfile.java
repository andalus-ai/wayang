package tech.kayys.wayang.publish;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.runtime.json.Json;

/** Deterministic lock of the internal Maven artifacts required by a generated release. */
final class ReleaseArtifactLockfile {
    record Result(String json, String sha256, List<ReleaseArtifactCatalog.Artifact> artifacts) {
        Result { artifacts = List.copyOf(artifacts); }
    }

    private ReleaseArtifactLockfile() {}

    static Result create(List<String> componentIds, String version) {
        List<ReleaseArtifactCatalog.Artifact> artifacts = ReleaseArtifactCatalog.resolve(componentIds, version);
        List<Map<String, Object>> entries = artifacts.stream().map(artifact -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("groupId", ReleaseArtifactCatalog.GROUP_ID);
            item.put("artifactId", artifact.artifactId());
            item.put("version", artifact.version());
            item.put("scope", "compile");
            item.put("dependencies", artifact.dependencies().stream().map(id -> Map.of(
                    "groupId", ReleaseArtifactCatalog.GROUP_ID,
                    "artifactId", id,
                    "version", artifact.version(),
                    "scope", "compile")).toList());
            return item;
        }).toList();

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "wayang.artifact-lock");
        document.put("formatVersion", 1);
        document.put("versionPolicy", "wayangVersion");
        document.put("roots", ReleaseArtifactCatalog.roots(componentIds, gamelan));
        document.put("artifacts", entries);
        String digest = sha256(Json.write(canonical(document)));
        document.put("sha256", digest);
        return new Result(Json.write(canonical(document)), digest, artifacts);
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonical(nested)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ReleaseArtifactLockfile::canonical).toList();
        return value;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
