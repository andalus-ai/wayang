package tech.kayys.wayang.rag.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ServiceLoader;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import tech.kayys.wayang.rag.DocumentExtractor;
import tech.kayys.wayang.rag.Extractors;
import tech.kayys.wayang.rag.Limits;
import tech.kayys.wayang.rag.Ocr;
import tech.kayys.wayang.rag.RagException;

class PdfExtractorTest {

    private static byte[] pdf(boolean encrypt, String... pages) throws Exception {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage p = new PDPage();
                d.addPage(p);
                if (!text.isEmpty()) {
                    try (PDPageContentStream c = new PDPageContentStream(d, p)) {
                        c.beginText();
                        c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        c.newLineAtOffset(50, 700);
                        c.showText(text);
                        c.endText();
                    }
                }
            }
            if (encrypt) {
                StandardProtectionPolicy pol = new StandardProtectionPolicy("owner-pw", "user-pw", new AccessPermission());
                pol.setEncryptionKeyLength(128);
                d.protect(pol);
            }
            d.save(o);
            return o.toByteArray();
        }
    }

    /** Pages given as text get a text layer; a null page is a scan: only an image, no text at all. */
    private static byte[] mixed(String... pages) throws Exception {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage p = new PDPage();
                d.addPage(p);
                try (PDPageContentStream c = new PDPageContentStream(d, p)) {
                    if (text == null) {
                        BufferedImage img = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = img.createGraphics();
                        g.setColor(Color.WHITE);
                        g.fillRect(0, 0, 300, 200);
                        g.setColor(Color.BLACK);
                        g.fillRect(20, 20, 200, 30);
                        g.dispose();
                        PDImageXObject x = LosslessFactory.createFromImage(d, img);
                        c.drawImage(x, 50, 500, 300, 200);
                    } else {
                        c.beginText();
                        c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        c.newLineAtOffset(50, 700);
                        c.showText(text);
                        c.endText();
                    }
                }
            }
            d.save(o);
            return o.toByteArray();
        }
    }

    /** Records every call and answers "scan <n>"; the images it is given are checked to be real PNGs. */
    private static final class FakeOcr implements Ocr {
        final List<Integer> sizes = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();

        @Override
        public String recognise(byte[] png, Duration timeout) {
            assertTrue(png.length > 8 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G', "a PNG image is handed to OCR");
            sizes.add(png.length);
            timeouts.add(timeout);
            return "scanned text " + sizes.size();
        }
    }

    @Test
    void scannedPagesAreRecognisedOnlyWhenOcrIsOnAndTextPagesAreNeverSentToIt() throws Exception {
        FakeOcr ocr = new FakeOcr();
        String t = new PdfExtractor(ocr, Map.<String, String>of()::get).extract(mixed("Real text page", null, "More real text", null), Limits.DEFAULT);
        assertEquals(2, ocr.sizes.size(), "only the two scans");
        assertTrue(t.contains("## Page 1") && t.contains("Real text page"), t);
        assertTrue(t.contains("## Page 2\n\nscanned text 1"), t);
        assertTrue(t.contains("## Page 3") && t.contains("More real text"), t);
        assertTrue(t.contains("## Page 4\n\nscanned text 2"), t);
        assertTrue(ocr.timeouts.stream().allMatch(d -> d.toSeconds() <= 60 && !d.isNegative()), "each page has a bounded time");
    }

    @Test
    void withoutOcrAScanIsSkippedAndAllScanFilesExplainHowToTurnItOn() throws Exception {
        PdfExtractor off = new PdfExtractor(null, Map.<String, String>of()::get);
        assertEquals("## Page 1\n\nOnly this", off.extract(mixed("Only this", null), Limits.DEFAULT));
        RagException e = assertThrows(RagException.class, () -> off.extract(mixed(null, null), Limits.DEFAULT));
        assertTrue(e.getMessage().contains("WAYANG_OCR=tesseract"), e.getMessage());
    }

    @Test
    void theEnvironmentTurnsOcrOnAndATypoIsAnErrorNotSilence() throws Exception {
        RagException e = assertThrows(RagException.class,
                () -> new PdfExtractor(null, Map.of("WAYANG_OCR", "tesseractt")::get).extract(mixed("Text", null), Limits.DEFAULT));
        assertTrue(e.getMessage().contains("WAYANG_OCR"), e.getMessage());
        // on with a program that is not there: the failure names the setting, never the page content
        RagException m = assertThrows(RagException.class, () -> new PdfExtractor(null,
                Map.of("WAYANG_OCR", "tesseract", "WAYANG_OCR_COMMAND", "/no/such/binary")::get).extract(mixed((String) null), Limits.DEFAULT));
        assertTrue(m.getMessage().contains("could not be run"), m.getMessage());
    }

    @Test
    void thePageBudgetStopsOcrButKeepsWhatWasRead() throws Exception {
        FakeOcr ocr = new FakeOcr();
        // a given engine uses the default budget of 30 pages, so build more scans than that
        String[] pages = new String[Ocr.DEFAULT_MAX_PAGES + 2];
        pages[0] = "Has text";
        String t = new PdfExtractor(ocr, Map.<String, String>of()::get).extract(mixed(pages), Limits.DEFAULT);
        assertEquals(Ocr.DEFAULT_MAX_PAGES, ocr.sizes.size(), "no more pages than the budget are recognised");
        assertTrue(t.contains("Has text") && t.contains("scanned text " + Ocr.DEFAULT_MAX_PAGES), t);
        assertFalse(t.contains("scanned text " + (Ocr.DEFAULT_MAX_PAGES + 1)));
    }

    @Test
    void anOcrFailureOrAnEmptyRecognitionIsReportedNotSilentlyEmpty() throws Exception {
        Ocr failing = (png, timeout) -> {
            throw new RagException("OCR failed (the tesseract command exited with 1)");
        };
        assertThrows(RagException.class, () -> new PdfExtractor(failing, Map.<String, String>of()::get).extract(mixed((String) null), Limits.DEFAULT));
        Ocr blank = (png, timeout) -> "";
        RagException e = assertThrows(RagException.class, () -> new PdfExtractor(blank, Map.<String, String>of()::get).extract(mixed((String) null), Limits.DEFAULT));
        assertTrue(e.getMessage().contains("no text could be recognised"), e.getMessage());
    }

    @Test
    void readsTextPageByPage() throws Exception {
        String t = new PdfExtractor().extract(pdf(false, "Clause 14.2 termination", "", "Payment is net 45"), Limits.DEFAULT);
        assertTrue(t.contains("## Page 1") && t.contains("Clause 14.2 termination"), t);
        assertTrue(t.contains("## Page 3") && t.contains("Payment is net 45"), t);
        assertFalse(t.contains("## Page 2"), "a page without text is skipped");
    }

    @Test
    void aPdfWithoutTextIsReportedNotSilentlyEmpty() throws Exception {
        RagException e = assertThrows(RagException.class, () -> new PdfExtractor().extract(pdf(false, ""), Limits.DEFAULT));
        assertTrue(e.getMessage().contains("OCR"), e.getMessage());
    }

    @Test
    void passwordProtectedAndDamagedFilesAreRejectedWithoutEchoingContent() throws Exception {
        assertThrows(RagException.class, () -> new PdfExtractor().extract(pdf(true, "secret words"), Limits.DEFAULT));
        RagException e = assertThrows(RagException.class,
                () -> new PdfExtractor().extract("%PDF-1.4 SECRETBODY not really a pdf".getBytes(StandardCharsets.UTF_8), Limits.DEFAULT));
        assertFalse(e.getMessage().contains("SECRETBODY"));
    }

    @Test
    void isFoundThroughServiceLoaderAndWorksByContentSniffing() throws Exception {
        Extractors ex = Extractors.defaults();
        for (DocumentExtractor d : ServiceLoader.load(DocumentExtractor.class)) {
            ex = ex.with(d);
        }
        assertTrue(ex.extensions().contains("pdf"));
        byte[] bytes = pdf(false, "Hello knowledge");
        assertTrue(ex.extract("report.pdf", bytes, Limits.DEFAULT).contains("Hello knowledge"));
        assertTrue(ex.extract(null, bytes, Limits.DEFAULT).contains("Hello knowledge"));
        assertEquals(List.of("pdf"), List.copyOf(new PdfExtractor().extensions()));
    }
}
