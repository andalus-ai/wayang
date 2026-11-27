package tech.kayys.wayang.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.rag.Chunk;
import tech.kayys.wayang.rag.Chunkers;
import tech.kayys.wayang.rag.Document;
import tech.kayys.wayang.rag.EntityCandidate;
import tech.kayys.wayang.rag.EntityResolutionRequest;
import tech.kayys.wayang.rag.EntityResolver;
import tech.kayys.wayang.rag.ExactAliasEntityResolver;
import tech.kayys.wayang.rag.GraphEntityLookup;
import tech.kayys.wayang.rag.GraphStore;
import tech.kayys.wayang.rag.KnowledgeCandidateValidator;
import tech.kayys.wayang.rag.KnowledgeExtractionInput;
import tech.kayys.wayang.rag.KnowledgeGraphIngestionResult;
import tech.kayys.wayang.rag.KnowledgeGraphIngestionService;
import tech.kayys.wayang.rag.KnowledgeSchema;
import tech.kayys.wayang.rag.RelationshipCandidate;
import tech.kayys.wayang.rag.EntityExtractor;
import tech.kayys.wayang.rag.RelationshipExtractor;
import tech.kayys.wayang.rag.ValidatedKnowledgeCandidates;
import tech.kayys.wayang.rag.RagException;
import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Workflow connector that extracts graph candidates from supplied source text,
 * validates them against deployment-owned ontology, resolves canonical entities,
 * and writes source-versioned evidence through the graph ingestion service.
 */
final class GraphIngestConnector implements ServiceHandler.Connector {
    static final int MAX_TEXT_CHARS = 200_000;
    static final int MAX_CHUNKS = 100;
    static final int MAX_CANDIDATES_PER_CHUNK = 500;
    static final int MAX_EXISTING_ENTITIES = 2_000;

    private final GraphStore graphStore;
    private final GraphEntityLookup entityLookup;
    private final EntityExtractor entityExtractor;
    private final RelationshipExtractor relationshipExtractor;
    private final KnowledgeSchema schema;
    private final KnowledgeCandidateValidator validator = new KnowledgeCandidateValidator();
    private final EntityResolver resolver = new ExactAliasEntityResolver();

    GraphIngestConnector(GraphStore graphStore, GraphEntityLookup entityLookup,
            EntityExtractor entityExtractor, RelationshipExtractor relationshipExtractor,
            KnowledgeSchema schema) {
        this.graphStore = java.util.Objects.requireNonNull(graphStore, "graphStore");
        this.entityLookup = java.util.Objects.requireNonNull(entityLookup, "entityLookup");
        this.entityExtractor = java.util.Objects.requireNonNull(entityExtractor, "entityExtractor");
        this.relationshipExtractor = java.util.Objects.requireNonNull(relationshipExtractor, "relationshipExtractor");
        this.schema = java.util.Objects.requireNonNull(schema, "schema");
    }

    @Override
    public List<ConnectorInput> inputs() {
        return List.of(
                ConnectorInput.required("sourceId", "string", "Stable source document id"),
                ConnectorInput.required("sourceVersion", "string", "Exact source version used for evidence lifecycle"),
                ConnectorInput.required("text", "string", "Normalized source text to extract; connect from file-get or RAG preparation"));
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
        Object sourceValue = inputs.get("sourceId");
        Object versionValue = inputs.get("sourceVersion");
        Object textValue = inputs.get("text");
        if (missing(sourceValue) || missing(versionValue) || missing(textValue)) {
            return Map.of("ingested", false, "chunksProcessed", 0, "entitiesUpserted", 0,
                    "relationshipsUpserted", 0, "issues", List.of(),
                    "reason", "sourceId, sourceVersion and text are required; no graph data was changed");
        }

        String sourceId = bounded(sourceValue, "sourceId", 200);
        String sourceVersion = bounded(versionValue, "sourceVersion", 200);
        String sourceText = boundedText(textValue);
        String tenant = bounded(config.get("wayang.tenant"), "wayang.tenant", 200);
        String collection = bounded(config.get("graphCollection"), "graphCollection", 200);
        String extractorId = config.get("extractorId") instanceof String id && !id.isBlank()
                ? bounded(id, "extractorId", 200) : schema.schemaId() + ":" + schema.version();

        try {
            Document document = new Document(sourceId, sourceId, sourceText, Map.of());
            List<Chunk> chunks = Chunkers.paragraph(Chunkers.MAX_CHARS).chunk(document);
            if (chunks.size() > MAX_CHUNKS) {
                throw new ServiceHandler.ConnectorException("source text produces more than " + MAX_CHUNKS + " chunks", false);
            }

            int entitiesUpserted = 0;
            int relationshipsUpserted = 0;
            int unresolvedEntities = 0;
            int skippedRelationships = 0;
            List<Map<String, Object>> issues = new ArrayList<>();

            for (Chunk chunk : chunks) {
                KnowledgeExtractionInput extractionInput =
                        new KnowledgeExtractionInput(tenant, collection, sourceVersion, document, chunk);
                List<EntityCandidate> entities = boundedEntities(entityExtractor.extract(extractionInput));
                List<RelationshipCandidate> relationships =
                        boundedRelationships(relationshipExtractor.extract(extractionInput, entities));
                ValidatedKnowledgeCandidates validated =
                        validator.validate(extractionInput, entities, relationships, schema);

                if (validated.entities().isEmpty()) {
                    appendIssues(issues, validated.issues());
                    continue;
                }

                Set<String> types = validated.entities().stream().map(EntityCandidate::entityType)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                List<tech.kayys.wayang.rag.KnowledgeEntity> existing =
                        entityLookup.findCandidates(tenant, collection, types, MAX_EXISTING_ENTITIES);
                var decisions = resolver.resolve(new EntityResolutionRequest(tenant, collection,
                        validated.entities(), existing));
                KnowledgeGraphIngestionResult result =
                        new KnowledgeGraphIngestionService(graphStore).ingest(extractionInput, validated, decisions, extractorId);
                entitiesUpserted += result.entitiesUpserted();
                relationshipsUpserted += result.relationshipsUpserted();
                unresolvedEntities += result.unresolvedEntities();
                skippedRelationships += result.skippedRelationships();
                appendIssues(issues, result.issues());
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ingested", entitiesUpserted > 0 || relationshipsUpserted > 0);
            out.put("sourceId", sourceId);
            out.put("sourceVersion", sourceVersion);
            out.put("chunksProcessed", chunks.size());
            out.put("entitiesUpserted", entitiesUpserted);
            out.put("relationshipsUpserted", relationshipsUpserted);
            out.put("unresolvedEntities", unresolvedEntities);
            out.put("skippedRelationships", skippedRelationships);
            out.put("issues", List.copyOf(issues));
            return out;
        } catch (RagException | IllegalArgumentException failure) {
            throw new ServiceHandler.ConnectorException(failure.getMessage(), false);
        }
    }

    private static List<EntityCandidate> boundedEntities(List<EntityCandidate> candidates)
            throws ServiceHandler.ConnectorException {
        if (candidates == null) return List.of();
        if (candidates.size() > MAX_CANDIDATES_PER_CHUNK)
            throw new ServiceHandler.ConnectorException("entity extractor exceeded the per-chunk candidate limit", false);
        return List.copyOf(candidates);
    }

    private static List<RelationshipCandidate> boundedRelationships(List<RelationshipCandidate> candidates)
            throws ServiceHandler.ConnectorException {
        if (candidates == null) return List.of();
        if (candidates.size() > MAX_CANDIDATES_PER_CHUNK)
            throw new ServiceHandler.ConnectorException("relationship extractor exceeded the per-chunk candidate limit", false);
        return List.copyOf(candidates);
    }

    private static void appendIssues(List<Map<String, Object>> target,
            List<tech.kayys.wayang.rag.KnowledgeValidationIssue> source) {
        for (var issue : source) {
            target.add(Map.of("candidateId", issue.candidateId(), "code", issue.code(), "message", issue.message()));
        }
    }

    private static boolean missing(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private static String bounded(Object value, String field, int max) throws ServiceHandler.ConnectorException {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max)
            throw new ServiceHandler.ConnectorException(field + " must contain 1.." + max + " characters", false);
        return text.strip();
    }

    private static String boundedText(Object value) throws ServiceHandler.ConnectorException {
        if (!(value instanceof String text) || text.isBlank() || text.length() > MAX_TEXT_CHARS)
            throw new ServiceHandler.ConnectorException("text must contain 1.." + MAX_TEXT_CHARS + " characters", false);
        return text;
    }
}
