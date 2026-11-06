package tech.kayys.wayang.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extension point, discovered with {@link java.util.ServiceLoader}. An extension contributes connectors, trigger
 * pollers, webhook providers and handlers for custom executor types (use a SERVICE_TASK with
 * {@code executorType} in its config) without touching Wayang itself. Names must not collide with built-ins or
 * other extensions; collisions fail at start-up.
 */
public interface WayangExtension {

    /** Stable identifier, used in diagnostics. */
    String id();

    /** Features that must be enabled in the profile for this extension to load; empty means always. */
    default Set<Profile.Feature> requires() {
        return Set.of();
    }

    default Map<String, ServiceHandler.Connector> connectors() {
        return Map.of();
    }

    default Map<String, Poller> pollers() {
        return Map.of();
    }

    default Map<String, WebhookProvider> webhookProviders() {
        return Map.of();
    }

    default List<DomainHandler> handlers() {
        return List.of();
    }

    /**
     * Metadata catalog contributed by this extension. Descriptors do not replace
     * executable handlers; they describe selectable components for authoring and
     * release-time dependency resolution.
     */
    default List<ComponentDescriptor> components() {
        return List.of();
    }
}
