package tech.kayys.wayang.deployment;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeploymentCommandTest {
    @Test void commandParametersAreDefensivelyCopied() {
        var parameters = new java.util.HashMap<String, String>();
        parameters.put("channel", "stable");
        var command = new DeploymentCommand("dep", "tenant", "prod", "target", "release", "key", parameters);
        parameters.put("channel", "untrusted-change");
        assertEquals("stable", command.parameters().get("channel"));
        assertThrows(UnsupportedOperationException.class, () -> command.parameters().put("x", "y"));
    }

    @Test void commandRequiresScopeAndIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentCommand("dep", " ", "prod", "target", "release", "key", Map.of()));
    }
}
