package tech.kayys.wayang.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ComponentDescriptor;
import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.Profile;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WayangExtension;

/**
 * Adds the knowledge connectors ({@code rag-ingest}, {@code rag-search}, {@code rag-forget}) and the reusable file storage steps ({@code file-put},
 * {@code file-get}, {@code file-list}, {@code file-delete}); loaded only when the profile enables the KNOWLEDGE feature.
 */
public final class KnowledgeExtension implements WayangExtension {

    private final KnowledgeRegistry registry;
    private final FileStore files;
    private final Map<String, ServiceHandler.Connector> connectors;

    public KnowledgeExtension() {
        this(FileStore.fromEnvironment());
    }

    KnowledgeExtension(FileStore files) {
        this(files, new UrlGuard("true".equalsIgnoreCase(System.getenv("WAYANG_CONNECTORS_ALLOW_PRIVATE")), hosts()));
    }

    KnowledgeExtension(FileStore files, UrlGuard guard) {
        this.registry = new KnowledgeRegistry(
                guard,
                files == null ? null : new KnowledgePersistence(files.root().resolve("knowledge")));
        this.files = files;
        this.connectors = Map.of(
                "rag-ingest", new RagIngest(registry, files),
                "rag-search", new RagSearch(registry),
                "rag-forget", new RagForget(registry),
                "file-put", new FileConnectors.Put(files),
                "file-get", new FileConnectors.Get(files),
                "file-list", new FileConnectors.ListFiles(files),
                "file-delete", new FileConnectors.Delete(files),
                "file-purge", new FileConnectors.Purge(files));
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
        return List.of(
                component("rag-ingest", "Add documents to knowledge", "Add documents (files from storage, text, txt, md, html, csv, docx, xlsx, pptx, pdf) to a knowledge base",
                        "knowledge.ingest", "rag",
                        Map.of("knowledge", knowledge, "chunking", Map.of("type", "object"), "embedder", Map.of("type", "object"),
                                "maxDocuments", Map.of("type", "number"), "maxChunks", Map.of("type", "number")),
                        List.of("knowledge")),
                component("rag-search", "Search knowledge", "Retrieve passages from a knowledge base (bm25, vector, hybrid)",
                        "knowledge.search", "rag",
                        Map.of("knowledge", knowledge, "retrieval", Map.of("type", "object")), List.of("knowledge")),
                component("rag-forget", "Remove documents from knowledge", "Remove documents from a knowledge base and its stored copy",
                        "knowledge.forget", "rag", Map.of("knowledge", knowledge), List.of("knowledge")),
                component("file-put", "Store file", "Store a file in the shared file storage and get its id", "files.put", "storage", Map.of(), List.of()),
                component("file-get", "Read file", "Read a stored file by id", "files.get", "storage", Map.of(), List.of()),
                component("file-list", "List files", "List the stored files of the tenant", "files.list", "storage", Map.of(), List.of()),
                component("file-delete", "Delete file", "Delete a stored file by id", "files.delete", "storage", Map.of(), List.of()),
                component("file-purge", "Purge expired files", "Remove the tenant's expired files (they are already unreadable); run it on a schedule", "files.purge", "storage", Map.of(), List.of()));
    }

    private ComponentDescriptor component(String connector, String title, String description, String capability, String category,
            Map<String, Object> props, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", true); // retries, timeouts, inputs, outputMapping... are node-level keys
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
