package tech.kayys.wayang.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import tech.kayys.wayang.rag.GraphEntityLookup;
import tech.kayys.wayang.rag.GraphStore;
import tech.kayys.wayang.rag.KnowledgeEntity;

/**
 * Bounded PostgreSQL candidate lookup for query-time entity linking.
 * Candidate rows are scoped in SQL; canonical names and aliases are resolved by the
 * provider-neutral EntityResolver in wayang-rag.
 */
public final class JdbcGraphEntityLookup implements GraphEntityLookup {
    private final DataSource dataSource;
    private final GraphStore graphStore;

    public JdbcGraphEntityLookup(DataSource dataSource, GraphStore graphStore) {
        if (dataSource == null || graphStore == null)
            throw new IllegalArgumentException("dataSource and graphStore are required");
        this.dataSource = dataSource;
        this.graphStore = graphStore;
    }

    @Override
    public List<KnowledgeEntity> findCandidates(
            String tenantId, String collectionId, Set<String> entityTypes, int limit) {
        String tenant = required(tenantId, "tenantId");
        String collection = required(collectionId, "collectionId");
        if (entityTypes == null || entityTypes.isEmpty()) return List.of();
        if (entityTypes.size() > 100 || entityTypes.stream().anyMatch(t -> t == null || t.isBlank()))
            throw new IllegalArgumentException("entityTypes must contain at most 100 non-empty types");
        if (limit < 1 || limit > 2_000) throw new IllegalArgumentException("limit must be 1..2000");
        List<String> types = entityTypes.stream().sorted().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(types.size(), "?"));
        String sql = "SELECT entity_id FROM wayang_kg_entity WHERE tenant_id = ? AND collection_id = ?"
                + " AND entity_type IN (" + placeholders + ") ORDER BY entity_id LIMIT ?";
        List<String> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            statement.setString(2, collection);
            int index = 3;
            for (String type : types) statement.setString(index++, type);
            statement.setInt(index, limit + 1);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getString(1));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("JDBC graph entity candidate lookup failed", failure);
        }
        if (ids.size() > limit)
            throw new IllegalStateException("graph entity candidate set exceeds the configured lookup limit");
        List<KnowledgeEntity> candidates = new ArrayList<>(ids.size());
        for (String id : ids) graphStore.findEntity(tenant, collection, id).ifPresent(candidates::add);
        return List.copyOf(candidates);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 200)
            throw new IllegalArgumentException(field + " must contain 1..200 characters");
        return value.strip();
    }
}
