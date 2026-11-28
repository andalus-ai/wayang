package tech.kayys.wayang.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the shared JDBC work ledger through independent store instances,
 * representing separate worker processes using the same database.
 */
class DeploymentReconciliationMultiWorkerIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    private JdbcDataSource dataSource;
    private DeploymentOperationReceipt receipt;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:multi_worker_" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE wayang_deployment_operation_receipt (
                      deployment_id VARCHAR(160) NOT NULL,
                      operation_key VARCHAR(200) NOT NULL,
                      command_fingerprint CHAR(64) NOT NULL,
                      operation VARCHAR(40) NOT NULL,
                      status VARCHAR(16) NOT NULL,
                      attempt INTEGER NOT NULL,
                      target_operation_key VARCHAR(200) NOT NULL,
                      result_code VARCHAR(120),
                      result_detail VARCHAR(2048),
                      lease_until TIMESTAMP WITH TIME ZONE,
                      created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      PRIMARY KEY (deployment_id, operation_key))
                    """);
            statement.execute("""
                    CREATE TABLE wayang_deployment_reconciliation_work (
                      deployment_id VARCHAR(160) NOT NULL,
                      operation_key VARCHAR(200) NOT NULL,
                      command_fingerprint CHAR(64) NOT NULL,
                      status VARCHAR(32) NOT NULL,
                      claim_token VARCHAR(64),
                      lease_until TIMESTAMP WITH TIME ZONE,
                      retry_at TIMESTAMP WITH TIME ZONE,
                      detail VARCHAR(2048),
                      created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      PRIMARY KEY (deployment_id, operation_key),
                      FOREIGN KEY (deployment_id, operation_key)
                        REFERENCES wayang_deployment_operation_receipt (deployment_id, operation_key))
                    """);
        }
        receipt = new DeploymentOperationReceipt("dep-multi", "operation-multi", "a".repeat(64),
                "ACTIVATE", DeploymentOperationStatus.SUCCEEDED, 1, "target-operation",
                "SUCCEEDED", "target completed", null, NOW, NOW);
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO wayang_deployment_operation_receipt
                (deployment_id, operation_key, command_fingerprint, operation, status, attempt,
                 target_operation_key, result_code, result_detail, lease_until, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SUCCEEDED', 1, ?, 'SUCCEEDED', 'target completed', NULL, ?, ?)
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
    void independentWorkersCannotAcquireTheSameLiveLease() throws Exception {
        var workerA = new JdbcDeploymentLifecycleReconciliationWorkStore(dataSource);
        var workerB = new JdbcDeploymentLifecycleReconciliationWorkStore(dataSource);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> claimAtSameTime(workerA, ready, start));
            var second = executor.submit(() -> claimAtSameTime(workerB, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "workers did not reach the claim barrier");
            start.countDown();

            boolean firstClaimed = first.get(10, TimeUnit.SECONDS);
            boolean secondClaimed = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstClaimed, secondClaimed,
                    "exactly one independent worker must acquire the shared lease");
        }
    }

    @Test
    void restartedWorkerCanReclaimExpiredLeaseButStaleOwnerCannotComplete() {
        var beforeRestart = new JdbcDeploymentLifecycleReconciliationWorkStore(dataSource);
        var afterRestart = new JdbcDeploymentLifecycleReconciliationWorkStore(dataSource);

        var firstOwner = beforeRestart.claimOwned(receipt, NOW, NOW.plusSeconds(30));
        assertNotNull(firstOwner);
        var secondOwner = afterRestart.claimOwned(receipt, NOW.plusSeconds(30), NOW.plusSeconds(90));
        assertNotNull(secondOwner, "a new worker instance must reclaim an expired lease");
        assertNotEquals(firstOwner.ownershipToken(), secondOwner.ownershipToken());

        assertThrows(IllegalStateException.class, () -> beforeRestart.complete(firstOwner,
                DeploymentLifecycleReconciliationWorkStatus.RECONCILED, "stale result",
                NOW.plusSeconds(31), null));

        afterRestart.complete(secondOwner, DeploymentLifecycleReconciliationWorkStatus.RECONCILED,
                "lifecycle reconciled", NOW.plusSeconds(32), null);

        assertNull(beforeRestart.claimOwned(receipt, NOW.plusSeconds(120), NOW.plusSeconds(180)),
                "terminal work must not be reclaimed after restart");
    }

    private boolean claimAtSameTime(JdbcDeploymentLifecycleReconciliationWorkStore store,
            CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("claim barrier timed out");
        }
        return store.claimOwned(receipt, NOW, NOW.plusSeconds(60)) != null;
    }
}
