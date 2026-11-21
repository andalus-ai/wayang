package tech.kayys.wayang.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.rag.Document;
import tech.kayys.wayang.rag.RagException;
import tech.kayys.wayang.runtime.json.Json;

/**
 * Keeps a knowledge base's source documents and settings on disk so it survives a restart. Only the sources are stored; chunks are rebuilt when the
 * base is first used again, and a model-backed embedder's vectors come back from a {@link VectorCache} (so a restart does not pay for them again). Secrets are never written: an embedder's
 * {@code apiKey} is dropped, and the first node that uses the base after a restart supplies it from its own configuration.
 *
 * <p>Layout: {@code <root>/<tenant hash>/<name>/definition.json} and {@code docs/<hash of document id>.json}. Writes are atomic. Bounded to
 * {@value #DEFAULT_MAX_BYTES} bytes of documents per base.
 */
final class KnowledgePersistence {

    static final long DEFAULT_MAX_BYTES = 200L << 20;

    /** {@code expiry} maps a document id to its expiry (epoch milliseconds) for the documents that have one; expired ones are already deleted. */
    record Loaded(Map<String, Object> definition, Map<String, Object> embedder, List<Document> documents, Map<String, Long> expiry, int expired) {
    }

    private final Path root;
    private final long maxBytes;

    KnowledgePersistence(Path root) {
        this(root, DEFAULT_MAX_BYTES);
    }

    KnowledgePersistence(Path root, long maxBytes) {
        this.root = root;
        this.maxBytes = maxBytes;
    }

    private Path dir(String tenant, String name) {
        return root.resolve(FileStore.tenantDirName(tenant)).resolve(name);
    }

    /** Where the vector cache of a base lives (the file may not exist yet). */
    Path vectorFile(String tenant, String name) {
        return dir(tenant, name).resolve("vectors.bin");
    }

    boolean exists(String tenant, String name) {
        return Files.isRegularFile(dir(tenant, name).resolve("definition.json"));
    }

    /** Written once, when the base is first created. */
    void saveDefinition(String tenant, String name, Map<String, Object> definition, Map<String, Object> embedderWithoutKey) {
        Path d = dir(tenant, name);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("definition", definition);
        m.put("embedder", embedderWithoutKey);
        try {
            Files.createDirectories(d.resolve("docs"));
            atomic(d.resolve("definition.json"), Json.write(m));
        } catch (IOException e) {
            throw new RagException("the knowledge settings could not be saved");
        }
    }

    /** @param expiresAt epoch milliseconds, 0 when the document never expires */
    void saveDocument(String tenant, String name, Document doc, long expiresAt) {
        Path docs = dir(tenant, name).resolve("docs");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", doc.id());
        m.put("name", doc.name());
        m.put("text", doc.text());
        m.put("metadata", doc.metadata());
        if (expiresAt > 0) {
            m.put("expiresAt", expiresAt);
        }
        byte[] bytes = Json.write(m).getBytes(StandardCharsets.UTF_8);
        try {
            Files.createDirectories(docs);
            Path target = docs.resolve(hash(doc.id()) + ".json");
            long others = size(docs) - (Files.isRegularFile(target) ? Files.size(target) : 0);
            if (others + bytes.length > maxBytes) {
                throw new RagException("the stored documents of this knowledge base would exceed " + (maxBytes >> 20) + " MiB");
            }
            atomic(target, bytes);
        } catch (IOException e) {
            throw new RagException("the document could not be saved");
        }
    }

    void deleteDocument(String tenant, String name, String docId) {
        try {
            Files.deleteIfExists(dir(tenant, name).resolve("docs").resolve(hash(docId) + ".json"));
        } catch (IOException e) {
            throw new RagException("the document could not be removed from storage");
        }
    }

    /** The stored base, or null when there is none. A damaged file is an error, never silently skipped. */
    @SuppressWarnings("unchecked")
    Loaded load(String tenant, String name, long now) {
        Path d = dir(tenant, name);
        if (!exists(tenant, name)) {
            return null;
        }
        try {
            Map<String, Object> def = (Map<String, Object>) Json.read(Files.readString(d.resolve("definition.json"), StandardCharsets.UTF_8));
            List<Document> docs = new ArrayList<>();
            Map<String, Long> expiry = new LinkedHashMap<>();
            int gone = 0;
            Path dd = d.resolve("docs");
            if (Files.isDirectory(dd)) {
                List<Path> files = new ArrayList<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dd, "*.json")) {
                    ds.forEach(files::add);
                }
                files.sort(java.util.Comparator.comparing(Path::toString));
                for (Path f : files) {
                    Map<String, Object> m = (Map<String, Object>) Json.read(Files.readString(f, StandardCharsets.UTF_8));
                    long exp = m.get("expiresAt") instanceof Number n ? n.longValue() : 0;
                    if (exp > 0 && now >= exp) {
                        Files.deleteIfExists(f); // expired while the process was down: gone, on disk too
                        gone++;
                        continue;
                    }
                    Map<String, String> md = new LinkedHashMap<>();
                    ((Map<String, Object>) m.get("metadata")).forEach((k, v) -> md.put(k, String.valueOf(v)));
                    docs.add(new Document((String) m.get("id"), (String) m.get("name"), (String) m.get("text"), md));
                    if (exp > 0) {
                        expiry.put((String) m.get("id"), exp);
                    }
                }
            }
            return new Loaded((Map<String, Object>) def.get("definition"), (Map<String, Object>) def.get("embedder"), docs, expiry, gone);
        } catch (IOException | RuntimeException e) {
            throw new RagException("the stored knowledge '" + name + "' is damaged and could not be loaded");
        }
    }

    private static long size(Path docs) throws IOException {
        long n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(docs, "*.json")) {
            for (Path p : ds) {
                n += Files.size(p);
            }
        }
        return n;
    }

    private static void atomic(Path target, String content) throws IOException {
        atomic(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void atomic(Path target, byte[] content) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), ".write", ".tmp");
        Files.write(tmp, content);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
