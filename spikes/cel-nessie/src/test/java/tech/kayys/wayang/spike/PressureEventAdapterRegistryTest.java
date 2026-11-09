package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PressureEventAdapterRegistryTest {
    private static final String ID = "example.metrics";
    private static final String VERSION = "1.2.0";

    private static final class TestAdapter implements PressureEventAdapter {
        private final String id;
        private final String version;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TestAdapter(String id, String version) {
            this.id = id;
            this.version = version;
        }

        @Override public String providerId() { return id; }
        @Override public String providerVersion() { return version; }
        @Override public void onPressureTransition(EvaluationPressureEvent event) { }
        @Override public void close() { closed.set(true); }
    }

    private static PressureEventAdapterProvider provider(String id, String version,
            java.util.function.Function<PressureEventAdapterConfig, PressureEventAdapter> factory) {
        return new PressureEventAdapterProvider() {
            @Override public String id() { return id; }
            @Override public String version() { return version; }
            @Override public PressureEventAdapter create(PressureEventAdapterConfig config) {
                return factory.apply(config);
            }
        };
    }

    @Test
    void selectsExactProviderAndCreatesAdapter() {
        var expected = new TestAdapter(ID, VERSION);
        var registry = PressureEventAdapterRegistry.of(List.of(
                provider(ID, "2.0.0", config -> new TestAdapter(ID, "2.0.0")),
                provider(ID, VERSION, config -> expected)));

        var adapter = registry.create(new PressureEventAdapterConfig(ID, VERSION, Map.of("endpoint", "local")));

        assertSame(expected, adapter);
        assertEquals(2, registry.size());
    }

    @Test
    void doesNotFallbackToAnotherVersion() {
        var registry = PressureEventAdapterRegistry.of(List.of(
                provider(ID, "2.0.0", config -> new TestAdapter(ID, "2.0.0"))));

        assertThrows(IllegalArgumentException.class, () -> registry.require(ID, VERSION));
    }

    @Test
    void rejectsDuplicateProviderIdentity() {
        var first = provider(ID, VERSION, config -> new TestAdapter(ID, VERSION));
        var second = provider(ID, VERSION, config -> new TestAdapter(ID, VERSION));

        assertThrows(IllegalArgumentException.class,
                () -> PressureEventAdapterRegistry.of(List.of(first, second)));
    }

    @Test
    void closesAdapterWhenFactoryReturnsMismatchedIdentity() {
        var wrong = new TestAdapter("wrong.adapter", VERSION);
        var registry = PressureEventAdapterRegistry.of(List.of(
                provider(ID, VERSION, config -> wrong)));

        assertThrows(IllegalStateException.class,
                () -> registry.create(new PressureEventAdapterConfig(ID, VERSION, Map.of())));
        assertTrue(wrong.closed.get());
    }

    @Test
    void configDefensivelyCopiesAndFreezesProperties() {
        var properties = new HashMap<String, String>();
        properties.put("capacity", "32");
        var config = new PressureEventAdapterConfig(ID, VERSION, properties);
        properties.put("capacity", "999");

        assertEquals("32", config.properties().get("capacity"));
        assertThrows(UnsupportedOperationException.class,
                () -> config.properties().put("other", "value"));
    }

    @Test
    void rejectsInvalidProviderIdentityAndProperties() {
        assertThrows(IllegalArgumentException.class,
                () -> new PressureEventAdapterConfig(" ", VERSION, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PressureEventAdapterConfig(ID, VERSION, Map.of(" ", "value")));
        assertThrows(IllegalArgumentException.class,
                () -> PressureEventAdapterRegistry.of(List.of(
                        provider(ID, " ", config -> new TestAdapter(ID, VERSION)))));
    }

    @Test
    void rejectsNullAdapterFromProvider() {
        var registry = PressureEventAdapterRegistry.of(List.of(provider(ID, VERSION, config -> null)));
        assertThrows(NullPointerException.class,
                () -> registry.create(new PressureEventAdapterConfig(ID, VERSION, Map.of())));
    }
}
