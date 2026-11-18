package tech.kayys.wayang.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A bounded, in-memory knowledge base: documents are chunked on add and searched with {@link RetrievalConfig}. It is <strong>not durable</strong> (a restart empties
 * it) and not distributed; persistence and external vector stores are separate adapters. Safe for concurrent use. Retrieved text comes from documents that may hold
 * instructions aimed at the model: treat it as untrusted data, never as instructions.
 */
public final class KnowledgeBase {

    public static final int DEFAULT_MAX_DOCUMENTS = 1000;
    public static final int DEFAULT_MAX_CHUNKS = 20_000;
    private static final int MAX_QUERY = 2000;
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final int RRF_K = 60;

    private record Entry(Chunk chunk, float[] vec, Map<String, Integer> tf, int len) {
        // identity semantics: entries are map keys while ranking, and hashing their term maps would be needlessly slow
        @Override
        public boolean equals(Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    private final Chunker chunker;
    private final Embedder embedder;
    private final int maxDocuments;
    private final int maxChunks;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, List<Entry>> byDoc = new LinkedHashMap<>();
    private final Map<String, Integer> df = new HashMap<>();
    private long totalLen;
    private int chunkCount;

    public KnowledgeBase(Chunker chunker, Embedder embedder) {
        this(chunker, embedder, DEFAULT_MAX_DOCUMENTS, DEFAULT_MAX_CHUNKS);
    }

    public KnowledgeBase(Chunker chunker, Embedder embedder, int maxDocuments, int maxChunks) {
        if (chunker == null) {
            throw new RagException("a knowledge base needs a chunker");
        }
        if (maxDocuments < 1 || maxChunks < 1) {
            throw new RagException("limits must be positive");
        }
        this.chunker = chunker;
        this.embedder = embedder;
        this.maxDocuments = maxDocuments;
        this.maxChunks = maxChunks;
    }

    public boolean hasEmbedder() {
        return embedder != null;
    }

    public String chunking() {
        return chunker.type();
    }

    /** Adds a document, replacing any earlier one with the same id. Chunking and embedding happen before the index is touched, so a failure leaves it unchanged. */
    public int add(Document doc) {
        List<Chunk> chunks = chunker.chunk(doc);
        List<Entry> entries = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            List<String> toks = Tokens.of(c.text());
            Map<String, Integer> tf = new HashMap<>();
            toks.forEach(t -> tf.merge(t, 1, Integer::sum));
            entries.add(new Entry(c, embedder == null ? null : unit(embed(c.text())), tf, toks.size()));
        }
        lock.writeLock().lock();
        try {
            List<Entry> old = byDoc.get(doc.id());
            int docsAfter = byDoc.size() + (old == null ? 1 : 0);
            int chunksAfter = chunkCount - (old == null ? 0 : old.size()) + entries.size();
            if (docsAfter > maxDocuments) {
                throw new RagException("the knowledge base already holds " + maxDocuments + " documents");
            }
            if (chunksAfter > maxChunks) {
                throw new RagException("adding this document would exceed " + maxChunks + " chunks");
            }
            if (old != null) {
                drop(doc.id());
            }
            if (!entries.isEmpty()) {
                byDoc.put(doc.id(), entries);
                for (Entry e : entries) {
                    e.tf().keySet().forEach(t -> df.merge(t, 1, Integer::sum));
                    totalLen += e.len();
                }
                chunkCount += entries.size();
            }
            return entries.size();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean remove(String docId) {
        lock.writeLock().lock();
        try {
            return drop(docId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean drop(String docId) {
        List<Entry> old = byDoc.remove(docId);
        if (old == null) {
            return false;
        }
        for (Entry e : old) {
            for (String t : e.tf().keySet()) {
                df.computeIfPresent(t, (k, v) -> v <= 1 ? null : v - 1);
            }
            totalLen -= e.len();
        }
        chunkCount -= old.size();
        return true;
    }

    public int documents() {
        lock.readLock().lock();
        try {
            return byDoc.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int chunks() {
        lock.readLock().lock();
        try {
            return chunkCount;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * @param filter exact-match conditions on document metadata (plus the pseudo keys {@code docId} and {@code docName}); null or empty for none
     * @throws RagException when the method needs an embedder that is not configured, or the query is too long
     */
    public List<Hit> search(String query, RetrievalConfig cfg, Map<String, String> filter) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (query.length() > MAX_QUERY) {
            throw new RagException("the query is longer than " + MAX_QUERY + " characters");
        }
        if (cfg.method() != RetrievalConfig.Method.BM25 && embedder == null) {
            throw new RagException("retrieval method '" + cfg.method().name().toLowerCase() + "' needs an embedder, which is not configured; use bm25 or configure one");
        }
        float[] qv = cfg.method() == RetrievalConfig.Method.BM25 ? null : unit(embed(query));
        lock.readLock().lock();
        try {
            List<Entry> all = new ArrayList<>();
            byDoc.values().forEach(l -> l.stream().filter(e -> matches(e.chunk(), filter)).forEach(all::add));
            if (all.isEmpty()) {
                return List.of();
            }
            int pool = cfg.mmrLambda() == null ? cfg.topK() : Math.min(all.size(), cfg.topK() * 4);
            List<Scored> ranked = switch (cfg.method()) {
                case BM25 -> top(bm25(all, query), pool);
                case VECTOR -> top(vector(all, qv), pool);
                case HYBRID -> fuse(top(bm25(all, query), Math.max(pool * 2, 20)), top(vector(all, qv), Math.max(pool * 2, 20)), pool);
            };
            if (cfg.mmrLambda() != null) {
                ranked = mmr(ranked, cfg.mmrLambda(), cfg.topK());
            }
            return hits(ranked, cfg);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---- scoring ----

    private record Scored(Entry entry, double score) {
    }

    private Map<Entry, Double> bm25(List<Entry> all, String query) {
        List<String> terms = Tokens.of(query).stream().distinct().toList();
        double n = chunkCount;
        double avg = chunkCount == 0 ? 1 : Math.max(1.0, (double) totalLen / chunkCount);
        Map<Entry, Double> out = new HashMap<>();
        for (Entry e : all) {
            double s = 0;
            for (String t : terms) {
                Integer f = e.tf().get(t);
                if (f == null) {
                    continue;
                }
                int d = df.getOrDefault(t, 1);
                double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
                s += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * e.len() / avg));
            }
            if (s > 0) {
                out.put(e, s);
            }
        }
        return out;
    }

    private Map<Entry, Double> vector(List<Entry> all, float[] qv) {
        Map<Entry, Double> out = new HashMap<>();
        for (Entry e : all) {
            double s = dot(e.vec(), qv);
            if (s > 0) {
                out.put(e, s);
            }
        }
        return out;
    }

    private static List<Scored> top(Map<Entry, Double> scores, int n) {
        return scores.entrySet().stream()
                .map(x -> new Scored(x.getKey(), x.getValue()))
                .sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparing(s -> s.entry().chunk().id()))
                .limit(n).toList();
    }

    private static List<Scored> fuse(List<Scored> a, List<Scored> b, int n) {
        Map<Entry, Double> rrf = new HashMap<>();
        for (List<Scored> l : List.of(a, b)) {
            for (int i = 0; i < l.size(); i++) {
                rrf.merge(l.get(i).entry(), 1.0 / (RRF_K + i + 1), Double::sum);
            }
        }
        return top(rrf, n);
    }

    private List<Scored> mmr(List<Scored> cand, double lambda, int k) {
        if (cand.isEmpty()) {
            return cand;
        }
        double max = cand.get(0).score() <= 0 ? 1 : cand.get(0).score();
        List<Scored> left = new ArrayList<>(cand);
        List<Scored> chosen = new ArrayList<>();
        while (chosen.size() < k && !left.isEmpty()) {
            Scored best = null;
            double bestV = Double.NEGATIVE_INFINITY;
            for (Scored c : left) {
                double sim = 0;
                for (Scored s : chosen) {
                    sim = Math.max(sim, similarity(c.entry(), s.entry()));
                }
                double v = lambda * (c.score() / max) - (1 - lambda) * sim;
                if (v > bestV) {
                    bestV = v;
                    best = c;
                }
            }
            chosen.add(best);
            left.remove(best);
        }
        return chosen;
    }

    private static double similarity(Entry a, Entry b) {
        if (a.vec() != null && b.vec() != null) {
            return Math.max(0, dot(a.vec(), b.vec()));
        }
        Set<String> x = a.tf().keySet();
        Set<String> y = b.tf().keySet();
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        return (double) inter.size() / (x.size() + y.size() - inter.size());
    }

    private static List<Hit> hits(List<Scored> ranked, RetrievalConfig cfg) {
        List<Hit> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Scored s : ranked) {
            Chunk c = s.entry().chunk();
            String text = cfg.expandParent() && c.context() != null ? c.context() : c.text();
            if (seen.add(c.docId() + "\u0000" + text)) {
                out.add(new Hit(c, s.score(), text, cfg.method().name().toLowerCase()));
            }
            if (out.size() >= cfg.topK()) {
                break;
            }
        }
        return out;
    }

    private static boolean matches(Chunk c, Map<String, String> filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        for (var f : filter.entrySet()) {
            String have = switch (f.getKey()) {
                case "docId" -> c.docId();
                case "docName" -> c.docName();
                default -> c.metadata().get(f.getKey());
            };
            if (!f.getValue().equals(have)) {
                return false;
            }
        }
        return true;
    }

    // ---- vectors ----

    private float[] embed(String text) {
        float[] v = embedder.embed(text);
        if (v == null || v.length != embedder.dimensions()) {
            throw new RagException("the embedder returned a vector of the wrong size");
        }
        return v;
    }

    private static float[] unit(float[] v) {
        double n = 0;
        for (float x : v) {
            n += x * x;
        }
        if (n == 0) {
            return v;
        }
        float inv = (float) (1.0 / Math.sqrt(n));
        float[] o = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            o[i] = v[i] * inv;
        }
        return o;
    }

    private static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
