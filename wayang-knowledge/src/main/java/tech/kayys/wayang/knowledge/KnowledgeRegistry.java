package tech.kayys.wayang.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.connectors.MediaHttp;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.rag.Embedder;
import tech.kayys.wayang.rag.HashingEmbedder;
import tech.kayys.wayang.rag.KnowledgeBase;
import tech.kayys.wayang.rag.KnowledgeConfig;
import tech.kayys.wayang.rag.RagException;
import tech.kayys.wayang.rag.RetrievalConfig;

/**
 * Named knowledge bases of this process. The name comes from the node configuration (never from model-controlled input), so an agent can
 * only reach the knowledge its author wired to it. Bounded (at most {@value #MAX_BASES} bases), in memory, not durable, and shared by
 * every run in the process: there is no per-tenant separation, so use distinct names per tenant or purpose.
 */
final class KnowledgeRegistry {

    static final int MAX_BASES = 20;
    static final int MAX_TOTAL = 200;
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    private record Entry(KnowledgeBase kb, KnowledgeConfig cfg, String signature, Embedder embedder) {
    }

    private final Map<String, Entry> bases = new LinkedHashMap<>();
    private final MediaHttp http;

    KnowledgeRegistry(UrlGuard guard) {
        this.http = new MediaHttp(guard);
    }

    /** The base for {@code config}, created on first use. A later call with different chunking/limits settings is an error, not a silent reconfigure. */
    synchronized KnowledgeBase get(Map<String, Object> config, boolean create) {
        String key = key(config);
        Map<String, Object> def = definition(config);
        String sig = def + "|" + embedderSignature(config.get("embedder"));
        Entry e = bases.get(key);
        if (e != null) {
            if (!e.signature.equals(sig)) {
                throw new RagException("knowledge '" + name(config) + "' already exists with different settings; use another name");
            }
            return e.kb;
        }
        if (!create) {
            throw new RagException("knowledge '" + name(config) + "' is empty: ingest documents into it first");
        }
        String prefix = tenant(config) + "|";
        if (bases.keySet().stream().filter(k -> k.startsWith(prefix)).count() >= MAX_BASES || bases.size() >= MAX_TOTAL) {
            throw new RagException("too many knowledge bases (max " + MAX_BASES + ")");
        }
        Embedder emb = embedder(config.get("embedder"));
        if (config.get("retrieval") instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) config.get("retrieval");
            RetrievalConfig.fromMap(r, emb != null); // fail at ingest time, not on the first question
        }
        KnowledgeConfig kc = KnowledgeConfig.fromMap(def);
        KnowledgeBase kb = kc.build(emb);
        bases.put(key, new Entry(kb, kc, sig, emb));
        return kb;
    }

    synchronized RetrievalConfig retrieval(Map<String, Object> config, KnowledgeBase kb) {
        Entry e = bases.get(key(config));
        Object r = config.get("retrieval");
        if (r != null && !(r instanceof Map)) {
            throw new RagException("retrieval must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = r == null ? e.cfg.retrieval() : (Map<String, Object>) r;
        return RetrievalConfig.fromMap(m, kb.hasEmbedder());
    }

    /** The run's tenant as set by the engine ("" when the caller has none, as in a single-tenant release). */
    private static String tenant(Map<String, Object> config) {
        return config.get("wayang.tenant") instanceof String t ? t : "";
    }

    private static String key(Map<String, Object> config) {
        return tenant(config) + "|" + name(config);
    }

    static String name(Map<String, Object> config) {
        Object n = config.get("knowledge");
        if (!(n instanceof String s) || !NAME.matcher(s).matches()) {
            throw new RagException("'knowledge' must name the knowledge base (letters, digits, - and _, starting with a letter)");
        }
        return s;
    }

    /** The settings that define a base's shape: chunking, caps and default retrieval. Ingest and search must agree on them. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> definition(Map<String, Object> config) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (String k : new String[] {"chunking", "maxDocuments", "maxChunks"}) {
            if (config.get(k) != null) {
                d.put(k, config.get(k));
            }
        }
        return d;
    }

    /** The embedder settings without the API key, so a credential is never part of anything kept or compared as text. */
    private static String embedderSignature(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<Object, Object> c = new LinkedHashMap<>(m);
            c.remove("apiKey");
            return c.toString();
        }
        return String.valueOf(o);
    }

    private Embedder embedder(Object o) {
        if (o == null || "none".equals(o)) {
            return null;
        }
        if (o instanceof Map<?, ?> m) {
            Object type = m.get("type");
            List<String> allowed = "openai".equals(type) ? List.of("type", "model", "dimensions", "baseUrl", "apiKey") : List.of("type", "dimensions");
            for (Object k : m.keySet()) {
                if (!allowed.contains(k)) {
                    throw new RagException("embedder has an unknown setting '" + k + "'");
                }
            }
            Object d = m.get("dimensions");
            if (d != null && !(d instanceof Number)) {
                throw new RagException("embedder dimensions must be a number");
            }
            if ("hashing".equals(type)) {
                return new HashingEmbedder(d instanceof Number n ? n.intValue() : 512);
            }
            if ("openai".equals(type)) {
                if (!(m.get("model") instanceof String model) || model.isBlank() || !(d instanceof Number n)) {
                    throw new RagException("an openai embedder needs 'model' and 'dimensions' (the size of its vectors)");
                }
                String base = m.get("baseUrl") instanceof String b && !b.isBlank() ? b : "https://api.openai.com/v1";
                return new OpenAiEmbedder(http, base, model, n.intValue(), m.get("apiKey") instanceof String k ? k : null);
            }
        }
        throw new RagException("embedder must be 'none', {type: hashing, dimensions} or {type: openai, model, dimensions, baseUrl, apiKey}");
    }
}
