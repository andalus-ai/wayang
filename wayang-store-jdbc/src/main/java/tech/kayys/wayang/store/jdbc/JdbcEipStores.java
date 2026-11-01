package tech.kayys.wayang.store.jdbc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import tech.kayys.wayang.eip.AggregationStore;
import tech.kayys.wayang.eip.ClaimStore;
import tech.kayys.wayang.eip.EipException;
import tech.kayys.wayang.eip.Formats;
import tech.kayys.wayang.eip.IdempotencyStore;

/** Durable, replica-shared stores for the EIP steps ({@code claimCheck}, {@code collect}, {@code idempotent}) over plain JDBC. */
public final class JdbcEipStores {

    private JdbcEipStores() {
    }

    private static IllegalStateException failure(String what, SQLException e) {
        return new IllegalStateException("EIP store " + what + " failed: " + e.getMessage(), e);
    }

    /** Claim check bodies as JSON text. Expired rows are purged opportunistically on put. */
    public static final class Claims implements ClaimStore {
        private final DataSource ds;
        private final AtomicLong puts = new AtomicLong();

        public Claims(DataSource ds) {
            this.ds = ds;
        }

        @Override
        public void put(String ns, String token, Object body, Instant expires) {
            String json = Formats.serialize("json", body, Map.of());
            try (Connection c = ds.getConnection()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_eip_claim WHERE ns = ? AND token = ?")) {
                    ps.setString(1, ns);
                    ps.setString(2, token);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO wayang_eip_claim (ns, token, expires_ms, body) VALUES (?,?,?,?)")) {
                    ps.setString(1, ns);
                    ps.setString(2, token);
                    ps.setLong(3, expires.toEpochMilli());
                    ps.setString(4, json);
                    ps.executeUpdate();
                }
                if (puts.incrementAndGet() % 200 == 0) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_eip_claim WHERE expires_ms < ?")) {
                        ps.setLong(1, System.currentTimeMillis() - 60_000);
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                throw failure("put", e);
            }
        }

        @Override
        public Optional<Object> get(String ns, String token, Instant now) {
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                    "SELECT body FROM wayang_eip_claim WHERE ns = ? AND token = ? AND expires_ms > ?")) {
                ps.setString(1, ns);
                ps.setString(2, token);
                ps.setLong(3, now.toEpochMilli());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(Formats.parse("json", rs.getString(1), Map.of())) : Optional.empty();
                }
            } catch (SQLException e) {
                throw failure("get", e);
            }
        }

        @Override
        public void delete(String ns, String token) {
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM wayang_eip_claim WHERE ns = ? AND token = ?")) {
                ps.setString(1, ns);
                ps.setString(2, token);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw failure("delete", e);
            }
        }
    }

    /** Groups of the {@code collect} step. {@code claim} is a single DELETE, so of several replicas exactly one gets rows back. */
    public static final class Aggregation implements AggregationStore {
        private final DataSource ds;
        private final Duration retention;
        private final java.util.concurrent.atomic.AtomicInteger appends = new java.util.concurrent.atomic.AtomicInteger();

        public Aggregation(DataSource ds) {
            this(ds, Duration.ofDays(30));
        }

        /** @param retention groups that have not completed after this long are dropped (checked on every 32nd append) */
        public Aggregation(DataSource ds, Duration retention) {
            this.ds = ds;
            this.retention = retention;
        }

        @Override
        public java.util.Set<String> namespaces() {
            java.util.Set<String> out = new java.util.TreeSet<>();
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT DISTINCT ns FROM wayang_eip_agg");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            } catch (SQLException e) {
                throw failure("namespaces", e);
            }
        }

        @Override
        public List<Group> expired(String ns, long cutoffMs, int limit) {
            try (Connection c = ds.getConnection()) {
                List<String> keys = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT k FROM wayang_eip_agg WHERE ns = ? GROUP BY k HAVING MIN(created_ms) < ? "
                        + "ORDER BY MIN(created_ms) LIMIT ?")) {
                    ps.setString(1, ns);
                    ps.setLong(2, cutoffMs);
                    ps.setInt(3, Math.max(1, limit));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            keys.add(rs.getString(1));
                        }
                    }
                }
                List<Group> out = new ArrayList<>();
                for (String k : keys) {
                    List<Entry> es = new ArrayList<>();
                    try (PreparedStatement ps = c.prepareStatement("SELECT seq, created_ms, msg FROM wayang_eip_agg WHERE ns = ? AND k = ? ORDER BY seq")) {
                        ps.setString(1, ns);
                        ps.setString(2, k);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                es.add(new Entry(rs.getLong(1), rs.getLong(2), rs.getString(3)));
                            }
                        }
                    }
                    if (!es.isEmpty()) {
                        out.add(new Group(k, es));
                    }
                }
                return out;
            } catch (SQLException e) {
                throw failure("expired", e);
            }
        }

        @Override
        public int purge(long cutoffMs) {
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_eip_agg WHERE created_ms < ?")) {
                ps.setLong(1, cutoffMs);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw failure("purge", e);
            }
        }

        @Override
        public List<Entry> append(String ns, String key, String json, long nowMs, int maxEntries) {
            if (appends.incrementAndGet() % 32 == 0) {
                purge(nowMs - retention.toMillis());
            }
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                try {
                    // serialise appends to one group so the size cap cannot be overshot by concurrent inserts
                    try (PreparedStatement lock = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
                        lock.setString(1, ns + '\0' + key);
                        lock.execute();
                    } catch (SQLException noAdvisory) {
                        c.rollback(); // not PostgreSQL: fall back to no lock (the cap may then be exceeded by a race)
                    }
                    int existing;
                    try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM wayang_eip_agg WHERE ns = ? AND k = ?")) {
                        ps.setString(1, ns);
                        ps.setString(2, key);
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            existing = rs.getInt(1);
                        }
                    }
                    if (existing >= maxEntries) {
                        c.rollback();
                        throw new EipException("collect group '" + key + "' exceeds " + maxEntries + " messages");
                    }
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO wayang_eip_agg (ns, k, created_ms, msg) VALUES (?,?,?,?)")) {
                        ps.setString(1, ns);
                        ps.setString(2, key);
                        ps.setLong(3, nowMs);
                        ps.setString(4, json);
                        ps.executeUpdate();
                    }
                    List<Entry> out = new ArrayList<>();
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT seq, created_ms, msg FROM wayang_eip_agg WHERE ns = ? AND k = ? ORDER BY seq")) {
                        ps.setString(1, ns);
                        ps.setString(2, key);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                out.add(new Entry(rs.getLong(1), rs.getLong(2), rs.getString(3)));
                            }
                        }
                    }
                    c.commit();
                    return out;
                } catch (RuntimeException | SQLException e) {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {
                        // the original failure matters more
                    }
                    throw e;
                }
            } catch (SQLException e) {
                throw failure("append", e);
            }
        }

        @Override
        public boolean claim(String ns, String key, long upToSeq) {
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM wayang_eip_agg WHERE ns = ? AND k = ? AND seq <= ?")) {
                ps.setString(1, ns);
                ps.setString(2, key);
                ps.setLong(3, upToSeq);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                throw failure("claim", e);
            }
        }
    }

    /** Effectively-once receiver backed by a table: INSERT ... ON CONFLICT DO NOTHING. Old keys are purged after {@code retention}. */
    public static final class Idempotency implements IdempotencyStore {
        private final DataSource ds;
        private final Clock clock;
        private final Duration retention;
        private final AtomicLong inserts = new AtomicLong();

        public Idempotency(DataSource ds, Duration retention) {
            this(ds, retention, Clock.systemUTC());
        }

        Idempotency(DataSource ds, Duration retention, Clock clock) {
            this.ds = ds;
            this.retention = retention;
            this.clock = clock;
        }

        static String hash(String key) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean markIfNew(String key) {
            try (Connection c = ds.getConnection()) {
                boolean fresh;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO wayang_eip_seen (k, seen_ms) VALUES (?,?) ON CONFLICT DO NOTHING")) {
                    ps.setString(1, hash(key));
                    ps.setLong(2, clock.millis());
                    fresh = ps.executeUpdate() == 1;
                }
                if (fresh && inserts.incrementAndGet() % 500 == 0) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_eip_seen WHERE seen_ms < ?")) {
                        ps.setLong(1, clock.millis() - retention.toMillis());
                        ps.executeUpdate();
                    }
                }
                return fresh;
            } catch (SQLException e) {
                throw failure("markIfNew", e);
            }
        }
    }
}
