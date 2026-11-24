package tech.kayys.wayang.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.rag.InMemoryGraphStore;
import tech.kayys.wayang.rag.KnowledgeEntity;
import tech.kayys.wayang.rag.KnowledgeEvidence;
import tech.kayys.wayang.rag.KnowledgeRelationship;
import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.ServiceHandler;

class GraphWorkflowConnectorTest {

    private static KnowledgeEvidence evidence(String version) {
        return new KnowledgeEvidence("policy-doc", version, "chunk-1", 0, 12, "test", 0.95);
    }

    private static KnowledgeEntity entity(String id, String name, String version) {
        return new KnowledgeEntity("tenant-a", "policies", id, "Organization", name,
                List.of(), Map.of(), List.of(evidence(version)));
    }

    private static KnowledgeRelationship relationship(String version) {
        return new KnowledgeRelationship("tenant-a", "policies", "rel-1", "acme", "OWNS", "globex",
                Map.of(), List.of(evidence(version)), 0.9);
    }

    private static KnowledgeExtension extension(InMemoryGraphStore store) {
        return new KnowledgeExtension(null, UrlGuard.strict(), store);
    }

    @Test
    void graphQueryUsesBoundedTenantScopedTraversalAndPublishesMandatorySeedInput() throws Exception {
        InMemoryGraphStore store = new InMemoryGraphStore();
        store.upsertGraph(List.of(entity("acme", "Acme Corp", "v1"), entity("globex", "Globex", "v1")),
                List.of(relationship("v1")));
        ServiceHandler.Connector connector = extension(store).connectors().get("graph-query");

        assertTrue(connector.inputs().stream().anyMatch(input ->
                input.name().equals("seedEntityIds") && input.mandatory()));
        Map<String, Object> result = connector.call(
                Map.of("wayang.tenant", "tenant-a", "graphCollection", "policies"),
                Map.of("seedEntityIds", List.of("acme"), "predicates", List.of("OWNS")));

        assertEquals(2, result.get("entityCount"));
        assertEquals(1, result.get("relationshipCount"));
        assertEquals(false, result.get("truncated"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relationships = (List<Map<String, Object>>) result.get("relationships");
        assertEquals("OWNS", relationships.get(0).get("predicate"));
    }

    @Test
    void missingGraphSeedsReturnAnEmptyAdvisoryResultInsteadOfFailingTheNode() throws Exception {
        ServiceHandler.Connector connector = extension(new InMemoryGraphStore()).connectors().get("graph-query");

        Map<String, Object> result = connector.call(
                Map.of("wayang.tenant", "tenant-a", "graphCollection", "policies"), Map.of());

        assertEquals(0, result.get("entityCount"));
        assertEquals(0, result.get("relationshipCount"));
        assertEquals("no seed entity ids were provided", result.get("reason"));
    }

    @Test
    void graphRetractionRemovesOnlyTheExactSourceVersion() throws Exception {
        InMemoryGraphStore store = new InMemoryGraphStore();
        store.upsertGraph(
                List.of(entity("acme", "Acme Corp", "v1"), entity("acme", "Acme Corp", "v2"),
                        entity("globex", "Globex", "v1"), entity("globex", "Globex", "v2")),
                List.of(relationship("v1"), relationship("v2")));
        ServiceHandler.Connector connector = extension(store).connectors().get("graph-retract");

        assertTrue(connector.inputs().stream().filter(ConnectorInput::mandatory).count() == 2);
        Map<String, Object> result = connector.call(
                Map.of("wayang.tenant", "tenant-a", "graphCollection", "policies"),
                Map.of("sourceId", "policy-doc", "sourceVersion", "v1"));

        assertEquals(true, result.get("retracted"));
        assertEquals(2, result.get("entityEvidenceRemoved"));
        assertEquals(1, result.get("relationshipEvidenceRemoved"));
        assertEquals(0, result.get("relationshipsRemoved"));
        var traversal = store.traverse(new tech.kayys.wayang.rag.GraphQuery("tenant-a", "policies",
                Set.of("acme"), tech.kayys.wayang.rag.GraphQuery.Direction.BOTH, Set.of(), 1, 10, 10));
        assertEquals(1, traversal.relationships().size());
        assertEquals("v2", traversal.relationships().get(0).evidence().get(0).sourceVersion());
    }

    @Test
    void missingRetractionInputsAreAdvisoryAndDoNotMutateTheGraph() throws Exception {
        InMemoryGraphStore store = new InMemoryGraphStore();
        store.upsertEntity(entity("acme", "Acme Corp", "v1"));
        ServiceHandler.Connector connector = extension(store).connectors().get("graph-retract");

        Map<String, Object> result = connector.call(
                Map.of("wayang.tenant", "tenant-a", "graphCollection", "policies"), Map.of("sourceId", "policy-doc"));

        assertEquals(false, result.get("retracted"));
        assertEquals(0, result.get("entityEvidenceRemoved"));
        assertTrue(store.findEntity("tenant-a", "policies", "acme").isPresent());
    }
}
