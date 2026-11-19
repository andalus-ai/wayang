package tech.kayys.wayang.rag.pdf;

import java.io.IOException;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import tech.kayys.wayang.rag.DocumentExtractor;
import tech.kayys.wayang.rag.Limits;
import tech.kayys.wayang.rag.RagException;

/**
 * Text of a PDF, one {@code ## Page N} section per page that has text. Only the text layer is read: nothing in the file is executed or
 * followed (no JavaScript, links, attachments or forms), and scanned pages without a text layer produce no text (OCR is not included).
 * Password-protected files are rejected. At most {@value #MAX_PAGES} pages are read.
 */
public final class PdfExtractor implements DocumentExtractor {

    static final int MAX_PAGES = 500;

    @Override
    public Set<String> extensions() {
        return Set.of("pdf");
    }

    @Override
    public String extract(byte[] content, Limits limits) {
        try (PDDocument doc = Loader.loadPDF(content)) {
            if (doc.isEncrypted()) {
                throw new RagException("the PDF is encrypted; remove the password first");
            }
            int pages = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder out = new StringBuilder();
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String t = stripper.getText(doc).strip();
                if (!t.isEmpty()) {
                    out.append("## Page ").append(p).append("\n\n").append(t).append("\n\n");
                }
                if (out.length() > limits.maxChars()) {
                    throw new RagException("the PDF text is longer than " + limits.maxChars() + " characters");
                }
            }
            if (out.length() == 0) {
                throw new RagException("the PDF has no text layer (a scanned PDF needs OCR, which is not supported)");
            }
            return out.toString().strip();
        } catch (InvalidPasswordException e) {
            throw new RagException("the PDF is password protected");
        } catch (IOException | RuntimeException e) {
            if (e instanceof RagException r) {
                throw r;
            }
            throw new RagException("the PDF is damaged or not readable");
        }
    }
}
