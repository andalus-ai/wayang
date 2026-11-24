package tech.kayys.wayang.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.rag.GraphQuery;
import tech.kayys.wayang.rag.GraphStore;
import tech.kayys.wayang.rag.KnowledgeEntity;
import tech.kayys.wayang.rag.KnowledgeEvidence;
import tech.kayys.wayang.rag.KnowledgeRelationship;
import tech.kayys.wayang.rag.RagException;
import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.ServiceHandler;

/** Bounded tenant-scoped graph traversal workflow connector. */
final class GraphQueryConnector implements ServiceHandler.Connector {
    private final GraphStore graphStore;

    GraphQueryConnector(GraphStore graphStore) {
        this.graphStore = java.util.Objects.requireNonNull(graphStore, "graphStore");
    }

    @Override
    public List<ConnectorInput> inputs() {
        return List.of(
                ConnectorInput.required("seedEntityIds", "array", "Canonical entity ids to expand; connect from entity linking or a prior graph step"),
                ConnectorInput.optional("predicates", "array", "Optional relationship predicate allow-list"));
    }

    @Override
    public boolean tenantScoped() {
        return true;
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws ServiceHandler.ConnectorException {
        throw new ServiceHandler.ConnectorException("graph-query needs node configuration", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        List<String> seeds = strings(inputs.get("seedEntityIds"), "seedEntityIds", 100);
        if (seeds.isEmpty()) {
            return Map.of("entities", List.of(), "relationships", List.of(), "entityCount", 0,
                    "relationshipCount", 0, "truncated", false, "reason", "no seed entity ids were provided");
        }
        List<String> predicates = strings(inputs.get("predicates"), "predicates", 100);
        String tenant = required(config.get("wayang.tenant"), "wayang.tenant");
        String collection = required(config.get("graphCollection"), "graphCollection");
        GraphQuery.Direction direction = direction(config.get("graphDirection"));
        GraphQuery query = new GraphQuery(tenant, collection, Set.copyOf(seeds), direction, Set.copyOf(predicates),
                integer(config.get("graphMaxDepth"), 2),
                integer(config.get("graphMaxNodes"), 100),
                integer(config.get("graphMaxEdges"), 200));
        var result = graphStore.traverse(query);
        List<Map<String, Object>> entities = result.entities().stream().map(GraphQueryConnector::entity).toList();
        List<Map<String, Object>> relationships = result.relationships().stream().map(GraphQueryConnector::relationship).toList();
        return Map.of("entities", entities, "relationships", relationships,
                "entityCount", entities.size(), "relationshipCount", relationships.size(),
                "truncated", result.truncated());
    }

    private static Map<String, Object> entity(KnowledgeEntity value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", value.id());
        out.put("type", value.type());
        out.put("canonicalName", value.canonicalName());
        out.put("aliases", value.aliases());
        out.put("properties", value.properties());
        out.put("evidence", value.evidence().stream().map(GraphQueryConnector::evidence).toList());
        return out;
    }

    private static Map<String, Object> relationship(KnowledgeRelationship value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", value.id());
        out.put("fromEntityId", value.fromEntityId());
        out.put("predicate", value.predicate());
        out.put("toEntityId", value.toEntityId());
        out.put("properties", value.properties());
        out.put("confidence", value.confidence());
        out.put("evidence", value.evidence().stream().map(GraphQueryConnector::evidence).toList());
        return out;
    }

    private static Map<String, Object> evidence(KnowledgeEvidence value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceId", value.sourceId());
        out.put("sourceVersion", value.sourceVersion());
        out.put("chunkId", value.chunkId());
        out.put("startOffset", value.startOffset());
        out.put("endOffset", value.endOffset());
        out.put("extractor", value.extractor());
        out.put("confidence", value.confidence());
        return out;
    }

    private static List<String> strings(Object value, String field, int max) throws ServiceHandler.ConnectorException {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.size() > max)
            throw new ServiceHandler.ConnectorException(field + " must be an array of at most " + max + " strings", false);
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank() || text.length() > 300)
                throw new ServiceHandler.ConnectorException(field + " must contain non-blank strings of at most 300 characters", false);
            result.add(text.strip());
        }
        return List.copyOf(result);
    }

    private static String required(Object value, String field) throws ServiceHandler.ConnectorException {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 200)
            throw new ServiceHandler.ConnectorException(field + " must be configured", false);
        return text.strip();
    }

    private static int integer(Object value, int fallback) throws ServiceHandler.ConnectorException {
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue()))
            throw new ServiceHandler.ConnectorException("graph traversal limits must be whole numbers", false);
        return number.intValue();
    }

    private static GraphQuery.Direction direction(Object value) throws ServiceHandler.ConnectorException {
        if (value == null) return GraphQuery.Direction.BOTH;
        if (!(value instanceof String text)) throw new ServiceHandler.ConnectorException("graphDirection must be a string", false);
        try {
            return GraphQuery.Direction.valueOf(text.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ServiceHandler.ConnectorException("graphDirection must be OUTGOING, INCOMING or BOTH", false);
        }
    }
}
