package tech.kayys.wayang.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import tech.kayys.wayang.runtime.WaitRegistry;
import tech.kayys.wayang.runtime.json.Json;
import tech.kayys.wayang.runtime.WaitTicket;

/**
 * Durable {@link WaitRegistry} over plain JDBC. Several Wayang nodes may share
 * one database: {@code take} is a conditional DELETE (exactly one caller wins)
 * and {@code register} relies on the unique {@code (run_id, node_id)} key.
 *
 * <p>
 * <strong>Verification status:</strong> compiled only. It has <em>not</em> been
 * executed against any database (no JDBC driver was available when it was
 * written). Run {@code WaitRegistryContract.verify(...)} against your database
 * before relying on it. Create the tables with
 * {@code wayang/schema-wait-registry.sql}.
 */
public final class JdbcWaitRegistry implements WaitRegistry {

    private static final int MAX_BUFFERED_PER_KEY = 100;
    private static final String COLS = "token, run_id, node_id, kind, info_json, due_at_ms, message_name, correlation_key";

    private final DataSource ds;

    public JdbcWaitRegistry(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public WaitTicket register(String runId, WaitTicket t) {
        try (Connection c = ds.getConnection()) {
            Optional<Registered> existing = query(c, "SELECT " + COLS + " FROM wayang_wait WHERE run_id = ? AND node_id = ?",
                    runId, t.nodeId()).stream().findFirst();
            if (existing.isPresent()) {
                return existing.get().ticket();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO wayang_wait (" + COLS
                    + ", created_ms) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, t.token());
                ps.setString(2, runId);
                ps.setString(3, t.nodeId());
                ps.setString(4, t.kind());
                ps.setString(5, Json.write(t.info()));
                if (t.dueAt() == null) {
                    ps.setNull(6, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(6, t.dueAt().toEpochMilli());
                }
                ps.setString(7, t.messageName());
                ps.setString(8, t.correlationKey());
                ps.setLong(9, System.currentTimeMillis());
                ps.executeUpdate();
                return t;
            } catch (SQLException e) {
                if (isUniqueViolation(e)) { // another node registered the same run+node first
                    return query(c, "SELECT " + COLS + " FROM wayang_wait WHERE run_id = ? AND node_id = ?", runId,
                            t.nodeId()).stream().findFirst().orElseThrow(() -> e).ticket();
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("register failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Registered> take(String token) {
        try (Connection c = ds.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                Optional<Registered> r = query(c, "SELECT " + COLS + " FROM wayang_wait WHERE token = ?", token).stream()
                        .findFirst();
                if (r.isEmpty()) {
                    c.rollback();
                    return Optional.empty();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_wait WHERE token = ?")) {
                    ps.setString(1, token);
                    if (ps.executeUpdate() != 1) { // somebody else took it between our SELECT and DELETE
                        c.rollback();
                        return Optional.empty();
                    }
                }
                c.commit();
                return r;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("take failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Registered> find(String token) {
        return one("SELECT " + COLS + " FROM wayang_wait WHERE token = ?", token);
    }

    @Override
    public Optional<Registered> findByNode(String runId, String nodeId) {
        return one("SELECT " + COLS + " FROM wayang_wait WHERE run_id = ? AND node_id = ?", runId, nodeId);
    }

    @Override
    public List<Registered> due(Instant now) {
        try (Connection c = ds.getConnection()) {
            return query(c, "SELECT " + COLS + " FROM wayang_wait WHERE kind = 'timer' AND due_at_ms IS NOT NULL"
                    + " AND due_at_ms <= ? ORDER BY due_at_ms", now.toEpochMilli());
        } catch (SQLException e) {
            throw new IllegalStateException("due failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Registered> listByRun(String runId) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLS
                    + " FROM wayang_wait WHERE run_id = ? ORDER BY created_ms, token")) {
                ps.setString(1, runId);
                return read(ps);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("listByRun failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Registered> listByKind(String kind, int limit) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLS
                    + " FROM wayang_wait WHERE kind = ? ORDER BY created_ms, token")) {
                ps.setString(1, kind);
                ps.setMaxRows(limit);
                return read(ps);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("listByKind failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Registered> findMessage(String name, String correlationKey) {
        return one("SELECT " + COLS + " FROM wayang_wait WHERE kind = 'message' AND message_name = ?"
                + " AND correlation_key = ? ORDER BY created_ms", name, correlationKey);
    }

    @Override
    public void bufferMessage(String name, String correlationKey, Map<String, Object> payload) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM wayang_buffered_message WHERE message_name = ? AND correlation_key = ?")) {
                ps.setString(1, name);
                ps.setString(2, correlationKey);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) >= MAX_BUFFERED_PER_KEY) { // approximate under concurrency
                        throw new IllegalStateException("Too many undelivered messages for " + name + "/" + correlationKey);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO wayang_buffered_message"
                    + " (id, message_name, correlation_key, payload_json, created_ms) VALUES (?,?,?,?,?)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, name);
                ps.setString(3, correlationKey);
                ps.setString(4, Json.write(payload));
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("bufferMessage failed: " + e.getMessage(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> takeBuffered(String name, String correlationKey) {
        try (Connection c = ds.getConnection()) {
            while (true) { // loop: another node may win the DELETE for the oldest row
                String id;
                String payload;
                try (PreparedStatement ps = c.prepareStatement("SELECT id, payload_json FROM wayang_buffered_message"
                        + " WHERE message_name = ? AND correlation_key = ? ORDER BY created_ms, id")) {
                    ps.setString(1, name);
                    ps.setString(2, correlationKey);
                    ps.setMaxRows(1);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return Optional.empty();
                        }
                        id = rs.getString(1);
                        payload = rs.getString(2);
                    }
                }
                try (PreparedStatement del = c.prepareStatement("DELETE FROM wayang_buffered_message WHERE id = ?")) {
                    del.setString(1, id);
                    if (del.executeUpdate() == 1) {
                        return Optional.of((Map<String, Object>) Json.read(payload));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("takeBuffered failed: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private Optional<Registered> one(String sql, Object... args) {
        try (Connection c = ds.getConnection()) {
            return query(c, sql, args).stream().findFirst();
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + e.getMessage(), e);
        }
    }

    private static List<Registered> query(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Long l) {
                    ps.setLong(i + 1, l);
                } else {
                    ps.setString(i + 1, (String) args[i]);
                }
            }
            return read(ps);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Registered> read(PreparedStatement ps) throws SQLException {
        List<Registered> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long due = rs.getLong(6);
                Instant dueAt = rs.wasNull() ? null : Instant.ofEpochMilli(due);
                WaitTicket t = new WaitTicket(rs.getString(1), rs.getString(3), rs.getString(4),
                        (Map<String, Object>) Json.read(rs.getString(5)), dueAt, rs.getString(7), rs.getString(8));
                out.add(new Registered(rs.getString(2), t));
            }
        }
        return out;
    }

    private static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }
}
