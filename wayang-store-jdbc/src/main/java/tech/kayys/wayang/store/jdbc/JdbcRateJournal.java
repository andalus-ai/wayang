package tech.kayys.wayang.store.jdbc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.sql.DataSource;

import tech.kayys.wayang.runtime.RateLimiter;

/** {@link RateLimiter.Journal} over plain JDBC. Keys are stored as SHA-256 hashes, so a chat or sender id is not kept in the clear. */
public final class JdbcRateJournal implements RateLimiter.Journal {

    private final DataSource ds;

    public JdbcRateJournal(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public List<Instant> load(String scope, String key, Instant since) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT at_ms FROM wayang_rate_events WHERE scope = ? AND key_hash = ? AND at_ms > ?")) {
            ps.setString(1, scope);
            ps.setString(2, hash(key));
            ps.setLong(3, since.toEpochMilli());
            List<Instant> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(Instant.ofEpochMilli(rs.getLong(1)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Rate journal read failed");
        }
    }

    @Override
    public void record(String scope, String key, Instant at) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO wayang_rate_events (scope, key_hash, at_ms) VALUES (?,?,?)")) {
            ps.setString(1, scope);
            ps.setString(2, hash(key));
            ps.setLong(3, at.toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Rate journal write failed");
        }
    }

    @Override
    public void purgeBefore(Instant before) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_rate_events WHERE at_ms < ?")) {
            ps.setLong(1, before.toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Rate journal purge failed");
        }
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
