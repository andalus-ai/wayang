package tech.kayys.wayang.store.jdbc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import tech.kayys.wayang.rag.KnowledgeSchema;
import tech.kayys.wayang.rag.RagException;
import tech.kayys.wayang.rag.RelationshipTypeDefinition;
import tech.kayys.wayang.rag.TenantKnowledgeSchemaRegistry;
import tech.kayys.wayang.runtime.json.Json;

/**
 * PostgreSQL-backed, tenant-isolated registry for immutable knowledge schema versions.
 *
 * <p>The database primary key arbitrates concurrent registration. Identical
 * registrations are idempotent; a different definition for an existing key
 * is rejected after the winning row is read back.</p>
 */
public final class JdbcKnowledgeSchemaRegistry implements TenantKnowledgeSchemaRegistry {

    private final DataSource dataSource;

    public JdbcKnowledgeSchemaRegistry(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void register(String tenantId, KnowledgeSchema schema) {
        String tenant = required(tenantId, "tenantId", 200);
        if (schema == null) throw new RagException("knowledge schema is required");
        String document = encode(schema);
        String digest = sha256(document);
        String insert = "INSERT INTO wayang_kg_schema_version "
                + "(tenant_id, schema_id, schema_version, schema_json, content_sha256, created_at_ms) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, schema_id, schema_version) DO NOTHING";
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, tenant);
                statement.setString(2, schema.schemaId());
                statement.setString(3, schema.version());
                statement.setString(4, document);
                statement.setString(5, digest);
                statement.setLong(6, System.currentTimeMillis());
                statement.executeUpdate();
            }
            String existingDigest;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT content_sha256 FROM wayang_kg_schema_version "
                            + "WHERE tenant_id = ? AND schema_id = ? AND schema_version = ?")) {
                statement.setString(1, tenant);
                statement.setString(2, schema.schemaId());
                statement.setString(3, schema.version());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("schema row missing after registration");
                    }
                    existingDigest = result.getString(1);
                }
            }
            if (!digest.equals(existingDigest)) {
                throw new RagException("schema version is immutable and already registered");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("knowledge schema registration failed", e);
        }
    }

    @Override
    public Optional<KnowledgeSchema> find(String tenantId, String schemaId, String version) {
        String tenant = required(tenantId, "tenantId", 200);
        String id = required(schemaId, "schemaId", 200);
        String revision = required(version, "version", 100);
        String sql = "SELECT schema_json FROM wayang_kg_schema_version "
                + "WHERE tenant_id = ? AND schema_id = ? AND schema_version = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            statement.setString(2, id);
            statement.setString(3, revision);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(decode(result.getString(1))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("knowledge schema lookup failed", e);
        }
    }

    @Override
    public List<KnowledgeSchema> versions(String tenantId, String schemaId) {
        String tenant = required(tenantId, "tenantId", 200);
        String id = required(schemaId, "schemaId", 200);
        String sql = "SELECT schema_json FROM wayang_kg_schema_version "
                + "WHERE tenant_id = ? AND schema_id = ? ORDER BY schema_version";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            statement.setString(2, id);
            List<KnowledgeSchema> schemas = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) schemas.add(decode(result.getString(1)));
            }
            return List.copyOf(schemas);
        } catch (SQLException e) {
            throw new IllegalStateException("knowledge schema version listing failed", e);
        }
    }

    private static String encode(KnowledgeSchema schema) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaId", schema.schemaId());
        root.put("version", schema.version());
        root.put("entityTypes", schema.entityTypes().stream().sorted().toList());
        List<Map<String, Object>> relationships = new ArrayList<>();
        schema.relationshipTypes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            RelationshipTypeDefinition definition = entry.getValue();
            Map<String, Object> relationship = new LinkedHashMap<>();
            relationship.put("predicate", definition.predicate());
            relationship.put("fromEntityTypes", definition.fromEntityTypes().stream().sorted().toList());
            relationship.put("toEntityTypes", definition.toEntityTypes().stream().sorted().toList());
            relationships.add(relationship);
        });
        root.put("relationshipTypes", relationships);
        return Json.write(root);
    }

    private static KnowledgeSchema decode(String document) {
        try {
            Object parsed = Json.read(document);
            if (!(parsed instanceof Map<?, ?> root)) throw new IllegalArgumentException("schema must be a JSON object");
            String schemaId = string(root.get("schemaId"));
            String version = string(root.get("version"));
            Set<String> entities = stringSet(root.get("entityTypes"));
            if (!(root.get("relationshipTypes") instanceof List<?> rows)) {
                throw new IllegalArgumentException("relationshipTypes must be an array");
            }
            Map<String, RelationshipTypeDefinition> relationships = new LinkedHashMap<>();
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> relationship)) {
                    throw new IllegalArgumentException("relationship definition must be an object");
                }
                String predicate = string(relationship.get("predicate"));
                RelationshipTypeDefinition definition = new RelationshipTypeDefinition(predicate,
                        stringSet(relationship.get("fromEntityTypes")),
                        stringSet(relationship.get("toEntityTypes")));
                relationships.put(predicate, definition);
            }
            return new KnowledgeSchema(schemaId, version, entities, relationships);
        } catch (RuntimeException e) {
            throw new IllegalStateException("stored knowledge schema is invalid", e);
        }
    }

    private static Set<String> stringSet(Object value) {
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("expected string array");
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (Object item : values) result.add(string(item));
        return Set.copyOf(result);
    }

    private static String string(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("expected string value");
        return text;
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new RagException(field + " must contain 1.." + max + " characters");
        }
        return value.strip();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
