package tech.kayys.wayang.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.OAuthConnection;
import tech.kayys.wayang.api.OAuthStore;
import tech.kayys.wayang.runtime.json.Json;

/** {@link OAuthStore} over plain JDBC. Holds no tokens: those live (encrypted) in the secret store. */
public final class JdbcOAuthStore implements OAuthStore {

    private static final String COLS = "tenant_id, connection_id, provider, client_id, client_secret, scopes, status, created_by,"
            + " created_ms, connected_ms";
    private final DataSource ds;

    public JdbcOAuthStore(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void create(OAuthConnection x) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO wayang_oauth_connection (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, x.tenant());
            ps.setString(2, x.id());
            ps.setString(3, x.provider());
            ps.setString(4, x.clientId());
            ps.setString(5, x.clientSecretName());
            ps.setString(6, Json.write(x.scopes()));
            ps.setString(7, x.status().name());
            ps.setString(8, x.createdBy());
            ps.setLong(9, x.createdAt().toEpochMilli());
            if (x.connectedAt() == null) {
                ps.setNull(10, java.sql.Types.BIGINT);
            } else {
                ps.setLong(10, x.connectedAt().toEpochMilli());
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new ApiException(ApiException.Code.CONFLICT, "Connection '" + x.id() + "' already exists");
            }
            throw failure("create", e);
        }
    }

    @Override
    public Optional<OAuthConnection> get(String tenant, String id) {
        List<OAuthConnection> l = query("SELECT " + COLS + " FROM wayang_oauth_connection WHERE tenant_id = ? AND connection_id = ?",
                tenant, id);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    @Override
    public List<OAuthConnection> list(String tenant) {
        return query("SELECT " + COLS + " FROM wayang_oauth_connection WHERE tenant_id = ? ORDER BY created_ms, connection_id", tenant);
    }

    @Override
    public void update(OAuthConnection x) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE wayang_oauth_connection SET status = ?, connected_ms = ? WHERE tenant_id = ? AND connection_id = ?")) {
            ps.setString(1, x.status().name());
            if (x.connectedAt() == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, x.connectedAt().toEpochMilli());
            }
            ps.setString(3, x.tenant());
            ps.setString(4, x.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("update", e);
        }
    }

    @Override
    public boolean delete(String tenant, String id) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_oauth_state WHERE tenant_id = ? AND connection_id = ?")) {
                ps.setString(1, tenant);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_oauth_connection WHERE tenant_id = ? AND connection_id = ?")) {
                ps.setString(1, tenant);
                ps.setString(2, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            throw failure("delete", e);
        }
    }

    @Override
    public void saveState(State s) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO wayang_oauth_state (state, tenant_id, connection_id, verifier, expires_ms) VALUES (?,?,?,?,?)")) {
            ps.setString(1, s.state());
            ps.setString(2, s.tenant());
            ps.setString(3, s.connectionId());
            ps.setString(4, s.verifier());
            ps.setLong(5, s.expiresAt().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("saveState", e);
        }
    }

    /** DELETE ... RETURNING is atomic, so two concurrent callbacks cannot both consume one state. */
    @Override
    public Optional<State> takeState(String state, Instant now) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM wayang_oauth_state WHERE state = ? RETURNING tenant_id, connection_id, verifier, expires_ms")) {
            ps.setString(1, state);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Instant exp = Instant.ofEpochMilli(rs.getLong(4));
                return exp.isAfter(now) ? Optional.of(new State(state, rs.getString(1), rs.getString(2), rs.getString(3), exp))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("takeState", e);
        }
    }

    private List<OAuthConnection> query(String sql, String... args) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            List<OAuthConnection> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long conn = rs.getLong(10);
                    boolean none = rs.wasNull();
                    @SuppressWarnings("unchecked")
                    List<String> scopes = (List<String>) (List<?>) Json.read(rs.getString(6));
                    out.add(new OAuthConnection(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            scopes, OAuthConnection.Status.valueOf(rs.getString(7)), rs.getString(8),
                            Instant.ofEpochMilli(rs.getLong(9)), none ? null : Instant.ofEpochMilli(conn)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw failure("query", e);
        }
    }

    private static ApiException failure(String op, SQLException e) {
        return new ApiException(ApiException.Code.UNAVAILABLE, "OAuth store " + op + " failed: " + e.getMessage());
    }
}
