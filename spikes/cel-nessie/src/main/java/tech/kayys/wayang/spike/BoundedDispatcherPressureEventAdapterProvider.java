package tech.kayys.wayang.spike;

import java.util.Objects;
import java.util.Set;

/**
 * Reference provider for a bounded, single-consumer pressure event dispatcher.
 *
 * <p>The downstream listener is supplied by the host, keeping transport and
 * telemetry dependencies outside the CEL qualification module.</p>
 */
public final class BoundedDispatcherPressureEventAdapterProvider implements PressureEventAdapterProvider {
    public static final String ID = "tech.kayys.wayang.pressure.bounded-dispatcher";
    public static final String VERSION = "1.0.0";
    public static final String CAPACITY = "capacity";
    public static final String OVERFLOW_POLICY = "overflow-policy";
    private static final Set<String> SUPPORTED_PROPERTIES = Set.of(CAPACITY, OVERFLOW_POLICY);

    private final EvaluationPressureEventListener downstream;

    public BoundedDispatcherPressureEventAdapterProvider(EvaluationPressureEventListener downstream) {
        this.downstream = Objects.requireNonNull(downstream, "downstream");
    }

    @Override public String id() { return ID; }
    @Override public String version() { return VERSION; }

    @Override
    public PressureEventAdapter create(PressureEventAdapterConfig config) {
        Objects.requireNonNull(config, "config");
        if (!ID.equals(config.providerId()) || !VERSION.equals(config.providerVersion())) {
            throw new IllegalArgumentException("Configuration does not target this provider version");
        }
        var properties = config.properties();
        for (var key : properties.keySet()) {
            if (!SUPPORTED_PROPERTIES.contains(key)) {
                throw new IllegalArgumentException("Unsupported bounded-dispatcher property: " + key);
            }
        }
        var capacityValue = properties.get(CAPACITY);
        if (capacityValue == null) {
            throw new IllegalArgumentException("Missing required property: " + CAPACITY);
        }
        final int capacity;
        try {
            capacity = Integer.parseInt(capacityValue);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Property capacity must be a positive integer");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("Property capacity must be a positive integer");
        }
        var overflowValue = properties.getOrDefault(OVERFLOW_POLICY, "REJECT_NEWEST");
        final BoundedPressureEventDispatcher.OverflowPolicy overflowPolicy;
        try {
            overflowPolicy = BoundedPressureEventDispatcher.OverflowPolicy.valueOf(overflowValue);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unsupported bounded-dispatcher overflow policy");
        }
        var dispatcher = new BoundedPressureEventDispatcher(capacity, overflowPolicy, downstream);
        return new BoundedDispatcherPressureEventAdapter(ID, VERSION, dispatcher);
    }
}
