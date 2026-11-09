package tech.kayys.wayang.spike;

/**
 * Host-owned lifecycle boundary for a pressure-event integration.
 *
 * <p>Construction through a provider activates the adapter. The host that
 * creates it is responsible for closing it during application shutdown.</p>
 */
public interface PressureEventAdapter extends EvaluationPressureEventListener, AutoCloseable {
    /** Stable, namespaced provider identifier. */
    String providerId();

    /** Exact provider version used to create this adapter. */
    String providerVersion();

    /** Stop accepting work and release adapter-owned resources. */
    @Override
    void close();
}
