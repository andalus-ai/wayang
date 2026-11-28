package tech.kayys.wayang.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.rag.EntityExtractor;
import tech.kayys.wayang.rag.GraphEntityLookup;
import tech.kayys.wayang.rag.GraphStore;
import tech.kayys.wayang.rag.InMemoryGraphStore;
import tech.kayys.wayang.rag.KnowledgeSchema;
import tech.kayys.wayang.rag.RelationshipExtractor;
import tech.kayys.wayang.runtime.ComponentDescriptor;
import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.Profile;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WayangExtension;

/**
 * Adds knowledge, graph and reusable file-storage workflow components. Graph ingestion
 * is published only when a deployment explicitly supplies extraction providers and schema.
 */
public final class KnowledgeExtension implements WayangExtension {

    private final KnowledgeRegistry registry;
    private final Map<String, ServiceHandler.Connector> connectors;

    public KnowledgeExtension() {
        this(FileStore.fromEnvironment());
    }

    KnowledgeExtension(FileStore files) {
        this(files, new UrlGuard("true".equalsIgnoreCase(System.getenv("WAYANG_CONNECTORS_ALLOW_PRIVATE")), hosts()),
                new InMemoryGraphStore());
    }

    KnowledgeExtension(FileStore files, UrlGuard guard) {
        this(files, guard, new InMemoryGraphStore());
    }

    /** Uses a bounded process-local graph store for local development and standalone use. */
    public KnowledgeExtension(FileStore files, UrlGuard guard, GraphStore graphStore) {
        this(files, guard, graphStore, null, null, null, null);
    }

    /**
     * Adds graph ingestion when all deployment-owned extraction dependencies are supplied.
     * No default ontology or extraction provider is invented by the generic extension.
     */
    public KnowledgeExtension(FileStore files, UrlGuard guard, GraphStore graphStore,
            GraphEntityLookup entityLookup, EntityExtractor entityExtractor,
            RelationshipExtractor relationshipExtractor, KnowledgeSchema schema) {
        this.registry = new KnowledgeRegistry(
                guard,
                files == null ? null : new KnowledgePersistence(files.root().resolve("knowledge")),
                files == null ? java.time.Clock.systemUTC() : files.clock());
        java.util.Objects.requireNonNull(graphStore, "graphStore");
        boolean anyExtractionDependency = entityLookup != null || entityExtractor != null
                || relationshipExtractor != null || schema != null;
        boolean allExtractionDependencies = entityLookup != null && entityExtractor != null
                && relationshipExtractor != null && schema != null;
        if (anyExtractionDependency && !allExtractionDependencies) {
            throw new IllegalArgumentException("graph ingestion requires entity lookup, both extractors and a schema");
        }

        Map<String, ServiceHandler.Connector> registered = new LinkedHashMap<>();
        registered.put("rag-ingest", new RagIngest(registry, files));
        registered.put("rag-search", new RagSearch(registry));
        registered.put("rag-forget", new RagForget(registry));
        registered.put("rag-list", new RagList(registry));
        registered.put("file-put", new FileConnectors.Put(files));
        registered.put("file-fetch", new FileConnectors.Fetch(files, guard));
        registered.put("file-get", new FileConnectors.Get(files));
        registered.put("file-list", new FileConnectors.ListFiles(files));
        registered.put("file-delete", new FileConnectors.Delete(files));
        registered.put("file-purge", new FileConnectors.Purge(files));
        registered.put("graph-query", new GraphQueryConnector(graphStore));
        registered.put("graph-retract", new GraphRetractConnector(graphStore));
        if (allExtractionDependencies) {
            registered.put("graph-ingest", new GraphIngestConnector(graphStore, files, entityLookup,
                    entityExtractor, relationshipExtractor, schema));
        }
        this.connectors = Map.copyOf(registered);
    }

    @Override
    public String id() {
        return "knowledge";
    }

    @Override
    public Set<Profile.Feature> requires() {
        return Set.of(Profile.Feature.KNOWLEDGE);
    }

    @Override
    public Map<String, ServiceHandler.Connector> connectors() {
        return connectors;
    }

    @Override
    public List<ComponentDescriptor> components() {
        Map<String, Object> knowledge = Map.of("type", "string", "description", "Name of the knowledge base (letters, digits, - and _)");
        List<ComponentDescriptor> result = new ArrayList<>(List.of(
                component("rag-ingest", "Add documents to knowledge", "Add documents (files from storage, text, txt, md, html, csv, docx, xlsx, pptx, pdf) to a knowledge base",
                        "knowledge.ingest", "rag",
                        Map.of("knowledge", knowledge, "chunking", Map.of("type", "object"), "embedder", Map.of("type", "object"),
                                "maxDocuments", Map.of("type", "number"), "maxChunks", Map.of("type", "number"),
                                "ttlDays", Map.of("type", "number", "description", "Default life of ingested documents in days (1-3650); a document's own ttlDays wins")),
                        List.of("knowledge")),
                component("rag-search", "Search knowledge", "Retrieve passages from a knowledge base (bm25, vector, hybrid)",
                        "knowledge.search", "rag",
                        Map.of("knowledge", knowledge, "retrieval", Map.of("type", "object")), List.of("knowledge")),
                component("rag-forget", "Remove documents from knowledge", "Remove documents from a knowledge base and its stored copy; with no ids it clears expired documents (schedule it)",
                        "knowledge.forget", "rag", Map.of("knowledge", knowledge), List.of("knowledge")),
                component("rag-list", "List knowledge documents", "List the documents of a knowledge base with the time each expires (ids only, never their text)",
                        "knowledge.list", "rag", Map.of("knowledge", knowledge), List.of("knowledge")),
                component("file-put", "Store file", "Store a file in the shared file storage and get its id", "files.put", "storage", Map.of(), List.of()),
                component("file-fetch", "Fetch file from URL", "Download a public document from a URL into the shared file storage and get its id", "files.fetch", "storage", Map.of(), List.of()),
                component("file-get", "Read file", "Read a stored file by id", "files.get", "storage", Map.of(), List.of()),
                component("file-list", "List files", "List the stored files of the tenant", "files.list", "storage", Map.of(), List.of()),
                component("file-delete", "Delete file", "Delete a stored file by id", "files.delete", "storage", Map.of(), List.of()),
                component("file-purge", "Purge expired files", "Remove the tenant's expired files (they are already unreadable); run it on a schedule", "files.purge", "storage", Map.of(), List.of()),
                component("graph-query", "Traverse knowledge graph", "Expand canonical entity ids through a bounded, tenant-scoped knowledge graph traversal",
                        "knowledge.graph.query", "knowledge-graph",
                        Map.of("graphCollection", Map.of("type", "string", "minLength", 1, "maxLength", 200),
                                "graphDirection", Map.of("type", "string", "enum", List.of("OUTGOING", "INCOMING", "BOTH")),
                                "graphMaxDepth", Map.of("type", "integer", "minimum", 1, "maximum", 3),
                                "graphMaxNodes", Map.of("type", "integer", "minimum", 1, "maximum", 1000),
                                "graphMaxEdges", Map.of("type", "integer", "minimum", 1, "maximum", 2000)), List.of("graphCollection")),
                component("graph-retract", "Retract graph evidence", "Remove evidence for an exact source version while preserving facts supported by other sources",
                        "knowledge.graph.retract", "knowledge-graph",
                        Map.of("graphCollection", Map.of("type", "string", "minLength", 1, "maxLength", 200)), List.of("graphCollection"))));
        if (connectors.containsKey("graph-ingest")) {
            result.add(component("graph-ingest", "Extract knowledge graph", "Extract and validate entities and relationships from source text using deployment-configured providers and ontology",
                    "knowledge.graph.ingest", "knowledge-graph",
                    Map.of("graphCollection", Map.of("type", "string", "minLength", 1, "maxLength", 200),
                            "extractorId", Map.of("type", "string", "maxLength", 200)), List.of("graphCollection")));
        }
        return List.copyOf(result);
    }

    private ComponentDescriptor component(String connector, String title, String description, String capability, String category,
            Map<String, Object> props, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", true);
        List<Map<String, Object>> inputs = connectors.get(connector).inputs().stream().map(ConnectorInput::toMap).toList();
        return new ComponentDescriptor("wayang.knowledge." + connector, "0.0.1", ComponentDescriptor.Kind.CONNECTOR, "wayang-service",
                description, Set.of(Profile.Feature.KNOWLEDGE), List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of(capability),
                title, category, null, schema, Map.of("inputs", inputs));
    }

    private static Set<String> hosts() {
        String h = System.getenv("WAYANG_CONNECTORS_ALLOWED_HOSTS");
        if (h == null || h.isBlank()) {
            return Set.of();
        }
        return Set.copyOf(java.util.Arrays.stream(h.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
    }
}
