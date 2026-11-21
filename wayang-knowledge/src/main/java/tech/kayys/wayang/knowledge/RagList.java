package tech.kayys.wayang.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.rag.KnowledgeBase;
import tech.kayys.wayang.rag.RagException;
import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code rag-list}: which documents a knowledge base holds. Config: {@code knowledge}. Output: {@code documents} ({@code id} and, for
 * documents with a lifetime, {@code expiresAt}; never their text), {@code count}. Expired documents are cleared first, so the list is what a search can find.
 * A base that does not exist yet is an empty list.
 */
final class RagList implements ServiceHandler.Connector {

    private static final int MAX = 1000;

    private final KnowledgeRegistry registry;

    RagList(KnowledgeRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean tenantScoped() {
        return true;
    }

    @Override
    public List<ConnectorInput> inputs() {
        return List.of();
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ServiceHandler.ConnectorException("rag-list needs node configuration", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        try {
            KnowledgeRegistry.name(config);
            Map<String, Object> out = new LinkedHashMap<>();
            List<Object> docs = new ArrayList<>();
            KnowledgeBase kb;
            try {
                kb = registry.get(config, false);
            } catch (RagException e) {
                out.put("documents", docs);
                out.put("count", 0);
                return out;
            }
            Map<String, Long> expiry = registry.expiries(config);
            for (String id : kb.documentIds()) {
                if (docs.size() >= MAX) {
                    break;
                }
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", id);
                if (expiry.get(id) != null) {
                    d.put("expiresAt", Instant.ofEpochMilli(expiry.get(id)).toString());
                }
                docs.add(d);
            }
            out.put("documents", docs);
            out.put("count", kb.documents());
            return out;
        } catch (RagException e) {
            throw new ServiceHandler.ConnectorException(e.getMessage(), false);
        }
    }
}
