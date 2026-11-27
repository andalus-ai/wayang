package tech.kayys.wayang.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Verifies that independent PostgreSQL-backed workers cannot both acquire one reconciliation claim. */
class JdbcDeploymentLifecycleReconciliationPostgresConcurrencyTest {
    private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");

    @Test
    void onlyOneIndependentWorkerClaimsTheSameWorkItem() throws Exception {
        String jdbcUrl = System.getenv("WAYANG_TEST_JDBC_URL");
        Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank(),
                "PostgreSQL integration is enabled by WAYANG_TEST_JDBC_URL in CI");

        String schema = "wayang_claim_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(jdbcUrl);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }

        try {
            String scopedUrl = jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setUrl(scopedUrl);
            createSchema(source);

            DeploymentOperationReceipt receipt = new DeploymentOperationReceipt(
                    "dep-concurrent", "op-concurrent", "c".repeat(64), "ACTIVATE",
                    DeploymentOperationStatus.SUCCEEDED, 1, "target-op-concurrent",
                    "SUCCEEDED", "target completed", null, NOW, NOW);
            insertReceipt(source, receipt);

            // Separate store instances model independent application workers/connections.
            var workerA = new JdbcDeploymentLifecycleReconciliationWorkStore(source);
            var workerB = new JdbcDeploymentLifecycleReconciliationWorkStore(source);
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var first = executor.submit(() -> claim(workerA, receipt, ready, start));
                var second = executor.submit(() -> claim(workerB, receipt, ready, start));
                assertTrue(ready.await(10, TimeUnit.SECONDS), "both workers should reach the claim barrier");
                start.countDown();
                int winners = (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                        + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
                assertEquals(1, winners, "the unique work key must have exactly one claim winner");
            } finally {
                executor.shutdownNow();
            }
        } finally {
            try (var connection = DriverManager.getConnection(jdbcUrl);
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }


    @Test
    void independentWorkerCanReclaimOnlyAfterPostgresLeaseExpires() throws Exception {
        String jdbcUrl = System.getenv("WAYANG_TEST_JDBC_URL");
        Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank(),
                "PostgreSQL integration is enabled by WAYANG_TEST_JDBC_URL in CI");

        String schema = "wayang_lease_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(jdbcUrl);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }

        try {
            String scopedUrl = jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setUrl(scopedUrl);
            createSchema(source);

            DeploymentOperationReceipt receipt = new DeploymentOperationReceipt(
                    "dep-lease", "op-lease", "d".repeat(64), "ACTIVATE",
                    DeploymentOperationStatus.SUCCEEDED, 1, "target-op-lease",
                    "SUCCEEDED", "target completed", null, NOW, NOW);
            insertReceipt(source, receipt);

            var workerA = new JdbcDeploymentLifecycleReconciliationWorkStore(source);
            var workerB = new JdbcDeploymentLifecycleReconciliationWorkStore(source);
            assertTrue(workerA.claim(receipt, NOW, NOW.plusSeconds(60)),
                    "first worker should acquire the initial lease");
            assertFalse(workerB.claim(receipt, NOW.plusSeconds(30), NOW.plusSeconds(90)),
                    "second worker must not steal a live lease");
            assertTrue(workerB.claim(receipt, NOW.plusSeconds(61), NOW.plusSeconds(121)),
                    "second worker should reclaim the lease after expiry");
        } finally {
            try (var connection = DriverManager.getConnection(jdbcUrl);
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static boolean claim(JdbcDeploymentLifecycleReconciliationWorkStore store,
            DeploymentOperationReceipt receipt, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("claim barrier timed out");
        }
        return store.claim(receipt, NOW, NOW.plusSeconds(60));
    }

    private static void createSchema(DataSource source) throws Exception {
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
                      command_fingerprint CHAR(64) NOT NULL, status VARCHAR(32) NOT NULL,
                      lease_until TIMESTAMP WITH TIME ZONE, retry_at TIMESTAMP WITH TIME ZONE,
                      detail VARCHAR(2048), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      PRIMARY KEY (deployment_id, operation_key),
                      FOREIGN KEY (deployment_id, operation_key)
                        REFERENCES wayang_deployment_operation_receipt (deployment_id, operation_key))
                    """);
        }
    }

    private static void insertReceipt(DataSource source, DeploymentOperationReceipt receipt) throws Exception {
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("""
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
            statement.setTimestamp(6, Timestamp.from(NOW));
            statement.setTimestamp(7, Timestamp.from(NOW));
            statement.executeUpdate();
        }
    }
}
