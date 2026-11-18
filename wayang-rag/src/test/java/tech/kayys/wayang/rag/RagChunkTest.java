package tech.kayys.wayang.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RagChunkTest {

    private static Document doc(String text) {
        return new Document("d1", "d1.md", text, Map.of());
    }

    @Test
    void fixedWindowsOverlapAndStayBounded() {
        List<Chunk> cs = Chunkers.fixed(100, 20).chunk(doc("word ".repeat(200)));
        assertTrue(cs.size() > 5);
        for (Chunk c : cs) {
            assertTrue(c.text().length() <= 100, "chunk too long: " + c.text().length());
        }
    }

    @Test
    void paragraphKeepsParagraphsTogether() {
        List<Chunk> cs = Chunkers.paragraph(100).chunk(doc("Alpha one.\n\nBeta two.\n\n" + "Gamma ".repeat(40)));
        assertTrue(cs.get(0).text().contains("Alpha one.") && cs.get(0).text().contains("Beta two."));
        for (Chunk c : cs) {
            assertTrue(c.text().length() <= 100);
        }
    }

    @Test
    void headingChunksCarryTheirHeadingPath() {
        List<Chunk> cs = Chunkers.heading(500).chunk(doc("# Policy\n\nIntro text.\n\n## Refunds\n\nRefunds take 5 days.\n\n## Returns\n\nReturn within 30 days."));
        Chunk refunds = cs.stream().filter(c -> c.text().contains("5 days")).findFirst().orElseThrow();
        assertTrue(refunds.text().contains("Policy") && refunds.text().contains("Refunds"), refunds.text());
        Chunk returns = cs.stream().filter(c -> c.text().contains("30 days")).findFirst().orElseThrow();
        assertTrue(returns.text().contains("Returns"));
        assertTrue(!returns.text().contains("5 days"));
    }

    @Test
    void clauseSplitsOnLegalMarkersAndKeepsSubclausesWithTheirParent() {
        String t = "Article 1 Definitions\nTerms are defined here.\n\nArticle 2 Payment\n(a) Invoices are due in 30 days.\n(b) Late fees apply.\n\nPasal 3 Sanksi\nDenda berlaku.";
        List<Chunk> cs = Chunkers.clause(500).chunk(doc(t));
        Chunk late = cs.stream().filter(c -> c.text().contains("Late fees")).findFirst().orElseThrow();
        assertTrue(late.text().contains("Article 2"), late.text());
        Chunk pasal = cs.stream().filter(c -> c.text().contains("Denda")).findFirst().orElseThrow();
        assertTrue(pasal.text().contains("Pasal 3"));
        assertTrue(!pasal.text().contains("Late fees"));
    }

    @Test
    void parentChildGivesSmallChunksALargerContext() {
        String t = ("Sentence about topic %d. ").repeat(1).formatted(1) + "Filler words here. ".repeat(60) + "The needle sits here. " + "More filler text. ".repeat(60);
        List<Chunk> cs = Chunkers.parentChild(100, 600).chunk(doc(t));
        Chunk hit = cs.stream().filter(c -> c.text().contains("needle")).findFirst().orElseThrow();
        assertTrue(hit.text().length() <= 100);
        assertTrue(hit.context() != null && hit.context().length() > hit.text().length());
        assertTrue(hit.context().contains("needle"));
    }

    @Test
    void emptyDocumentHasNoChunks() {
        assertEquals(0, Chunkers.paragraph(100).chunk(doc("   \n\n ")).size());
    }

    @Test
    void configIsValidated() {
        assertEquals("heading", Chunkers.fromConfig(Map.of("type", "heading", "maxChars", 800)).type());
        assertThrows(RagException.class, () -> Chunkers.fromConfig(Map.of("type", "magic")));
        assertThrows(RagException.class, () -> Chunkers.fromConfig(Map.of("type", "fixed", "sizee", 5)));
        assertThrows(RagException.class, () -> Chunkers.fromConfig(Map.of("type", "fixed", "size", "big")));
        assertThrows(RagException.class, () -> Chunkers.fixed(10, 10));
    }
}
