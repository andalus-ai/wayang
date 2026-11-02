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

import tech.kayys.wayang.api.secret.SealedSecret;
import tech.kayys.wayang.api.secret.SecretStore;

/** Stores ciphertext only; compare-and-set is done in SQL so it holds across nodes. */
public final class JdbcSecretStore implements SecretStore {

    private static final String COLS = "tenant_id, name, version, key_id, sealed, updated_ms, updated_by";
    private final DataSource ds;

    public JdbcSecretStore(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public Optional<SealedSecret> get(String tenant, String name) {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM wayang_secret WHERE tenant_id=? AND name=?")) {
            ps.setString(1, tenant);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Secret store failure: " + e.getMessage(), e);
        }
    }

    @Override
    public List<SealedSecret> list(String tenant) {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM wayang_secret WHERE tenant_id=? ORDER BY name")) {
            ps.setString(1, tenant);
            List<SealedSecret> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Secret store failure: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean put(SealedSecret s, int expectedVersion) {
        try (Connection c = ds.getConnection()) {
            if (expectedVersion == 0) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO wayang_secret (" + COLS
                        + ") VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
                    bind(ps, s);
                    return ps.executeUpdate() == 1;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE wayang_secret SET version=?, key_id=?, sealed=?, "
                    + "updated_ms=?, updated_by=? WHERE tenant_id=? AND name=? AND version=?")) {
                ps.setInt(1, s.version());
                ps.setString(2, s.keyId());
                ps.setString(3, s.sealed());
                ps.setLong(4, s.updatedAt().toEpochMilli());
                ps.setString(5, s.updatedBy());
                ps.setString(6, s.tenant());
                ps.setString(7, s.name());
                ps.setInt(8, expectedVersion);
                return ps.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Secret store failure: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean reseal(SealedSecret s) {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE wayang_secret SET key_id=?, sealed=? WHERE tenant_id=? AND name=? AND version=?")) {
            ps.setString(1, s.keyId());
            ps.setString(2, s.sealed());
            ps.setString(3, s.tenant());
            ps.setString(4, s.name());
            ps.setInt(5, s.version());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Secret store failure: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete(String tenant, String name) {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_secret WHERE tenant_id=? AND name=?")) {
            ps.setString(1, tenant);
            ps.setString(2, name);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Secret store failure: " + e.getMessage(), e);
        }
    }

    private static void bind(PreparedStatement ps, SealedSecret s) throws SQLException {
        ps.setString(1, s.tenant());
        ps.setString(2, s.name());
        ps.setInt(3, s.version());
        ps.setString(4, s.keyId());
        ps.setString(5, s.sealed());
        ps.setLong(6, s.updatedAt().toEpochMilli());
        ps.setString(7, s.updatedBy());
    }

    private static SealedSecret read(ResultSet rs) throws SQLException {
        return new SealedSecret(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                Instant.ofEpochMilli(rs.getLong(6)), rs.getString(7));
    }
}
