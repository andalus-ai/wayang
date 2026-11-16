package tech.kayys.wayang.deployment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * JDBC store for encrypted reconciliation context. Ciphertext is immutable and keyed by the
 * durable operation receipt. The caller must provide a secure codec; plaintext fallback is absent.
 */
public final class JdbcDeploymentLifecycleReconciliationContextStore
        implements DeploymentLifecycleReconciliationContextStore {
    private static final int MAX_CIPHERTEXT_BYTES = 1_048_576;

    private final DataSource dataSource;
    private final DeploymentLifecycleReconciliationContextCodec codec;
    private final Clock clock;

    public JdbcDeploymentLifecycleReconciliationContextStore(DataSource dataSource,
            DeploymentLifecycleReconciliationContextCodec codec, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void save(DeploymentLifecycleReconciliationContext context) {
        Objects.requireNonNull(context, "context");
        byte[] encrypted = validateCiphertext(codec.encode(context));
        String fingerprint = context.commandFingerprint();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<DeploymentLifecycleReconciliationContext> existing =
                        select(connection, context.command().deploymentId(), context.command().idempotencyKey(),
                                fingerprint, context.operation(), true);
                if (existing.isPresent()) {
                    if (!existing.get().equals(context)) {
                        throw new SecurityException("Reconciliation context already exists with different request data");
                    }
                    connection.commit();
                    return;
                }
                String sql = """
                        INSERT INTO wayang_deployment_reconciliation_context
                        (deployment_id, operation_key, command_fingerprint, operation, encrypted_context, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, context.command().deploymentId());
                    statement.setString(2, context.command().idempotencyKey());
                    statement.setString(3, fingerprint);
                    statement.setString(4, context.operation());
                    statement.setBytes(5, encrypted);
                    statement.setTimestamp(6, Timestamp.from(clock.instant()));
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                if (failure instanceof SQLException sqlFailure && isConstraintViolation(sqlFailure)) {
                    Optional<DeploymentLifecycleReconciliationContext> raced = findByIdentity(
                            context.command().deploymentId(), context.command().idempotencyKey(), fingerprint,
                            context.operation());
                    if (raced.isPresent() && raced.get().equals(context)) return;
                    if (raced.isPresent()) throw new SecurityException(
                            "Reconciliation context already exists with different request data");
                }
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Unable to persist deployment reconciliation context", failure);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to open reconciliation context transaction", failure);
        }
    }

    @Override
    public Optional<DeploymentLifecycleReconciliationContext> find(DeploymentOperationReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        return findByIdentity(receipt.deploymentId(), receipt.operationKey(),
                receipt.commandFingerprint(), receipt.operation());
    }

    private Optional<DeploymentLifecycleReconciliationContext> findByIdentity(
            String deploymentId, String operationKey, String fingerprint, String operation) {
        try (Connection connection = dataSource.getConnection()) {
            return select(connection, deploymentId, operationKey, fingerprint, operation, false);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read deployment reconciliation context", failure);
        }
    }

    private Optional<DeploymentLifecycleReconciliationContext> select(Connection connection,
            String deploymentId, String operationKey, String fingerprint, String operation, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT encrypted_context FROM wayang_deployment_reconciliation_context
                WHERE deployment_id = ? AND operation_key = ? AND command_fingerprint = ? AND operation = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, deploymentId);
            statement.setString(2, operationKey);
            statement.setString(3, fingerprint);
            statement.setString(4, operation);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                byte[] ciphertext = validateCiphertext(result.getBytes(1));
                DeploymentLifecycleReconciliationContext decoded = Objects.requireNonNull(
                        codec.decode(ciphertext), "decoded reconciliation context");
                verifyIdentity(decoded, deploymentId, operationKey, fingerprint, operation);
                return Optional.of(decoded);
            }
        }
    }

    private static void verifyIdentity(DeploymentLifecycleReconciliationContext context,
            String deploymentId, String operationKey, String fingerprint, String operation) {
        if (!context.command().deploymentId().equals(deploymentId)
                || !context.command().idempotencyKey().equals(operationKey)
                || !context.commandFingerprint().equals(fingerprint)
                || !context.operation().equals(operation)) {
            throw new SecurityException("Decrypted reconciliation context does not match its database identity");
        }
    }

    private static byte[] validateCiphertext(byte[] value) {
        if (value == null || value.length == 0 || value.length > MAX_CIPHERTEXT_BYTES) {
            throw new IllegalArgumentException("Encrypted reconciliation context must be between 1 byte and 1 MiB");
        }
        return Arrays.copyOf(value, value.length);
    }

    private static boolean isConstraintViolation(SQLException failure) {
        return failure.getSQLState() != null && failure.getSQLState().startsWith("23");
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
