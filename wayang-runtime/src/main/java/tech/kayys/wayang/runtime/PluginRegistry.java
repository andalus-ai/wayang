package tech.kayys.wayang.runtime;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable catalog over discovered plugin packages; not an installer or class loader. */
public final class PluginRegistry {
    private final Map<String, PluginPackage> plugins;
    private final Map<String, String> componentOwners;

    private PluginRegistry(Map<String, PluginPackage> plugins, Map<String, String> owners) {
        this.plugins = Map.copyOf(plugins);
        this.componentOwners = Map.copyOf(owners);
    }

    public static PluginRegistry empty() { return of(List.of()); }

    public static PluginRegistry of(Collection<PluginPackage> packages) {
        Objects.requireNonNull(packages, "packages");
        Map<String, PluginPackage> indexed = new LinkedHashMap<>();
        Map<String, String> owners = new LinkedHashMap<>();
        packages.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(p -> p.manifest().id()))
                .forEach(plugin -> {
                    String id = plugin.manifest().id();
                    if (indexed.putIfAbsent(id, plugin) != null)
                        throw new PluginResolutionException("Duplicate plugin id '" + id + "'");
                    for (ComponentDescriptor descriptor : plugin.components()) {
                        String previous = owners.putIfAbsent(descriptor.id(), id);
                        if (previous != null)
                            throw new PluginResolutionException("Component '" + descriptor.id()
                                    + "' is contributed by both plugin '" + previous + "' and plugin '" + id + "'");
                    }
                });
        PluginRegistry registry = new PluginRegistry(indexed, owners);
        registry.validateDependencies();
        return registry;
    }

    private void validateDependencies() {
        for (PluginPackage plugin : plugins.values()) {
            for (PluginManifest.Dependency dependency : plugin.manifest().dependencies()) {
                PluginPackage target = plugins.get(dependency.id());
                if (target == null) {
                    if (!dependency.optional())
                        throw new PluginResolutionException("Plugin '" + plugin.manifest().id()
                                + "' requires missing plugin '" + dependency.id() + "'");
                } else if (!VersionRange.matches(target.manifest().version(), dependency.versionRange())) {
                    throw new PluginResolutionException("Plugin '" + plugin.manifest().id() + "' requires '"
                            + dependency.id() + "' " + dependency.versionRange() + " but installed version is "
                            + target.manifest().version());
                }
            }
        }
    }

    public PluginPackage find(String id) { return plugins.get(id); }
    public Set<String> ids() { return plugins.keySet(); }
    public String ownerOf(String componentId) { return componentOwners.get(componentId); }
    public List<PluginPackage> all() {
        return plugins.values().stream().sorted(Comparator.comparing(p -> p.manifest().id())).toList();
    }

    /** Resolve requested plugins and installed dependencies in deterministic dependency-first order. */
    public List<PluginPackage> resolve(Collection<String> requestedIds) {
        Objects.requireNonNull(requestedIds, "requestedIds");
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String id : requestedIds) {
            if (id == null || id.isBlank()) throw new PluginResolutionException("Requested plugin id must not be blank");
            visit(id.trim(), ordered, visiting);
        }
        return ordered.stream().map(plugins::get).toList();
    }

    private void visit(String id, LinkedHashSet<String> ordered, Set<String> visiting) {
        if (ordered.contains(id)) return;
        PluginPackage plugin = plugins.get(id);
        if (plugin == null) throw new PluginResolutionException("Plugin '" + id + "' is not installed");
        if (!visiting.add(id))
            throw new PluginResolutionException("Plugin dependency cycle detected: "
                    + String.join(" -> ", visiting) + " -> " + id);
        for (PluginManifest.Dependency dependency : plugin.manifest().dependencies())
            if (plugins.containsKey(dependency.id())) visit(dependency.id(), ordered, visiting);
        visiting.remove(id);
        ordered.add(id);
    }

    public record PluginPackage(PluginManifest manifest, List<ComponentDescriptor> components) {
        public PluginPackage {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(components, "components");
            components = PluginManifestValidator.validateComponents(manifest, components);
        }
    }

    /** Bounded support: exact, *, ^, ~, and numeric comparator conjunctions. */
    static final class VersionRange {
        private VersionRange() { }
        static boolean matches(String version, String expression) {
            String candidate = normalize(version);
            String range = expression.trim();
            if (range.equals("*") || range.equalsIgnoreCase("x")) return true;
            if (range.startsWith("^") || range.startsWith("~")) {
                int[] base = parts(range.substring(1)), actual = parts(candidate);
                if (compare(actual, base) < 0) return false;
                int[] upper = base.clone();
                if (range.charAt(0) == '^') {
                    if (base[0] > 0) { upper[0]++; upper[1] = 0; upper[2] = 0; }
                    else if (base[1] > 0) { upper[1]++; upper[2] = 0; }
                    else upper[2]++;
                } else { upper[1]++; upper[2] = 0; }
                return compare(actual, upper) < 0;
            }
            if (range.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
                return compare(parts(candidate), parts(range)) == 0;
            for (String token : range.split("[ ,]+")) {
                if (token.isBlank()) continue;
                String op;
                if (token.startsWith(">=") || token.startsWith("<=")) op = token.substring(0, 2);
                else if (token.startsWith(">") || token.startsWith("<") || token.startsWith("=")) op = token.substring(0, 1);
                else throw new PluginResolutionException("Unsupported plugin version range '" + expression + "'");
                int c = compare(parts(candidate), parts(token.substring(op.length())));
                boolean ok = switch (op) {
                    case ">=" -> c >= 0; case "<=" -> c <= 0; case ">" -> c > 0;
                    case "<" -> c < 0; case "=" -> c == 0; default -> false;
                };
                if (!ok) return false;
            }
            return true;
        }
        private static String normalize(String value) {
            if (value == null || !value.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?"))
                throw new PluginResolutionException("Unsupported semantic version '" + value + "'");
            return value.split("[-+]", 2)[0];
        }
        private static int[] parts(String value) {
            String[] p = normalize(value).split("\\.");
            return new int[] { Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]) };
        }
        private static int compare(int[] a, int[] b) {
            for (int i = 0; i < 3; i++) { int c = Integer.compare(a[i], b[i]); if (c != 0) return c; }
            return 0;
        }
    }

    public static final class PluginResolutionException extends IllegalArgumentException {
        public PluginResolutionException(String message) { super(message); }
    }
}
