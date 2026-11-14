package tech.kayys.wayang.deployment;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeploymentOrchestrationServiceTest {
    private InMemoryDeploymentLedger ledger;
    private DeploymentLifecycleService lifecycle;
    private DeploymentTargetRegistry registry;
    private DeploymentOrchestrationService orchestration;
    private final Instant now = Instant.parse("2026-11-14T00:00:00Z");

    @BeforeEach void setUp() {
        ledger = new InMemoryDeploymentLedger();
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        lifecycle = new DeploymentLifecycleService(ledger, clock);
        lifecycle.registerInstalled("dep-1", "tenant-a", "prod", "target-a", "release-1",
                "publisher", "verified package installed", "corr-install", "install-1");
        registry = new DeploymentTargetRegistry();
        orchestration = new DeploymentOrchestrationService(ledger, lifecycle, registry, clock);
    }

    @Test void activatesAndPromotesOnlyAfterHealthyReadiness() {
        registry.register("target-a", target(TargetOperationResult.Status.SUCCEEDED,
                new HealthCheckResult(HealthOutcome.HEALTHY, Map.of("ready", true, "live", true), "all checks passed", now)));
        DeploymentExecutionResult result = orchestration.activate(command("activate-1"), "operator",
                "activate release", "corr-activate", Duration.ofSeconds(30));
        assertEquals(DeploymentState.ACTIVE, result.snapshot().state());
        assertEquals(2, result.snapshot().version());
        assertEquals(HealthOutcome.HEALTHY, result.healthCheck().outcome());
        assertEquals(3, ledger.events("dep-1").size());
    }

    @Test void activationFailureIsRecordedAndSkipsHealthCheck() {
        registry.register("target-a", target(TargetOperationResult.Status.REJECTED, null));
        DeploymentExecutionResult result = orchestration.activate(command("activate-2"), "operator",
                "activate release", "corr-activate", Duration.ofSeconds(30));
        assertEquals(DeploymentState.FAILED, result.snapshot().state());
        assertNull(result.healthCheck());
        assertEquals(2, ledger.events("dep-1").size());
    }

    @Test void degradedReadinessIsNotPromotedToActive() {
        registry.register("target-a", target(TargetOperationResult.Status.SUCCEEDED,
                new HealthCheckResult(HealthOutcome.DEGRADED, Map.of("ready", true, "dependency", false),
                        "optional dependency unavailable", now)));
        DeploymentExecutionResult result = orchestration.activate(command("activate-3"), "operator",
                "activate release", "corr-activate", Duration.ofSeconds(30));
        assertEquals(DeploymentState.DEGRADED, result.snapshot().state());
    }

    @Test void unknownHealthOutcomeFailsClosed() {
        registry.register("target-a", target(TargetOperationResult.Status.SUCCEEDED,
                new HealthCheckResult(HealthOutcome.UNKNOWN, Map.of(), "probe inconclusive", now)));
        DeploymentExecutionResult result = orchestration.activate(command("activate-4"), "operator",
                "activate release", "corr-activate", Duration.ofSeconds(30));
        assertEquals(DeploymentState.FAILED, result.snapshot().state());
    }

    @Test void rejectsScopeMismatchBeforeCallingTarget() {
        DeploymentTarget neverCalled = new DeploymentTarget() {
            @Override public TargetOperationResult activate(DeploymentCommand c, Duration t) {
                fail("target must not be called"); return null;
            }
            @Override public TargetOperationResult deactivate(DeploymentCommand c, Duration t) { return null; }
            @Override public TargetOperationResult rollback(DeploymentCommand c, Duration t) { return null; }
            @Override public HealthCheckResult healthCheck(DeploymentCommand c, Duration t) { return null; }
        };
        registry.register("target-a", neverCalled);
        DeploymentCommand wrongTenant = new DeploymentCommand("dep-1", "tenant-b", "prod", "target-a",
                "release-1", "activate-5", Map.of());
        assertThrows(SecurityException.class, () -> orchestration.activate(wrongTenant, "operator",
                "activate release", "corr", Duration.ofSeconds(30)));
        assertEquals(DeploymentState.INSTALLED, ledger.find("dep-1").orElseThrow().state());
    }

    @Test void rejectsMissingTargetAndInvalidTimeout() {
        assertThrows(IllegalArgumentException.class, () -> orchestration.activate(command("activate-6"),
                "operator", "activate", "corr", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> orchestration.activate(command("activate-7"),
                "operator", "activate", "corr", Duration.ofMinutes(11)));
        assertThrows(IllegalArgumentException.class, () -> orchestration.activate(command("activate-8"),
                "operator", "activate", "corr", Duration.ofSeconds(5)));
        assertEquals(DeploymentState.INSTALLED, ledger.find("dep-1").orElseThrow().state());
    }

    private DeploymentCommand command(String key) {
        return new DeploymentCommand("dep-1", "tenant-a", "prod", "target-a", "release-1", key, Map.of());
    }
    private DeploymentTarget target(TargetOperationResult.Status activationStatus, HealthCheckResult health) {
        return new DeploymentTarget() {
            @Override public TargetOperationResult activate(DeploymentCommand command, Duration timeout) {
                return new TargetOperationResult(activationStatus, command.idempotencyKey() + ":activate",
                        activationStatus.name(), now);
            }
            @Override public TargetOperationResult deactivate(DeploymentCommand command, Duration timeout) {
                return new TargetOperationResult(TargetOperationResult.Status.SUCCEEDED,
                        command.idempotencyKey() + ":deactivate", "deactivated", now);
            }
            @Override public TargetOperationResult rollback(DeploymentCommand command, Duration timeout) {
                return new TargetOperationResult(TargetOperationResult.Status.SUCCEEDED,
                        command.idempotencyKey() + ":rollback", "rolled back", now);
            }
            @Override public HealthCheckResult healthCheck(DeploymentCommand command, Duration timeout) { return health; }
        };
    }
}
