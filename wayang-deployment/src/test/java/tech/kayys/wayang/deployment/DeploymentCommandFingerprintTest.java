package tech.kayys.wayang.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import java.util.Map;

class DeploymentCommandFingerprintTest {
    @Test void parameterMapOrderDoesNotChangeFingerprint() {
        var first = new DeploymentCommand("dep", "tenant", "prod", "target", "release", "key",
                Map.of("z", "last", "a", "first"));
        var second = new DeploymentCommand("dep", "tenant", "prod", "target", "release", "key",
                Map.of("a", "first", "z", "last"));
        assertEquals(DeploymentCommandFingerprint.of("ACTIVATE", first),
                DeploymentCommandFingerprint.of("ACTIVATE", second));
    }

    @Test void operationTypeChangesFingerprint() {
        var command = new DeploymentCommand("dep", "tenant", "prod", "target", "release", "key", Map.of());
        org.junit.jupiter.api.Assertions.assertNotEquals(DeploymentCommandFingerprint.of("ACTIVATE", command),
                DeploymentCommandFingerprint.of("ROLLBACK", command));
    }
}
