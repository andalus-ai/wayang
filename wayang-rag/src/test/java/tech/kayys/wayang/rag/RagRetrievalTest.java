package tech.kayys.wayang.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RagRetrievalTest {

    private static final Embedder EMB = new HashingEmbedder(512);

    private static KnowledgeBase kb(Embedder e) {
        KnowledgeBase k = new KnowledgeBase(Chunkers.paragraph(400), e);
        k.add(new Document("law", "contract.md", "Clause 14.2 governs termination for convenience with 30 days notice.\n\nPayment terms are net 45.", Map.of("kind", "legal")));
        k.add(new Document("faq", "faq.md", "Refunds are issued to the original payment method within five business days.", Map.of("kind", "support")));
        k.add(new Document("misc", "misc.md", "The cafeteria serves lunch from noon.", Map.of("kind", "other")));
        return k;
    }

    private static RetrievalConfig cfg(RetrievalConfig.Method m) {
        return new RetrievalConfig(m, 3, null, false);
    }

    @Test
    void bm25FindsAnExactIdentifier() {
        List<Hit> hits = kb(null).search("clause 14.2 termination", cfg(RetrievalConfig.Method.BM25), Map.of());
        assertEquals("law", hits.get(0).chunk().docId());
        assertEquals("bm25", hits.get(0).method());
    }

    @Test
    void vectorAndHybridWorkWithAnEmbedder() {
        KnowledgeBase k = kb(EMB);
        assertEquals("faq", k.search("refunds original payment method", cfg(RetrievalConfig.Method.VECTOR), Map.of()).get(0).chunk().docId());
        assertEquals("law", k.search("termination notice 30 days", cfg(RetrievalConfig.Method.HYBRID), Map.of()).get(0).chunk().docId());
    }

    @Test
    void vectorAndHybridWithoutEmbedderFailLoudly() {
        KnowledgeBase k = kb(null);
        assertThrows(RagException.class, () -> k.search("x", cfg(RetrievalConfig.Method.VECTOR), Map.of()));
        assertThrows(RagException.class, () -> k.search("x", cfg(RetrievalConfig.Method.HYBRID), Map.of()));
    }

    @Test
    void metadataFilterNarrowsResults() {
        List<Hit> hits = kb(null).search("payment", cfg(RetrievalConfig.Method.BM25), Map.of("kind", "support"));
        assertEquals(1, hits.size());
        assertEquals("faq", hits.get(0).chunk().docId());
        assertEquals(1, kb(null).search("payment", cfg(RetrievalConfig.Method.BM25), Map.of("docName", "faq.md")).size());
    }

    @Test
    void mmrDropsNearDuplicates() {
        KnowledgeBase k = new KnowledgeBase(Chunkers.paragraph(400), EMB);
        k.add(new Document("a", "a", "Termination requires thirty days written notice.", Map.of()));
        k.add(new Document("b", "b", "Termination requires thirty days written notice.", Map.of()));
        k.add(new Document("c", "c", "Termination for cause is immediate upon material breach.", Map.of()));
        List<Hit> plain = k.search("termination notice", new RetrievalConfig(RetrievalConfig.Method.VECTOR, 2, null, false), Map.of());
        List<Hit> mmr = k.search("termination notice", new RetrievalConfig(RetrievalConfig.Method.VECTOR, 2, 0.3, false), Map.of());
        assertEquals(plain.get(0).text(), plain.get(1).text());
        assertTrue(!mmr.get(0).text().equals(mmr.get(1).text()), "mmr should diversify");
    }

    @Test
    void parentExpansionReturnsTheWiderContext() {
        KnowledgeBase k = new KnowledgeBase(Chunkers.parentChild(80, 500), null);
        k.add(new Document("p", "p", "Intro filler. ".repeat(20) + "The zebra clause applies. " + "Outro filler. ".repeat(20), Map.of()));
        Hit narrow = k.search("zebra", new RetrievalConfig(RetrievalConfig.Method.BM25, 1, null, false), Map.of()).get(0);
        Hit wide = k.search("zebra", new RetrievalConfig(RetrievalConfig.Method.BM25, 1, null, true), Map.of()).get(0);
        assertTrue(wide.text().length() > narrow.text().length());
    }

    @Test
    void replaceAndRemove() {
        KnowledgeBase k = kb(null);
        int before = k.chunks();
        k.add(new Document("faq", "faq.md", "Completely new text about warranties.", Map.of()));
        assertEquals(3, k.documents());
        assertTrue(k.search("refunds", cfg(RetrievalConfig.Method.BM25), Map.of()).isEmpty());
        assertEquals("faq", k.search("warranties", cfg(RetrievalConfig.Method.BM25), Map.of()).get(0).chunk().docId());
        assertTrue(k.remove("faq"));
        assertTrue(!k.remove("faq"));
        assertEquals(2, k.documents());
        assertTrue(k.chunks() < before);
    }

    @Test
    void capsAreEnforced() {
        KnowledgeBase k = new KnowledgeBase(Chunkers.paragraph(100), null, 2, 1000);
        k.add(new Document("1", "1", "one", Map.of()));
        k.add(new Document("2", "2", "two", Map.of()));
        assertThrows(RagException.class, () -> k.add(new Document("3", "3", "three", Map.of())));
        k.add(new Document("2", "2", "two again", Map.of())); // replacing is allowed at the cap
        KnowledgeBase c = new KnowledgeBase(Chunkers.paragraph(100), null, 10, 2);
        assertThrows(RagException.class, () -> c.add(new Document("big", "big", ("a".repeat(90) + "\n\n").repeat(4), Map.of())));
    }

    @Test
    void queryIsBounded() {
        assertThrows(RagException.class, () -> kb(null).search("x".repeat(2001), cfg(RetrievalConfig.Method.BM25), Map.of()));
    }

    @Test
    void configParsingRejectsTyposAndMissingEmbedder() {
        KnowledgeConfig kc = KnowledgeConfig.fromMap(Map.of("chunking", Map.of("type", "clause"), "retrieval", Map.of("method", "hybrid", "topK", 4, "mmr", true)));
        assertEquals("clause", kc.chunker().type());
        assertThrows(RagException.class, () -> kc.build(null)); // hybrid needs an embedder
        assertEquals(RetrievalConfig.Method.HYBRID, kc.defaultRetrieval(true).method());
        assertThrows(RagException.class, () -> KnowledgeConfig.fromMap(Map.of("chunkin", Map.of())));
        assertThrows(RagException.class, () -> KnowledgeConfig.fromMap(Map.of("retrieval", Map.of("topk", 3))));
        assertThrows(RagException.class, () -> KnowledgeConfig.fromMap(Map.of("retrieval", Map.of("method", "magic"))));
        assertEquals(RetrievalConfig.Method.BM25, KnowledgeConfig.fromMap(Map.of()).defaultRetrieval(false).method());
    }
}
