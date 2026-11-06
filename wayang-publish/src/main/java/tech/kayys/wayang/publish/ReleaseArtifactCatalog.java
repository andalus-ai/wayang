package tech.kayys.wayang.publish;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Versioned mapping from Wayang component contracts to the Maven modules that
 * provide them. This is packaging metadata, deliberately separate from the
 * runtime-neutral ComponentDescriptor SPI.
 *
 * <p>The catalog describes the current published Maven module graph. It does
 * not claim that the current runtime is feature-pruned: wayang-embed depends on
 * wayang-runtime, whose POM currently brings the BPMN, DMN, EIP and agent
 * modules transitively.</p>
 */
final class ReleaseArtifactCatalog {
    static final String GROUP_ID = "tech.kayys.wayang";

    record Artifact(String artifactId, String version, List<String> dependencies) {
        Artifact {
            if (artifactId == null || !artifactId.matches("[a-z][a-z0-9-]+")) {
                throw new IllegalArgumentException("Invalid Wayang artifact id: " + artifactId);
            }
            if (version == null || version.isBlank()) throw new IllegalArgumentException("Artifact version is required");
            dependencies = dependencies == null ? List.of() : dependencies.stream().sorted().distinct().toList();
        }
    }

    private static final Map<String, List<String>> COMPONENT_ARTIFACTS = Map.ofEntries(
            Map.entry("wayang.bpmn.control-flow", List.of("wayang-business-process")),
            Map.entry("wayang.service.connector", List.of("wayang-connectors")),
            Map.entry("wayang.eip.pipeline", List.of("wayang-integration")),
            Map.entry("wayang.dmn.decision", List.of("wayang-dmn")),
            Map.entry("wayang.agent.invoke", List.of("wayang-agent")),
            Map.entry("wayang.human.task", List.of("wayang-embed")),
            Map.entry("wayang.multimodal.image-generate", List.of("wayang-multimodal")),
            Map.entry("wayang.multimodal.comfyui", List.of("wayang-multimodal")),
            Map.entry("wayang.multimodal.transcribe", List.of("wayang-multimodal")),
            Map.entry("wayang.multimodal.speak", List.of("wayang-multimodal"))
    );

    private static final Map<String, List<String>> MODULE_DEPENDENCIES = Map.ofEntries(
            Map.entry("wayang-embed", List.of("wayang-runtime")),
            Map.entry("wayang-runtime", List.of("wayang-expression", "wayang-dmn", "wayang-business-process", "wayang-integration", "wayang-agent")),
            Map.entry("wayang-connectors", List.of("wayang-runtime")),
            Map.entry("wayang-multimodal", List.of("wayang-runtime", "wayang-connectors")),
            Map.entry("wayang-business-process", List.of("wayang-expression")),
            Map.entry("wayang-dmn", List.of("wayang-expression")),
            Map.entry("wayang-integration", List.of("wayang-expression")),
            Map.entry("wayang-agent", List.of("wayang-expression")),
            Map.entry("wayang-expression", List.of()),
            Map.entry("wayang-gamelan-worker", List.of("wayang-runtime", "wayang-embed"))
    );

    private ReleaseArtifactCatalog() {}

    static List<Artifact> resolve(Collection<String> componentIds, String version) {
        return resolve(componentIds, version, false);
    }

    static List<Artifact> resolve(Collection<String> componentIds, String version, boolean gamelan) {
        Objects.requireNonNull(componentIds, "componentIds");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Wayang artifact version is required");

        TreeSet<String> roots = new TreeSet<>();
        roots.add(gamelan ? "wayang-gamelan-worker" : "wayang-embed"); // Gamelan worker embeds the runtime; standalone uses the embedded entry point
        for (String componentId : componentIds) {
            List<String> mapped = COMPONENT_ARTIFACTS.get(componentId);
            if (mapped == null) {
                throw new IllegalArgumentException("No Maven artifact mapping is registered for component '" + componentId + "'");
            }
            roots.addAll(mapped);
        }

        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String root : roots) visit(root, ordered, visiting);

        return ordered.stream()
                .map(id -> new Artifact(id, version, MODULE_DEPENDENCIES.get(id)))
                .sorted(Comparator.comparing(Artifact::artifactId))
                .toList();
    }

    private static void visit(String id, LinkedHashSet<String> ordered, Set<String> visiting) {
        if (ordered.contains(id)) return;
        List<String> dependencies = MODULE_DEPENDENCIES.get(id);
        if (dependencies == null) throw new IllegalArgumentException("Artifact dependency is not catalogued: " + id);
        if (!visiting.add(id)) throw new IllegalArgumentException("Artifact dependency cycle: " + String.join(" -> ", visiting) + " -> " + id);
        for (String dependency : dependencies.stream().sorted().toList()) visit(dependency, ordered, visiting);
        visiting.remove(id);
        ordered.add(id);
    }

    static List<String> roots(Collection<String> componentIds, boolean gamelan) {
        TreeSet<String> roots = new TreeSet<>();
        roots.add(gamelan ? "wayang-gamelan-worker" : "wayang-embed");
        for (String componentId : componentIds) {
            List<String> mapped = COMPONENT_ARTIFACTS.get(componentId);
            if (mapped == null) throw new IllegalArgumentException("No Maven artifact mapping is registered for component '" + componentId + "'");
            roots.addAll(mapped);
        }
        return List.copyOf(roots);
    }

    static List<String> mappedArtifacts(String componentId) {
        return COMPONENT_ARTIFACTS.getOrDefault(componentId, List.of());
    }
}
