package tech.kayys.wayang.rag.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ServiceLoader;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import tech.kayys.wayang.rag.DocumentExtractor;
import tech.kayys.wayang.rag.Extractors;
import tech.kayys.wayang.rag.Limits;
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
