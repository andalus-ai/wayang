package tech.kayys.wayang.publish;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import tech.kayys.wayang.runtime.json.Json;

/**
 * Verifies and resolves a generated Wayang Maven artifact lockfile without contacting
 * a repository. Repository downloads and binary checksum verification are deliberately
 * separate concerns; this class validates the immutable coordinate graph and its digest.
 */
public final class ReleaseArtifactResolver {

    public record Coordinate(String groupId, String artifactId, String version, String scope) {
    }

    public record Artifact(Coordinate coordinate, List<Coordinate> dependencies) {
        public Artifact {
            Objects.requireNonNull(coordinate, "coordinate");
            dependencies = List.copyOf(dependencies);
        }
    }

    public record VerifiedLock(String sha256, List<String> roots, List<Artifact> artifacts) {
        public VerifiedLock {
            roots = List.copyOf(roots);
            artifacts = List.copyOf(artifacts);
        }
    }

    private ReleaseArtifactResolver() {
    }

    /**
     * Verify format, digest, unique coordinates, root membership, dependency closure,
     * and acyclic dependency ordering. No network or filesystem access is performed.
     */
    public static VerifiedLock verify(String lockJson) {
        if (lockJson == null || lockJson.isBlank()) {
            throw new IllegalArgumentException("Artifact lockfile is empty");
        }
        Object parsed = Json.read(lockJson);
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Artifact lockfile must be a JSON object");
        }
        Map<String, Object> document = stringMap(raw, "artifact lockfile");
        if (!"wayang.artifact-lock".equals(document.get("format"))) {
            throw new IllegalArgumentException("Unsupported artifact lock format");
        }
        if (!(document.get("formatVersion") instanceof Number formatVersion)
                || formatVersion.intValue() != 1 || formatVersion.doubleValue() != 1.0d) {
            throw new IllegalArgumentException("Unsupported artifact lock formatVersion");
        }
        String expectedDigest = requiredString(document.get("sha256"), "sha256");
        if (!expectedDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Artifact lock sha256 must be 64 lowercase hexadecimal characters");
        }

        Map<String, Object> unsigned = new LinkedHashMap<>(document);
        unsigned.remove("sha256");
        String actualDigest = sha256(Json.write(canonical(unsigned)));
        if (!MessageDigest.isEqual(expectedDigest.getBytes(StandardCharsets.US_ASCII),
                actualDigest.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Artifact lock digest mismatch");
        }

        List<String> roots = stringList(document.get("roots"), "roots");
        if (roots.isEmpty() || new HashSet<>(roots).size() != roots.size()) {
            throw new IllegalArgumentException("Artifact lock roots must be non-empty and unique");
        }

        Object rawArtifacts = document.get("artifacts");
        if (!(rawArtifacts instanceof List<?> entries) || entries.isEmpty()) {
            throw new IllegalArgumentException("Artifact lock must contain at least one artifact");
        }

        Map<String, Artifact> byKey = new HashMap<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> rawEntry)) {
                throw new IllegalArgumentException("Artifact lock contains a malformed artifact entry");
            }
            Map<String, Object> item = stringMap(rawEntry, "artifact entry");
            Coordinate coordinate = new Coordinate(
                    requiredString(item.get("groupId"), "artifact.groupId"),
                    requiredString(item.get("artifactId"), "artifact.artifactId"),
                    requiredString(item.get("version"), "artifact.version"),
                    requiredString(item.get("scope"), "artifact.scope"));
            if (!"compile".equals(coordinate.scope())) {
                throw new IllegalArgumentException("Unsupported artifact scope for " + coordinate.artifactId()
                        + ": " + coordinate.scope());
            }
            List<Coordinate> dependencies = new ArrayList<>();
            Object rawDependencies = item.get("dependencies");
            if (!(rawDependencies instanceof List<?> dependencyEntries)) {
                throw new IllegalArgumentException("Artifact dependencies must be an array for " + coordinate.artifactId());
            }
            for (Object dependency : dependencyEntries) {
                if (!(dependency instanceof Map<?, ?> rawDependency)) {
                    throw new IllegalArgumentException("Malformed dependency in " + coordinate.artifactId());
                }
                Map<String, Object> dep = stringMap(rawDependency, "artifact dependency");
                dependencies.add(new Coordinate(
                        requiredString(dep.get("groupId"), "dependency.groupId"),
                        requiredString(dep.get("artifactId"), "dependency.artifactId"),
                        requiredString(dep.get("version"), "dependency.version"),
                        requiredString(dep.get("scope"), "dependency.scope")));
            }
            Artifact artifact = new Artifact(coordinate, dependencies);
            String key = key(coordinate);
            if (byKey.putIfAbsent(key, artifact) != null) {
                throw new IllegalArgumentException("Duplicate artifact coordinate: " + key);
            }
        }

        for (String root : roots) {
            boolean found = byKey.values().stream().anyMatch(a ->
                    a.coordinate().artifactId().equals(root)
                            && a.coordinate().groupId().equals(ReleaseArtifactCatalog.GROUP_ID));
            if (!found) throw new IllegalArgumentException("Artifact lock root is missing: " + root);
        }
        for (Artifact artifact : byKey.values()) {
            for (Coordinate dependency : artifact.dependencies()) {
                if (!"compile".equals(dependency.scope())) {
                    throw new IllegalArgumentException("Unsupported dependency scope: " + dependency.scope());
                }
                if (!byKey.containsKey(key(dependency))) {
                    throw new IllegalArgumentException("Artifact dependency is not locked: "
                            + artifact.coordinate().artifactId() + " -> " + key(dependency));
                }
            }
        }

        Set<String> reachable = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String root : roots) {
            Coordinate coordinate = byKey.values().stream()
                    .map(Artifact::coordinate)
                    .filter(c -> c.artifactId().equals(root) && c.groupId().equals(ReleaseArtifactCatalog.GROUP_ID))
                    .findFirst().orElseThrow();
            visit(coordinate, byKey, reachable, visiting);
        }
        if (reachable.size() != byKey.size()) {
            Set<String> orphaned = new HashSet<>(byKey.keySet());
            orphaned.removeAll(reachable);
            throw new IllegalArgumentException("Artifact lock contains unreachable artifacts: " + orphaned);
        }

        List<Artifact> ordered = byKey.values().stream()
                .sorted(java.util.Comparator.comparing(a -> key(a.coordinate())))
                .toList();
        return new VerifiedLock(expectedDigest, roots, ordered);
    }

    private static void visit(Coordinate coordinate, Map<String, Artifact> artifacts,
            Set<String> visited, Set<String> visiting) {
        String key = key(coordinate);
        if (visited.contains(key)) return;
        if (!visiting.add(key)) throw new IllegalArgumentException("Artifact dependency cycle detected at " + key);
        Artifact artifact = artifacts.get(key);
        if (artifact == null) throw new IllegalArgumentException("Artifact dependency is not locked: " + key);
        for (Coordinate dependency : artifact.dependencies()) {
            visit(dependency, artifacts, visited, visiting);
        }
        visiting.remove(key);
        visited.add(key);
    }

    private static String key(Coordinate coordinate) {
        return coordinate.groupId() + ":" + coordinate.artifactId() + ":" + coordinate.version();
    }

    private static String requiredString(Object value, String field) {
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException("Missing or invalid " + field);
        }
        return string;
    }

    private static List<String> stringList(Object value, String field) {
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException(field + " must be an array");
        List<String> result = new ArrayList<>();
        for (Object item : values) result.add(requiredString(item, field + " entry"));
        return List.copyOf(result);
    }

    private static Map<String, Object> stringMap(Map<?, ?> value, String field) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (!(key instanceof String string)) throw new IllegalArgumentException(field + " keys must be strings");
            result.put(string, item);
        });
        return result;
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonical(nested)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ReleaseArtifactResolver::canonical).toList();
        return value;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
