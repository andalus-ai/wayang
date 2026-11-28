package tech.kayys.wayang.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.rag.EntityCandidate;
import tech.kayys.wayang.rag.EntityExtractor;
import tech.kayys.wayang.rag.GraphQuery;
import tech.kayys.wayang.rag.InMemoryGraphStore;
import tech.kayys.wayang.rag.KnowledgeSchema;
import tech.kayys.wayang.rag.InMemoryKnowledgeSchemaRegistry;
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
        return extension(store, null);
    }

    private static KnowledgeExtension extension(InMemoryGraphStore store, FileStore files) {
        return new KnowledgeExtension(files, UrlGuard.strict(), store, store, entities(), relationships(), schema());
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
        assertEquals("provide exactly one of text or fileId; no graph data was changed", result.get("reason"));
        assertTrue(store.findCandidates("tenant-a", "companies", Set.of("Organization"), 10).isEmpty());
    }


    @Test
    void graphIngestReadsTenantOwnedTextFileAndUsesContentDigestAsVersion(@TempDir Path temp) throws Exception {
        InMemoryGraphStore store = new InMemoryGraphStore();
        FileStore files = new FileStore(temp);
        FileStore.Info file = files.put("tenant-a", "annual-report.txt",
                SOURCE_TEXT.getBytes(StandardCharsets.UTF_8), null);
        ServiceHandler.Connector connector = extension(store, files).connectors().get("graph-ingest");

        Map<String, Object> result = connector.call(config(), Map.of("fileId", file.id()));

        assertEquals(true, result.get("ingested"));
        assertEquals(file.id(), result.get("sourceId"));
        assertEquals(file.sha256(), result.get("sourceVersion"));
        var graph = store.traverse(new GraphQuery("tenant-a", "companies",
                Set.of(entityId(store, "Acme")), GraphQuery.Direction.OUTGOING, Set.of(), 1, 10, 10));
        assertEquals(file.id(), graph.relationships().get(0).evidence().get(0).sourceId());
        assertEquals(file.sha256(), graph.relationships().get(0).evidence().get(0).sourceVersion());
    }

    @Test
    void graphIngestCannotReadAnotherTenantsFile(@TempDir Path temp) throws Exception {
        InMemoryGraphStore store = new InMemoryGraphStore();
        FileStore files = new FileStore(temp);
        FileStore.Info file = files.put("tenant-b", "annual-report.txt",
                SOURCE_TEXT.getBytes(StandardCharsets.UTF_8), null);
        ServiceHandler.Connector connector = extension(store, files).connectors().get("graph-ingest");

        Map<String, Object> result = connector.call(config(), Map.of("fileId", file.id()));

        assertEquals(false, result.get("ingested"));
        assertTrue(String.valueOf(result.get("reason")).contains("not found for the current tenant"));
        assertTrue(store.findCandidates("tenant-a", "companies", Set.of("Organization"), 10).isEmpty());
    }

    @Test
    void graphIngestRejectsBinaryFileTypes(@TempDir Path temp) throws Exception {
        InMemoryGraphStore store = new InMemoryGraphStore();
        FileStore files = new FileStore(temp);
        FileStore.Info file = files.put("tenant-a", "report.pdf", new byte[] {1, 2, 3}, null);
        ServiceHandler.Connector connector = extension(store, files).connectors().get("graph-ingest");

        ServiceHandler.ConnectorException failure = assertThrows(ServiceHandler.ConnectorException.class,
                () -> connector.call(config(), Map.of("fileId", file.id())));

        assertTrue(failure.getMessage().contains("supports text files only"));
        assertTrue(store.findCandidates("tenant-a", "companies", Set.of("Organization"), 10).isEmpty());
    }

    @Test
    void graphIngestRequiresExactlyOneTextSource() throws Exception {
        InMemoryGraphStore store = new InMemoryGraphStore();
        ServiceHandler.Connector connector = extension(store).connectors().get("graph-ingest");

        Map<String, Object> result = connector.call(config(),
                Map.of("sourceId", "annual-report", "text", SOURCE_TEXT, "fileId", "000000000000000000000000"));

        assertEquals(false, result.get("ingested"));
        assertTrue(String.valueOf(result.get("reason")).contains("exactly one of text or fileId"));
    }

    @Test
    void graphIngestResolvesExactPinnedSchemaVersionFromRegistry() throws Exception {
        InMemoryGraphStore store = new InMemoryGraphStore();
        InMemoryKnowledgeSchemaRegistry schemas = new InMemoryKnowledgeSchemaRegistry();
        schemas.register(schema());
        KnowledgeExtension extension = new KnowledgeExtension(null, UrlGuard.strict(), store,
                store, entities(), relationships(), schemas);
        ServiceHandler.Connector connector = extension.connectors().get("graph-ingest");

        Map<String, Object> graphConfig = new java.util.HashMap<>(config());
        graphConfig.put("graphSchemaId", "organizations");
        graphConfig.put("graphSchemaVersion", "1");

        Map<String, Object> result = connector.call(graphConfig, inputs("annual-report", "v1"));

        assertEquals(true, result.get("ingested"));
        assertEquals(2, result.get("entitiesUpserted"));
        assertEquals(1, result.get("relationshipsUpserted"));
    }

    @Test
    void graphIngestDoesNotFallBackWhenPinnedSchemaVersionIsMissing() throws Exception {
        InMemoryGraphStore store = new InMemoryGraphStore();
        InMemoryKnowledgeSchemaRegistry schemas = new InMemoryKnowledgeSchemaRegistry();
        schemas.register(schema());
        KnowledgeExtension extension = new KnowledgeExtension(null, UrlGuard.strict(), store,
                store, entities(), relationships(), schemas);
        ServiceHandler.Connector connector = extension.connectors().get("graph-ingest");

        Map<String, Object> graphConfig = new java.util.HashMap<>(config());
        graphConfig.put("graphSchemaId", "organizations");
        graphConfig.put("graphSchemaVersion", "2");

        ServiceHandler.ConnectorException failure = assertThrows(ServiceHandler.ConnectorException.class,
                () -> connector.call(graphConfig, inputs("annual-report", "v1")));

        assertTrue(failure.getMessage().contains("schema version not found"));
        assertTrue(store.findCandidates("tenant-a", "companies", Set.of("Organization"), 10).isEmpty());
    }

    @Test
    void registryBackedGraphIngestRequiresExplicitSchemaIdAndVersion() throws Exception {
        InMemoryGraphStore store = new InMemoryGraphStore();
        InMemoryKnowledgeSchemaRegistry schemas = new InMemoryKnowledgeSchemaRegistry();
        schemas.register(schema());
        KnowledgeExtension extension = new KnowledgeExtension(null, UrlGuard.strict(), store,
                store, entities(), relationships(), schemas);
        ServiceHandler.Connector connector = extension.connectors().get("graph-ingest");

        ServiceHandler.ConnectorException failure = assertThrows(ServiceHandler.ConnectorException.class,
                () -> connector.call(config(), inputs("annual-report", "v1")));

        assertTrue(failure.getMessage().contains("graphSchemaId"));
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
