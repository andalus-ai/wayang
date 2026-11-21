package tech.kayys.wayang.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.connectors.MediaHttp;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.rag.Document;
import tech.kayys.wayang.rag.Embedder;
import tech.kayys.wayang.rag.HashingEmbedder;
import tech.kayys.wayang.rag.KnowledgeBase;
import tech.kayys.wayang.rag.KnowledgeConfig;
import tech.kayys.wayang.rag.RagException;
import tech.kayys.wayang.rag.RetrievalConfig;
import tech.kayys.wayang.runtime.json.Json;

/**
 * Named knowledge bases of this process. The name comes from the node configuration (never from model-controlled input), so an agent can
 * only reach the knowledge its author wired to it. Bounded (at most {@value #MAX_BASES} bases), in memory, not durable, and shared by
 * every run in the process. With a storage directory configured the sources are also kept on disk and the base is rebuilt after a restart.
 */
final class KnowledgeRegistry {

    static final int MAX_BASES = 20;
    static final int MAX_TOTAL = 200;
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    private record Entry(KnowledgeBase kb, KnowledgeConfig cfg, String signature, Embedder embedder, Map<String, Object> definition,
            Map<String, Object> embedderSettings, boolean persist, Map<String, Long> expiry) {
    }

    private final Map<String, Entry> bases = new LinkedHashMap<>();
    /** Documents that expired while the process was down, dropped when their base loaded: reported by the next purge. */
    private final Map<String, Integer> expiredOnLoad = new LinkedHashMap<>();
    private final MediaHttp http;
    private final KnowledgePersistence persistence;
    private final java.time.Clock clock;

    KnowledgeRegistry(UrlGuard guard) {
        this(guard, null, java.time.Clock.systemUTC());
    }

    KnowledgeRegistry(UrlGuard guard, KnowledgePersistence persistence) {
        this(guard, persistence, java.time.Clock.systemUTC());
    }

    /** @param persistence where knowledge survives restarts, or null for memory only; @param clock decides when a document has expired */
    KnowledgeRegistry(UrlGuard guard, KnowledgePersistence persistence, java.time.Clock clock) {
        this.http = new MediaHttp(guard);
        this.persistence = persistence;
        this.clock = clock;
    }

    long now() {
        return clock.millis();
    }

    /** The base for {@code config}, created on first use. A later call that states different chunking/limits/embedder settings is an error, not a silent reconfigure; a search that states none simply uses the base as ingested. */
    synchronized KnowledgeBase get(Map<String, Object> config, boolean create) {
        String key = key(config);
        Map<String, Object> def = definition(config);
        String sig = signature(def, config.get("embedder"));
        Entry e = bases.get(key);
        if (e == null && persistence != null && persistence.exists(tenant(config), name(config))) {
            e = loadPersisted(config, key);
        }
        if (e != null) {
            // A search attaches to the base that ingest built; it only has to agree on the settings it actually states.
            boolean statesNothing = !create && def.isEmpty() && config.get("embedder") == null;
            if (!statesNothing && !e.signature.equals(sig)) {
                if (create && e.kb.documents() == 0 && !(persistence != null && persistence.exists(tenant(config), name(config)))) {
                    bases.remove(key); // nothing was ever added (for example the first ingest failed): settings can still be corrected
                    e = null;
                } else {
                    throw new RagException("knowledge '" + name(config) + "' already exists with different settings; use another name");
                }
            }
            if (e != null) {
                purge(e, config);
                return e.kb;
            }
        }
        if (!create) {
            throw new RagException("knowledge '" + name(config) + "' is empty: ingest documents into it first");
        }
        String prefix = tenant(config) + "|";
        if (bases.keySet().stream().filter(k -> k.startsWith(prefix)).count() >= MAX_BASES || bases.size() >= MAX_TOTAL) {
            throw new RagException("too many knowledge bases (max " + MAX_BASES + ")");
        }
        Embedder emb = cached(tenant(config), name(config), embedder(config.get("embedder")), config.get("embedder"), def, persistence != null && persist(config));
        if (config.get("retrieval") instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) config.get("retrieval");
            RetrievalConfig.fromMap(r, emb != null); // fail at ingest time, not on the first question
        }
        KnowledgeConfig kc = KnowledgeConfig.fromMap(def);
        KnowledgeBase kb = kc.build(emb);
        bases.put(key, new Entry(kb, kc, sig, emb, def, embedderWithoutKey(config.get("embedder")), persistence != null && persist(config), new LinkedHashMap<>()));
        return kb;
    }

    /** Stores a document of the base for restarts (when persistence is on and the base was not created with {@code persist: false}). Returns false if not stored. */
    synchronized boolean persistDocument(Map<String, Object> config, Document doc, long expiresAt) {
        Entry e = bases.get(key(config));
        if (persistence == null || e == null || !e.persist) {
            return false;
        }
        if (!persistence.exists(tenant(config), name(config))) {
            // the settings are written with the first document, so a base whose first ingest failed leaves nothing behind
            persistence.saveDefinition(tenant(config), name(config), e.definition, e.embedderSettings);
        }
        persistence.saveDocument(tenant(config), name(config), doc, expiresAt);
        return true;
    }

    /** Records when a document expires (0 = never); a re-ingested document replaces the earlier expiry, or clears it. */
    synchronized void setExpiry(Map<String, Object> config, String docId, long expiresAt) {
        Entry e = bases.get(key(config));
        if (e == null) {
            return;
        }
        if (expiresAt > 0) {
            e.expiry.put(docId, expiresAt);
        } else {
            e.expiry.remove(docId);
        }
    }

    /**
     * Removes the base's expired documents, in memory and on disk; returns how many. Does nothing (0) when the base does not exist. Every use of a
     * base does this first, so an expired document is never searchable; calling it on a schedule also clears bases nobody searches.
     */
    synchronized int purgeExpired(Map<String, Object> config) {
        String key = key(config);
        Entry e = bases.get(key);
        if (e == null && persistence != null && persistence.exists(tenant(config), name(config))) {
            e = loadPersisted(config, key);
        }
        if (e == null) {
            return 0;
        }
        Integer early = expiredOnLoad.remove(key);
        return purge(e, config) + (early == null ? 0 : early);
    }

    private int purge(Entry e, Map<String, Object> config) {
        long now = clock.millis();
        int n = 0;
        for (java.util.Iterator<Map.Entry<String, Long>> it = e.expiry.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Long> x = it.next();
            if (now >= x.getValue()) {
                e.kb.remove(x.getKey());
                if (persistence != null && persistence.exists(tenant(config), name(config))) {
                    persistence.deleteDocument(tenant(config), name(config), x.getKey());
                }
                it.remove();
                n++;
            }
        }
        return n;
    }

    /** Expiry (epoch milliseconds) per document id of a base that is in memory; documents without a lifetime are absent. */
    synchronized Map<String, Long> expiries(Map<String, Object> config) {
        Entry e = bases.get(key(config));
        return e == null ? Map.of() : new LinkedHashMap<>(e.expiry);
    }

    synchronized void forgetDocument(Map<String, Object> config, String docId) {
        if (persistence != null && persistence.exists(tenant(config), name(config))) {
            persistence.deleteDocument(tenant(config), name(config), docId);
        }
    }

    private static boolean persist(Map<String, Object> config) {
        Object p = config.get("persist");
        if (p != null && !(p instanceof Boolean)) {
            throw new RagException("persist must be true or false");
        }
        return !Boolean.FALSE.equals(p);
    }

    /** Rebuilds a stored base: its saved settings and embedder, then every saved document is chunked and embedded again. */
    @SuppressWarnings("unchecked")
    private Entry loadPersisted(Map<String, Object> config, String key) {
        KnowledgePersistence.Loaded l = persistence.load(tenant(config), name(config), clock.millis());
        Map<String, Object> emb = l.embedder() == null ? null : new LinkedHashMap<>(l.embedder());
        // The key is never stored; whichever node uses the base first after a restart supplies it from its own configuration.
        if (emb != null && config.get("embedder") instanceof Map<?, ?> given && given.get("apiKey") instanceof String k) {
            emb.put("apiKey", k);
        }
        Embedder embedder = cached(tenant(config), name(config), embedder(emb), emb, l.definition(), true);
        KnowledgeConfig kc = KnowledgeConfig.fromMap(l.definition());
        KnowledgeBase kb = kc.build(embedder);
        for (Document d : l.documents()) {
            kb.add(d);
        }
        Entry e = new Entry(kb, kc, signature(l.definition(), emb), embedder, l.definition(), l.embedder(), true, new LinkedHashMap<>(l.expiry()));
        bases.put(key, e);
        if (l.expired() > 0) {
            expiredOnLoad.merge(key, l.expired(), Integer::sum);
        }
        return e;
    }

    /**
     * A model-backed embedder of a persisted base gets a disk cache of its vectors, scoped to its settings without the key (so a changed model,
     * size or endpoint never reuses old vectors). Free local embedders are not cached; neither are bases that opted out of persistence.
     */
    private Embedder cached(String tenant, String name, Embedder e, Object embedderSettings, Map<String, Object> def, boolean persisted) {
        if (!persisted || !(e instanceof OpenAiEmbedder)) {
            return e;
        }
        int max = def.get("maxChunks") instanceof Number n && n.intValue() > 0 ? n.intValue() : KnowledgeBase.DEFAULT_MAX_CHUNKS;
        String scope = Json.write(canon(embedderWithoutKey(embedderSettings)));
        return new CachingEmbedder(e, new VectorCache(persistence.vectorFile(tenant, name), scope, e.dimensions(), max));
    }

    /** Settings in a fixed order, so a stored base and a live configuration compare equal whatever their map order. */
    private static String signature(Map<String, Object> def, Object embedder) {
        return Json.write(canon(def)) + "|" + Json.write(canon(embedderWithoutKey(embedder)));
    }

    private static Object canon(Object o) {
        if (o instanceof Map<?, ?> m) {
            java.util.TreeMap<String, Object> t = new java.util.TreeMap<>();
            m.forEach((k, v) -> t.put(String.valueOf(k), canon(v)));
            return t;
        }
        if (o instanceof List<?> l) {
            return l.stream().map(KnowledgeRegistry::canon).toList();
        }
        return o instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) ? (Object) n.longValue() : o;
    }

    /** The embedder settings without the API key. */
    private static Map<String, Object> embedderWithoutKey(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> c = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                if (!"apiKey".equals(k)) {
                    c.put(String.valueOf(k), v);
                }
            });
            return c;
        }
        return o == null || "none".equals(o) ? null : Map.of("type", String.valueOf(o));
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
