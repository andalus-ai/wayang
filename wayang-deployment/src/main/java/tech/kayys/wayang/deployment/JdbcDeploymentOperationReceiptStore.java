package tech.kayys.wayang.deployment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC receipt store. Claim/complete operations are transactional and safe across competing workers. */
public final class JdbcDeploymentOperationReceiptStore implements DeploymentOperationReceiptStore {
    private final DataSource dataSource;

    public JdbcDeploymentOperationReceiptStore(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public DeploymentOperationReceipt claim(String deploymentId, String operationKey, String fingerprint,
            String operation, String targetOperationKey, Instant now, Instant leaseUntil) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                DeploymentOperationReceipt current = select(c, deploymentId, operationKey, true).orElse(null);
                if (current != null) {
                    if (!current.commandFingerprint().equals(fingerprint) || !current.operation().equals(operation)) {
                        throw new SecurityException("Idempotency key was already used for a different deployment command");
                    }
                    if (current.status() == DeploymentOperationStatus.RUNNING
                            && current.leaseUntil() != null && !current.leaseUntil().isAfter(now)) {
                        updateUnknown(c, deploymentId, operationKey, "Operation lease expired; target outcome requires reconciliation", now);
                        c.commit();
                        return find(deploymentId, operationKey).orElseThrow();
                    }
                    c.commit();
                    return current;
                }
                String sql = """
                        INSERT INTO wayang_deployment_operation_receipt
                        (deployment_id, operation_key, command_fingerprint, operation, status, attempt,
                         target_operation_key, result_code, result_detail, lease_until, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'RUNNING', 1, ?, NULL, NULL, ?, ?, ?)
                        """;
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, deploymentId);
                    ps.setString(2, operationKey);
                    ps.setString(3, fingerprint);
                    ps.setString(4, operation);
                    ps.setString(5, targetOperationKey);
                    ps.setTimestamp(6, Timestamp.from(leaseUntil));
                    ps.setTimestamp(7, Timestamp.from(now));
                    ps.setTimestamp(8, Timestamp.from(now));
                    ps.executeUpdate();
                }
                c.commit();
                return find(deploymentId, operationKey).orElseThrow();
            } catch (SQLException | RuntimeException failure) {
                rollback(c, failure);
                throw translate("Unable to claim deployment operation", failure);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to open operation receipt transaction", failure);
        }
    }

    @Override
    public DeploymentOperationReceipt complete(String deploymentId, String operationKey, String fingerprint,
            DeploymentOperationStatus status, String resultCode, String resultDetail, Instant now) {
        if (status != DeploymentOperationStatus.SUCCEEDED && status != DeploymentOperationStatus.FAILED) {
            throw new IllegalArgumentException("status must be terminal success or failure");
        }
        if (resultDetail != null && resultDetail.length() > 2048) throw new IllegalArgumentException("resultDetail too long");
        String sql = """
                UPDATE wayang_deployment_operation_receipt
                SET status = ?, result_code = ?, result_detail = ?, lease_until = NULL, updated_at = ?
                WHERE deployment_id = ? AND operation_key = ? AND command_fingerprint = ? AND status = 'RUNNING'
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, bounded(resultCode, 120));
            ps.setString(3, resultDetail);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setString(5, deploymentId);
            ps.setString(6, operationKey);
            ps.setString(7, fingerprint);
            if (ps.executeUpdate() != 1) throw new IllegalStateException("Operation is missing, changed, or no longer RUNNING");
            return find(deploymentId, operationKey).orElseThrow();
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to complete deployment operation", failure);
        }
    }

    @Override
    public Optional<DeploymentOperationReceipt> find(String deploymentId, String operationKey) {
        try (Connection c = dataSource.getConnection()) {
            return select(c, deploymentId, operationKey, false);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read deployment operation receipt", failure);
        }
    }

    @Override
    public List<DeploymentOperationReceipt> findExpiredRunning(Instant now, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        String sql = """
                SELECT deployment_id, operation_key, command_fingerprint, operation, status, attempt,
                       target_operation_key, result_code, result_detail, lease_until, created_at, updated_at
                FROM wayang_deployment_operation_receipt
                WHERE status = 'RUNNING' AND lease_until <= ?
                ORDER BY lease_until, deployment_id, operation_key
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setMaxRows(limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<DeploymentOperationReceipt> found = new ArrayList<>();
                while (rs.next()) found.add(read(rs));
                return List.copyOf(found);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to scan expired deployment operations", failure);
        }
    }

    @Override
    public DeploymentOperationReceipt markUnknown(String deploymentId, String operationKey, String reason, Instant now) {
        updateUnknown(deploymentId, operationKey, reason, now);
        return find(deploymentId, operationKey).orElseThrow();
    }

    private static void updateUnknown(Connection c, String deploymentId, String operationKey,
            String reason, Instant now) throws SQLException {
        String sql = """
                UPDATE wayang_deployment_operation_receipt
                SET status = 'UNKNOWN', result_code = 'OUTCOME_UNCERTAIN', result_detail = ?,
                    lease_until = NULL, updated_at = ?
                WHERE deployment_id = ? AND operation_key = ? AND status IN ('ACCEPTED', 'RUNNING')
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, bounded(reason, 2048));
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setString(3, deploymentId);
            ps.setString(4, operationKey);
            if (ps.executeUpdate() != 1) throw new IllegalStateException("Operation is missing or already terminal");
        }
    }

    private void updateUnknown(String deploymentId, String operationKey, String reason, Instant now) {
        String sql = """
                UPDATE wayang_deployment_operation_receipt
                SET status = 'UNKNOWN', result_code = 'OUTCOME_UNCERTAIN', result_detail = ?,
                    lease_until = NULL, updated_at = ?
                WHERE deployment_id = ? AND operation_key = ? AND status IN ('ACCEPTED', 'RUNNING')
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, bounded(reason, 2048));
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setString(3, deploymentId);
            ps.setString(4, operationKey);
            if (ps.executeUpdate() != 1) throw new IllegalStateException("Operation is missing or already terminal");
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to mark deployment operation UNKNOWN", failure);
        }
    }

    private static Optional<DeploymentOperationReceipt> select(Connection c, String deploymentId,
            String operationKey, boolean forUpdate) throws SQLException {
        String sql = """
                SELECT deployment_id, operation_key, command_fingerprint, operation, status, attempt,
                       target_operation_key, result_code, result_detail, lease_until, created_at, updated_at
                FROM wayang_deployment_operation_receipt WHERE deployment_id = ? AND operation_key = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, deploymentId);
            ps.setString(2, operationKey);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(read(rs)) : Optional.empty(); }
        }
    }

    private static DeploymentOperationReceipt read(ResultSet rs) throws SQLException {
        Timestamp lease = rs.getTimestamp("lease_until");
        return new DeploymentOperationReceipt(rs.getString("deployment_id"), rs.getString("operation_key"),
                rs.getString("command_fingerprint"), rs.getString("operation"),
                DeploymentOperationStatus.valueOf(rs.getString("status")), rs.getInt("attempt"),
                rs.getString("target_operation_key"), rs.getString("result_code"), rs.getString("result_detail"),
                lease == null ? null : lease.toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static void rollback(Connection c, Throwable failure) {
        try { c.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
    }

    private static RuntimeException translate(String message, Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException(message, failure);
    }

    private static String bounded(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
