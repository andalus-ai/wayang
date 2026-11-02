package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.Extensions;
import tech.kayys.wayang.runtime.Profile;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WayangExtension;

class ExtensionConnectorsTest {

    private static WayangExtension ext(String connector) {
        return new WayangExtension() {
            public String id() {
                return "sample";
            }

            public Map<String, ServiceHandler.Connector> connectors() {
                return Map.of(connector, in -> Map.of("echo", in));
            }
        };
    }

    @Test
    void extensionConnectorsJoinTheBuiltIns() throws Exception {
        var all = Connectors.defaults(UrlGuard.strict(), Extensions.of(List.of(ext("comfyui")), Profile.FULL));
        assertTrue(all.containsKey("http") && all.containsKey("telegram"));
        assertEquals(Map.of("echo", Map.of("a", 1)), all.get("comfyui").call(Map.of("a", 1)));
    }

    @Test
    void anExtensionCannotReplaceABuiltIn() {
        assertThrows(IllegalStateException.class,
                () -> Connectors.defaults(UrlGuard.strict(), Extensions.of(List.of(ext("telegram")), Profile.FULL)));
    }
}
