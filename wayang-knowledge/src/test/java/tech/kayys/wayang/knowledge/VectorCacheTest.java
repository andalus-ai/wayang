package tech.kayys.wayang.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tech.kayys.wayang.rag.Embedder;

class VectorCacheTest {

    private static float[] v(float a) {
        return new float[] {a, a + 1, a + 2};
    }

    private static Path file() throws Exception {
        return Files.createTempDirectory("wayang-vc").resolve("k").resolve("vectors.bin");
    }

    @Test
    void storesAndReturnsVectorsAcrossInstances() throws Exception {
        Path f = file();
        VectorCache a = new VectorCache(f, "scope-1", 3, 100);
        assertNull(a.get("hello"));
        Map<String, float[]> m = new LinkedHashMap<>();
        m.put("hello", v(1));
        m.put("world", v(5));
        a.put(m);
        assertEquals(2, a.size());
        VectorCache b = new VectorCache(f, "scope-1", 3, 100);
        assertEquals(2, b.size());
        assertEquals(6f, b.get("world")[1]);
        assertNull(b.get("other"));
        b.put(Map.of("hello", v(99))); // an existing key is not stored twice
        assertEquals(2, b.size());
        assertEquals(1f, b.get("hello")[0]);
    }

    @Test
    void anotherEmbedderOrSizeNeverReusesOldVectors() throws Exception {
        Path f = file();
        new VectorCache(f, "model-a", 3, 100).put(Map.of("x", v(1)));
        assertNull(new VectorCache(f, "model-b", 3, 100).get("x"), "different scope (model, size or endpoint)");
        new VectorCache(f, "model-a", 3, 100).put(Map.of("x", v(1)));
        assertNull(new VectorCache(f, "model-a", 4, 100).get("x"), "different vector size");
        // the cache for the other scope is rewritten on first use, not appended to a foreign file
        VectorCache c = new VectorCache(f, "model-b", 3, 100);
        c.put(Map.of("y", v(2)));
        assertEquals(1, new VectorCache(f, "model-b", 3, 100).size());
    }

    @Test
    void aDamagedOrTornFileIsJustACacheMiss() throws Exception {
        Path f = file();
        VectorCache a = new VectorCache(f, "s", 3, 100);
        a.put(Map.of("one", v(1), "two", v(2)));
        byte[] all = Files.readAllBytes(f);
        Files.write(f, java.util.Arrays.copyOf(all, all.length - 5)); // a crash in the middle of the last record
        VectorCache torn = new VectorCache(f, "s", 3, 100);
        assertEquals(1, torn.size(), "the complete record survives, the torn one is dropped");
        torn.put(Map.of("three", v(3)));
        assertEquals(2, new VectorCache(f, "s", 3, 100).size());
        Files.write(f, "garbage that is not a cache".getBytes());
        VectorCache bad = new VectorCache(f, "s", 3, 100);
        assertEquals(0, bad.size());
        bad.put(Map.of("again", v(4)));
        assertNotNull(new VectorCache(f, "s", 3, 100).get("again"), "it is rebuilt");
        Files.write(f, new byte[0]);
        assertEquals(0, new VectorCache(f, "s", 3, 100).size());
    }

    @Test
    void theNewestEntriesAreKeptWhenTheFileIsOpenedOverTheLimit() throws Exception {
        Path f = file();
        VectorCache a = new VectorCache(f, "s", 3, 100);
        Map<String, float[]> m = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            m.put("t" + i, v(i));
        }
        a.put(m);
        VectorCache small = new VectorCache(f, "s", 3, 4);
        assertEquals(4, small.size());
        assertNull(small.get("t0"));
        assertNotNull(small.get("t9"));
        assertEquals(9f, small.get("t9")[0]);
    }

    @Test
    void noNewEntriesAreAppendedOncetheFileWouldPassTwiceTheLimit() throws Exception {
        Path f = file();
        VectorCache a = new VectorCache(f, "s", 3, 2);
        Map<String, float[]> m = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            m.put("t" + i, v(i));
        }
        a.put(m);
        assertEquals(4, a.size(), "at most twice the limit between restarts");
    }

    @Test
    void theCachingEmbedderAsksOnlyForWhatItHasNotSeenAndNeverStoresQuestions() throws Exception {
        List<List<String>> calls = new ArrayList<>();
        Embedder real = new Embedder() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public int dimensions() {
                return 3;
            }

            @Override
            public float[] embed(String text) {
                calls.add(List.of(text));
                return v(text.length());
            }

            @Override
            public List<float[]> embedAll(List<String> texts) {
                calls.add(List.copyOf(texts));
                return texts.stream().map(t -> v(t.length())).toList();
            }
        };
        VectorCache cache = new VectorCache(file(), "s", 3, 100);
        CachingEmbedder e = new CachingEmbedder(real, cache);
        List<float[]> first = e.embedAll(List.of("aa", "bbb", "aa"));
        assertEquals(1, calls.size());
        assertEquals(List.of("aa", "bbb"), calls.get(0), "duplicates inside a batch are sent once");
        assertEquals(2f, first.get(0)[0]);
        assertEquals(3f, first.get(1)[0]);
        assertEquals(2f, first.get(2)[0]);
        e.embedAll(List.of("bbb", "cccc"));
        assertEquals(List.of("cccc"), calls.get(1), "only the new text goes out");
        e.embedAll(List.of("aa", "bbb", "cccc"));
        assertEquals(2, calls.size(), "all cached: no call at all");
        e.embed("a question");
        e.embed("a question");
        assertEquals(4, calls.size(), "a single text is a question: asked every time, never stored");
        assertEquals(3, cache.size());
        assertTrue(e.embed("aa")[0] == 2f, "but a question equal to a stored text can be answered from the cache");
        assertEquals(4, calls.size());
    }
}
