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
import tech.kayys.wayang.api.Project;
import tech.kayys.wayang.api.ProjectStore;

/** {@link ProjectStore} over plain JDBC ({@code wayang_project}, see {@code schema-definitions.sql}). */
public final class JdbcProjectStore implements ProjectStore {

    private static final String COLS = "tenant_id, project_id, name, description, created_by, created_ms, updated_ms";
    private final DataSource ds;

    public JdbcProjectStore(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public Project create(String tenant, String id, String name, String description, String user, Instant now) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO wayang_project (" + COLS + ") VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, tenant);
            ps.setString(2, id);
            ps.setString(3, name);
            ps.setString(4, description);
            ps.setString(5, user);
            ps.setLong(6, now.toEpochMilli());
            ps.setLong(7, now.toEpochMilli());
            ps.executeUpdate();
            return new Project(tenant, id, name, description, user, now, now);
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new ApiException(ApiException.Code.CONFLICT, "Project '" + id + "' already exists");
            }
            throw failure("create", e);
        }
    }

    @Override
    public Optional<Project> get(String tenant, String id) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLS + " FROM wayang_project WHERE tenant_id = ? AND project_id = ?")) {
            ps.setString(1, tenant);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("get", e);
        }
    }

    @Override
    public List<Project> list(String tenant) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLS + " FROM wayang_project WHERE tenant_id = ? ORDER BY created_ms DESC, project_id")) {
            ps.setString(1, tenant);
            List<Project> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw failure("list", e);
        }
    }

    @Override
    public Optional<Project> update(String tenant, String id, String name, String description, Instant now) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE wayang_project SET name = ?, description = ?, updated_ms = ? WHERE tenant_id = ? AND project_id = ?")) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setLong(3, now.toEpochMilli());
            ps.setString(4, tenant);
            ps.setString(5, id);
            return ps.executeUpdate() == 0 ? Optional.empty() : get(tenant, id);
        } catch (SQLException e) {
            throw failure("update", e);
        }
    }

    @Override
    public boolean delete(String tenant, String id) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM wayang_project WHERE tenant_id = ? AND project_id = ?")) {
            ps.setString(1, tenant);
            ps.setString(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw failure("delete", e);
        }
    }

    private static Project read(ResultSet rs) throws SQLException {
        return new Project(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                Instant.ofEpochMilli(rs.getLong(6)), Instant.ofEpochMilli(rs.getLong(7)));
    }

    private static ApiException failure(String op, SQLException e) {
        return new ApiException(ApiException.Code.UNAVAILABLE, "Project store " + op + " failed: " + e.getMessage());
    }
}
