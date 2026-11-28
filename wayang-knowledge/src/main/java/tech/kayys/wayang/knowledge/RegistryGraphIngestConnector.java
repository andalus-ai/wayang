package tech.kayys.wayang.knowledge;

import java.util.List;
import java.util.Map;

import tech.kayys.wayang.rag.EntityExtractor;
import tech.kayys.wayang.rag.GraphEntityLookup;
import tech.kayys.wayang.rag.GraphStore;
import tech.kayys.wayang.rag.KnowledgeSchema;
import tech.kayys.wayang.rag.KnowledgeSchemaRegistry;
import tech.kayys.wayang.rag.RelationshipExtractor;
import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Resolves an explicitly pinned schema version for each graph-ingest invocation.
 * It never selects "latest" and never falls back to a different schema.
 */
final class RegistryGraphIngestConnector implements ServiceHandler.Connector {

    private final GraphStore graphStore;
    private final FileStore fileStore;
    private final GraphEntityLookup entityLookup;
    private final EntityExtractor entityExtractor;
    private final RelationshipExtractor relationshipExtractor;
    private final KnowledgeSchemaRegistry schemaRegistry;

    RegistryGraphIngestConnector(
            GraphStore graphStore,
            FileStore fileStore,
            GraphEntityLookup entityLookup,
            EntityExtractor entityExtractor,
            RelationshipExtractor relationshipExtractor,
            KnowledgeSchemaRegistry schemaRegistry) {
        this.graphStore = java.util.Objects.requireNonNull(graphStore, "graphStore");
        this.fileStore = fileStore;
        this.entityLookup = java.util.Objects.requireNonNull(entityLookup, "entityLookup");
        this.entityExtractor = java.util.Objects.requireNonNull(entityExtractor, "entityExtractor");
        this.relationshipExtractor = java.util.Objects.requireNonNull(relationshipExtractor, "relationshipExtractor");
        this.schemaRegistry = java.util.Objects.requireNonNull(schemaRegistry, "schemaRegistry");
    }

    @Override
    public List<ConnectorInput> inputs() {
        return List.of(
                ConnectorInput.optional("sourceId", "string", "Stable source document id; defaults to fileId when ingesting a stored file"),
                ConnectorInput.optional("sourceVersion", "string", "Exact source version; defaults to the content SHA-256 when omitted"),
                ConnectorInput.optional("text", "string", "Normalized UTF-8 text to extract"),
                ConnectorInput.optional("fileId", "string", "Tenant-owned file id returned by file-put or file-fetch; supported for text files"));
    }

    @Override
    public boolean tenantScoped() {
        return true;
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ServiceHandler.ConnectorException("graph-ingest needs node configuration", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        String schemaId = required(config.get("graphSchemaId"), "graphSchemaId");
        String schemaVersion = required(config.get("graphSchemaVersion"), "graphSchemaVersion");
        KnowledgeSchema schema = schemaRegistry.find(schemaId, schemaVersion).orElseThrow(
                () -> new ServiceHandler.ConnectorException(
                        "knowledge schema version not found: " + schemaId + "@" + schemaVersion, false));
        return new GraphIngestConnector(graphStore, fileStore, entityLookup,
                entityExtractor, relationshipExtractor, schema).call(config, inputs);
    }

    private static String required(Object value, String field) throws ServiceHandler.ConnectorException {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 200) {
            throw new ServiceHandler.ConnectorException(field + " must contain 1..200 characters", false);
        }
        return text.strip();
    }
}
