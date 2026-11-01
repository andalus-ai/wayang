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
import java.util.Optional;

import javax.sql.DataSource;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.DefinitionKind;
import tech.kayys.wayang.api.DefinitionStore;
import tech.kayys.wayang.api.DefinitionSummary;
import tech.kayys.wayang.api.StoredDefinition;
import tech.kayys.wayang.runtime.json.Json;

/**
 * Durable {@link DefinitionStore} over plain JDBC (tables in
 * {@code wayang/schema-definitions.sql}). Draft edits are compare-and-swap on
 * the revision column; publishing assigns {@code max(version)+1} and relies on
 * the primary key to arbitrate concurrent publishers (the loser retries).
 *
 * <p>
 * Verified against PostgreSQL 16 with {@code DefinitionStoreContract} (see
 * {@code JdbcDefinitionStoreIT}); other databases are untested.
 */
public final class JdbcDefinitionStore implements DefinitionStore {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final int PUBLISH_RETRIES = 20;

    private final DataSource ds;

    public JdbcDefinitionStore(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public StoredDefinition putDraft(String tenant, DefinitionKind kind, String id, Map<String, Object> doc,
            Long expectedRevision, String user, Instant now) {
        String json = Json.write(doc);
        try (Connection c = ds.getConnection()) {
            if (expectedRevision == null || expectedRevision == 0) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO wayang_definition_draft "
                        + "(tenant_id, kind, def_id, revision, doc_json, updated_ms, updated_by) VALUES (?,?,?,1,?,?,?)")) {
                    key(ps, tenant, kind, id);
                    ps.setString(4, json);
                    ps.setLong(5, now.toEpochMilli());
                    ps.setString(6, user);
                    ps.executeUpdate();
                    return new StoredDefinition(tenant, kind, id, 0, 1, doc, now, user);
                } catch (SQLException e) {
                    if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                        throw new ApiException(ApiException.Code.CONFLICT, "Draft '" + id + "' already exists; load it and send its revision");
                    }
                    throw e;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE wayang_definition_draft SET revision = revision + 1, doc_json = ?,"
                    + " updated_ms = ?, updated_by = ? WHERE tenant_id = ? AND kind = ? AND def_id = ? AND revision = ?")) {
                ps.setString(1, json);
                ps.setLong(2, now.toEpochMilli());
                ps.setString(3, user);
                ps.setString(4, tenant);
                ps.setString(5, kind.name());
                ps.setString(6, id);
                ps.setLong(7, expectedRevision);
                if (ps.executeUpdate() == 0) {
                    throw new ApiException(ApiException.Code.CONFLICT,
                            "Draft '" + id + "' does not exist or was changed by someone else (you edited revision " + expectedRevision + ")");
                }
                return new StoredDefinition(tenant, kind, id, 0, expectedRevision + 1, doc, now, user);
            }
        } catch (SQLException e) {
            throw failure("putDraft", e);
        }
    }

    @Override
    public Optional<StoredDefinition> getDraft(String tenant, DefinitionKind kind, String id) {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT revision, doc_json, updated_ms, updated_by FROM wayang_definition_draft"
                        + " WHERE tenant_id = ? AND kind = ? AND def_id = ?")) {
            key(ps, tenant, kind, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(draft(tenant, kind, id, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("getDraft", e);
        }
    }

    @Override
    public StoredDefinition publish(String tenant, DefinitionKind kind, String id, String user, Instant now) {
        for (int attempt = 0; attempt < PUBLISH_RETRIES; attempt++) {
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                try {
                    StoredDefinition d;
                    try (PreparedStatement ps = c.prepareStatement("SELECT revision, doc_json, updated_ms, updated_by FROM wayang_definition_draft"
                            + " WHERE tenant_id = ? AND kind = ? AND def_id = ?")) {
                        key(ps, tenant, kind, id);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw ApiException.notFound(kind + " draft '" + id + "'");
                            }
                            d = draft(tenant, kind, id, rs);
                        }
                    }
                    int next;
                    try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(version), 0) + 1 FROM wayang_definition_version"
                            + " WHERE tenant_id = ? AND kind = ? AND def_id = ?")) {
                        key(ps, tenant, kind, id);
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            next = rs.getInt(1);
                        }
                    }
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO wayang_definition_version "
                            + "(tenant_id, kind, def_id, version, revision, doc_json, updated_ms, updated_by) VALUES (?,?,?,?,?,?,?,?)")) {
                        key(ps, tenant, kind, id);
                        ps.setInt(4, next);
                        ps.setLong(5, d.revision());
                        ps.setString(6, Json.write(d.document()));
                        ps.setLong(7, now.toEpochMilli());
                        ps.setString(8, user);
                        ps.executeUpdate();
                    }
                    c.commit();
                    return new StoredDefinition(tenant, kind, id, next, d.revision(), d.document(), now, user);
                } catch (SQLException e) {
                    c.rollback();
                    if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                        continue; // another publisher took this version number: read again
                    }
                    throw e;
                } catch (RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                throw failure("publish", e);
            }
        }
        throw new ApiException(ApiException.Code.CONFLICT, "Too much contention publishing '" + id + "'; retry");
    }

    @Override
    public Optional<StoredDefinition> getVersion(String tenant, DefinitionKind kind, String id, int version) {
        return version(tenant, kind, id, "SELECT version, revision, doc_json, updated_ms, updated_by FROM wayang_definition_version"
                + " WHERE tenant_id = ? AND kind = ? AND def_id = ? AND version = ?", version);
    }

    @Override
    public Optional<StoredDefinition> getLatest(String tenant, DefinitionKind kind, String id) {
        return version(tenant, kind, id, "SELECT version, revision, doc_json, updated_ms, updated_by FROM wayang_definition_version"
                + " WHERE tenant_id = ? AND kind = ? AND def_id = ? ORDER BY version DESC", null);
    }

    private Optional<StoredDefinition> version(String tenant, DefinitionKind kind, String id, String sql, Integer v) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            key(ps, tenant, kind, id);
            if (v != null) {
                ps.setInt(4, v);
            }
            ps.setMaxRows(1);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredDefinition(tenant, kind, id, rs.getInt(1), rs.getLong(2), parse(rs.getString(3)),
                        Instant.ofEpochMilli(rs.getLong(4)), rs.getString(5)));
            }
        } catch (SQLException e) {
            throw failure("version lookup", e);
        }
    }

    @Override
    public List<DefinitionSummary> list(String tenant, DefinitionKind kind) {
        Map<String, Object[]> rows = new java.util.TreeMap<>(); // id -> {name, hasDraft, latest, updatedMs, updatedBy}
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT v.def_id, v.version, v.doc_json, v.updated_ms, v.updated_by"
                    + " FROM wayang_definition_version v WHERE v.tenant_id = ? AND v.kind = ? AND v.version ="
                    + " (SELECT MAX(version) FROM wayang_definition_version m WHERE m.tenant_id = v.tenant_id AND m.kind = v.kind"
                    + " AND m.def_id = v.def_id)")) {
                ps.setString(1, tenant);
                ps.setString(2, kind.name());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.put(rs.getString(1), new Object[] { nameOf(rs.getString(3), rs.getString(1)), false, rs.getInt(2),
                                rs.getLong(4), rs.getString(5), projectOf(rs.getString(3)) });
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT def_id, doc_json, updated_ms, updated_by FROM wayang_definition_draft"
                    + " WHERE tenant_id = ? AND kind = ?")) {
                ps.setString(1, tenant);
                ps.setString(2, kind.name());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Object[] prev = rows.get(rs.getString(1));
                        rows.put(rs.getString(1), new Object[] { nameOf(rs.getString(2), rs.getString(1)), true,
                                prev == null ? 0 : prev[2], rs.getLong(3), rs.getString(4), projectOf(rs.getString(2)) });
                    }
                }
            }
        } catch (SQLException e) {
            throw failure("list", e);
        }
        List<DefinitionSummary> out = new ArrayList<>();
        rows.forEach((id, r) -> out.add(new DefinitionSummary(kind, id, (String) r[0], (Boolean) r[1], (Integer) r[2],
                Instant.ofEpochMilli((Long) r[3]), (String) r[4], (String) r[5])));
        return out;
    }

    @Override
    public boolean deleteDraft(String tenant, DefinitionKind kind, String id) {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM wayang_definition_draft WHERE tenant_id = ? AND kind = ? AND def_id = ?")) {
            key(ps, tenant, kind, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw failure("deleteDraft", e);
        }
    }

    // ----------------------------------------------------------------- helpers

    private static void key(PreparedStatement ps, String tenant, DefinitionKind kind, String id) throws SQLException {
        ps.setString(1, tenant);
        ps.setString(2, kind.name());
        ps.setString(3, id);
    }

    private static StoredDefinition draft(String tenant, DefinitionKind kind, String id, ResultSet rs) throws SQLException {
        return new StoredDefinition(tenant, kind, id, 0, rs.getLong(1), parse(rs.getString(2)),
                Instant.ofEpochMilli(rs.getLong(3)), rs.getString(4));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        Object o = Json.read(json);
        if (!(o instanceof Map)) {
            throw new IllegalStateException("Stored definition is not a JSON object");
        }
        return new LinkedHashMap<>((Map<String, Object>) o);
    }

    private static String projectOf(String json) {
        return tech.kayys.wayang.api.Studio.projectId(parse(json));
    }

    private static String nameOf(String json, String fallback) {
        return parse(json).get("name") instanceof String s ? s : fallback;
    }

    private static ApiException failure(String op, SQLException e) {
        return new ApiException(ApiException.Code.UNAVAILABLE, "Definition store " + op + " failed: " + e.getMessage());
    }
}
