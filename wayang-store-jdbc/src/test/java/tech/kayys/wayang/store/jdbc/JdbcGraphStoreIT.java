package tech.kayys.wayang.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.rag.GraphQuery;
import tech.kayys.wayang.rag.KnowledgeEntity;
import tech.kayys.wayang.rag.KnowledgeEvidence;
import tech.kayys.wayang.rag.KnowledgeRelationship;

/**
 * PostgreSQL integration coverage for the durable graph adapter.
 * Runs when WAYANG_TEST_JDBC_URL is configured and a PostgreSQL driver is on the test classpath.
 */
class JdbcGraphStoreIT {

    @Test
    void persistsAtomicTenantScopedGraphAndRetractsExactSourceVersion() throws Exception {
        String url = System.getenv("WAYANG_TEST_JDBC_URL");
        if (url == null || url.isBlank()) return;

        DataSource dataSource = new JdbcWaitRegistryIT.DriverManagerDataSource(url);
        JdbcSchema.apply(dataSource);
        clearGraph(dataSource);

        JdbcGraphStore store = new JdbcGraphStore(dataSource);
        KnowledgeEntity company = entity("tenant-a", "company", "Acme",
                List.of(evidence("handbook", "v1"), evidence("handbook", "v2")));
        KnowledgeEntity policy = entity("tenant-a", "policy", "Policy",
                List.of(evidence("handbook", "v1")));
        KnowledgeRelationship relation = relation("tenant-a", "company-policy", "company", "policy",
                evidence("handbook", "v1"));

        store.upsertGraph(List.of(company, policy), List.of(relation));

        // A fresh adapter instance proves that data is stored in the database, not in process memory.
        JdbcGraphStore restarted = new JdbcGraphStore(dataSource);
        assertEquals(2, restarted.traverse(new GraphQuery("tenant-a", "default",
                Set.of("company"), GraphQuery.Direction.OUTGOING, Set.of(), 1, 10, 10)).entities().size());
        assertTrue(restarted.findEntity("tenant-b", "default", "company").isEmpty());

        KnowledgeEntity staged = entity("tenant-a", "must-rollback", "Must Roll Back", List.of(evidence("x", "1")));
        KnowledgeRelationship invalid = relation("tenant-a", "invalid-edge", "must-rollback", "missing",
                evidence("x", "1"));
        assertThrows(IllegalArgumentException.class,
                () -> restarted.upsertGraph(List.of(staged), List.of(invalid)));
        assertTrue(restarted.findEntity("tenant-a", "default", "must-rollback").isEmpty());

        var retraction = restarted.retractEvidence("tenant-a", "default", "handbook", "v1");
        assertEquals(2, retraction.entityEvidenceRemoved());
        assertEquals(1, retraction.relationshipEvidenceRemoved());
        assertEquals(1, retraction.relationshipsRemoved());

        KnowledgeEntity retained = restarted.findEntity("tenant-a", "default", "company").orElseThrow();
        assertEquals(List.of("v2"), retained.evidence().stream().map(KnowledgeEvidence::sourceVersion).toList());
        assertFalse(restarted.traverse(new GraphQuery("tenant-a", "default",
                Set.of("company"), GraphQuery.Direction.OUTGOING, Set.of(), 1, 10, 10))
                .relationships().stream().anyMatch(edge -> edge.id().equals("company-policy")));
    }

    private static void clearGraph(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM wayang_kg_relationship_evidence");
            statement.executeUpdate("DELETE FROM wayang_kg_entity_evidence");
            statement.executeUpdate("DELETE FROM wayang_kg_relationship");
            statement.executeUpdate("DELETE FROM wayang_kg_entity");
        }
    }

    private static KnowledgeEntity entity(String tenant, String id, String name, List<KnowledgeEvidence> evidence) {
        return new KnowledgeEntity(tenant, "default", id, "Organization", name,
                List.of(), Map.of(), evidence);
    }

    private static KnowledgeRelationship relation(
            String tenant, String id, String from, String to, KnowledgeEvidence evidence) {
        return new KnowledgeRelationship(tenant, "default", id, from, "GOVERNS", to,
                Map.of(), List.of(evidence), 0.9);
    }

    private static KnowledgeEvidence evidence(String source, String version) {
        return new KnowledgeEvidence(source, version, "chunk-" + version, 0, 12, "integration-test", 0.95);
    }
}
