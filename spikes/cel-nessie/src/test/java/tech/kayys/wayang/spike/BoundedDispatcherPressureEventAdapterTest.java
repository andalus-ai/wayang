package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedDispatcherPressureEventAdapterTest {
    private static EvaluationPressureEvent event() {
        return new EvaluationPressureEvent(Instant.parse("2026-01-01T00:00:00Z"),
                EvaluationPressureAssessment.Level.NORMAL,
                EvaluationPressureAssessment.Level.ELEVATED,
                EvaluationPressureAssessment.Level.ELEVATED,
                List.of());
    }

    @Test
    void registryCreatesAdapterThatDeliversAndDrainsAcceptedEvents() throws Exception {
        var delivered = new CountDownLatch(1);
        EvaluationPressureEventListener sink = ignored -> delivered.countDown();
        var provider = new BoundedDispatcherPressureEventAdapterProvider(sink);
        var registry = PressureEventAdapterRegistry.of(List.of(provider));
        var config = new PressureEventAdapterConfig(provider.id(), provider.version(),
                Map.of(BoundedDispatcherPressureEventAdapterProvider.CAPACITY, "8"));

        var adapter = assertInstanceOf(BoundedDispatcherPressureEventAdapter.class, registry.create(config));
        adapter.onPressureTransition(event());
        adapter.close();

        assertTrue(delivered.await(2, TimeUnit.SECONDS));
        assertTrue(adapter.awaitTermination(Duration.ofSeconds(2)));
        assertTrue(adapter.snapshot().closed());
        assertEquals(1, adapter.snapshot().accepted());
        assertEquals(1, adapter.snapshot().delivered());
    }

    @Test
    void supportsExplicitDropOldestPolicy() {
        var provider = new BoundedDispatcherPressureEventAdapterProvider(EvaluationPressureEventListener.NOOP);
        var adapter = provider.create(new PressureEventAdapterConfig(provider.id(), provider.version(),
                Map.of("capacity", "2", "overflow-policy", "DROP_OLDEST")));

        assertInstanceOf(BoundedDispatcherPressureEventAdapter.class, adapter);
        adapter.close();
    }

    @Test
    void rejectsMissingOrInvalidCapacity() {
        var provider = new BoundedDispatcherPressureEventAdapterProvider(EvaluationPressureEventListener.NOOP);
        assertThrows(IllegalArgumentException.class, () -> provider.create(
                new PressureEventAdapterConfig(provider.id(), provider.version(), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> provider.create(
                new PressureEventAdapterConfig(provider.id(), provider.version(), Map.of("capacity", "0"))));
        assertThrows(IllegalArgumentException.class, () -> provider.create(
                new PressureEventAdapterConfig(provider.id(), provider.version(), Map.of("capacity", "NaN"))));
    }

    @Test
    void rejectsUnknownPropertiesAndOverflowPolicy() {
        var provider = new BoundedDispatcherPressureEventAdapterProvider(EvaluationPressureEventListener.NOOP);
        assertThrows(IllegalArgumentException.class, () -> provider.create(
                new PressureEventAdapterConfig(provider.id(), provider.version(),
                        Map.of("capacity", "4", "endpoint", "not-supported"))));
        assertThrows(IllegalArgumentException.class, () -> provider.create(
                new PressureEventAdapterConfig(provider.id(), provider.version(),
                        Map.of("capacity", "4", "overflow-policy", "BLOCK"))));
    }

    @Test
    void rejectsMismatchedProviderVersion() {
        var provider = new BoundedDispatcherPressureEventAdapterProvider(EvaluationPressureEventListener.NOOP);
        assertThrows(IllegalArgumentException.class, () -> provider.create(
                new PressureEventAdapterConfig(provider.id(), "2.0.0", Map.of("capacity", "4"))));
    }
}
