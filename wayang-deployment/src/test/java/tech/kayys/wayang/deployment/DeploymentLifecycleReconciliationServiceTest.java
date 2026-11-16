package tech.kayys.wayang.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeploymentLifecycleReconciliationServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");

    @Test
    void appliesSuccessfulActivationOnlyAfterHealthyReadinessAndIsIdempotent() {
        var ledger = ledger();
        var lifecycle = lifecycle(ledger);
        var command = command("activate-1");
        lifecycle.beginActivation("dep-1", 0, "operator", "activate", "corr", eventKey("activate-1", "begin"));
        var targetRegistry = new DeploymentTargetRegistry();
        targetRegistry.register("target-a", target(HealthOutcome.HEALTHY));
        var service = new DeploymentLifecycleReconciliationService(ledger, lifecycle, targetRegistry, clock());
        var receipt = receipt(command, "ACTIVATE", DeploymentOperationStatus.SUCCEEDED, "SUCCEEDED");

        var reconciled = service.reconcile(receipt, command, "operator", "activation completed", "corr",
                Duration.ofSeconds(20));
        var replay = service.reconcile(receipt, command, "operator", "activation completed", "corr",
                Duration.ofSeconds(20));

        assertEquals(DeploymentLifecycleReconciliationResult.Status.RECONCILED, reconciled.status());
        assertEquals(DeploymentState.ACTIVE, reconciled.snapshot().state());
        assertEquals(DeploymentLifecycleReconciliationResult.Status.ALREADY_RECONCILED, replay.status());
        assertEquals(DeploymentState.ACTIVE, replay.snapshot().state());
    }

    @Test
    void doesNotAdvanceLifecycleForUnknownReceipt() {
        var ledger = ledger();
        var lifecycle = lifecycle(ledger);
        var command = command("activate-unknown");
        lifecycle.beginActivation("dep-1", 0, "operator", "activate", "corr",
                eventKey("activate-unknown", "begin"));
        var registry = new DeploymentTargetRegistry();
        registry.register("target-a", target(HealthOutcome.HEALTHY));
        var service = new DeploymentLifecycleReconciliationService(ledger, lifecycle, registry, clock());

        var result = service.reconcile(receipt(command, "ACTIVATE", DeploymentOperationStatus.UNKNOWN, "OUTCOME_UNCERTAIN"),
                command, "operator", "activation", "corr", Duration.ofSeconds(20));

        assertEquals(DeploymentLifecycleReconciliationResult.Status.UNRESOLVED, result.status());
        assertEquals(DeploymentState.ACTIVATING, result.snapshot().state());
    }

    @Test
    void doesNotAssumeSuccessWhenReadinessCannotBeObserved() {
        var ledger = ledger();
        var lifecycle = lifecycle(ledger);
        var command = command("activate-no-health");
        lifecycle.beginActivation("dep-1", 0, "operator", "activate", "corr",
                eventKey("activate-no-health", "begin"));
        var registry = new DeploymentTargetRegistry();
        registry.register("target-a", target(null));
        var service = new DeploymentLifecycleReconciliationService(ledger, lifecycle, registry, clock());

        var result = service.reconcile(receipt(command, "ACTIVATE", DeploymentOperationStatus.SUCCEEDED, "SUCCEEDED"),
                command, "operator", "activation", "corr", Duration.ofSeconds(20));

        assertEquals(DeploymentLifecycleReconciliationResult.Status.WAITING_FOR_HEALTH, result.status());
        assertEquals(DeploymentState.ACTIVATING, result.snapshot().state());
    }

    @Test
    void rejectsReceiptForDifferentCommandFingerprint() {
        var ledger = ledger();
        var lifecycle = lifecycle(ledger);
        var command = command("activate-correct");
        var other = command("activate-other");
        lifecycle.beginActivation("dep-1", 0, "operator", "activate", "corr",
                eventKey("activate-correct", "begin"));
        var registry = new DeploymentTargetRegistry();
        registry.register("target-a", target(HealthOutcome.HEALTHY));
        var service = new DeploymentLifecycleReconciliationService(ledger, lifecycle, registry, clock());

        assertThrows(SecurityException.class, () -> service.reconcile(
                receipt(command, "ACTIVATE", DeploymentOperationStatus.SUCCEEDED, "SUCCEEDED"),
                other, "operator", "activation", "corr", Duration.ofSeconds(20)));
        assertEquals(DeploymentState.ACTIVATING, ledger.find("dep-1").orElseThrow().state());
    }

    private static InMemoryDeploymentLedger ledger() {
        var ledger = new InMemoryDeploymentLedger();
        lifecycle(ledger).registerInstalled("dep-1", "tenant-a", "prod", "target-a", "release-1",
                "operator", "installed", "corr-install", "install-1");
        return ledger;
    }

    private static DeploymentLifecycleService lifecycle(InMemoryDeploymentLedger ledger) {
        return new DeploymentLifecycleService(ledger, clock());
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static DeploymentCommand command(String key) {
        return new DeploymentCommand("dep-1", "tenant-a", "prod", "target-a", "release-1", key, Map.of());
    }

    private static DeploymentOperationReceipt receipt(DeploymentCommand command, String operation,
            DeploymentOperationStatus status, String resultCode) {
        return new DeploymentOperationReceipt(command.deploymentId(), command.idempotencyKey(),
                DeploymentCommandFingerprint.of(operation, command), operation, status, 1,
                "target-operation-" + command.idempotencyKey(), resultCode, "test result", null, NOW, NOW);
    }

    private static DeploymentTarget target(HealthOutcome healthOutcome) {
        return new DeploymentTarget() {
            @Override public TargetOperationResult activate(DeploymentCommand c, Duration timeout) { return null; }
            @Override public TargetOperationResult deactivate(DeploymentCommand c, Duration timeout) { return null; }
            @Override public TargetOperationResult rollback(DeploymentCommand c, Duration timeout) { return null; }
            @Override public HealthCheckResult healthCheck(DeploymentCommand c, Duration timeout) {
                return healthOutcome == null ? null
                        : new HealthCheckResult(healthOutcome, Map.of("ready", healthOutcome == HealthOutcome.HEALTHY),
                                "health observed", NOW);
            }
        };
    }

    private static String eventKey(String key, String phase) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((key + ":" + phase).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
