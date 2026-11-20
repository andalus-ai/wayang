package tech.kayys.wayang.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class OcrTest {

    private static byte[] png(String text) throws Exception {
        System.setProperty("java.awt.headless", "true");
        BufferedImage img = new BufferedImage(1400, 220, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
        g.drawString(text, 40, 130);
        g.dispose();
        try (ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", o);
            return o.toByteArray();
        }
    }

    private static boolean tesseractInstalled() {
        try {
            Process p = new ProcessBuilder("tesseract", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Path script(String body) throws Exception {
        Path f = Files.createTempFile("fake-ocr", ".sh");
        Files.writeString(f, "#!/bin/sh\n" + body + "\n");
        f.toFile().setExecutable(true);
        f.toFile().deleteOnExit();
        return f;
    }

    private static boolean posix() {
        return !System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Test
    void offUnsetAndTypoAreDistinguished() {
        assertNull(Ocr.fromEnvironment(Map.<String, String>of()::get));
        assertNull(Ocr.fromEnvironment(Map.of("WAYANG_OCR", "off")::get));
        assertNull(Ocr.fromEnvironment(Map.of("WAYANG_OCR", " ")::get));
        assertTrue(Ocr.fromEnvironment(Map.of("WAYANG_OCR", "Tesseract")::get) instanceof TesseractOcr);
        RagException e = assertThrows(RagException.class, () -> Ocr.fromEnvironment(Map.of("WAYANG_OCR", "tesseractt")::get));
        assertTrue(e.getMessage().contains("WAYANG_OCR"), "a typo is an error, never silently no OCR");
    }

    @Test
    void settingsAreValidated() {
        assertEquals(30, Ocr.maxPages(Map.<String, String>of()::get));
        assertEquals(5, Ocr.maxPages(Map.of("WAYANG_OCR_MAX_PAGES", "5")::get));
        for (String bad : new String[] {"0", "101", "x", "-1", "1.5"}) {
            assertThrows(RagException.class, () -> Ocr.maxPages(Map.of("WAYANG_OCR_MAX_PAGES", bad)::get), bad);
        }
        for (String langs : new String[] {"", "eng;rm -rf /", "eng deu", "eng+", "../eng", "a", "eng+deu+fra+spa+ita"}) {
            assertThrows(RagException.class, () -> new TesseractOcr("tesseract", langs), langs);
        }
        assertThrows(RagException.class, () -> new TesseractOcr("tess\neract", "eng"));
        assertThrows(RagException.class, () -> new TesseractOcr(" ", "eng"));
        new TesseractOcr("tesseract", "eng+deu");
    }

    @Test
    void aMissingProgramIsAClearErrorAndNothingIsLeftBehind() throws Exception {
        RagException e = assertThrows(RagException.class,
                () -> new TesseractOcr("/no/such/tesseract-binary", "eng").recognise(png("HELLO"), Duration.ofSeconds(5)));
        assertTrue(e.getMessage().contains("could not be run"), e.getMessage());
        assertThrows(RagException.class, () -> new TesseractOcr("tesseract", "eng").recognise(new byte[0], Duration.ofSeconds(5)));
    }

    @Test
    void aFailingProgramAndASlowOneAreReportedWithoutOutput() throws Exception {
        if (!posix()) {
            return;
        }
        RagException f = assertThrows(RagException.class,
                () -> new TesseractOcr(script("echo SECRET-PAGE-TEXT; echo SECRET >&2; exit 3").toString(), "eng").recognise(png("A"), Duration.ofSeconds(10)));
        assertTrue(f.getMessage().contains("exited with 3"), f.getMessage());
        assertFalse(f.getMessage().contains("SECRET"));
        long t0 = System.nanoTime();
        RagException s = assertThrows(RagException.class,
                () -> new TesseractOcr(script("sleep 30").toString(), "eng").recognise(png("A"), Duration.ofMillis(700)));
        assertTrue(s.getMessage().contains("longer than"), s.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toSeconds() < 10, "a slow program is stopped, not waited for");
    }

    @Test
    void textTheProgramWritesIsReadBackAndALargeOutputIsRefused() throws Exception {
        if (!posix()) {
            return;
        }
        // fake tesseract: $2 is the output base; writes <base>.txt
        assertEquals("fake text", new TesseractOcr(script("printf '  fake text \\n' > \"$2.txt\"").toString(), "eng")
                .recognise(png("A"), Duration.ofSeconds(10)));
        assertEquals("", new TesseractOcr(script("exit 0").toString(), "eng").recognise(png("A"), Duration.ofSeconds(10)), "no output file means no text");
        assertThrows(RagException.class, () -> new TesseractOcr(script("head -c 3000000 /dev/zero | tr '\\0' 'a' > \"$2.txt\"").toString(), "eng")
                .recognise(png("A"), Duration.ofSeconds(20)));
    }

    @Test
    void realTesseractReadsAnImageWhenItIsInstalled() throws Exception {
        if (!tesseractInstalled()) {
            return; // not part of every build machine; the cases above cover the wrapper without it
        }
        String text = new TesseractOcr("tesseract", "eng").recognise(png("TERMINATION NOTICE 30 DAYS"), Duration.ofSeconds(60));
        String flat = text.toUpperCase().replaceAll("\\s+", " ");
        assertTrue(flat.contains("TERMINATION") && flat.contains("NOTICE") && flat.contains("30"), text);
    }
}
