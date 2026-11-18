package tech.kayys.wayang.rag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds tiny Office Open XML files for tests. */
final class Office {

    private Office() {
    }

    static byte[] zip(Map<String, String> parts) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(bos)) {
                for (var e : parts.entrySet()) {
                    z.putNextEntry(new ZipEntry(e.getKey()));
                    z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    z.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";

    static byte[] docx(String bodyXml) {
        return zip(Map.of("word/document.xml", "<w:document " + W + "><w:body>" + bodyXml + "</w:body></w:document>"));
    }

    static String p(String style, String text) {
        return "<w:p>" + (style == null ? "" : "<w:pPr><w:pStyle w:val=\"" + style + "\"/></w:pPr>") + "<w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    static byte[] xlsx() {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("xl/workbook.xml", "<workbook xmlns:r=\"r\"><sheets><sheet name=\"Prices\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
        parts.put("xl/sharedStrings.xml", "<sst><si><t>Item</t></si><si><t>Price</t></si><si><t>Tea</t></si><si><r><t>Cof</t></r><r><t>fee</t></r></si></sst>");
        parts.put("xl/worksheets/sheet1.xml", "<worksheet><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>3.5</v></c></row>"
                + "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>3</v></c><c r=\"C3\" t=\"inlineStr\"><is><t>note</t></is></c></row>"
                + "</sheetData></worksheet>");
        return zip(parts);
    }

    static byte[] pptx() {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("ppt/presentation.xml", "<p:presentation/>");
        parts.put("ppt/slides/slide2.xml", "<p:sld xmlns:p=\"p\" xmlns:a=\"a\"><a:p><a:r><a:t>Second slide</a:t></a:r></a:p></p:sld>");
        parts.put("ppt/slides/slide1.xml", "<p:sld xmlns:p=\"p\" xmlns:a=\"a\"><a:p><a:r><a:t>Title</a:t></a:r></a:p><a:p><a:r><a:t>Hello </a:t></a:r><a:r><a:t>world</a:t></a:r></a:p></p:sld>");
        parts.put("ppt/slides/slide10.xml", "<p:sld xmlns:p=\"p\" xmlns:a=\"a\"><a:p><a:r><a:t>Tenth</a:t></a:r></a:p></p:sld>");
        return zip(parts);
    }
}
