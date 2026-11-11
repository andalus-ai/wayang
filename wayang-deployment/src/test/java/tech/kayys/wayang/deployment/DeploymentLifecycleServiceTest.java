package tech.kayys.wayang.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeploymentLifecycleServiceTest {
    private InMemoryDeploymentLedger ledger;
    private DeploymentLifecycleService lifecycle;

    @BeforeEach void setUp() {
        ledger = new InMemoryDeploymentLedger();
        lifecycle = new DeploymentLifecycleService(ledger,
                Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC));
        lifecycle.registerInstalled("dep-1", "tenant-a", "prod", "runtime-1", "release-42",
                "publisher", "verified package installed", "corr-1", "install-1");
    }

    @Test void recordsActivationAndHealthAsSeparateEvents() {
        var activating = lifecycle.beginActivation("dep-1", 0, "operator", "activate requested", "corr-2", "activate-1");
        assertEquals(DeploymentState.ACTIVATING, activating.state());
        var active = lifecycle.recordHealth("dep-1", 1, HealthOutcome.HEALTHY,
                "health-agent", "readiness and liveness passed", "corr-2", "health-1");
        assertEquals(DeploymentState.ACTIVE, active.state());
        assertEquals(2, active.version());
        assertEquals(3, ledger.events("dep-1").size());
        assertEquals(DeploymentState.ACTIVATING, ledger.events("dep-1").get(2).fromState());
    }

    @Test void unhealthyActivationMustBeRolledBackExplicitly() {
        lifecycle.beginActivation("dep-1", 0, "operator", "activate", "corr", "a1");
        var failed = lifecycle.recordHealth("dep-1", 1, HealthOutcome.UNHEALTHY,
                "health-agent", "readiness failed", "corr", "h1");
        assertEquals(DeploymentState.FAILED, failed.state());
        var rollingBack = lifecycle.beginRollback("dep-1", 2, "operator", "restore previous revision", "corr", "rb1");
        var rolledBack = lifecycle.completeRollback("dep-1", 3, "operator", "previous revision restored", "corr", "rb2");
        assertEquals(DeploymentState.ROLLED_BACK, rolledBack.state());
        assertEquals(4, rollingBack.version() + 1);
    }

    @Test void rejectsIllegalTransitionsAndStaleVersions() {
        assertThrows(IllegalStateException.class, () -> lifecycle.recordHealth("dep-1", 0,
                HealthOutcome.HEALTHY, "agent", "not activating", "corr", "health"));
        lifecycle.beginActivation("dep-1", 0, "operator", "activate", "corr", "a1");
        assertThrows(IllegalStateException.class, () -> lifecycle.beginRollback("dep-1", 0,
                "operator", "stale", "corr", "rb"));
        assertThrows(IllegalStateException.class, () -> lifecycle.beginRollback("dep-1", 1,
                "operator", "cannot rollback while activating", "corr", "rb2"));
    }

    @Test void degradedActivationIsNotReportedAsActive() {
        lifecycle.beginActivation("dep-1", 0, "operator", "activate", "corr", "a1");
        var degraded = lifecycle.recordHealth("dep-1", 1, HealthOutcome.DEGRADED,
                "health-agent", "optional dependency unavailable", "corr", "h1");
        assertEquals(DeploymentState.DEGRADED, degraded.state());
    }

    @Test void refusesDuplicateDeploymentAndIdempotencyKey() {
        assertThrows(IllegalStateException.class, () -> lifecycle.registerInstalled("dep-1", "tenant-a", "prod",
                "runtime-1", "release-43", "publisher", "duplicate", "corr", "install-2"));
        lifecycle.beginActivation("dep-1", 0, "operator", "activate", "corr", "a1");
        assertThrows(IllegalStateException.class, () -> lifecycle.recordHealth("dep-1", 1, HealthOutcome.HEALTHY,
                "agent", "health", "corr", "a1"));
    }
}
