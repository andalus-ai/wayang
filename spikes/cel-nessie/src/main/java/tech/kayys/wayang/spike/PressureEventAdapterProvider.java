package tech.kayys.wayang.spike;

/**
 * Service-provider contract for optional pressure-event integrations.
 *
 * <p>Implementations belong in host or extension modules, not in the CEL core.
 * Provider creation may allocate resources; the returned adapter is then
 * owned and closed by the caller.</p>
 */
public interface PressureEventAdapterProvider {
    /** Stable, namespaced identifier; do not use class names as identifiers. */
    String id();

    /** Exact provider version, selected without fallback. */
    String version();

    PressureEventAdapter create(PressureEventAdapterConfig config);
}
