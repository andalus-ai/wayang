package tech.kayys.wayang.rag;

import java.time.Duration;
import java.util.function.Function;

/**
 * Recognises text in a page image. Optional: an extractor uses it only for a page that has no text layer, and only when the operator
 * turned it on (see {@link #fromEnvironment}). The image never leaves the machine with the built-in engine (a local tesseract).
 */
public interface Ocr {

    /** Hard ceilings, whatever the settings say. */
    int MAX_PAGES_CEILING = 100;
    int DEFAULT_MAX_PAGES = 30;

    /**
     * @param png     one page, PNG
     * @param timeout the most this page may take
     * @return the text, possibly empty; never null
     * @throws RagException when recognition fails or is not possible (the message never contains the text)
     */
    String recognise(byte[] png, Duration timeout);

    /**
     * {@code WAYANG_OCR=tesseract} turns it on ({@code off} or unset: no OCR). {@code WAYANG_OCR_COMMAND} is the executable (default
     * {@code tesseract}), {@code WAYANG_OCR_LANGS} the languages (default {@code eng}, for example {@code eng+deu}).
     *
     * @return null when OCR is off
     * @throws RagException for a setting that is not understood: OCR is never silently skipped on a typo
     */
    static Ocr fromEnvironment(Function<String, String> env) {
        String mode = env.apply("WAYANG_OCR");
        if (mode == null || mode.isBlank() || mode.strip().equalsIgnoreCase("off")) {
            return null;
        }
        if (!mode.strip().equalsIgnoreCase("tesseract")) {
            throw new RagException("WAYANG_OCR must be 'tesseract' or 'off'");
        }
        String cmd = env.apply("WAYANG_OCR_COMMAND");
        String langs = env.apply("WAYANG_OCR_LANGS");
        return new TesseractOcr(cmd == null || cmd.isBlank() ? "tesseract" : cmd.strip(), langs == null || langs.isBlank() ? "eng" : langs.strip());
    }

    /** How many pages of one document may be recognised: {@code WAYANG_OCR_MAX_PAGES}, default 30, at most 100. */
    static int maxPages(Function<String, String> env) {
        String v = env.apply("WAYANG_OCR_MAX_PAGES");
        if (v == null || v.isBlank()) {
            return DEFAULT_MAX_PAGES;
        }
        try {
            int n = Integer.parseInt(v.strip());
            if (n < 1 || n > MAX_PAGES_CEILING) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new RagException("WAYANG_OCR_MAX_PAGES must be a number from 1 to " + MAX_PAGES_CEILING);
        }
    }
}
