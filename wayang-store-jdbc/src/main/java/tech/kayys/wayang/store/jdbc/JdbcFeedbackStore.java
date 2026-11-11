package tech.kayys.wayang.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.feedback.FeedbackEntry;
import tech.kayys.wayang.api.feedback.FeedbackStore;
import tech.kayys.wayang.runtime.json.Json;

/** {@link FeedbackStore} over plain JDBC. INSERT and SELECT, plus the age-based DELETE of {@link #purgeBefore}; rows are never updated. */
public final class JdbcFeedbackStore implements FeedbackStore {

    private final DataSource ds;

    public JdbcFeedbackStore(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void append(FeedbackEntry e) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO wayang_feedback (tenant_id, at_ms, kind, entry) VALUES (?,?,?,?)")) {
            ps.setString(1, e.tenant());
            ps.setLong(2, e.time().toEpochMilli());
            ps.setString(3, e.kind());
            ps.setString(4, Json.write(e.toMap()));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw failure("append");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public java.util.Optional<FeedbackEntry> find(String tenant, String id) {
        if (!id.matches("[0-9a-f-]{36}")) {
            return java.util.Optional.empty(); // ids are UUIDs; anything else cannot exist (and never reaches the LIKE below)
        }
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT at_ms, entry FROM wayang_feedback WHERE tenant_id = ? AND entry LIKE ?")) {
            ps.setString(1, tenant);
            ps.setString(2, "%\"id\":\"" + id + "\"%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = (Map<String, Object>) Json.read(rs.getString(2));
                    if (id.equals(m.get("id"))) {
                        return java.util.Optional.of(read(tenant, rs.getLong(1), m, statuses(c, tenant, List.of(id))));
                    }
                }
            }
            return java.util.Optional.empty();
        } catch (SQLException ex) {
            throw failure("find");
        }
    }

    @Override
    public boolean setStatus(String tenant, String id, String status) {
        if (find(tenant, id).isEmpty()) {
            return false;
        }
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM wayang_feedback_status WHERE tenant_id = ? AND id = ?")) {
                del.setString(1, tenant);
                del.setString(2, id);
                del.executeUpdate();
            }
            if (FeedbackEntry.DONE.equals(status)) { // open is the absence of a row
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO wayang_feedback_status (tenant_id, id, status) VALUES (?,?,?)")) {
                    ins.setString(1, tenant);
                    ins.setString(2, id);
                    ins.setString(3, status);
                    ins.executeUpdate();
                }
            }
            return true;
        } catch (SQLException ex) {
            throw failure("status");
        }
    }

    /** The ids among {@code ids} that are marked done. */
    private static java.util.Set<String> statuses(Connection c, String tenant, List<String> ids) throws SQLException {
        java.util.Set<String> done = new java.util.HashSet<>();
        if (ids.isEmpty()) {
            return done;
        }
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM wayang_feedback_status WHERE tenant_id = ? AND id IN (" + marks + ")")) {
            ps.setString(1, tenant);
            for (int i = 0; i < ids.size(); i++) {
                ps.setString(i + 2, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    done.add(rs.getString(1));
                }
            }
        }
        return done;
    }

    private static FeedbackEntry read(String tenant, long atMs, Map<String, Object> m, java.util.Set<String> done) {
        Map<String, String> ctx = new LinkedHashMap<>();
        if (m.get("context") instanceof Map<?, ?> cm) {
            cm.forEach((k, v) -> ctx.put(String.valueOf(k), String.valueOf(v)));
        }
        String id = String.valueOf(m.get("id"));
        return new FeedbackEntry(id, tenant, Instant.ofEpochMilli(atMs), String.valueOf(m.get("user")), String.valueOf(m.get("kind")),
                m.get("rating") == null ? null : String.valueOf(m.get("rating")), String.valueOf(m.get("message")), ctx,
                done.contains(id) ? FeedbackEntry.DONE : FeedbackEntry.OPEN);
    }

    @Override
    public int purgeBefore(String tenant, Instant before) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_feedback WHERE tenant_id = ? AND at_ms < ?")) {
            ps.setString(1, tenant);
            ps.setLong(2, before.toEpochMilli());
            return ps.executeUpdate();
        } catch (SQLException ex) {
            throw failure("purge");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<FeedbackEntry> list(String tenant, String kind, int limit, Instant before) {
        String sql = "SELECT at_ms, entry FROM wayang_feedback WHERE tenant_id = ?" + (kind == null ? "" : " AND kind = ?")
                + (before == null ? "" : " AND at_ms < ?") + " ORDER BY at_ms DESC LIMIT ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, tenant);
            if (kind != null) {
                ps.setString(i++, kind);
            }
            if (before != null) {
                ps.setLong(i++, before.toEpochMilli());
            }
            ps.setInt(i, limit);
            List<long[]> times = new ArrayList<>();
            List<Map<String, Object>> raw = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    times.add(new long[] { rs.getLong(1) });
                    raw.add((Map<String, Object>) Json.read(rs.getString(2)));
                }
            }
            java.util.Set<String> done = statuses(c, tenant, raw.stream().map(m -> String.valueOf(m.get("id"))).toList());
            List<FeedbackEntry> out = new ArrayList<>();
            for (int k = 0; k < raw.size(); k++) {
                out.add(read(tenant, times.get(k)[0], raw.get(k), done));
            }
            return out;
        } catch (SQLException ex) {
            throw failure("list");
        }
    }

    private static ApiException failure(String op) {
        return new ApiException(ApiException.Code.UNAVAILABLE, "Feedback store " + op + " failed");
    }
}
