package tech.kayys.wayang.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcDeploymentLifecycleReconciliationWorkStoreTest {
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    private JdbcDeploymentLifecycleReconciliationWorkStore store;
    private DeploymentOperationReceipt receipt;

    @BeforeEach
    void setUp() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:reconciliation_work_" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE wayang_deployment_operation_receipt (
                      deployment_id VARCHAR(160) NOT NULL, operation_key VARCHAR(200) NOT NULL,
                      command_fingerprint CHAR(64) NOT NULL, operation VARCHAR(40) NOT NULL,
                      status VARCHAR(16) NOT NULL, attempt INTEGER NOT NULL, target_operation_key VARCHAR(200) NOT NULL,
                      result_code VARCHAR(120), result_detail VARCHAR(2048), lease_until TIMESTAMP WITH TIME ZONE,
                      created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      PRIMARY KEY (deployment_id, operation_key))
                    """);
            statement.execute("""
                    CREATE TABLE wayang_deployment_reconciliation_work (
                      deployment_id VARCHAR(160) NOT NULL, operation_key VARCHAR(200) NOT NULL,
                      command_fingerprint CHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, claim_token VARCHAR(64),
                      lease_until TIMESTAMP WITH TIME ZONE, retry_at TIMESTAMP WITH TIME ZONE,
                      detail VARCHAR(2048), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      PRIMARY KEY (deployment_id, operation_key),
                      FOREIGN KEY (deployment_id, operation_key)
                        REFERENCES wayang_deployment_operation_receipt (deployment_id, operation_key))
                    """);
        }
        store = new JdbcDeploymentLifecycleReconciliationWorkStore(source);
        receipt = new DeploymentOperationReceipt("dep", "op", "a".repeat(64), "ACTIVATE",
                DeploymentOperationStatus.SUCCEEDED, 1, "target-op", "SUCCEEDED", "ok", null, NOW, NOW);
        try (var connection = source.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO wayang_deployment_operation_receipt
                (deployment_id, operation_key, command_fingerprint, operation, status, attempt,
                 target_operation_key, result_code, result_detail, lease_until, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SUCCEEDED', 1, ?, 'SUCCEEDED', 'ok', NULL, ?, ?)
                """)) {
            statement.setString(1, receipt.deploymentId());
            statement.setString(2, receipt.operationKey());
            statement.setString(3, receipt.commandFingerprint());
            statement.setString(4, receipt.operation());
            statement.setString(5, receipt.targetOperationKey());
            statement.setObject(6, NOW);
            statement.setObject(7, NOW);
            statement.executeUpdate();
        }
    }

    @Test
    void onlyOneWorkerAcquiresNewClaim() {
        assertTrue(store.claim(receipt, NOW, NOW.plusSeconds(60)));
        assertFalse(store.claim(receipt, NOW, NOW.plusSeconds(60)));
    }

    @Test
    void waitingWorkCanBeClaimedOnlyWhenRetryIsDue() {
        assertTrue(store.claim(receipt, NOW, NOW.plusSeconds(60)));
        store.complete(receipt, DeploymentLifecycleReconciliationWorkStatus.WAITING,
                "health unavailable", NOW, NOW.plusSeconds(30));

        assertFalse(store.claim(receipt, NOW.plusSeconds(29), NOW.plusSeconds(90)));
        assertTrue(store.claim(receipt, NOW.plusSeconds(30), NOW.plusSeconds(90)));
    }

    @Test
    void expiredClaimCanBeRecoveredButCompletedWorkCannot() {
        assertTrue(store.claim(receipt, NOW, NOW.plusSeconds(30)));
        assertTrue(store.claim(receipt, NOW.plusSeconds(30), NOW.plusSeconds(60)));
        store.complete(receipt, DeploymentLifecycleReconciliationWorkStatus.RECONCILED,
                "applied", NOW.plusSeconds(31), null);

        assertFalse(store.claim(receipt, NOW.plusSeconds(120), NOW.plusSeconds(180)));
    }

    @Test
    void rejectsClaimWithDifferentFingerprintForSameOperationKey() {
        assertTrue(store.claim(receipt, NOW, NOW.plusSeconds(60)));
        var different = new DeploymentOperationReceipt("dep", "op", "b".repeat(64), "ACTIVATE",
                DeploymentOperationStatus.SUCCEEDED, 1, "target-op", "SUCCEEDED", "ok", null, NOW, NOW);
        assertThrows(SecurityException.class, () -> store.claim(different, NOW, NOW.plusSeconds(60)));
    }

    @Test
    void expiredWorkerCannotCompleteAfterAnotherWorkerReclaimsLease() {
        var firstOwner = store.claimOwned(receipt, NOW, NOW.plusSeconds(30));
        assertNotNull(firstOwner);
        var secondOwner = store.claimOwned(receipt, NOW.plusSeconds(31), NOW.plusSeconds(90));
        assertNotNull(secondOwner);
        assertNotEquals(firstOwner.ownershipToken(), secondOwner.ownershipToken());

        assertThrows(IllegalStateException.class, () -> store.complete(firstOwner,
                DeploymentLifecycleReconciliationWorkStatus.RECONCILED, "stale result",
                NOW.plusSeconds(32), null));

        store.complete(secondOwner, DeploymentLifecycleReconciliationWorkStatus.RECONCILED,
                "current owner result", NOW.plusSeconds(33), null);
        assertFalse(store.claimOwned(receipt, NOW.plusSeconds(120), NOW.plusSeconds(180)) != null);
    }
}
