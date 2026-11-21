package tech.kayys.wayang.knowledge;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A disk cache of embedding vectors, keyed by the SHA-256 of the text, for one embedder. It exists so a restart (or the re-ingest of an edited
 * document) does not pay again for text that was already embedded. It is only a cache: a file that is missing, damaged, truncated, for another
 * embedder or of the wrong size is ignored and rebuilt, and an I/O failure never fails an ingest.
 *
 * <p>File: {@code "WVC1"}, the SHA-256 of the embedder {@code scope} (its settings without the API key), the vector size, then records of a
 * 32-byte key and the vector as big-endian floats. Memory holds only an index (key to offset); vectors are read when needed. At most
 * {@code maxEntries} are kept (the oldest are dropped when the file is next opened), and no new entry is appended once the file would pass
 * twice that. The file holds numbers derived from document text and is as sensitive as the stored documents next to it; it never holds a key.
 */
final class VectorCache {

    private static final byte[] MAGIC = "WVC1".getBytes(StandardCharsets.US_ASCII);
    private static final int KEY = 32;
    private static final int HEADER = 4 + 32 + 4;

    private final Path file;
    private final byte[] scope;
    private final int dims;
    private final int maxEntries;
    private final int record;
    private final Map<String, Long> index = new LinkedHashMap<>();
    private boolean headerOk;

    VectorCache(Path file, String scope, int dims, int maxEntries) {
        if (dims < 1 || maxEntries < 1) {
            throw new IllegalArgumentException("dims and maxEntries must be positive");
        }
        this.file = file;
        this.scope = sha(scope.getBytes(StandardCharsets.UTF_8));
        this.dims = dims;
        this.maxEntries = maxEntries;
        this.record = KEY + 4 * dims;
        open();
    }

    static byte[] sha(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String key(String text) {
        return HexFormat.of().formatHex(sha(text.getBytes(StandardCharsets.UTF_8)));
    }

    synchronized int size() {
        return index.size();
    }

    /** The cached vector for this text, or null. */
    synchronized float[] get(String text) {
        Long off = index.get(key(text));
        if (off == null) {
            return null;
        }
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            byte[] b = new byte[4 * dims];
            f.seek(off + KEY);
            f.readFully(b);
            float[] v = new float[dims];
            ByteBuffer.wrap(b).asFloatBuffer().get(v);
            return v;
        } catch (IOException e) {
            return null; // a cache that cannot be read is a miss
        }
    }

    /** Stores vectors that were just computed. Failures and a full cache are silent: the vectors are simply not kept. */
    synchronized void put(Map<String, float[]> byText) {
        try {
            if (!headerOk) {
                rewrite(new LinkedHashMap<>());
            }
            long limit = HEADER + 2L * maxEntries * record;
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                long size = ch.size();
                for (Map.Entry<String, float[]> e : byText.entrySet()) {
                    String k = key(e.getKey());
                    float[] v = e.getValue();
                    if (index.containsKey(k) || v == null || v.length != dims || size + record > limit) {
                        continue;
                    }
                    ByteBuffer buf = ByteBuffer.allocate(record);
                    buf.put(HexFormat.of().parseHex(k));
                    buf.asFloatBuffer().put(v);
                    buf.position(0).limit(record);
                    ch.write(buf);
                    index.put(k, size);
                    size += record;
                }
            }
        } catch (IOException | RuntimeException e) {
            // a cache write that fails costs only a later re-embed
        }
    }

    /** Reads the index of an existing file; anything unexpected means an empty cache that is rewritten on the first put. */
    private void open() {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) < HEADER) {
                return;
            }
            try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
                byte[] h = new byte[HEADER];
                f.readFully(h);
                ByteBuffer hb = ByteBuffer.wrap(h);
                byte[] magic = new byte[4];
                hb.get(magic);
                byte[] sc = new byte[32];
                hb.get(sc);
                if (!java.util.Arrays.equals(magic, MAGIC) || !java.util.Arrays.equals(sc, scope) || hb.getInt() != dims) {
                    return;
                }
                long whole = (f.length() - HEADER) / record; // a partly written last record is dropped
                Map<String, Long> all = new LinkedHashMap<>();
                byte[] k = new byte[KEY];
                for (long i = 0; i < whole; i++) {
                    long off = HEADER + i * record;
                    f.seek(off);
                    f.readFully(k);
                    all.put(HexFormat.of().formatHex(k), off);
                }
                headerOk = true;
                if (all.size() > maxEntries || f.length() != HEADER + whole * record) {
                    compact(all);
                } else {
                    index.putAll(all);
                }
            }
        } catch (IOException | RuntimeException e) {
            index.clear();
            headerOk = false;
        }
    }

    /** Keeps the newest {@code maxEntries}, drops a torn tail. */
    private void compact(Map<String, Long> all) throws IOException {
        int skip = Math.max(0, all.size() - maxEntries);
        Map<String, float[]> keep = new LinkedHashMap<>();
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            int i = 0;
            for (Map.Entry<String, Long> e : all.entrySet()) {
                if (i++ < skip) {
                    continue;
                }
                byte[] b = new byte[4 * dims];
                f.seek(e.getValue() + KEY);
                f.readFully(b);
                float[] v = new float[dims];
                ByteBuffer.wrap(b).asFloatBuffer().get(v);
                keep.put(e.getKey(), v);
            }
        }
        rewrite(keep);
    }

    private void rewrite(Map<String, float[]> entries) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), ".vectors", ".tmp");
        Map<String, Long> fresh = new HashMap<>();
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ByteBuffer h = ByteBuffer.allocate(HEADER);
            h.put(MAGIC).put(scope).putInt(dims).flip();
            ch.write(h);
            long off = HEADER;
            for (Map.Entry<String, float[]> e : entries.entrySet()) {
                ByteBuffer buf = ByteBuffer.allocate(record);
                buf.put(HexFormat.of().parseHex(e.getKey()));
                buf.asFloatBuffer().put(e.getValue());
                buf.position(0).limit(record);
                ch.write(buf);
                fresh.put(e.getKey(), off);
                off += record;
            }
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        index.clear();
        entries.keySet().forEach(k -> index.put(k, fresh.get(k)));
        headerOk = true;
    }
}
