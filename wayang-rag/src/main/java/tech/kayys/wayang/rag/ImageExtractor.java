package tech.kayys.wayang.rag;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Function;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Text in a picture (a photographed or scanned page, a screenshot) through {@link Ocr}. It needs OCR to be turned on
 * ({@code WAYANG_OCR=tesseract}); without it the file is refused with a message that says so. The size is read from the header and
 * checked before the picture is decoded, a transparent picture is flattened onto white, and what reaches OCR is a plain PNG made here,
 * never the original bytes. Only the first frame of an animation is read. Recognised text can contain mistakes.
 */
public final class ImageExtractor implements DocumentExtractor {

    static final long MAX_PIXELS = 16_000_000L;
    static final int MAX_SIDE = 20_000;
    static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final Ocr fixedOcr;
    private final Function<String, String> env;

    public ImageExtractor() {
        this(null, System::getenv);
    }

    /** For tests and embedding: a given engine (null = decide from the environment) and environment. */
    ImageExtractor(Ocr ocr, Function<String, String> env) {
        this.fixedOcr = ocr;
        this.env = env;
    }

    @Override
    public Set<String> extensions() {
        return Set.of("png", "jpg", "jpeg", "gif", "tif", "tiff");
    }

    @Override
    public String extract(byte[] content, Limits limits) {
        Ocr ocr = fixedOcr != null ? fixedOcr : Ocr.fromEnvironment(env);
        if (ocr == null) {
            throw new RagException("an image needs OCR: the operator can enable it with WAYANG_OCR=tesseract (tesseract must be installed)");
        }
        byte[] png = normalise(content);
        String text = ocr.recognise(png, TIMEOUT);
        if (text == null || text.isBlank()) {
            throw new RagException("no text could be recognised in the image");
        }
        return text.strip();
    }

    private static byte[] normalise(byte[] content) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new RagException("the image is damaged or not a supported picture (png, jpg, gif, tiff)");
            }
            ImageReader r = readers.next();
            try {
                r.setInput(in, true, true);
                long w = r.getWidth(0);
                long h = r.getHeight(0);
                if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE || w * h > MAX_PIXELS) {
                    throw new RagException("the image is too large to read (at most " + (MAX_PIXELS / 1_000_000) + " megapixels)");
                }
                BufferedImage src = r.read(0);
                BufferedImage flat = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = flat.createGraphics();
                try {
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, flat.getWidth(), flat.getHeight());
                    g.drawImage(src, 0, 0, null);
                } finally {
                    g.dispose();
                }
                try (ByteArrayOutputStream o = new ByteArrayOutputStream()) {
                    ImageIO.write(flat, "png", o);
                    return o.toByteArray();
                }
            } finally {
                r.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof RagException re) {
                throw re;
            }
            throw new RagException("the image is damaged or not a supported picture (png, jpg, gif, tiff)");
        }
    }
}
