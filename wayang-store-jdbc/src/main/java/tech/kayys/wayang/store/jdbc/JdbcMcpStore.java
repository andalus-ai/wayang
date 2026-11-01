package tech.kayys.wayang.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.McpServerDef;
import tech.kayys.wayang.api.McpStore;
import tech.kayys.wayang.runtime.json.Json;

/** {@link McpStore} over plain JDBC. The definition (tools, header templates with secret references) is one JSON document. */
public final class JdbcMcpStore implements McpStore {

    private final DataSource ds;

    public JdbcMcpStore(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void create(McpServerDef d) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO wayang_mcp_server (tenant_id, server_id, definition, created_ms) VALUES (?,?,?,?)")) {
            ps.setString(1, d.tenant());
            ps.setString(2, d.id());
            ps.setString(3, Json.write(d.toMap()));
            ps.setLong(4, d.createdAt().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new ApiException(ApiException.Code.CONFLICT, "MCP server '" + d.id() + "' already exists");
            }
            throw failure("create", e);
        }
    }

    @Override
    public Optional<McpServerDef> get(String tenant, String id) {
        List<McpServerDef> l = query("SELECT definition FROM wayang_mcp_server WHERE tenant_id = ? AND server_id = ?", tenant, id);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    @Override
    public List<McpServerDef> list(String tenant) {
        return query("SELECT definition FROM wayang_mcp_server WHERE tenant_id = ? ORDER BY created_ms, server_id", tenant);
    }

    @Override
    public void update(McpServerDef d) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE wayang_mcp_server SET definition = ? WHERE tenant_id = ? AND server_id = ?")) {
            ps.setString(1, Json.write(d.toMap()));
            ps.setString(2, d.tenant());
            ps.setString(3, d.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("update", e);
        }
    }

    @Override
    public boolean delete(String tenant, String id) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM wayang_mcp_server WHERE tenant_id = ? AND server_id = ?")) {
            ps.setString(1, tenant);
            ps.setString(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw failure("delete", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<McpServerDef> query(String sql, String... args) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            List<McpServerDef> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(McpServerDef.fromMap((Map<String, Object>) Json.read(rs.getString(1))));
                }
            }
            return out;
        } catch (SQLException e) {
            throw failure("query", e);
        }
    }

    private static ApiException failure(String op, SQLException e) {
        return new ApiException(ApiException.Code.UNAVAILABLE, "MCP store " + op + " failed: " + e.getMessage());
    }
}
