package tech.kayys.wayang.publish;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import tech.kayys.wayang.runtime.json.Json;

/**
 * Produces a deterministic CycloneDX 1.5 SBOM from a verified Wayang release
 * lock and its separately signed artifact digest manifest.
 *
 * <p>No repository metadata is consulted here. Component hashes come only from
 * the verified manifest, and dependency edges come only from the verified lock.</p>
 */
public final class ReleaseArtifactSbom {
    private ReleaseArtifactSbom() {}

    public static String create(ReleaseArtifactResolver.VerifiedLock lock,
            ReleaseArtifactDigestManifest.VerifiedManifest manifest) {
        Objects.requireNonNull(lock, "lock");
        Objects.requireNonNull(manifest, "manifest");
        if (!lock.sha256().equals(manifest.lockSha256())) {
            throw new IllegalArgumentException("SBOM inputs do not refer to the same verified release lock");
        }
        List<Map<String, Object>> components = new ArrayList<>();
        List<Map<String, Object>> dependencies = new ArrayList<>();
        for (ReleaseArtifactResolver.Artifact artifact : lock.artifacts()) {
            var coordinate = artifact.coordinate();
            String ref = purl(coordinate.groupId(), coordinate.artifactId(), coordinate.version());
            var digest = manifest.artifact(coordinate);
            Map<String, Object> component = new LinkedHashMap<>();
            component.put("type", "library");
            component.put("bom-ref", ref);
            component.put("group", coordinate.groupId());
            component.put("name", coordinate.artifactId());
            component.put("version", coordinate.version());
            component.put("purl", ref);
            component.put("scope", "required");
            component.put("hashes", List.of(hash("SHA-256", digest.jarSha256())));
            component.put("properties", List.of(Map.of(
                    "name", "tech.kayys.wayang.pom.sha256", "value", digest.pomSha256())));
            components.add(component);
            Map<String, Object> dependency = new LinkedHashMap<>();
            dependency.put("ref", ref);
            dependency.put("dependsOn", artifact.dependencies().stream()
                    .map(d -> purl(d.groupId(), d.artifactId(), d.version())).sorted().toList());
            dependencies.add(dependency);
        }
        Map<String, Object> metadataComponent = new LinkedHashMap<>();
        metadataComponent.put("type", "application");
        metadataComponent.put("name", "Wayang release");
        metadataComponent.put("version", lock.artifacts().get(0).coordinate().version());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("component", metadataComponent);
        metadata.put("properties", List.of(Map.of("name", "tech.kayys.wayang.release-lock.sha256", "value", lock.sha256())));
        Map<String, Object> bom = new LinkedHashMap<>();
        bom.put("bomFormat", "CycloneDX");
        bom.put("specVersion", "1.5");
        bom.put("serialNumber", "urn:uuid:" + UUID.nameUUIDFromBytes(("wayang-release:" + lock.sha256()).getBytes(StandardCharsets.UTF_8)));
        bom.put("version", 1);
        bom.put("metadata", metadata);
        bom.put("components", components);
        bom.put("dependencies", dependencies);
        return Json.write(bom);
    }

    private static Map<String, Object> hash(String algorithm, String content) {
        return Map.of("alg", algorithm, "content", content);
    }

    private static String purl(String groupId, String artifactId, String version) {
        return "pkg:maven/" + groupId + "/" + artifactId + "@" + version;
    }
}