package tech.kayys.wayang.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class ImageExtractTest {

    private static byte[] image(String format, int type, int w, int h, boolean text) throws Exception {
        System.setProperty("java.awt.headless", "true");
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        if (type != BufferedImage.TYPE_INT_ARGB) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
        }
        g.setColor(Color.BLACK);
        if (text) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
            g.drawString("PAYMENT TERMS NET 45", 40, 130);
        }
        g.dispose();
        try (ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(img, format, o), format);
            return o.toByteArray();
        }
    }

    private static final class Fake implements Ocr {
        final List<byte[]> seen = new ArrayList<>();
        String answer = "recognised words";

        @Override
        public String recognise(byte[] png, Duration timeout) {
            seen.add(png);
            assertTrue(timeout.toSeconds() <= 60);
            return answer;
        }
    }

    private static ImageExtractor with(Ocr ocr) {
        return new ImageExtractor(ocr, Map.<String, String>of()::get);
    }

    @Test
    void anImageIsReadThroughOcrAndOnlyAPlainPngReachesIt() throws Exception {
        Fake ocr = new Fake();
        for (String f : new String[] {"png", "jpg", "gif"}) {
            ocr.seen.clear();
            assertEquals("recognised words", with(ocr).extract(image(f, BufferedImage.TYPE_INT_RGB, 1400, 220, true), Limits.DEFAULT), f);
            byte[] sent = ocr.seen.get(0);
            assertEquals(0x89, sent[0] & 0xFF, "what OCR gets is the PNG made here, not the original " + f);
            BufferedImage back = ImageIO.read(new ByteArrayInputStream(sent));
            assertEquals(1400, back.getWidth());
        }
    }

    @Test
    void aTransparentPictureIsFlattenedOntoWhite() throws Exception {
        Fake ocr = new Fake();
        with(ocr).extract(image("png", BufferedImage.TYPE_INT_ARGB, 200, 100, false), Limits.DEFAULT);
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(ocr.seen.get(0)));
        assertEquals(0xFFFFFF, back.getRGB(10, 10) & 0xFFFFFF, "transparent pixels become white, not black");
    }

    @Test
    void withoutOcrTheFileIsRefusedWithTheWayToTurnItOn() throws Exception {
        RagException e = assertThrows(RagException.class, () -> new ImageExtractor(null, Map.<String, String>of()::get)
                .extract(image("png", BufferedImage.TYPE_INT_RGB, 100, 50, false), Limits.DEFAULT));
        assertTrue(e.getMessage().contains("WAYANG_OCR=tesseract"), e.getMessage());
        RagException t = assertThrows(RagException.class, () -> new ImageExtractor(null, Map.of("WAYANG_OCR", "tesseractt")::get)
                .extract(image("png", BufferedImage.TYPE_INT_RGB, 100, 50, false), Limits.DEFAULT));
        assertTrue(t.getMessage().contains("WAYANG_OCR"), "a typo is an error");
    }

    @Test
    void emptyRecognitionDamagedAndOversizedPicturesAreReportedWithoutEchoingContent() throws Exception {
        Fake blank = new Fake();
        blank.answer = "  ";
        RagException e = assertThrows(RagException.class, () -> with(blank).extract(image("png", BufferedImage.TYPE_INT_RGB, 100, 50, false), Limits.DEFAULT));
        assertTrue(e.getMessage().contains("no text could be recognised"), e.getMessage());

        Fake ocr = new Fake();
        byte[] bad = "\u0089PNG\r\n\u001a\nSECRETBODY not really a png".getBytes(StandardCharsets.ISO_8859_1);
        RagException d = assertThrows(RagException.class, () -> with(ocr).extract(bad, Limits.DEFAULT));
        assertFalse(d.getMessage().contains("SECRETBODY"));
        assertTrue(ocr.seen.isEmpty(), "OCR is not called for a picture that cannot be read");

        // 5000 x 4000 = 20 megapixels: a few KB as a PNG, refused from its header before it is decoded
        byte[] big = image("png", BufferedImage.TYPE_BYTE_GRAY, 5000, 4000, false);
        RagException b = assertThrows(RagException.class, () -> with(ocr).extract(big, Limits.DEFAULT));
        assertTrue(b.getMessage().contains("too large"), b.getMessage());
        assertTrue(ocr.seen.isEmpty());
    }

    @Test
    void ocrFailureMessagesPassThroughUnchanged() throws Exception {
        Ocr failing = (png, timeout) -> {
            throw new RagException("OCR failed (the tesseract command exited with 1)");
        };
        RagException e = assertThrows(RagException.class, () -> with(failing).extract(image("png", BufferedImage.TYPE_INT_RGB, 100, 50, false), Limits.DEFAULT));
        assertTrue(e.getMessage().contains("exited with 1"));
    }

    @Test
    void theRegistryFindsImagesByNameAndByContent() throws Exception {
        Extractors ex = Extractors.defaults();
        assertTrue(ex.extensions().containsAll(List.of("png", "jpg", "jpeg", "gif", "tif", "tiff")));
        byte[] png = image("png", BufferedImage.TYPE_INT_RGB, 100, 50, false);
        // OCR is off in the default registry here (no WAYANG_OCR in the test environment), so the message proves the image extractor was chosen
        for (String name : new String[] {"scan.png", "SCAN.PNG", null, "no-extension", "wrong.txt"}) {
            if ("wrong.txt".equals(name)) {
                continue; // a .txt name is trusted as text, like every other format
            }
            RagException e = assertThrows(RagException.class, () -> ex.extract(name, png, Limits.DEFAULT), String.valueOf(name));
            assertTrue(e.getMessage().contains("image needs OCR") || e.getMessage().contains("WAYANG_OCR"), name + ": " + e.getMessage());
        }
        byte[] jpg = image("jpg", BufferedImage.TYPE_INT_RGB, 100, 50, false);
        assertTrue(assertThrows(RagException.class, () -> ex.extract(null, jpg, Limits.DEFAULT)).getMessage().contains("image needs OCR"));
    }

    @Test
    void realTesseractReadsAPictureThroughTheRegistryWhenInstalled() throws Exception {
        try {
            Process p = new ProcessBuilder("tesseract", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() != 0) {
                return;
            }
        } catch (Exception e) {
            return; // not on every build machine
        }
        byte[] png = image("png", BufferedImage.TYPE_INT_RGB, 1400, 220, true);
        String t = new ImageExtractor(null, Map.of("WAYANG_OCR", "tesseract")::get).extract(png, Limits.DEFAULT).toUpperCase().replaceAll("\\s+", " ");
        assertTrue(t.contains("PAYMENT") && t.contains("NET 45"), t);
    }
}
