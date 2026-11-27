package tech.kayys.wayang.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.rag.EntityCandidate;
import tech.kayys.wayang.rag.EntityExtractor;
import tech.kayys.wayang.rag.GraphQuery;
import tech.kayys.wayang.rag.InMemoryGraphStore;
import tech.kayys.wayang.rag.KnowledgeSchema;
import tech.kayys.wayang.rag.RelationshipCandidate;
import tech.kayys.wayang.rag.RelationshipExtractor;
import tech.kayys.wayang.rag.RelationshipTypeDefinition;
import tech.kayys.wayang.runtime.ServiceHandler;

class GraphIngestConnectorTest {
    private static final String SOURCE_TEXT = "Acme owns Beta.";

    private static KnowledgeSchema schema() {
        return new KnowledgeSchema("organizations", "1",
                Set.of("Organization"),
                Map.of("OWNS", new RelationshipTypeDefinition("OWNS",
                        Set.of("Organization"), Set.of("Organization"))));
    }

    private static EntityExtractor entities() {
        return input -> List.of(
                new EntityCandidate("acme-mention", "Organization", "Acme", "acme", 0, 4, 0.99),
                new EntityCandidate("beta-mention", "Organization", "Beta", "beta", 10, 14, 0.98));
    }

    private static RelationshipExtractor relationships() {
        return (input, candidates) -> List.of(new RelationshipCandidate(
                "owns-mention", "acme-mention", "OWNS", "beta-mention", 0, 14, 0.95));
    }

    private static KnowledgeExtension extension(InMemoryGraphStore store) {
        return new KnowledgeExtension(null, UrlGuard.strict(), store, store, entities(), relationships(), schema());
    }

    private static Map<String, Object> config() {
        return Map.of("wayang.tenant", "tenant-a", "graphCollection", "companies");
    }

    private static Map<String, Object> inputs(String source, String version) {
        return Map.of("sourceId", source, "sourceVersion", version, "text", SOURCE_TEXT);
    }

    @Test
    void graphIngestExtractsValidatesResolvesAndWritesEvidence() throws Exception {
        InMemoryGraphStore store = new InMemoryGraphStore();
        ServiceHandler.Connector connector = extension(store).connectors().get("graph-ingest");

        Map<String, Object> result = connector.call(config(), inputs("annual-report", "v1"));

        assertEquals(true, result.get("ingested"));
        assertEquals(1, result.get("chunksProcessed"));
        assertEquals(2, result.get("entitiesUpserted"));
        assertEquals(1, result.get("relationshipsUpserted"));
        assertEquals(0, result.get("unresolvedEntities"));
        assertTrue(((List<?>) result.get("issues")).isEmpty());
        var graph = store.traverse(new GraphQuery("tenant-a", "companies",
                Set.of(entityId(store, "Acme")), GraphQuery.Direction.OUTGOING, Set.of(), 1, 10, 10));
        assertEquals(2, graph.entities().size());
        assertEquals(1, graph.relationships().size());
        assertEquals("annual-report", graph.relationships().get(0).evidence().get(0).sourceId());
    }

    @Test
    void repeatedSourceVersionIsIdempotentAndNewVersionAccumulatesEvidence() throws Exception {
        InMemoryGraphStore store = new InMemoryGraphStore();
        ServiceHandler.Connector connector = extension(store).connectors().get("graph-ingest");

        connector.call(config(), inputs("annual-report", "v1"));
        connector.call(config(), inputs("annual-report", "v1"));
        connector.call(config(), inputs("annual-report", "v2"));

        var graph = store.traverse(new GraphQuery("tenant-a", "companies",
                Set.of(entityId(store, "Acme")), GraphQuery.Direction.OUTGOING, Set.of(), 1, 10, 10));
        assertEquals(2, graph.entities().size());
        assertEquals(1, graph.relationships().size());
        assertEquals(2, graph.relationships().get(0).evidence().size());
        assertTrue(graph.relationships().get(0).evidence().stream()
                .anyMatch(e -> e.sourceVersion().equals("v1")));
        assertTrue(graph.relationships().get(0).evidence().stream()
                .anyMatch(e -> e.sourceVersion().equals("v2")));
    }

    @Test
    void missingInputsAreAdvisoryAndDoNotWriteGraphData() throws Exception {
        InMemoryGraphStore store = new InMemoryGraphStore();
        ServiceHandler.Connector connector = extension(store).connectors().get("graph-ingest");

        Map<String, Object> result = connector.call(config(), Map.of("sourceId", "annual-report"));

        assertEquals(false, result.get("ingested"));
        assertEquals(0, result.get("chunksProcessed"));
        assertEquals("sourceId, sourceVersion and text are required; no graph data was changed", result.get("reason"));
        assertTrue(store.findCandidates("tenant-a", "companies", Set.of("Organization"), 10).isEmpty());
    }

    @Test
    void graphIngestIsNotAdvertisedWithoutDeploymentExtractionConfiguration() {
        KnowledgeExtension extension = new KnowledgeExtension(null, UrlGuard.strict(), new InMemoryGraphStore());
        assertFalse(extension.connectors().containsKey("graph-ingest"));
        assertTrue(extension.components().stream().noneMatch(c -> c.id().equals("wayang.knowledge.graph-ingest")));
    }

    @Test
    void partialExtractionConfigurationIsRejected() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        assertThrows(IllegalArgumentException.class, () ->
                new KnowledgeExtension(null, UrlGuard.strict(), store, store, entities(), null, schema()));
    }

    private static String entityId(InMemoryGraphStore store, String name) {
        return store.findCandidates("tenant-a", "companies", Set.of("Organization"), 10).stream()
                .filter(e -> e.canonicalName().equals(name)).findFirst().orElseThrow().id();
    }
}
