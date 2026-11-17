package tech.kayys.wayang.deployment.quarkus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeploymentLifecycleReconciliationReadinessCheckTest {
    @Test
    void disabledFeatureDoesNotAffectApplicationReadiness() {
        assertTrue(DeploymentLifecycleReconciliationReadinessCheck.isReady(false, false));
        assertTrue(DeploymentLifecycleReconciliationReadinessCheck.isReady(false, true));
    }

    @Test
    void enabledFeatureRequiresResolvableWorker() {
        assertFalse(DeploymentLifecycleReconciliationReadinessCheck.isReady(true, false));
        assertTrue(DeploymentLifecycleReconciliationReadinessCheck.isReady(true, true));
    }
}
