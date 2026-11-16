package tech.kayys.wayang.deployment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;

/** JDBC-backed cross-process reconciliation lease store. */
public final class JdbcDeploymentLifecycleReconciliationWorkStore
        implements DeploymentLifecycleReconciliationWorkStore {
    private final DataSource dataSource;

    public JdbcDeploymentLifecycleReconciliationWorkStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean claim(DeploymentOperationReceipt receipt, Instant now, Instant leaseUntil) {
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (!leaseUntil.isAfter(now)) throw new IllegalArgumentException("leaseUntil must be after now");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String update = """
                        UPDATE wayang_deployment_reconciliation_work
                        SET status = 'CLAIMED', lease_until = ?, retry_at = NULL, updated_at = ?
                        WHERE deployment_id = ? AND operation_key = ? AND command_fingerprint = ?
                          AND ((status = 'WAITING' AND retry_at <= ?)
                            OR (status = 'CLAIMED' AND lease_until <= ?))
                        """;
                try (PreparedStatement statement = connection.prepareStatement(update)) {
                    statement.setTimestamp(1, Timestamp.from(leaseUntil));
                    statement.setTimestamp(2, Timestamp.from(now));
                    statement.setString(3, receipt.deploymentId());
                    statement.setString(4, receipt.operationKey());
                    statement.setString(5, receipt.commandFingerprint());
                    statement.setTimestamp(6, Timestamp.from(now));
                    statement.setTimestamp(7, Timestamp.from(now));
                    if (statement.executeUpdate() == 1) {
                        connection.commit();
                        return true;
                    }
                }
                String insert = """
                        INSERT INTO wayang_deployment_reconciliation_work
                        (deployment_id, operation_key, command_fingerprint, status, lease_until, retry_at,
                         detail, created_at, updated_at)
                        VALUES (?, ?, ?, 'CLAIMED', ?, NULL, NULL, ?, ?)
                        """;
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    statement.setString(1, receipt.deploymentId());
                    statement.setString(2, receipt.operationKey());
                    statement.setString(3, receipt.commandFingerprint());
                    statement.setTimestamp(4, Timestamp.from(leaseUntil));
                    statement.setTimestamp(5, Timestamp.from(now));
                    statement.setTimestamp(6, Timestamp.from(now));
                    statement.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException failure) {
                rollback(connection, failure);
                if (isUniqueViolation(failure)) {
                    String existingFingerprint = findFingerprint(receipt.deploymentId(), receipt.operationKey());
                    if (existingFingerprint != null && !existingFingerprint.equals(receipt.commandFingerprint())) {
                        recordIdentityConflict(receipt, now);
                        throw new SecurityException("Reconciliation work key was reused with a different command fingerprint");
                    }
                    return false;
                }
                throw new IllegalStateException("Unable to claim lifecycle reconciliation work", failure);
            } catch (RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to open lifecycle reconciliation claim transaction", failure);
        }
    }

    @Override
    public void complete(DeploymentOperationReceipt receipt, DeploymentLifecycleReconciliationWorkStatus status,
            String detail, Instant now, Instant retryAt) {
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(now, "now");
        if (status == DeploymentLifecycleReconciliationWorkStatus.CLAIMED) {
            throw new IllegalArgumentException("completion status cannot be CLAIMED");
        }
        if (status == DeploymentLifecycleReconciliationWorkStatus.WAITING && retryAt == null) {
            throw new IllegalArgumentException("WAITING work requires retryAt");
        }
        if (status != DeploymentLifecycleReconciliationWorkStatus.WAITING && retryAt != null) {
            throw new IllegalArgumentException("retryAt is only valid for WAITING work");
        }
        String safeDetail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 2048));
        String sql = """
                UPDATE wayang_deployment_reconciliation_work
                SET status = ?, detail = ?, lease_until = NULL, retry_at = ?, updated_at = ?
                WHERE deployment_id = ? AND operation_key = ? AND command_fingerprint = ? AND status = 'CLAIMED'
                """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setString(2, safeDetail);
            statement.setTimestamp(3, retryAt == null ? null : Timestamp.from(retryAt));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setString(5, receipt.deploymentId());
            statement.setString(6, receipt.operationKey());
            statement.setString(7, receipt.commandFingerprint());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Reconciliation claim is missing, changed, or no longer owned");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to complete lifecycle reconciliation work", failure);
        }
    }

    private void recordIdentityConflict(DeploymentOperationReceipt receipt, Instant now) {
        String sql = """
                UPDATE wayang_deployment_reconciliation_work
                SET status = 'CONFLICT', detail = 'Command fingerprint changed for an existing work key',
                    lease_until = NULL, retry_at = NULL, updated_at = ?
                WHERE deployment_id = ? AND operation_key = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setString(2, receipt.deploymentId());
            statement.setString(3, receipt.operationKey());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to record reconciliation identity conflict", failure);
        }
    }

    private String findFingerprint(String deploymentId, String operationKey) {
        String sql = """
                SELECT command_fingerprint FROM wayang_deployment_reconciliation_work
                WHERE deployment_id = ? AND operation_key = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, deploymentId);
            statement.setString(2, operationKey);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to verify reconciliation work identity", failure);
        }
    }

    private static boolean isUniqueViolation(SQLException failure) {
        return "23505".equals(failure.getSQLState());
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
