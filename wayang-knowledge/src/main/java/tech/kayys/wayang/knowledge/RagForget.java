package tech.kayys.wayang.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.rag.KnowledgeBase;
import tech.kayys.wayang.rag.RagException;
import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code rag-forget}: removes documents from a knowledge base (and from its stored copy). Config: {@code knowledge}. Input:
 * {@code documentIds} (mandatory, advisory: when missing nothing is removed). Output: {@code removed}, {@code documents} left.
 */
final class RagForget implements ServiceHandler.Connector {

    private final KnowledgeRegistry registry;

    RagForget(KnowledgeRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean tenantScoped() {
        return true;
    }

    @Override
    public List<ConnectorInput> inputs() {
        return List.of(ConnectorInput.required("documentIds", "array", "Ids of the documents to remove (the id given at ingest, else the file id or name)"));
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ServiceHandler.ConnectorException("rag-forget needs node configuration", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        try {
            KnowledgeRegistry.name(config);
            Map<String, Object> out = new LinkedHashMap<>();
            if (!(inputs.get("documentIds") instanceof List<?> ids) || ids.isEmpty()) {
                out.put("removed", 0);
                return out;
            }
            if (ids.size() > 200) {
                throw new RagException("rag-forget takes at most 200 documents per call");
            }
            KnowledgeBase kb = registry.get(config, false);
            int removed = 0;
            for (Object id : ids) {
                if (id instanceof String s && kb.remove(s)) {
                    registry.forgetDocument(config, s);
                    removed++;
                }
            }
            out.put("removed", removed);
            out.put("documents", kb.documents());
            return out;
        } catch (RagException e) {
            throw new ServiceHandler.ConnectorException(e.getMessage(), false);
        }
    }
}
