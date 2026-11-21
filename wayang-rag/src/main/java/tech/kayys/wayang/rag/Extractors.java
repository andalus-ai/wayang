package tech.kayys.wayang.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chooses the extractor for a file by its extension, then by its content, and applies the size limit. The built-in set reads txt, md, html, csv/tsv, docx, xlsx and
 * pptx with the JDK only; more formats (PDF, ...) are added with {@link #with}, so heavy parsers stay optional.
 */
public final class Extractors {

    private final List<DocumentExtractor> all;

    private Extractors(List<DocumentExtractor> all) {
        this.all = List.copyOf(all);
    }

    public static Extractors defaults() {
        return new Extractors(List.of(new PlainTextExtractor(), new HtmlExtractor(), new CsvExtractor(), new DocxExtractor(), new XlsxExtractor(), new PptxExtractor(), new ImageExtractor()));
    }

    /** A copy with one more extractor; an extension it declares replaces the built-in one for that extension. */
    public Extractors with(DocumentExtractor extra) {
        List<DocumentExtractor> l = new ArrayList<>(all);
        l.add(0, extra);
        return new Extractors(l);
    }

    public java.util.Set<String> extensions() {
        java.util.Set<String> s = new java.util.TreeSet<>();
        all.forEach(e -> s.addAll(e.extensions()));
        return s;
    }

    /**
     * @param fileName used for its extension; may be null when the content decides
     * @return the normalised text of the file
     * @throws RagException for an unsupported, damaged or oversized file; the message names the format, never the content
     */
    public String extract(String fileName, byte[] content, Limits limits) {
        if (content == null || content.length == 0) {
            throw new RagException("the file is empty");
        }
        if (content.length > limits.maxBytes()) {
            throw new RagException("the file is larger than " + (limits.maxBytes() >> 20) + " MiB");
        }
        String ext = extension(fileName);
        DocumentExtractor ex = ext == null ? null : find(ext);
        if (ex == null) {
            ex = find(sniff(content));
        }
        if (ex == null) {
            throw new RagException(unsupported(ext, content));
        }
        return ex.extract(content, limits);
    }

    private DocumentExtractor find(String ext) {
        if (ext == null) {
            return null;
        }
        for (DocumentExtractor e : all) {
            if (e.extensions().contains(ext)) {
                return e;
            }
        }
        return null;
    }

    static String extension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? null : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Content-based guess used when the name has no usable extension. */
    static String sniff(byte[] c) {
        if (c.length >= 4 && c[0] == 'P' && c[1] == 'K') {
            if (OoxmlZip.has(c, "word/document.xml")) {
                return "docx";
            }
            if (OoxmlZip.has(c, "xl/workbook.xml")) {
                return "xlsx";
            }
            if (OoxmlZip.has(c, "ppt/presentation.xml")) {
                return "pptx";
            }
            return null;
        }
        if (c.length >= 5 && c[0] == '%' && c[1] == 'P' && c[2] == 'D' && c[3] == 'F') {
            return "pdf";
        }
        if (c.length >= 8 && (c[0] & 0xFF) == 0x89 && c[1] == 'P' && c[2] == 'N' && c[3] == 'G') {
            return "png";
        }
        if (c.length >= 4 && (c[0] & 0xFF) == 0xFF && (c[1] & 0xFF) == 0xD8 && (c[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (c.length >= 6 && c[0] == 'G' && c[1] == 'I' && c[2] == 'F' && c[3] == '8') {
            return "gif";
        }
        if (c.length >= 4 && ((c[0] == 'I' && c[1] == 'I' && c[2] == 42 && c[3] == 0) || (c[0] == 'M' && c[1] == 'M' && c[2] == 0 && c[3] == 42))) {
            return "tiff";
        }
        if (c.length >= 8 && (c[0] & 0xFF) == 0xD0 && (c[1] & 0xFF) == 0xCF && (c[2] & 0xFF) == 0x11 && (c[3] & 0xFF) == 0xE0) {
            return "ole";
        }
        for (int i = 0; i < Math.min(c.length, 512); i++) {
            if (c[i] == 0) {
                return null; // binary
            }
        }
        String head = new String(c, 0, Math.min(c.length, 256), java.nio.charset.StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT);
        return head.startsWith("<!doctype html") || head.startsWith("<html") ? "html" : "txt";
    }

    private static String unsupported(String ext, byte[] content) {
        String kind = ext != null ? ext : sniff(content);
        if ("pdf".equals(kind)) {
            return "PDF needs the PDF extractor, which is not enabled in this build";
        }
        if ("ole".equals(kind) || "doc".equals(kind) || "xls".equals(kind) || "ppt".equals(kind)) {
            return "legacy binary Office files (.doc, .xls, .ppt) are not supported: save them as .docx, .xlsx or .pptx";
        }
        return "unsupported file format" + (kind == null ? "" : " '" + kind + "'");
    }
}
