package tech.kayys.wayang.spike;

/** Test fixture proving discovery through the real Java ServiceLoader mechanism. */
public final class ServiceLoaderPressureEventAdapterProvider implements PressureEventAdapterProvider {
    public static final String ID = "test.service-loader";
    public static final String VERSION = "1.0.0";

    public ServiceLoaderPressureEventAdapterProvider() {
    }

    @Override public String id() { return ID; }
    @Override public String version() { return VERSION; }

    @Override
    public PressureEventAdapter create(PressureEventAdapterConfig config) {
        if (!ID.equals(config.providerId()) || !VERSION.equals(config.providerVersion())) {
            throw new IllegalArgumentException("Unexpected test provider identity");
        }
        return new PressureEventAdapter() {
            @Override public String providerId() { return ID; }
            @Override public String providerVersion() { return VERSION; }
            @Override public void onPressureTransition(EvaluationPressureEvent event) { }
            @Override public void close() { }
        };
    }
}
