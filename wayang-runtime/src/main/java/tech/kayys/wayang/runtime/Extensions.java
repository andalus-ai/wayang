package tech.kayys.wayang.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/** The loaded {@link WayangExtension}s of a deployment, filtered by profile. */
public final class Extensions {

    private static final Extensions NONE = new Extensions(List.of());

    private final List<WayangExtension> loaded;

    private Extensions(List<WayangExtension> loaded) {
        this.loaded = List.copyOf(loaded);
    }

    public static Extensions none() {
        return NONE;
    }

    public static Extensions of(List<WayangExtension> extensions, Profile profile) {
        List<WayangExtension> ok = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (WayangExtension e : extensions) {
            if (!ids.add(e.id())) {
                throw new IllegalStateException("Two extensions with id '" + e.id() + "'");
            }
            if (profile.features().containsAll(e.requires())) {
                ok.add(e);
            }
        }
        return new Extensions(ok);
    }

    public static Extensions load(Profile profile) {
        return load(Extensions.class.getClassLoader(), profile);
    }

    public static Extensions load(ClassLoader cl, Profile profile) {
        List<WayangExtension> all = new ArrayList<>();
        ServiceLoader.load(WayangExtension.class, cl).forEach(all::add);
        return of(all, profile);
    }

    public List<String> ids() {
        return loaded.stream().map(WayangExtension::id).toList();
    }

    public Map<String, ServiceHandler.Connector> mergeConnectors(Map<String, ServiceHandler.Connector> base) {
        return merge(base, "connector", WayangExtension::connectors);
    }

    public Map<String, Poller> mergePollers(Map<String, Poller> base) {
        return merge(base, "poller", WayangExtension::pollers);
    }

    public Map<String, WebhookProvider> mergeWebhookProviders(Map<String, WebhookProvider> base) {
        return merge(base, "webhook provider", WayangExtension::webhookProviders);
    }

    public List<DomainHandler> handlers() {
        List<DomainHandler> out = new ArrayList<>();
        for (WayangExtension e : loaded) {
            out.addAll(e.handlers());
        }
        return out;
    }

    private <V> Map<String, V> merge(Map<String, V> base, String what,
            java.util.function.Function<WayangExtension, Map<String, V>> get) {
        Map<String, V> m = new LinkedHashMap<>(base);
        for (WayangExtension e : loaded) {
            for (Map.Entry<String, V> en : get.apply(e).entrySet()) {
                if (m.putIfAbsent(en.getKey(), en.getValue()) != null) {
                    throw new IllegalStateException("Extension '" + e.id() + "' redefines " + what + " '"
                            + en.getKey() + "'");
                }
            }
        }
        return m;
    }
}
