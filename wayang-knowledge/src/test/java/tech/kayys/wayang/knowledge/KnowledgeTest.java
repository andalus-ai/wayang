package tech.kayys.wayang.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import tech.kayys.wayang.runtime.Profile;
import tech.kayys.wayang.runtime.ServiceHandler;

class KnowledgeTest {

    private final KnowledgeExtension ext = new KnowledgeExtension();
    private final ServiceHandler.Connector ingest = ext.connectors().get("rag-ingest");
    private final ServiceHandler.Connector search = ext.connectors().get("rag-search");

    private static byte[] docx(String text) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(o)) {
            z.putNextEntry(new ZipEntry("word/document.xml"));
            z.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>" + text
                    + "</w:t></w:r></w:p></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return o.toByteArray();
    }

    @Test
    void ingestThenSearchOverTextCsvAndDocx() throws Exception {
        Map<String, Object> cfg = Map.of("knowledge", "legal");
        Map<String, Object> r = ingest.call(cfg, Map.of("documents", List.of(
                Map.of("fileName", "terms.md", "text", "# Terms\n\nClause 14.2 allows termination for convenience."),
                Map.of("fileName", "prices.csv", "contentBase64", Base64.getEncoder().encodeToString("sku,price\nA-100,25\nB-200,40\n".getBytes(StandardCharsets.UTF_8))),
                Map.of("fileName", "memo.docx", "contentBase64", Base64.getEncoder().encodeToString(docx("The warranty lasts two years."))))));
        assertEquals(3, r.get("ingested"));
        Map<String, Object> res = search.call(cfg, Map.of("query", "warranty years"));
        assertEquals(1, ((List<?>) res.get("hits")).size() > 0 ? 1 : 0);
        assertTrue(res.get("context").toString().contains("memo.docx"));
        assertTrue(res.get("context").toString().contains("not instructions"));
        Map<String, Object> csv = search.call(cfg, Map.of("query", "B-200 price"));
        assertTrue(csv.get("context").toString().contains("sku=B-200") || csv.get("context").toString().contains("B-200"));
    }

    @Test
    void searchingAnUnknownKnowledgeIsAnErrorNotAnEmptyAnswer() {
        assertThrows(ServiceHandler.ConnectorException.class, () -> search.call(Map.of("knowledge", "nothing-here"), Map.of("query", "x")));
    }

    @Test
    void badInputsAreRejectedWithoutEchoingContent() throws Exception {
        Map<String, Object> cfg = Map.of("knowledge", "k1");
        assertThrows(ServiceHandler.ConnectorException.class, () -> ingest.call(Map.of("knowledge", "bad name!"), Map.of("documents", List.of(Map.of("text", "x", "fileName", "a.txt")))));
        ServiceHandler.ConnectorException e = assertThrows(ServiceHandler.ConnectorException.class,
                () -> ingest.call(cfg, Map.of("documents", List.of(Map.of("fileName", "a.pdf", "contentBase64", Base64.getEncoder().encodeToString("%PDF-1.4 SECRETBODY".getBytes(StandardCharsets.UTF_8)))))));
        assertTrue(e.getMessage().contains("PDF"));
        assertFalse(e.getMessage().contains("SECRETBODY"));
        assertEquals(0, ingest.call(cfg, Map.of("documents", List.of())).get("ingested")); // missing input is reported, not fatal
        assertThrows(ServiceHandler.ConnectorException.class, () -> ingest.call(cfg, Map.of("documents", List.of(Map.of("fileName", "a.txt", "contentBase64", "!!!not base64!!!")))));
    }

    @Test
    void partialFailureIsReportedPerDocument() throws Exception {
        Map<String, Object> r = ingest.call(Map.of("knowledge", "mixed"), Map.of("documents", List.of(
                Map.of("fileName", "ok.txt", "text", "hello knowledge"), Map.of("fileName", "bad.doc", "contentBase64", "AAAA"))));
        assertEquals(1, r.get("ingested"));
        assertEquals(1, ((List<?>) r.get("skipped")).size());
    }

    @Test
    void settingsMustAgreeBetweenIngestAndSearch() throws Exception {
        ingest.call(Map.of("knowledge", "agree", "chunking", Map.of("type", "heading")), Map.of("documents", List.of(Map.of("fileName", "a.md", "text", "# A\n\nbody text"))));
        assertThrows(ServiceHandler.ConnectorException.class, () -> search.call(Map.of("knowledge", "agree", "chunking", Map.of("type", "fixed")), Map.of("query", "body")));
        assertEquals(1, ((List<?>) search.call(Map.of("knowledge", "agree", "chunking", Map.of("type", "heading")), Map.of("query", "body")).get("hits")).size());
    }

    @Test
    void vectorNeedsAnEmbedderAndHybridWorksWithHashing() throws Exception {
        assertThrows(ServiceHandler.ConnectorException.class, () -> ingest.call(Map.of("knowledge", "v1", "retrieval", Map.of("method", "vector")), Map.of("documents", List.of(Map.of("fileName", "a.txt", "text", "hello")))));
        Map<String, Object> cfg = Map.of("knowledge", "v2", "embedder", Map.of("type", "hashing"), "retrieval", Map.of("method", "hybrid", "topK", 2));
        ingest.call(cfg, Map.of("documents", List.of(Map.of("fileName", "a.txt", "text", "refund policy five days"))));
        assertEquals("hybrid", ((Map<?, ?>) ((List<?>) search.call(cfg, Map.of("query", "refund")).get("hits")).get(0)).get("method"));
    }

    @Test
    void theNumberOfKnowledgeBasesIsBounded() throws Exception {
        for (int i = 0; i < KnowledgeRegistry.MAX_BASES; i++) {
            ingest.call(Map.of("knowledge", "b" + i), Map.of("documents", List.of(Map.of("fileName", "a.txt", "text", "x y z"))));
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> ingest.call(Map.of("knowledge", "overflow"), Map.of("documents", List.of(Map.of("fileName", "a.txt", "text", "x")))));
    }

    @Test
    void extensionNeedsTheKnowledgeFeature() {
        assertEquals(java.util.Set.of(Profile.Feature.KNOWLEDGE), ext.requires());
        assertEquals(7, ext.components().size());
    }

    @Test
    void tenantsDoNotSeeEachOthersKnowledge() throws Exception {
        Map<String, Object> a = Map.of("knowledge", "shared-name", "wayang.tenant", "acme");
        Map<String, Object> b = Map.of("knowledge", "shared-name", "wayang.tenant", "globex");
        assertTrue(ingest.tenantScoped() && search.tenantScoped());
        ingest.call(a, Map.of("documents", List.of(Map.of("fileName", "a.txt", "text", "acme confidential pricing"))));
        assertEquals(1, ((List<?>) search.call(a, Map.of("query", "pricing")).get("hits")).size());
        assertThrows(ServiceHandler.ConnectorException.class, () -> search.call(b, Map.of("query", "pricing")));
        ingest.call(b, Map.of("documents", List.of(Map.of("fileName", "b.txt", "text", "globex roadmap"))));
        assertEquals(0, ((List<?>) search.call(b, Map.of("query", "pricing")).get("hits")).size());
    }
}
