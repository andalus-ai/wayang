package tech.kayys.wayang.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.audit.AuditEntry;
import tech.kayys.wayang.api.audit.AuditStore;
import tech.kayys.wayang.runtime.json.Json;

/** {@link AuditStore} over plain JDBC. INSERT and SELECT only; the application never updates or deletes audit rows. */
public final class JdbcAuditStore implements AuditStore {

    private final DataSource ds;

    public JdbcAuditStore(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void append(AuditEntry e) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO wayang_audit (tenant_id, at_ms, entry) VALUES (?,?,?)")) {
            ps.setString(1, e.tenant());
            ps.setLong(2, e.time().toEpochMilli());
            ps.setString(3, Json.write(e.toMap()));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw failure("append", ex);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<AuditEntry> list(String tenant, int limit, Instant before) {
        String sql = "SELECT at_ms, entry FROM wayang_audit WHERE tenant_id = ?" + (before == null ? "" : " AND at_ms < ?")
                + " ORDER BY at_ms DESC LIMIT ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, tenant);
            if (before != null) {
                ps.setLong(i++, before.toEpochMilli());
            }
            ps.setInt(i, limit);
            List<AuditEntry> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = (Map<String, Object>) Json.read(rs.getString(2));
                    Map<String, String> ids = new java.util.LinkedHashMap<>();
                    if (m.get("ids") instanceof Map<?, ?> im) {
                        im.forEach((k, v) -> ids.put(String.valueOf(k), String.valueOf(v)));
                    }
                    out.add(new AuditEntry(tenant, Instant.ofEpochMilli(rs.getLong(1)), String.valueOf(m.get("actor")),
                            String.valueOf(m.get("method")), String.valueOf(m.get("route")), ids,
                            ((Number) m.get("status")).intValue(), String.valueOf(m.get("outcome"))));
                }
            }
            return out;
        } catch (SQLException ex) {
            throw failure("list", ex);
        }
    }

    private static ApiException failure(String op, SQLException e) {
        return new ApiException(ApiException.Code.UNAVAILABLE, "Audit store " + op + " failed");
    }
}
