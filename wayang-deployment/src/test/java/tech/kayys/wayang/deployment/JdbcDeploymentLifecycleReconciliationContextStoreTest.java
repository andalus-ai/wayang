package tech.kayys.wayang.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcDeploymentLifecycleReconciliationContextStoreTest {
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    private final TestCodec codec = new TestCodec();
    private JdbcDeploymentLifecycleReconciliationContextStore store;
    private DeploymentLifecycleReconciliationContext context;
    private DeploymentOperationReceipt receipt;

    @BeforeEach
    void setUp() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:reconciliation_context_" + UUID.randomUUID()
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
                    CREATE TABLE wayang_deployment_reconciliation_context (
                      deployment_id VARCHAR(160) NOT NULL, operation_key VARCHAR(200) NOT NULL,
                      command_fingerprint CHAR(64) NOT NULL, operation VARCHAR(40) NOT NULL,
                      encrypted_context BLOB NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      PRIMARY KEY (deployment_id, operation_key),
                      FOREIGN KEY (deployment_id, operation_key)
                        REFERENCES wayang_deployment_operation_receipt (deployment_id, operation_key))
                    """);
        }
        store = new JdbcDeploymentLifecycleReconciliationContextStore(source, codec,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var command = new DeploymentCommand("dep", "tenant", "prod", "target", "release", "op-key",
                Map.of("token", "sensitive-value"));
        context = new DeploymentLifecycleReconciliationContext("ACTIVATE", command,
                "operator", "release activation", "corr-1", Duration.ofSeconds(30));
        receipt = new DeploymentOperationReceipt("dep", "op-key", context.commandFingerprint(), "ACTIVATE",
                DeploymentOperationStatus.SUCCEEDED, 1, "target-op", "SUCCEEDED", "ok", null, NOW, NOW);
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("""
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
    void storesOnlyCodecOutputAndReloadsMatchingContext() {
        store.save(context);

        assertEquals(Optional.of(context), store.find(receipt));
        assertTrue(codec.encodedPayloads().stream()
                .noneMatch(value -> new String(value, StandardCharsets.UTF_8).contains("sensitive-value")));
    }

    @Test
    void identicalSaveIsIdempotentButDifferentAuditContextIsRejected() {
        store.save(context);
        store.save(context);

        var changed = new DeploymentLifecycleReconciliationContext("ACTIVATE", context.command(),
                "different-operator", context.reason(), context.correlationId(), context.timeout());
        assertThrows(SecurityException.class, () -> store.save(changed));
    }

    @Test
    void mismatchedReceiptCannotLoadContext() {
        store.save(context);
        var mismatched = new DeploymentOperationReceipt("dep", "op-key", "0".repeat(64), "ACTIVATE",
                DeploymentOperationStatus.SUCCEEDED, 1, "target-op", "SUCCEEDED", "ok", null, NOW, NOW);

        assertTrue(store.find(mismatched).isEmpty());
    }

    private static final class TestCodec implements DeploymentLifecycleReconciliationContextCodec {
        private final Map<String, DeploymentLifecycleReconciliationContext> contexts = new ConcurrentHashMap<>();
        private final Map<String, byte[]> payloads = new ConcurrentHashMap<>();

        @Override
        public byte[] encode(DeploymentLifecycleReconciliationContext value) {
            String key = UUID.randomUUID().toString();
            contexts.put(key, value);
            byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
            payloads.put(key, bytes);
            return bytes;
        }

        @Override
        public DeploymentLifecycleReconciliationContext decode(byte[] ciphertext) {
            return contexts.get(new String(ciphertext, StandardCharsets.UTF_8));
        }

        java.util.Collection<byte[]> encodedPayloads() {
            return payloads.values();
        }
    }
}
