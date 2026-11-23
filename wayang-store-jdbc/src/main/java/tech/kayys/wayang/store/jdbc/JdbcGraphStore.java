package tech.kayys.wayang.store.jdbc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import javax.sql.DataSource;

import tech.kayys.wayang.rag.EvidenceRetractionResult;
import tech.kayys.wayang.rag.GraphQuery;
import tech.kayys.wayang.rag.GraphStore;
import tech.kayys.wayang.rag.GraphTraversalResult;
import tech.kayys.wayang.rag.KnowledgeEntity;
import tech.kayys.wayang.rag.KnowledgeEvidence;
import tech.kayys.wayang.rag.KnowledgeRelationship;
import tech.kayys.wayang.runtime.json.Json;

/**
 * PostgreSQL 16 GraphStore adapter. All identity and evidence operations are scoped by
 * tenant and collection; graph batch writes and evidence retraction use one transaction.
 */
public final class JdbcGraphStore implements GraphStore {

    private static final String ENTITY_COLUMNS =
            "entity_type, canonical_name, aliases_json, properties_json";
    private static final String RELATIONSHIP_COLUMNS =
            "from_entity_id, predicate, to_entity_id, properties_json, confidence";

    private final DataSource dataSource;

    public JdbcGraphStore(DataSource dataSource) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource is required");
        this.dataSource = dataSource;
    }

    @Override
    public void upsertEntity(KnowledgeEntity entity) {
        upsertGraph(List.of(entity), List.of());
    }

    @Override
    public void upsertRelationship(KnowledgeRelationship relationship) {
        upsertGraph(List.of(), List.of(relationship));
    }

    @Override
    public void upsertGraph(List<KnowledgeEntity> entities, List<KnowledgeRelationship> relationships) {
        if (entities == null || relationships == null) {
            throw new IllegalArgumentException("entity and relationship batches are required");
        }
        transaction(connection -> {
            for (KnowledgeEntity entity : entities) {
                if (entity == null) throw new IllegalArgumentException("entity is required");
                Optional<KnowledgeEntity> existing = findEntity(connection,
                        entity.tenantId(), entity.collectionId(), entity.id(), true);
                KnowledgeEntity merged = existing.map(value -> merge(value, entity)).orElse(entity);
                if (existing.isEmpty()) insertEntity(connection, merged);
                else updateEntity(connection, merged);
                insertEntityEvidence(connection, merged);
            }
            for (KnowledgeRelationship relationship : relationships) {
                if (relationship == null) throw new IllegalArgumentException("relationship is required");
                if (findEntity(connection, relationship.tenantId(), relationship.collectionId(),
                        relationship.fromEntityId(), false).isEmpty()
                        || findEntity(connection, relationship.tenantId(), relationship.collectionId(),
                                relationship.toEntityId(), false).isEmpty()) {
                    throw new IllegalArgumentException(
                            "both relationship endpoints must exist in the same tenant and collection");
                }
                Optional<KnowledgeRelationship> existing = findRelationship(connection,
                        relationship.tenantId(), relationship.collectionId(), relationship.id(), true);
                KnowledgeRelationship merged = existing.map(value -> merge(value, relationship)).orElse(relationship);
                if (existing.isEmpty()) insertRelationship(connection, merged);
                else updateRelationship(connection, merged);
                insertRelationshipEvidence(connection, merged);
            }
            return null;
        });
    }

    @Override
    public Optional<KnowledgeEntity> findEntity(String tenantId, String collectionId, String entityId) {
        return withConnection(connection -> findEntity(connection, tenantId, collectionId, entityId, false));
    }

    @Override
    public EvidenceRetractionResult retractEvidence(
            String tenantId, String collectionId, String sourceId, String sourceVersion) {
        required(tenantId, "tenantId");
        required(collectionId, "collectionId");
        required(sourceId, "sourceId");
        required(sourceVersion, "sourceVersion");
        return transaction(connection -> {
            List<String> affectedRelationships = relationshipsWithEvidence(
                    connection, tenantId, collectionId, sourceId, sourceVersion);
            int entityRemoved = deleteEvidence(connection, "wayang_kg_entity_evidence",
                    tenantId, collectionId, sourceId, sourceVersion);
            int relationshipRemoved = deleteEvidence(connection, "wayang_kg_relationship_evidence",
                    tenantId, collectionId, sourceId, sourceVersion);
            int relationshipsDeleted = deleteUnsupportedRelationships(
                    connection, tenantId, collectionId, affectedRelationships);
            return new EvidenceRetractionResult(entityRemoved, relationshipRemoved, relationshipsDeleted);
        });
    }

    @Override
    public GraphTraversalResult traverse(GraphQuery query) {
        if (query == null) throw new IllegalArgumentException("query is required");
        return withConnection(connection -> {
            Map<String, KnowledgeEntity> foundEntities = new LinkedHashMap<>();
            Map<String, KnowledgeRelationship> foundRelationships = new LinkedHashMap<>();
            Set<String> visited = new LinkedHashSet<>();
            ArrayDeque<Visit> queue = new ArrayDeque<>();
            boolean truncated = false;
            for (String seed : query.seedEntityIds().stream().sorted().toList()) {
                Optional<KnowledgeEntity> entity = findEntity(connection, query.tenantId(),
                        query.collectionId(), seed, false);
                if (entity.isPresent()) {
                    if (foundEntities.size() >= query.maxNodes()) {
                        truncated = true;
                        break;
                    }
                    foundEntities.put(seed, entity.get());
                    visited.add(seed);
                    queue.addLast(new Visit(seed, 0));
                }
            }
            while (!queue.isEmpty() && !truncated) {
                Visit current = queue.removeFirst();
                if (current.depth() >= query.maxDepth()) continue;
                int remaining = query.maxEdges() - foundRelationships.size();
                List<String> edgeIds = adjacentIds(connection, query, current.entityId(), remaining + 1, foundRelationships.keySet());
                if (edgeIds.size() > remaining) {
                    edgeIds = edgeIds.subList(0, remaining);
                    truncated = true;
                }
                for (String edgeId : edgeIds) {
                    Optional<KnowledgeRelationship> found = findRelationship(connection,
                            query.tenantId(), query.collectionId(), edgeId, false);
                    if (found.isEmpty()) continue;
                    KnowledgeRelationship edge = found.get();
                    String next = nextEntity(edge, current.entityId(), query.direction());
                    Optional<KnowledgeEntity> nextEntity = findEntity(connection,
                            query.tenantId(), query.collectionId(), next, false);
                    if (nextEntity.isEmpty()) continue;
                    foundRelationships.putIfAbsent(edge.id(), edge);
                    if (!visited.contains(next)) {
                        if (foundEntities.size() >= query.maxNodes()) {
                            truncated = true;
                            break;
                        }
                        visited.add(next);
                        foundEntities.put(next, nextEntity.get());
                        queue.addLast(new Visit(next, current.depth() + 1));
                    }
                }
            }
            return new GraphTraversalResult(new ArrayList<>(foundEntities.values()),
                    new ArrayList<>(foundRelationships.values()), truncated);
        });
    }

    private static List<String> adjacentIds(Connection connection, GraphQuery query, String entityId, int limit,
            Set<String> excludedIds) throws SQLException {
        String predicate = query.predicates().isEmpty() ? "" : " AND predicate IN ("
                + String.join(",", java.util.Collections.nCopies(query.predicates().size(), "?")) + ")";
        String direction = switch (query.direction()) {
            case OUTGOING -> "from_entity_id = ?";
            case INCOMING -> "to_entity_id = ?";
            case BOTH -> "(from_entity_id = ? OR to_entity_id = ?)";
        };
        String excluded = excludedIds.isEmpty() ? "" : " AND relationship_id NOT IN ("
                + String.join(",", java.util.Collections.nCopies(excludedIds.size(), "?")) + ")";
        String sql = "SELECT relationship_id FROM wayang_kg_relationship WHERE tenant_id = ?"
                + " AND collection_id = ? AND " + direction + predicate + excluded
                + " ORDER BY relationship_id LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, query.tenantId());
            statement.setString(index++, query.collectionId());
            statement.setString(index++, entityId);
            if (query.direction() == GraphQuery.Direction.BOTH) statement.setString(index++, entityId);
            for (String value : query.predicates().stream().sorted().toList()) statement.setString(index++, value);
            for (String value : excludedIds.stream().sorted().toList()) statement.setString(index++, value);
            statement.setInt(index, Math.max(1, limit));
            List<String> ids = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getString(1));
            }
            return ids;
        }
    }

    private Optional<KnowledgeEntity> findEntity(
            Connection connection, String tenant, String collection, String id, boolean lock) throws SQLException {
        String sql = "SELECT " + ENTITY_COLUMNS + " FROM wayang_kg_entity"
                + " WHERE tenant_id = ? AND collection_id = ? AND entity_id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            statement.setString(2, collection);
            statement.setString(3, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new KnowledgeEntity(tenant, collection, id, result.getString(1),
                        result.getString(2), strings(result.getString(3)), stringMap(result.getString(4)),
                        readEvidence(connection, "wayang_kg_entity_evidence", "entity_id", id, tenant, collection)));
            }
        }
    }

    private Optional<KnowledgeRelationship> findRelationship(
            Connection connection, String tenant, String collection, String id, boolean lock) throws SQLException {
        String sql = "SELECT " + RELATIONSHIP_COLUMNS + " FROM wayang_kg_relationship"
                + " WHERE tenant_id = ? AND collection_id = ? AND relationship_id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            statement.setString(2, collection);
            statement.setString(3, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new KnowledgeRelationship(tenant, collection, id, result.getString(1),
                        result.getString(2), result.getString(3), stringMap(result.getString(4)),
                        readEvidence(connection, "wayang_kg_relationship_evidence", "relationship_id",
                                id, tenant, collection), result.getDouble(5)));
            }
        }
    }

    private static List<KnowledgeEvidence> readEvidence(
            Connection connection, String table, String ownerColumn, String ownerId,
            String tenant, String collection) throws SQLException {
        String sql = "SELECT source_id, source_version, chunk_id, start_offset, end_offset, extractor, confidence"
                + " FROM " + table + " WHERE tenant_id = ? AND collection_id = ? AND " + ownerColumn
                + " = ? ORDER BY evidence_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            statement.setString(2, collection);
            statement.setString(3, ownerId);
            List<KnowledgeEvidence> evidence = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    evidence.add(new KnowledgeEvidence(result.getString(1), result.getString(2), result.getString(3),
                            result.getInt(4), result.getInt(5), result.getString(6), result.getDouble(7)));
                }
            }
            return evidence;
        }
    }

    private static void insertEntity(Connection c, KnowledgeEntity entity) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO wayang_kg_entity (tenant_id, collection_id, entity_id, entity_type,"
                + " canonical_name, aliases_json, properties_json) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, entity.tenantId()); ps.setString(2, entity.collectionId()); ps.setString(3, entity.id());
            ps.setString(4, entity.type()); ps.setString(5, entity.canonicalName());
            ps.setString(6, Json.write(entity.aliases())); ps.setString(7, Json.write(entity.properties()));
            ps.executeUpdate();
        }
    }

    private static void updateEntity(Connection c, KnowledgeEntity entity) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE wayang_kg_entity SET aliases_json = ?, properties_json = ?"
                + " WHERE tenant_id = ? AND collection_id = ? AND entity_id = ?")) {
            ps.setString(1, Json.write(entity.aliases())); ps.setString(2, Json.write(entity.properties()));
            ps.setString(3, entity.tenantId()); ps.setString(4, entity.collectionId()); ps.setString(5, entity.id());
            ps.executeUpdate();
        }
    }

    private static void insertRelationship(Connection c, KnowledgeRelationship edge) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO wayang_kg_relationship (tenant_id, collection_id, relationship_id, from_entity_id,"
                + " predicate, to_entity_id, properties_json, confidence) VALUES (?,?,?,?,?,?,?,?)")) {
            bindRelationship(ps, edge, false);
            ps.executeUpdate();
        }
    }

    private static void updateRelationship(Connection c, KnowledgeRelationship edge) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE wayang_kg_relationship SET properties_json = ?, confidence = ?"
                + " WHERE tenant_id = ? AND collection_id = ? AND relationship_id = ?")) {
            ps.setString(1, Json.write(edge.properties())); ps.setDouble(2, edge.confidence());
            ps.setString(3, edge.tenantId()); ps.setString(4, edge.collectionId()); ps.setString(5, edge.id());
            ps.executeUpdate();
        }
    }

    private static void bindRelationship(PreparedStatement ps, KnowledgeRelationship edge, boolean update)
            throws SQLException {
        ps.setString(1, edge.tenantId()); ps.setString(2, edge.collectionId()); ps.setString(3, edge.id());
        ps.setString(4, edge.fromEntityId()); ps.setString(5, edge.predicate()); ps.setString(6, edge.toEntityId());
        ps.setString(7, Json.write(edge.properties())); ps.setDouble(8, edge.confidence());
    }

    private static void insertEntityEvidence(Connection c, KnowledgeEntity entity) throws SQLException {
        for (KnowledgeEvidence evidence : entity.evidence()) {
            String sql = "INSERT INTO wayang_kg_entity_evidence (tenant_id, collection_id, entity_id, evidence_id,"
                    + " source_id, source_version, chunk_id, start_offset, end_offset, extractor, confidence)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bindEvidence(ps, entity.tenantId(), entity.collectionId(), entity.id(), evidence);
                ps.executeUpdate();
            }
        }
    }

    private static void insertRelationshipEvidence(Connection c, KnowledgeRelationship edge) throws SQLException {
        for (KnowledgeEvidence evidence : edge.evidence()) {
            String sql = "INSERT INTO wayang_kg_relationship_evidence (tenant_id, collection_id, relationship_id,"
                    + " evidence_id, source_id, source_version, chunk_id, start_offset, end_offset, extractor, confidence)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bindEvidence(ps, edge.tenantId(), edge.collectionId(), edge.id(), evidence);
                ps.executeUpdate();
            }
        }
    }

    private static void bindEvidence(
            PreparedStatement ps, String tenant, String collection, String owner, KnowledgeEvidence evidence)
            throws SQLException {
        ps.setString(1, tenant); ps.setString(2, collection); ps.setString(3, owner);
        ps.setString(4, evidenceId(evidence)); ps.setString(5, evidence.sourceId());
        ps.setString(6, evidence.sourceVersion()); ps.setString(7, evidence.chunkId());
        ps.setInt(8, evidence.startOffset()); ps.setInt(9, evidence.endOffset());
        ps.setString(10, evidence.extractor()); ps.setDouble(11, evidence.confidence());
    }

    private static List<String> relationshipsWithEvidence(
            Connection c, String tenant, String collection, String source, String version) throws SQLException {
        String sql = "SELECT DISTINCT relationship_id FROM wayang_kg_relationship_evidence"
                + " WHERE tenant_id = ? AND collection_id = ? AND source_id = ? AND source_version = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tenant); ps.setString(2, collection); ps.setString(3, source); ps.setString(4, version);
            List<String> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString(1));
            }
            return ids;
        }
    }

    private static int deleteUnsupportedRelationships(
            Connection c, String tenant, String collection, List<String> affectedIds) throws SQLException {
        int deleted = 0;
        String sql = "DELETE FROM wayang_kg_relationship r WHERE r.tenant_id = ? AND r.collection_id = ?"
                + " AND r.relationship_id = ? AND NOT EXISTS (SELECT 1 FROM wayang_kg_relationship_evidence e"
                + " WHERE e.tenant_id = r.tenant_id AND e.collection_id = r.collection_id"
                + " AND e.relationship_id = r.relationship_id)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (String id : affectedIds) {
                ps.setString(1, tenant); ps.setString(2, collection); ps.setString(3, id);
                ps.addBatch();
            }
            for (int count : ps.executeBatch()) {
                if (count > 0) deleted += count;
                else if (count == java.sql.Statement.SUCCESS_NO_INFO) deleted++;
            }
        }
        return deleted;
    }

    private static int deleteEvidence(
            Connection c, String table, String tenant, String collection, String source, String version)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table
                + " WHERE tenant_id = ? AND collection_id = ? AND source_id = ? AND source_version = ?")) {
            ps.setString(1, tenant); ps.setString(2, collection); ps.setString(3, source); ps.setString(4, version);
            return ps.executeUpdate();
        }
    }

    private static KnowledgeEntity merge(KnowledgeEntity existing, KnowledgeEntity incoming) {
        if (!existing.type().equals(incoming.type())) {
            throw new IllegalArgumentException("entity ID collision: entity type cannot change");
        }
        Set<String> aliases = new LinkedHashSet<>(existing.aliases()); aliases.addAll(incoming.aliases());
        Map<String, String> properties = new LinkedHashMap<>(existing.properties());
        incoming.properties().forEach(properties::putIfAbsent);
        Set<KnowledgeEvidence> evidence = new LinkedHashSet<>(existing.evidence()); evidence.addAll(incoming.evidence());
        return new KnowledgeEntity(existing.tenantId(), existing.collectionId(), existing.id(), existing.type(),
                existing.canonicalName(), new ArrayList<>(aliases), properties, new ArrayList<>(evidence));
    }

    private static KnowledgeRelationship merge(KnowledgeRelationship existing, KnowledgeRelationship incoming) {
        if (!existing.fromEntityId().equals(incoming.fromEntityId())
                || !existing.toEntityId().equals(incoming.toEntityId())
                || !existing.predicate().equals(incoming.predicate())) {
            throw new IllegalArgumentException("relationship ID collision: endpoints and predicate cannot change");
        }
        Map<String, String> properties = new LinkedHashMap<>(existing.properties());
        incoming.properties().forEach(properties::putIfAbsent);
        Set<KnowledgeEvidence> evidence = new LinkedHashSet<>(existing.evidence()); evidence.addAll(incoming.evidence());
        return new KnowledgeRelationship(existing.tenantId(), existing.collectionId(), existing.id(),
                existing.fromEntityId(), existing.predicate(), existing.toEntityId(), properties,
                new ArrayList<>(evidence), Math.max(existing.confidence(), incoming.confidence()));
    }

    private static String evidenceId(KnowledgeEvidence evidence) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(evidence.sourceId(), evidence.sourceVersion(), evidence.chunkId(),
                    Integer.toString(evidence.startOffset()), Integer.toString(evidence.endOffset()),
                    evidence.extractor(), Double.toString(evidence.confidence()))) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24)); digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8)); digest.update((byte) bytes.length); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    private static List<String> strings(String json) {
        Object value = Json.read(json);
        if (!(value instanceof List<?> list)) throw new IllegalStateException("Expected JSON array");
        return list.stream().map(String.class::cast).toList();
    }

    private static Map<String, String> stringMap(String json) {
        Object value = Json.read(json);
        if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("Expected JSON object");
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return result;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.strip();
    }

    private static String nextEntity(KnowledgeRelationship edge, String current, GraphQuery.Direction direction) {
        if ((direction == GraphQuery.Direction.OUTGOING || direction == GraphQuery.Direction.BOTH)
                && edge.fromEntityId().equals(current)) return edge.toEntityId();
        if ((direction == GraphQuery.Direction.INCOMING || direction == GraphQuery.Direction.BOTH)
                && edge.toEntityId().equals(current)) return edge.fromEntityId();
        return null;
    }

    private record Visit(String entityId, int depth) {}

    private <T> T transaction(Function<Connection, T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("JDBC graph transaction failed", failure);
        }
    }

    private <T> T withConnection(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.apply(connection);
        } catch (SQLException failure) {
            throw new IllegalStateException("JDBC graph operation failed", failure);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }
}
