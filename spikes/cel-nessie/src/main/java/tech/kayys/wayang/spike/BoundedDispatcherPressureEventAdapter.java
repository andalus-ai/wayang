package tech.kayys.wayang.spike;

import java.time.Duration;
import java.util.Objects;

/** Reference SPI adapter backed by the bounded asynchronous dispatcher. */
public final class BoundedDispatcherPressureEventAdapter implements PressureEventAdapter {
    private final String providerId;
    private final String providerVersion;
    private final BoundedPressureEventDispatcher dispatcher;

    BoundedDispatcherPressureEventAdapter(String providerId, String providerVersion,
            BoundedPressureEventDispatcher dispatcher) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.providerVersion = Objects.requireNonNull(providerVersion, "providerVersion");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override public String providerId() { return providerId; }
    @Override public String providerVersion() { return providerVersion; }

    @Override
    public void onPressureTransition(EvaluationPressureEvent event) {
        dispatcher.onPressureTransition(event);
    }

    @Override
    public void close() {
        dispatcher.close();
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        return dispatcher.awaitTermination(timeout);
    }

    public BoundedPressureEventDispatcher.Snapshot snapshot() {
        return dispatcher.snapshot();
    }
}
