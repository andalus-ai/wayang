package tech.kayys.wayang.knowledge;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.rag.Document;
import tech.kayys.wayang.rag.Extractors;
import tech.kayys.wayang.rag.KnowledgeBase;
import tech.kayys.wayang.rag.Limits;
import tech.kayys.wayang.rag.RagException;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code rag-ingest}: adds documents to a named knowledge base, replacing any document with the same id.
 *
 * <p>Config: {@code knowledge} (name), optional {@code chunking}, {@code embedder}, {@code maxDocuments}, {@code maxChunks} (see docs/RAG.md).
 * Input: {@code documents} (mandatory, advisory: when missing nothing is ingested and the run goes on), a list of up to 50 objects. A
 * document is {@code fileId} (a file stored by {@code file-put}) or {@code fileName} with {@code text} or {@code contentBase64} (at most 8 MiB
 * decoded), plus optional {@code id} (default: the file id or name) and {@code metadata} (string values, at most 20). There is no fetching
 * from a URL here. Output: {@code ingested}, {@code chunks}, {@code documents},
 * {@code skipped} (name and reason, never content).
 */
final class RagIngest implements ServiceHandler.Connector {

    static final int MAX_DOCS_PER_CALL = 50;
    static final int MAX_DECODED = 8 << 20;
    static final Limits LIMITS = new Limits(MAX_DECODED, 5_000_000, 2000, 32 << 20, 200_000);

    private final KnowledgeRegistry registry;
    private final FileStore files;
    private final Extractors extractors = load();

    /** Built-in formats plus any extractor module on the classpath (for example wayang-rag-pdf), found through ServiceLoader. */
    private static Extractors load() {
        Extractors e = Extractors.defaults();
        for (tech.kayys.wayang.rag.DocumentExtractor d : java.util.ServiceLoader.load(tech.kayys.wayang.rag.DocumentExtractor.class)) {
            e = e.with(d);
        }
        return e;
    }

    RagIngest(KnowledgeRegistry registry, FileStore files) {
        this.registry = registry;
        this.files = files;
    }

    @Override
    public java.util.List<tech.kayys.wayang.runtime.ConnectorInput> inputs() {
        return java.util.List.of(tech.kayys.wayang.runtime.ConnectorInput.required("documents", "array",
                "Documents to add: {fileId} from a file-put step, or {fileName, text | contentBase64}; up to " + MAX_DOCS_PER_CALL));
    }

    @Override
    public boolean tenantScoped() {
        return true;
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ServiceHandler.ConnectorException("rag-ingest needs node configuration", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        try {
            KnowledgeRegistry.name(config); // a wrong configuration is an error even when there is nothing to add
        } catch (RagException e) {
            throw new ServiceHandler.ConnectorException(e.getMessage(), false);
        }
        if (!(inputs.get("documents") instanceof List<?> docs) || docs.isEmpty()) {
            // A missing mandatory input is reported, never fatal: nothing to add, nothing changed.
            return new LinkedHashMap<>(Map.of("ingested", 0, "chunks", 0, "skipped", List.of()));
        }
        KnowledgeBase kb;
        try {
            kb = registry.get(config, true);
        } catch (RagException e) {
            throw new ServiceHandler.ConnectorException(e.getMessage(), false);
        }
        if (docs.size() > MAX_DOCS_PER_CALL) {
            throw new ServiceHandler.ConnectorException("rag-ingest takes at most " + MAX_DOCS_PER_CALL + " documents per call", false);
        }
        String tenant = config.get("wayang.tenant") instanceof String t ? t : "";
        int ingested = 0;
        int chunks = 0;
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Object o : docs) {
            String label = "document";
            try {
                if (!(o instanceof Map<?, ?> d)) {
                    throw new RagException("a document must be an object");
                }
                String fileName = d.get("fileName") instanceof String s ? s : d.get("name") instanceof String s2 ? s2 : null;
                byte[] stored = null;
                if (d.get("fileId") instanceof String fid) {
                    if (files == null) {
                        throw new RagException("file storage is not configured");
                    }
                    FileStore.Stored f = files.get(tenant, fid);
                    if (f == null) {
                        throw new RagException("no such file");
                    }
                    stored = f.bytes();
                    fileName = fileName == null ? f.name() : fileName;
                }
                label = fileName == null ? "document" : fileName.length() > 120 ? fileName.substring(0, 120) : fileName;
                String text = stored != null ? extractors.extract(fileName, stored, LIMITS) : text(d, fileName);
                String id = d.get("id") instanceof String s && !s.isBlank() ? s : d.get("fileId") instanceof String fid2 ? fid2 : fileName;
                if (text.isBlank()) {
                    throw new RagException("no readable text");
                }
                chunks += kb.add(new Document(id, fileName, text, metadata(d.get("metadata"))));
                ingested++;
            } catch (RagException e) {
                skipped.add(skip(label, e.getMessage()));
            }
        }
        if (ingested == 0) {
            throw new ServiceHandler.ConnectorException("nothing was ingested: " + skipped, false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ingested", ingested);
        out.put("chunks", chunks);
        out.put("documents", kb.documents());
        out.put("skipped", skipped);
        return out;
    }

    private String text(Map<?, ?> d, String fileName) {
        if (d.get("text") instanceof String t) {
            if (t.length() > LIMITS.maxChars()) {
                throw new RagException("text is longer than " + LIMITS.maxChars() + " characters");
            }
            return t;
        }
        if (d.get("contentBase64") instanceof String b) {
            if (fileName == null || fileName.isBlank()) {
                throw new RagException("a file needs a 'fileName' so its format can be recognised");
            }
            if (b.length() > (long) MAX_DECODED * 4 / 3 + 16) {
                throw new RagException("file is larger than " + (MAX_DECODED >> 20) + " MiB");
            }
            byte[] bytes;
            try {
                bytes = Base64.getMimeDecoder().decode(b);
            } catch (IllegalArgumentException e) {
                throw new RagException("contentBase64 is not valid base64");
            }
            return extractors.extract(fileName, bytes, LIMITS);
        }
        throw new RagException("a document needs 'fileId', 'text' or 'contentBase64'");
    }

    private static Map<String, String> metadata(Object m) {
        if (m == null) {
            return Map.of();
        }
        if (!(m instanceof Map<?, ?> mm) || mm.size() > 20) {
            throw new RagException("metadata must be an object with at most 20 entries");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : mm.entrySet()) {
            if (!(e.getKey() instanceof String k) || !(e.getValue() instanceof String v) || k.length() > 64 || v.length() > 500) {
                throw new RagException("metadata keys and values must be short text");
            }
            out.put(k, v);
        }
        return out;
    }

    private static Map<String, Object> skip(String name, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("reason", reason == null ? "rejected" : reason);
        return m;
    }
}
