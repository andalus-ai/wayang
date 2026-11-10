package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PressureEventAdapterServiceLoaderTest {
    @Test
    void discoversTestProviderUsingServiceLoaderAndCreatesExactVersion() {
        var registry = PressureEventAdapterRegistry.load(getClass().getClassLoader());

        assertEquals(1, registry.size());
        var adapter = registry.create(new PressureEventAdapterConfig(
                ServiceLoaderPressureEventAdapterProvider.ID,
                ServiceLoaderPressureEventAdapterProvider.VERSION,
                Map.of()));

        assertNotNull(adapter);
        assertEquals(ServiceLoaderPressureEventAdapterProvider.ID, adapter.providerId());
        assertEquals(ServiceLoaderPressureEventAdapterProvider.VERSION, adapter.providerVersion());
        adapter.close();
    }
}
