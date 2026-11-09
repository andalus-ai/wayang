package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComponentIdentityTest {
    @Test void readsExplicitIdentityAndDefaultsOperation() {
        ComponentIdentity identity = ComponentIdentity.fromConfiguration(Map.of(
                ComponentIdentity.ID_KEY, "wayang.connector.http",
                ComponentIdentity.VERSION_KEY, "2.1.0"));
        assertEquals("wayang.connector.http", identity.id());
        assertEquals("2.1.0", identity.version());
        assertEquals("execute", identity.operation());
    }

    @Test void leavesLegacyNodesUnbound() {
        assertNull(ComponentIdentity.fromConfiguration(Map.of("url", "https://example.test")));
    }

    @Test void rejectsPartialAndMalformedBindings() {
        assertThrows(IllegalArgumentException.class, () -> ComponentIdentity.fromConfiguration(
                Map.of(ComponentIdentity.VERSION_KEY, "1.0")));
        assertThrows(IllegalArgumentException.class, () -> ComponentIdentity.fromConfiguration(
                Map.of(ComponentIdentity.ID_KEY, "Not A Component")));
        assertThrows(IllegalArgumentException.class, () -> ComponentIdentity.fromConfiguration(
                Map.of(ComponentIdentity.ID_KEY, 42)));
    }

    @Test void rejectsOperationWithoutIdentityAndOversizedOperation() {
        assertThrows(IllegalArgumentException.class, () -> ComponentIdentity.fromConfiguration(
                Map.of(ComponentIdentity.OPERATION_KEY, "execute")));
        assertThrows(IllegalArgumentException.class, () -> new ComponentIdentity(
                "wayang.connector.http", null, "x".repeat(129)));
    }
}
