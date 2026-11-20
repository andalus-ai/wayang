package tech.kayys.wayang.rag.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.function.Function;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import tech.kayys.wayang.rag.DocumentExtractor;
import tech.kayys.wayang.rag.Limits;
import tech.kayys.wayang.rag.Ocr;
import tech.kayys.wayang.rag.RagException;

/**
 * Text of a PDF, one {@code ## Page N} section per page that has text. Only the text layer is read: nothing in the file is executed or
 * followed (no JavaScript, links, attachments or forms). A page without a text layer (a scan) is skipped, unless the operator turned OCR
 * on ({@link Ocr#fromEnvironment}): then that page is rendered to an image and recognised, at most 30 pages per document (setting
 * {@code WAYANG_OCR_MAX_PAGES}, ceiling 100) and five minutes in all. Recognised text can contain mistakes. Password-protected files
 * are rejected. At most {@value #MAX_PAGES} pages are read.
 */
public final class PdfExtractor implements DocumentExtractor {

    static final int MAX_PAGES = 500;
    static final float OCR_DPI = 200f;
    static final double MAX_PIXELS = 30_000_000d;
    static final Duration OCR_PAGE_TIMEOUT = Duration.ofSeconds(60);
    static final Duration OCR_TOTAL = Duration.ofMinutes(5);

    private final Ocr fixedOcr;
    private final Function<String, String> env;

    public PdfExtractor() {
        this(null, System::getenv);
    }

    /** For tests and embedding: a given engine (null = decide from the environment) and environment. */
    PdfExtractor(Ocr ocr, Function<String, String> env) {
        this.fixedOcr = ocr;
        this.env = env;
    }

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
            Ocr ocr = fixedOcr != null ? fixedOcr : Ocr.fromEnvironment(env);
            int ocrBudget = ocr == null ? 0 : fixedOcr != null ? Ocr.DEFAULT_MAX_PAGES : Ocr.maxPages(env);
            long deadline = System.nanoTime() + OCR_TOTAL.toNanos();
            int ocrPages = 0;
            int skippedScans = 0;
            PDFRenderer renderer = ocr == null ? null : new PDFRenderer(doc);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder out = new StringBuilder();
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String t = stripper.getText(doc).strip();
                if (t.isEmpty() && ocr != null) {
                    if (ocrPages >= ocrBudget) {
                        skippedScans++;
                    } else {
                        long left = deadline - System.nanoTime();
                        if (left <= 0) {
                            throw new RagException("OCR of this PDF took longer than " + OCR_TOTAL.toMinutes() + " minutes");
                        }
                        ocrPages++;
                        t = ocr.recognise(png(doc, renderer, p - 1), Duration.ofNanos(Math.min(left, OCR_PAGE_TIMEOUT.toNanos())));
                    }
                }
                if (!t.isEmpty()) {
                    out.append("## Page ").append(p).append("\n\n").append(t).append("\n\n");
                }
                if (out.length() > limits.maxChars()) {
                    throw new RagException("the PDF text is longer than " + limits.maxChars() + " characters");
                }
            }
            if (out.length() == 0) {
                throw new RagException(ocr == null
                        ? "the PDF has no text layer (a scanned PDF needs OCR: the operator can enable it with WAYANG_OCR=tesseract)"
                        : skippedScans > 0
                                ? "the PDF has no text layer and has more scanned pages than OCR is allowed to read (" + ocrBudget + ")"
                                : "no text could be recognised in the PDF");
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

    /** One page as a PNG, at {@value #OCR_DPI} dpi, lowered for a very large page so the image stays within a pixel budget. */
    private static byte[] png(PDDocument doc, PDFRenderer renderer, int page) throws IOException {
        var box = doc.getPage(page).getCropBox();
        double w = Math.max(1, box.getWidth()) / 72.0;
        double h = Math.max(1, box.getHeight()) / 72.0;
        float dpi = OCR_DPI;
        if (w * dpi * h * dpi > MAX_PIXELS) {
            dpi = (float) Math.sqrt(MAX_PIXELS / (w * h));
        }
        if (dpi < 20f) {
            throw new RagException("a page is too large to recognise");
        }
        try (ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(renderer.renderImageWithDPI(page, dpi, ImageType.GRAY), "png", o);
            return o.toByteArray();
        }
    }
}
