package tech.kayys.wayang.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

/**
 * Exercises the persisted receipt, encrypted context and work queue together.
 *
 * The JDBC lifecycle ledger, receipt, encrypted context and work queue share one database.
 * This test proves that a restarted worker does not redispatch or reapply completed work.
 */
class JdbcDeploymentLifecycleReconciliationIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    @Test
    void terminalReceiptAndEncryptedContextAreReconciledAfterWorkerRestart() throws Exception {
        runRecoveryScenario(dataSource());
    }

    @Test
    void postgresPersistsLifecycleAndReconciliationAcrossWorkerRestart() throws Exception {
        String jdbcUrl = System.getenv("WAYANG_TEST_JDBC_URL");
        Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank(),
                "PostgreSQL integration is enabled by WAYANG_TEST_JDBC_URL in CI");
        String schema = "wayang_recovery_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = java.sql.DriverManager.getConnection(jdbcUrl);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        String scopedUrl = jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var postgres = new PGSimpleDataSource();
        postgres.setUrl(scopedUrl);
        try {
            runRecoveryScenario(postgres);
        } finally {
            try (var connection = java.sql.DriverManager.getConnection(jdbcUrl);
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private void runRecoveryScenario(DataSource dataSource) throws Exception {
        createSchema(dataSource);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var receiptStore = new JdbcDeploymentOperationReceiptStore(dataSource);
        var codec = new TestAesGcmCodec();
        var contextStore = new JdbcDeploymentLifecycleReconciliationContextStore(dataSource, codec, clock);
        var workStore = new JdbcDeploymentLifecycleReconciliationWorkStore(dataSource);

        var ledger = new JdbcDeploymentLedger(dataSource);
        var lifecycle = new DeploymentLifecycleService(ledger, clock);
        lifecycle.registerInstalled("dep-jdbc", "tenant-a", "prod", "target-a", "release-7",
                "publisher", "installed", "corr-install", "install");
        lifecycle.beginActivation("dep-jdbc", 0, "operator", "activation requested",
                "corr-activate", "begin-activation");

        var command = new DeploymentCommand("dep-jdbc", "tenant-a", "prod", "target-a",
                "release-7", "activate-jdbc", Map.of("region", "ap-southeast-1"));
        String fingerprint = DeploymentCommandFingerprint.of("ACTIVATE", command);
        var claim = receiptStore.claimExclusive(command.deploymentId(), command.idempotencyKey(),
                fingerprint, "ACTIVATE", "target-op-jdbc", NOW, NOW.plusSeconds(60));
        assertTrue(claim.acquired());
        var terminal = receiptStore.complete(command.deploymentId(), command.idempotencyKey(), fingerprint,
                DeploymentOperationStatus.SUCCEEDED, "TARGET_SUCCEEDED", "accepted by target", NOW.plusSeconds(1));

        var context = new DeploymentLifecycleReconciliationContext("ACTIVATE", command, "operator",
                "activation requested", "corr-activate", Duration.ofSeconds(20));
        contextStore.save(context);
        assertEquals(context, contextStore.find(terminal).orElseThrow());

        var targetOperationCalls = new AtomicInteger();
        var healthCalls = new AtomicInteger();
        var targets = new DeploymentTargetRegistry();
        targets.register("target-a", new DeploymentTarget() {
            @Override public TargetOperationResult activate(DeploymentCommand ignored, Duration timeout) {
                targetOperationCalls.incrementAndGet();
                throw new AssertionError("recovery must not redispatch activation");
            }
            @Override public TargetOperationResult deactivate(DeploymentCommand ignored, Duration timeout) {
                targetOperationCalls.incrementAndGet();
                throw new AssertionError("recovery must not dispatch deactivation");
            }
            @Override public TargetOperationResult rollback(DeploymentCommand ignored, Duration timeout) {
                targetOperationCalls.incrementAndGet();
                throw new AssertionError("recovery must not dispatch rollback");
            }
            @Override public HealthCheckResult healthCheck(DeploymentCommand ignored, Duration timeout) {
                healthCalls.incrementAndGet();
                return new HealthCheckResult(HealthOutcome.HEALTHY, Map.of("ready", true), "ready", NOW);
            }
        });

        var firstWorker = worker(receiptStore, contextStore, workStore, ledger, lifecycle, targets, clock);
        var first = firstWorker.runOnce(10);

        assertEquals(1, first.scanned());
        assertEquals(1, first.claimed());
        assertEquals(1, first.reconciled());
        assertEquals(DeploymentState.ACTIVE, ledger.find("dep-jdbc").orElseThrow().state());
        assertEquals(0, targetOperationCalls.get());
        assertEquals(1, healthCalls.get());
        assertEquals("RECONCILED", workStatus(dataSource, command));

        // Simulate a process restart: construct fresh JDBC adapters and a fresh worker over the same DB.
        var restartedReceiptStore = new JdbcDeploymentOperationReceiptStore(dataSource);
        var restartedContextStore = new JdbcDeploymentLifecycleReconciliationContextStore(dataSource, codec, clock);
        var restartedWorkStore = new JdbcDeploymentLifecycleReconciliationWorkStore(dataSource);
        var restartedLedger = new JdbcDeploymentLedger(dataSource);
        var restartedLifecycle = new DeploymentLifecycleService(restartedLedger, clock);
        var restartedWorker = worker(restartedReceiptStore, restartedContextStore, restartedWorkStore,
                restartedLedger, restartedLifecycle, targets, clock);
        var afterRestart = restartedWorker.runOnce(10);

        assertEquals(0, afterRestart.scanned());
        assertEquals(0, afterRestart.claimed());
        assertEquals(0, afterRestart.reconciled());
        assertEquals(DeploymentState.ACTIVE, restartedLedger.find("dep-jdbc").orElseThrow().state());
        assertEquals(3, restartedLedger.events("dep-jdbc").size(),
                "registration, activation start, and reconciliation result survive the worker restart");
        assertEquals(0, targetOperationCalls.get());
        assertEquals(1, healthCalls.get());
        assertEquals(terminal, restartedReceiptStore.find(command.deploymentId(), command.idempotencyKey()).orElseThrow());
    }

    private static DeploymentLifecycleReconciliationWorker worker(DeploymentOperationReceiptStore receipts,
            DeploymentLifecycleReconciliationContextStore contexts,
            DeploymentLifecycleReconciliationWorkStore work, DeploymentLedger ledger,
            DeploymentLifecycleService lifecycle, DeploymentTargetRegistry targets, Clock clock) {
        return new DeploymentLifecycleReconciliationWorker(receipts, contexts, work,
                new DeploymentLifecycleReconciliationService(ledger, lifecycle, targets, clock),
                clock, Duration.ofMinutes(2), Duration.ofSeconds(30));
    }

    private static JdbcDataSource dataSource() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:deployment_recovery_" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        return source;
    }

    private static void createSchema(DataSource source) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE wayang_deployment_snapshot (
                      deployment_id VARCHAR(160) PRIMARY KEY, tenant_id VARCHAR(160) NOT NULL,
                      environment VARCHAR(120) NOT NULL, target_id VARCHAR(200) NOT NULL,
                      release_id VARCHAR(200) NOT NULL, state VARCHAR(32) NOT NULL,
                      version BIGINT NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      last_reason VARCHAR(2048))
                    """);
            statement.execute("""
                    CREATE TABLE wayang_deployment_event (
                      deployment_id VARCHAR(160) NOT NULL REFERENCES wayang_deployment_snapshot(deployment_id),
                      sequence BIGINT NOT NULL, from_state VARCHAR(32), to_state VARCHAR(32) NOT NULL,
                      actor VARCHAR(200) NOT NULL, reason VARCHAR(2048) NOT NULL,
                      correlation_id VARCHAR(200) NOT NULL, idempotency_key VARCHAR(200) NOT NULL,
                      occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      PRIMARY KEY (deployment_id, sequence), UNIQUE (deployment_id, idempotency_key))
                    """);
            statement.execute("""
                    CREATE TABLE wayang_deployment_operation_receipt (
                      deployment_id VARCHAR(160) NOT NULL REFERENCES wayang_deployment_snapshot(deployment_id),
                      operation_key VARCHAR(200) NOT NULL, command_fingerprint CHAR(64) NOT NULL,
                      operation VARCHAR(40) NOT NULL, status VARCHAR(16) NOT NULL, attempt INTEGER NOT NULL,
                      target_operation_key VARCHAR(200) NOT NULL, result_code VARCHAR(120),
                      result_detail VARCHAR(2048), lease_until TIMESTAMP WITH TIME ZONE,
                      created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      PRIMARY KEY (deployment_id, operation_key))
                    """);
            statement.execute("""
                    CREATE TABLE wayang_deployment_reconciliation_context (
                      deployment_id VARCHAR(160) NOT NULL, operation_key VARCHAR(200) NOT NULL,
                      command_fingerprint CHAR(64) NOT NULL, operation VARCHAR(40) NOT NULL,
                      encrypted_context BYTEA NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      PRIMARY KEY (deployment_id, operation_key),
                      FOREIGN KEY (deployment_id, operation_key)
                        REFERENCES wayang_deployment_operation_receipt(deployment_id, operation_key))
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
                        REFERENCES wayang_deployment_operation_receipt(deployment_id, operation_key))
                    """);
        }
    }

    private static String workStatus(DataSource source, DeploymentCommand command) throws Exception {
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT status FROM wayang_deployment_reconciliation_work
                     WHERE deployment_id = ? AND operation_key = ?
                     """)) {
            statement.setString(1, command.deploymentId());
            statement.setString(2, command.idempotencyKey());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    /**
     * Test-only AES-GCM codec. The clear header is authenticated as AAD; the context payload is
     * encrypted. Production deployments must supply their own managed-key codec.
     */
    private static final class TestAesGcmCodec implements DeploymentLifecycleReconciliationContextCodec {
        private static final byte[] KEY = "wayang-test-key!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        private static final SecureRandom RANDOM = new SecureRandom();

        @Override
        public byte[] encode(DeploymentLifecycleReconciliationContext context) {
            try {
                byte[] aad = header(context.operation(), context.command().deploymentId(),
                        context.command().idempotencyKey(), context.commandFingerprint());
                var payloadBytes = new ByteArrayOutputStream();
                try (var payload = new DataOutputStream(payloadBytes)) {
                    DeploymentCommand command = context.command();
                    payload.writeUTF(command.tenantId());
                    payload.writeUTF(command.environment());
                    payload.writeUTF(command.targetId());
                    payload.writeUTF(command.releaseId());
                    payload.writeInt(command.parameters().size());
                    for (var entry : new java.util.TreeMap<>(command.parameters()).entrySet()) {
                        payload.writeUTF(entry.getKey());
                        payload.writeUTF(entry.getValue());
                    }
                    payload.writeUTF(context.actor());
                    payload.writeUTF(context.reason());
                    payload.writeUTF(context.correlationId());
                    payload.writeLong(context.timeout().toMillis());
                }
                byte[] nonce = new byte[12];
                RANDOM.nextBytes(nonce);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, nonce));
                cipher.updateAAD(aad);
                byte[] encrypted = cipher.doFinal(payloadBytes.toByteArray());
                return ByteBuffer.allocate(4 + aad.length + nonce.length + encrypted.length)
                        .putInt(aad.length).put(aad).put(nonce).put(encrypted).array();
            } catch (Exception failure) {
                throw new IllegalStateException("Unable to encrypt test reconciliation context", failure);
            }
        }

        @Override
        public DeploymentLifecycleReconciliationContext decode(byte[] ciphertext) {
            try {
                var input = new DataInputStream(new ByteArrayInputStream(ciphertext));
                int aadLength = input.readInt();
                if (aadLength < 1 || aadLength > 4096) throw new SecurityException("Invalid AAD length");
                byte[] aad = input.readNBytes(aadLength);
                try (var header = new DataInputStream(new ByteArrayInputStream(aad))) {
                    String operation = header.readUTF();
                    String deploymentId = header.readUTF();
                    String operationKey = header.readUTF();
                    String fingerprint = header.readUTF();
                    byte[] nonce = input.readNBytes(12);
                    byte[] encrypted = input.readAllBytes();
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"),
                            new GCMParameterSpec(128, nonce));
                    cipher.updateAAD(aad);
                    byte[] clear = cipher.doFinal(encrypted);
                    try (var payload = new DataInputStream(new ByteArrayInputStream(clear))) {
                        String tenant = payload.readUTF();
                        String environment = payload.readUTF();
                        String target = payload.readUTF();
                        String release = payload.readUTF();
                        int count = payload.readInt();
                        if (count < 0 || count > 1000) throw new SecurityException("Invalid parameter count");
                        Map<String, String> parameters = new HashMap<>();
                        for (int i = 0; i < count; i++) parameters.put(payload.readUTF(), payload.readUTF());
                        var command = new DeploymentCommand(deploymentId, tenant, environment, target, release,
                                operationKey, parameters);
                        if (!DeploymentCommandFingerprint.of(operation, command).equals(fingerprint)) {
                            throw new SecurityException("Authenticated context fingerprint mismatch");
                        }
                        return new DeploymentLifecycleReconciliationContext(operation, command, payload.readUTF(),
                                payload.readUTF(), payload.readUTF(), Duration.ofMillis(payload.readLong()));
                    }
                }
            } catch (Exception failure) {
                if (failure instanceof SecurityException security) throw security;
                throw new IllegalStateException("Unable to decrypt test reconciliation context", failure);
            }
        }

        private static byte[] header(String operation, String deploymentId, String operationKey, String fingerprint)
                throws Exception {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeUTF(operation);
                output.writeUTF(deploymentId);
                output.writeUTF(operationKey);
                output.writeUTF(fingerprint);
            }
            return bytes.toByteArray();
        }
    }
}
