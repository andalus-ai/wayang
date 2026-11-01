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

import tech.kayys.wayang.api.ConfigEntry;
import tech.kayys.wayang.api.ConfigStore;

/** Plain settings in SQL; compare-and-set is a conditional INSERT or UPDATE so it holds across nodes. */
public final class JdbcConfigStore implements ConfigStore {

    private static final String COLS = "tenant_id, scope, k, v, version, updated_ms, updated_by";
    private final DataSource ds;

    public JdbcConfigStore(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public Optional<ConfigEntry> get(String tenant, String scope, String key) {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM wayang_config WHERE tenant_id=? AND scope=? AND k=?")) {
            ps.setString(1, tenant);
            ps.setString(2, scope);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw fail(e);
        }
    }

    @Override
    public List<ConfigEntry> list(String tenant, String scope) {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM wayang_config WHERE tenant_id=? AND scope=? ORDER BY k")) {
            ps.setString(1, tenant);
            ps.setString(2, scope);
            List<ConfigEntry> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw fail(e);
        }
    }

    @Override
    public boolean put(ConfigEntry e, int expectedVersion) {
        try (Connection c = ds.getConnection()) {
            if (expectedVersion == 0) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO wayang_config (" + COLS + ") VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
                    ps.setString(1, e.tenant());
                    ps.setString(2, e.scope());
                    ps.setString(3, e.key());
                    ps.setString(4, e.value());
                    ps.setInt(5, e.version());
                    ps.setLong(6, e.updatedAt().toEpochMilli());
                    ps.setString(7, e.updatedBy());
                    return ps.executeUpdate() == 1;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE wayang_config SET v=?, version=?, updated_ms=?, updated_by=? WHERE tenant_id=? AND scope=? AND k=? AND version=?")) {
                ps.setString(1, e.value());
                ps.setInt(2, e.version());
                ps.setLong(3, e.updatedAt().toEpochMilli());
                ps.setString(4, e.updatedBy());
                ps.setString(5, e.tenant());
                ps.setString(6, e.scope());
                ps.setString(7, e.key());
                ps.setInt(8, expectedVersion);
                return ps.executeUpdate() == 1;
            }
        } catch (SQLException ex) {
            throw fail(ex);
        }
    }

    @Override
    public boolean delete(String tenant, String scope, String key) {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_config WHERE tenant_id=? AND scope=? AND k=?")) {
            ps.setString(1, tenant);
            ps.setString(2, scope);
            ps.setString(3, key);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw fail(e);
        }
    }

    @Override
    public int deleteScope(String tenant, String scope) {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_config WHERE tenant_id=? AND scope=?")) {
            ps.setString(1, tenant);
            ps.setString(2, scope);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw fail(e);
        }
    }

    private static ConfigEntry read(ResultSet rs) throws SQLException {
        return new ConfigEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                Instant.ofEpochMilli(rs.getLong(6)), rs.getString(7));
    }

    private static IllegalStateException fail(SQLException e) {
        return new IllegalStateException("Config store failure: " + e.getMessage(), e);
    }
}
