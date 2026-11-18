package tech.kayys.wayang.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ExtractTest {

    private final Extractors ex = Extractors.defaults();

    private String run(String name, byte[] b) {
        return ex.extract(name, b, Limits.DEFAULT);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void plainTextKeepsContentAndDropsBomAndNul() {
        byte[] b = utf8("﻿hello\r\nworld\u0000");
        assertEquals("hello\nworld", run("a.txt", b));
    }

    @Test
    void htmlDropsScriptsAndKeepsStructure() {
        String html = "<html><head><style>p{}</style><script>alert(1)</script></head><body><h1>Title &amp; more</h1><p>One<br>two</p><ul><li>a</li><li>b</li></ul>"
                + "<!-- hidden --><table><tr><td>x</td><td>y</td></tr></table></body></html>";
        String t = run("a.html", utf8(html));
        assertTrue(t.contains("# Title & more"), t);
        assertTrue(t.contains("- a") && t.contains("- b"), t);
        assertTrue(t.contains("x |"), t);
        assertFalse(t.contains("alert") || t.contains("hidden") || t.contains("p{}"), t);
    }

    @Test
    void csvBecomesHeaderValueLinesWithQuotingAndDelimiterDetection() {
        String csv = "Name;City;Note\nAna;\"Jakarta; ID\";\"said \"\"hi\"\"\"\n\nBudi;Bandung;\n";
        assertEquals("Name=Ana; City=Jakarta; ID; Note=said \"hi\"\nName=Budi; City=Bandung", run("people.csv", utf8(csv)).strip());
    }

    @Test
    void csvRowLimitIsEnforced() {
        StringBuilder sb = new StringBuilder("a,b\n");
        for (int i = 0; i < 20; i++) {
            sb.append("1,2\n");
        }
        Limits small = new Limits(1 << 20, 100000, 100, 1 << 20, 10);
        assertThrows(RagException.class, () -> ex.extract("x.csv", utf8(sb.toString()), small));
    }

    @Test
    void docxHasHeadingsParagraphsAndTableRows() {
        String body = Office.p("Heading1", "Chapter 1") + Office.p(null, "Intro text.") + Office.p("Heading2", "Scope") + Office.p("Title", "Doc")
                + "<w:tbl><w:tr><w:tc>" + Office.p(null, "Key") + "</w:tc><w:tc>" + Office.p(null, "Value") + "</w:tc></w:tr></w:tbl>" + Office.p(null, "After.");
        String t = run("a.docx", Office.docx(body));
        assertTrue(t.contains("# Chapter 1") && t.contains("## Scope") && t.contains("# Doc"), t);
        assertTrue(t.contains("Key | Value"), t);
        assertTrue(t.indexOf("Intro text.") < t.indexOf("## Scope"), t);
        assertTrue(t.contains("After."), t);
    }

    @Test
    void xlsxReadsSharedAndInlineStringsAndColumnGaps() {
        String t = run("prices.xlsx", Office.xlsx());
        assertTrue(t.startsWith("## Prices"), t);
        assertTrue(t.contains("Item=Tea; Price=3.5"), t);
        assertTrue(t.contains("Item=Coffee; col3=note"), t);
    }

    @Test
    void pptxIsOrderedBySlideNumberNotName() {
        String t = run("deck.pptx", Office.pptx());
        assertTrue(t.indexOf("## Slide 1") < t.indexOf("## Slide 2") && t.indexOf("## Slide 2") < t.indexOf("## Slide 10"), t);
        assertTrue(t.contains("Hello world"), t);
    }

    @Test
    void formatIsSniffedWhenTheNameHasNoExtension() {
        assertTrue(run("download", Office.docx(Office.p("Heading1", "Sniffed"))).contains("# Sniffed"));
        assertTrue(run(null, utf8("<!DOCTYPE html><html><body><h2>Hi</h2></body></html>")).contains("## Hi"));
        assertEquals("just text", run("noext", utf8("just text")));
    }

    @Test
    void unsupportedFormatsExplainThemselvesWithoutEchoingContent() {
        RagException pdf = assertThrows(RagException.class, () -> run("a.pdf", utf8("%PDF-1.7 secret-body")));
        assertTrue(pdf.getMessage().contains("PDF"), pdf.getMessage());
        assertFalse(pdf.getMessage().contains("secret-body"));
        byte[] ole = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 1, 2, 3, 4, 5 };
        assertTrue(assertThrows(RagException.class, () -> run("old.doc", ole)).getMessage().contains(".docx"));
        assertThrows(RagException.class, () -> run("x.bin", new byte[] { 1, 0, 2, 0, 3 }));
        assertThrows(RagException.class, () -> run("empty.txt", new byte[0]));
    }

    @Test
    void oversizeAndDamagedFilesAreRefused() {
        Limits tiny = new Limits(10, 100, 10, 100, 10);
        assertThrows(RagException.class, () -> ex.extract("a.txt", utf8("this is more than ten bytes"), tiny));
        assertThrows(RagException.class, () -> run("a.docx", utf8("not a zip at all")));
        assertThrows(RagException.class, () -> run("a.docx", Office.zip(Map.of("other.xml", "<x/>"))));
        assertThrows(RagException.class, () -> run("a.docx", Office.zip(Map.of("word/document.xml", "<w:document><unclosed>"))));
    }

    @Test
    void anOversizedPartInsideAnOfficeFileIsRefusedAndUnusedPartsAreNeverUnpacked() {
        String big = "x".repeat(200_000);
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("word/document.xml", "<w:document xmlns:w=\"w\"><w:body>" + Office.p(null, "ok") + "</w:body></w:document>");
        parts.put("word/media/huge.bin", big);
        Limits small = new Limits(1 << 20, 100_000, 100, 50_000, 1000);
        assertEquals("ok", ex.extract("a.docx", Office.zip(parts), small)); // the unused huge part is skipped, not inflated
        Map<String, String> bigDoc = new LinkedHashMap<>();
        bigDoc.put("word/document.xml", "<w:document xmlns:w=\"w\"><w:body>" + Office.p(null, big) + "</w:body></w:document>");
        assertThrows(RagException.class, () -> ex.extract("a.docx", Office.zip(bigDoc), small));
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            many.put("p" + i + ".xml", "<x/>");
        }
        assertThrows(RagException.class, () -> ex.extract("a.docx", Office.zip(many), new Limits(1 << 20, 1000, 10, 1000, 10)));
    }

    @Test
    void externalEntitiesAndDtdsAreNotResolved() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><w:document xmlns:w=\"w\"><w:body>"
                + "<w:p><w:r><w:t>before &x; after</w:t></w:r></w:p></w:body></w:document>";
        String t;
        try {
            t = run("a.docx", Office.zip(Map.of("word/document.xml", xxe)));
        } catch (RagException e) {
            return; // refusing the file is also safe
        }
        assertFalse(t.contains("root:"), t);
    }

    @Test
    void extraExtractorsCanBeAddedAndOverrideABuiltIn() {
        Extractors custom = ex.with(new DocumentExtractor() {
            public java.util.Set<String> extensions() {
                return java.util.Set.of("pdf", "txt");
            }

            public String extract(byte[] c, Limits l) {
                return "custom";
            }
        });
        assertEquals("custom", custom.extract("a.pdf", utf8("%PDF-"), Limits.DEFAULT));
        assertEquals("custom", custom.extract("a.txt", utf8("x"), Limits.DEFAULT));
        assertTrue(custom.extensions().contains("docx") && custom.extensions().contains("pdf"));
    }
}
