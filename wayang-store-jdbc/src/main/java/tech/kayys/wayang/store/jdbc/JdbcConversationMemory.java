package tech.kayys.wayang.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import tech.kayys.wayang.agent.ConversationMemory;

/**
 * {@link ConversationMemory} in the shared database: one row per exchange, so every replica sees the same conversation. Turns older than
 * the retention are deleted on every 64th append.
 */
public final class JdbcConversationMemory implements ConversationMemory {

    private final DataSource ds;
    private final Duration retention;
    private final AtomicInteger appends = new AtomicInteger();

    public JdbcConversationMemory(DataSource ds, Duration retention) {
        this.ds = ds;
        this.retention = retention;
    }

    private static IllegalStateException failure(String what, SQLException e) {
        return new IllegalStateException("Conversation memory " + what + " failed: " + e.getMessage(), e);
    }

    @Override
    public List<Turn> load(String ns, String key, int maxTurns) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT user_msg, assistant_msg, at_ms FROM wayang_agent_memory WHERE ns = ? AND k = ? ORDER BY seq DESC LIMIT ?")) {
            ps.setString(1, ns);
            ps.setString(2, key);
            ps.setInt(3, Math.max(0, maxTurns));
            List<Turn> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Turn(rs.getString(1), rs.getString(2), rs.getLong(3)));
                }
            }
            Collections.reverse(out);
            return out;
        } catch (SQLException e) {
            throw failure("load", e);
        }
    }

    @Override
    public void append(String ns, String key, Turn turn, int maxTurns) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO wayang_agent_memory (ns, k, at_ms, user_msg, assistant_msg) VALUES (?,?,?,?,?)")) {
                    ps.setString(1, ns);
                    ps.setString(2, key);
                    ps.setLong(3, turn.atMs());
                    ps.setString(4, turn.user());
                    ps.setString(5, turn.assistant());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_agent_memory WHERE ns = ? AND k = ? AND seq NOT IN ("
                        + "SELECT seq FROM wayang_agent_memory WHERE ns = ? AND k = ? ORDER BY seq DESC LIMIT ?)")) {
                    ps.setString(1, ns);
                    ps.setString(2, key);
                    ps.setString(3, ns);
                    ps.setString(4, key);
                    ps.setInt(5, Math.max(1, maxTurns));
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
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
        if (appends.incrementAndGet() % 64 == 0) {
            purge(System.currentTimeMillis() - retention.toMillis());
        }
    }

    @Override
    public boolean clear(String ns, String key) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_agent_memory WHERE ns = ? AND k = ?")) {
            ps.setString(1, ns);
            ps.setString(2, key);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw failure("clear", e);
        }
    }

    @Override
    public int purge(long cutoffMs) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_agent_memory WHERE at_ms < ?")) {
            ps.setLong(1, cutoffMs);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("purge", e);
        }
    }
}
