package tech.kayys.wayang.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.trigger.Trigger;
import tech.kayys.wayang.api.trigger.TriggerStore;
import tech.kayys.wayang.runtime.json.Json;

/** {@link TriggerStore} over plain JDBC ({@code wayang_trigger}). The config holds secret *references*, never values. */
public final class JdbcTriggerStore implements TriggerStore {

    private static final String COLS = "tenant_id, trigger_id, kind, process_id, enabled, config, cursor_value, secret_hash,"
            + " last_polled_ms, last_error, failures, created_by, created_ms";
    private final DataSource ds;

    public JdbcTriggerStore(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void create(Trigger t) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO wayang_trigger (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            bind(ps, t);
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new ApiException(ApiException.Code.CONFLICT, "Trigger '" + t.id() + "' already exists");
            }
            throw failure("create", e);
        }
    }

    @Override
    public Optional<Trigger> get(String tenant, String id) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLS + " FROM wayang_trigger WHERE tenant_id = ? AND trigger_id = ?")) {
            ps.setString(1, tenant);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("get", e);
        }
    }

    @Override
    public List<Trigger> list(String tenant) {
        return query("SELECT " + COLS + " FROM wayang_trigger WHERE tenant_id = ? ORDER BY created_ms, trigger_id", tenant);
    }

    @Override
    public List<Trigger> pollable() {
        return query("SELECT " + COLS + " FROM wayang_trigger WHERE enabled = TRUE AND kind IN ('POLL', 'SCHEDULE') ORDER BY tenant_id, trigger_id");
    }

    @Override
    public void update(Trigger t) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE wayang_trigger SET enabled = ?, config = ?, cursor_value = ?, last_polled_ms = ?, last_error = ?, failures = ?"
                        + " WHERE tenant_id = ? AND trigger_id = ?")) {
            ps.setBoolean(1, t.enabled());
            ps.setString(2, Json.write(t.config()));
            ps.setString(3, t.cursor());
            if (t.lastPolledAt() == null) {
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setLong(4, t.lastPolledAt().toEpochMilli());
            }
            ps.setString(5, t.lastError());
            ps.setInt(6, t.consecutiveFailures());
            ps.setString(7, t.tenant());
            ps.setString(8, t.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("update", e);
        }
    }

    @Override
    public boolean delete(String tenant, String id) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM wayang_trigger WHERE tenant_id = ? AND trigger_id = ?")) {
            ps.setString(1, tenant);
            ps.setString(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw failure("delete", e);
        }
    }

    private List<Trigger> query(String sql, String... args) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            List<Trigger> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw failure("query", e);
        }
    }

    private static void bind(PreparedStatement ps, Trigger t) throws SQLException {
        ps.setString(1, t.tenant());
        ps.setString(2, t.id());
        ps.setString(3, t.kind().name());
        ps.setString(4, t.processId());
        ps.setBoolean(5, t.enabled());
        ps.setString(6, Json.write(t.config()));
        ps.setString(7, t.cursor());
        ps.setString(8, t.secretHash());
        if (t.lastPolledAt() == null) {
            ps.setNull(9, Types.BIGINT);
        } else {
            ps.setLong(9, t.lastPolledAt().toEpochMilli());
        }
        ps.setString(10, t.lastError());
        ps.setInt(11, t.consecutiveFailures());
        ps.setString(12, t.createdBy());
        ps.setLong(13, t.createdAt().toEpochMilli());
    }

    @SuppressWarnings("unchecked")
    private static Trigger read(ResultSet rs) throws SQLException {
        long polled = rs.getLong(9);
        boolean polledNull = rs.wasNull();
        return new Trigger(rs.getString(1), rs.getString(2), Trigger.Kind.valueOf(rs.getString(3)), rs.getString(4), rs.getBoolean(5),
                (Map<String, Object>) Json.read(rs.getString(6)), rs.getString(7), rs.getString(8),
                polledNull ? null : Instant.ofEpochMilli(polled), rs.getString(10), rs.getInt(11), rs.getString(12),
                Instant.ofEpochMilli(rs.getLong(13)));
    }

    private static ApiException failure(String op, SQLException e) {
        return new ApiException(ApiException.Code.UNAVAILABLE, "Trigger store " + op + " failed: " + e.getMessage());
    }
}
