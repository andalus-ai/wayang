package tech.kayys.wayang.publish;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.embed.ReleaseBundle;
import tech.kayys.wayang.runtime.ComponentDescriptor;
import tech.kayys.wayang.runtime.ComponentRegistry;
import tech.kayys.wayang.runtime.ComponentIdentity;
import tech.kayys.wayang.runtime.ComponentRequirements;
import tech.kayys.wayang.runtime.Extensions;
import tech.kayys.wayang.runtime.Profile;
import tech.kayys.wayang.runtime.json.Json;

/** Creates the deterministic, exact component dependency lock embedded in every generated release. */
final class ComponentLockfile {
    record Result(String json, String sha256, List<String> componentIds) {
        Result {
            componentIds = List.copyOf(componentIds);
        }
    }

    private ComponentLockfile() {}

    static Result create(ReleaseBundle bundle, Profile profile, Extensions extensions, Set<String> connectorNames) {
        ComponentRegistry registry = ComponentRegistry.standard(profile, extensions);
        TreeSet<String> roots = new TreeSet<>();
        for (ReleaseBundle.Definition definition : bundle.definitions()) {
            if (!"PROCESS".equals(definition.kind())) continue;
            Object rawElements = definition.document().get("elements");
            if (!(rawElements instanceof List<?> elements)) {
                throw new IllegalArgumentException("Process '" + definition.id() + "' has no element list for component resolution");
            }
            List<ElementType> types = new ArrayList<>();
            List<String> explicitComponentIds = new ArrayList<>();
            for (Object raw : elements) {
                if (!(raw instanceof Map<?, ?> element) || !(element.get("type") instanceof String type)) {
                    throw new IllegalArgumentException("Process '" + definition.id() + "' contains a malformed element");
                }
                try {
                    types.add(ElementType.valueOf(type.toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Process '" + definition.id() + "' uses unsupported element type '" + type + "'");
                }
                Object rawConfig = element.get("config");
                if (rawConfig instanceof Map<?, ?> config) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    config.forEach((key, value) -> normalized.put(String.valueOf(key), value));
                    try {
                        ComponentIdentity identity = ComponentIdentity.fromConfiguration(normalized);
                        if (identity != null) explicitComponentIds.add(identity.id());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Process '" + definition.id()
                                + "' has an invalid component binding: " + e.getMessage(), e);
                    }
                }
            }
            roots.addAll(ComponentRequirements.componentIds(types, explicitComponentIds));
        }
        if (connectorNames != null && !connectorNames.isEmpty()) {
            roots.add("wayang.service.connector");
            for (String connector : new TreeSet<>(connectorNames)) {
                List<ComponentDescriptor> matches = registry.all().stream()
                        .filter(d -> d.kind() == ComponentDescriptor.Kind.CONNECTOR && d.id().endsWith("." + connector))
                        .toList();
                if (matches.size() > 1) {
                    throw new IllegalArgumentException("Connector '" + connector + "' maps to multiple component contracts: "
                            + matches.stream().map(ComponentDescriptor::id).toList());
                }
                if (!matches.isEmpty()) roots.add(matches.get(0).id());
            }
        }
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("The release has no process component requirements");
        }
        List<ComponentDescriptor> resolved;
        try {
            resolved = registry.resolve(roots);
        } catch (ComponentRegistry.ComponentResolutionException e) {
            throw new IllegalArgumentException("Cannot resolve release component dependencies: " + e.getMessage(), e);
        }

        List<Map<String, Object>> locked = new ArrayList<>();
        for (ComponentDescriptor descriptor : resolved) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", descriptor.id());
            item.put("version", descriptor.version());
            item.put("kind", descriptor.kind().name());
            item.put("executorType", descriptor.executorType());
            item.put("isolationLevel", descriptor.isolationLevel().name());
            item.put("requiredFeatures", descriptor.requiredFeatures().stream().map(Enum::name).sorted().toList());
            item.put("capabilities", descriptor.capabilities().stream().sorted().toList());
            item.put("configSchema", descriptor.configSchema());
            item.put("dependencies", descriptor.dependencies().stream()
                    .sorted(java.util.Comparator.comparing(ComponentDescriptor.Dependency::componentId))
                    .map(d -> Map.of("componentId", d.componentId(), "version", d.version()))
                    .toList());
            locked.add(item);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "wayang.component-lock");
        document.put("formatVersion", 1);
        document.put("profile", profile.name());
        document.put("roots", List.copyOf(roots));
        document.put("components", locked);
        String digest = sha256(Json.write(canonical(document)));
        document.put("sha256", digest);
        return new Result(Json.write(canonical(document)), digest, resolved.stream().map(ComponentDescriptor::id).toList());
    }

    /** Sort object keys recursively; Map.of iteration order is intentionally unspecified by the JDK. */
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonical(nested)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ComponentLockfile::canonical).toList();
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
