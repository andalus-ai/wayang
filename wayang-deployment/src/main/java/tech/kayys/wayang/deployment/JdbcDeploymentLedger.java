package tech.kayys.wayang.deployment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * PostgreSQL-compatible JDBC ledger. Schema is managed by migration, not at runtime.
 * Snapshot updates and event inserts share one transaction; failed event insertion rolls back the snapshot.
 */
public final class JdbcDeploymentLedger implements DeploymentLedger {
    private final DataSource dataSource;

    public JdbcDeploymentLedger(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public DeploymentSnapshot register(DeploymentSnapshot initial, DeploymentEvent event) {
        validateInitial(initial, event);
        String sql = """
                INSERT INTO wayang_deployment_snapshot
                (deployment_id, tenant_id, environment, target_id, release_id, state, version, updated_at, last_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    bindSnapshot(statement, initial);
                    statement.executeUpdate();
                }
                insertEvent(connection, event);
                connection.commit();
                return initial;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw translate("Unable to register deployment", failure);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to open deployment ledger transaction", failure);
        }
    }

    @Override
    public Optional<DeploymentSnapshot> find(String deploymentId) {
        String sql = """
                SELECT deployment_id, tenant_id, environment, target_id, release_id, state, version, updated_at, last_reason
                FROM wayang_deployment_snapshot WHERE deployment_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, deploymentId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readSnapshot(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read deployment snapshot", failure);
        }
    }

    @Override
    public List<DeploymentEvent> events(String deploymentId) {
        String sql = """
                SELECT deployment_id, sequence, from_state, to_state, actor, reason, correlation_id,
                       idempotency_key, occurred_at
                FROM wayang_deployment_event WHERE deployment_id = ? ORDER BY sequence
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, deploymentId);
            try (ResultSet result = statement.executeQuery()) {
                List<DeploymentEvent> events = new ArrayList<>();
                while (result.next()) events.add(readEvent(result));
                return List.copyOf(events);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read deployment events", failure);
        }
    }

    @Override
    public DeploymentSnapshot append(long expectedVersion, DeploymentSnapshot next, DeploymentEvent event) {
        if (next.version() != expectedVersion + 1 || event.sequence() != next.version()
                || !next.deploymentId().equals(event.deploymentId()) || event.toState() != next.state()) {
            throw new IllegalArgumentException("Snapshot and event do not describe the expected next version");
        }
        String sql = """
                UPDATE wayang_deployment_snapshot
                SET state = ?, version = ?, updated_at = ?, last_reason = ?
                WHERE deployment_id = ? AND version = ? AND tenant_id = ? AND environment = ?
                  AND target_id = ? AND release_id = ? AND state = ?
                """;
        if (event.fromState() == null || event.sequence() != next.version()
                || !event.deploymentId().equals(next.deploymentId()) || event.toState() != next.state()) {
            throw new IllegalArgumentException("Event does not match deployment transition");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement update = connection.prepareStatement(sql)) {
                    update.setString(1, next.state().name());
                    update.setLong(2, next.version());
                    update.setTimestamp(3, Timestamp.from(next.updatedAt()));
                    update.setString(4, next.lastReason());
                    update.setString(5, next.deploymentId());
                    update.setLong(6, expectedVersion);
                    update.setString(7, next.tenantId());
                    update.setString(8, next.environment());
                    update.setString(9, next.targetId());
                    update.setString(10, next.releaseId());
                    update.setString(11, event.fromState().name());
                    if (update.executeUpdate() != 1) throw new IllegalStateException("Stale or unknown deployment version");
                }
                insertEvent(connection, event);
                connection.commit();
                return next;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw translate("Unable to append deployment event", failure);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to open deployment ledger transaction", failure);
        }
    }

    private static void insertEvent(Connection connection, DeploymentEvent event) throws SQLException {
        String sql = """
                INSERT INTO wayang_deployment_event
                (deployment_id, sequence, from_state, to_state, actor, reason, correlation_id, idempotency_key, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.deploymentId());
            statement.setLong(2, event.sequence());
            if (event.fromState() == null) statement.setNull(3, java.sql.Types.VARCHAR);
            else statement.setString(3, event.fromState().name());
            statement.setString(4, event.toState().name());
            statement.setString(5, event.actor());
            statement.setString(6, event.reason());
            statement.setString(7, event.correlationId());
            statement.setString(8, event.idempotencyKey());
            statement.setTimestamp(9, Timestamp.from(event.occurredAt()));
            statement.executeUpdate();
        }
    }

    private static void bindSnapshot(PreparedStatement statement, DeploymentSnapshot snapshot) throws SQLException {
        statement.setString(1, snapshot.deploymentId());
        statement.setString(2, snapshot.tenantId());
        statement.setString(3, snapshot.environment());
        statement.setString(4, snapshot.targetId());
        statement.setString(5, snapshot.releaseId());
        statement.setString(6, snapshot.state().name());
        statement.setLong(7, snapshot.version());
        statement.setTimestamp(8, Timestamp.from(snapshot.updatedAt()));
        statement.setString(9, snapshot.lastReason());
    }

    private static DeploymentSnapshot readSnapshot(ResultSet result) throws SQLException {
        return new DeploymentSnapshot(result.getString("deployment_id"), result.getString("tenant_id"),
                result.getString("environment"), result.getString("target_id"), result.getString("release_id"),
                DeploymentState.valueOf(result.getString("state")), result.getLong("version"),
                result.getTimestamp("updated_at").toInstant(), result.getString("last_reason"));
    }

    private static DeploymentEvent readEvent(ResultSet result) throws SQLException {
        String from = result.getString("from_state");
        return new DeploymentEvent(result.getString("deployment_id"), result.getLong("sequence"),
                from == null ? null : DeploymentState.valueOf(from), DeploymentState.valueOf(result.getString("to_state")),
                result.getString("actor"), result.getString("reason"), result.getString("correlation_id"),
                result.getString("idempotency_key"), result.getTimestamp("occurred_at").toInstant());
    }

    private static void validateInitial(DeploymentSnapshot initial, DeploymentEvent event) {
        if (initial.state() != DeploymentState.INSTALLED || initial.version() != 0
                || event.fromState() != null || event.toState() != DeploymentState.INSTALLED
                || event.sequence() != 0 || !initial.deploymentId().equals(event.deploymentId())) {
            throw new IllegalArgumentException("Initial deployment must be version-zero INSTALLED with a matching event");
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
    }

    private static RuntimeException translate(String message, Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException(message, failure);
    }
}
