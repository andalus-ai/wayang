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
            List<FeedbackEntry> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = (Map<String, Object>) Json.read(rs.getString(2));
                    Map<String, String> ctx = new LinkedHashMap<>();
                    if (m.get("context") instanceof Map<?, ?> cm) {
                        cm.forEach((k, v) -> ctx.put(String.valueOf(k), String.valueOf(v)));
                    }
                    out.add(new FeedbackEntry(String.valueOf(m.get("id")), tenant, Instant.ofEpochMilli(rs.getLong(1)), String.valueOf(m.get("user")),
                            String.valueOf(m.get("kind")), m.get("rating") == null ? null : String.valueOf(m.get("rating")),
                            String.valueOf(m.get("message")), ctx));
                }
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
