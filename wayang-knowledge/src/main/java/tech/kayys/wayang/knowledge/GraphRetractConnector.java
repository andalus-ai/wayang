package tech.kayys.wayang.knowledge;

import java.util.List;
import java.util.Map;

import tech.kayys.wayang.rag.GraphStore;
import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.ServiceHandler;

/** Removes evidence for one exact source version without deleting still-supported graph facts. */
final class GraphRetractConnector implements ServiceHandler.Connector {
    private final GraphStore graphStore;

    GraphRetractConnector(GraphStore graphStore) {
        this.graphStore = java.util.Objects.requireNonNull(graphStore, "graphStore");
    }

    @Override
    public List<ConnectorInput> inputs() {
        return List.of(
                ConnectorInput.required("sourceId", "string", "Source document id whose evidence should be retracted"),
                ConnectorInput.required("sourceVersion", "string", "Exact source version; other versions remain untouched"));
    }

    @Override
    public boolean tenantScoped() {
        return true;
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws ServiceHandler.ConnectorException {
        throw new ServiceHandler.ConnectorException("graph-retract needs node configuration", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        Object sourceValue = inputs.get("sourceId");
        Object versionValue = inputs.get("sourceVersion");
        if (missing(sourceValue) || missing(versionValue)) {
            return Map.of("retracted", false, "entityEvidenceRemoved", 0,
                    "relationshipEvidenceRemoved", 0, "relationshipsRemoved", 0,
                    "reason", "sourceId and sourceVersion are required; no graph data was changed");
        }
        String source = bounded(sourceValue, "sourceId");
        String version = bounded(versionValue, "sourceVersion");
        String tenant = bounded(config.get("wayang.tenant"), "wayang.tenant");
        String collection = bounded(config.get("graphCollection"), "graphCollection");
        var result = graphStore.retractEvidence(tenant, collection, source, version);
        return Map.of("retracted", result.entityEvidenceRemoved() + result.relationshipEvidenceRemoved() > 0,
                "entityEvidenceRemoved", result.entityEvidenceRemoved(),
                "relationshipEvidenceRemoved", result.relationshipEvidenceRemoved(),
                "relationshipsRemoved", result.relationshipsRemoved());
    }

    private static boolean missing(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private static String bounded(Object value, String field) throws ServiceHandler.ConnectorException {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 200)
            throw new ServiceHandler.ConnectorException(field + " must contain 1..200 characters", false);
        return text.strip();
    }
}
