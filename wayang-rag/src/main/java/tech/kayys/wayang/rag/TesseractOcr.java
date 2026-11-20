package tech.kayys.wayang.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs a local {@code tesseract} on one page image. The program is started directly (no shell) with fixed arguments, reads a private
 * temporary file and writes another; its output and errors are discarded, and the text it produced is read back up to a size limit.
 * Nothing goes over the network. The command and languages come from the operator, never from a document.
 */
public final class TesseractOcr implements Ocr {

    static final Pattern LANGS = Pattern.compile("[A-Za-z0-9_]{2,16}(\\+[A-Za-z0-9_]{2,16}){0,3}");
    static final int MAX_PNG = 40 << 20;
    static final long MAX_TEXT = 2L << 20;

    private final String command;
    private final String langs;

    public TesseractOcr(String command, String langs) {
        if (command == null || command.isBlank() || command.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new RagException("WAYANG_OCR_COMMAND is not a valid command");
        }
        if (langs == null || !LANGS.matcher(langs).matches()) {
            throw new RagException("WAYANG_OCR_LANGS must be language codes joined by +, for example eng or eng+deu");
        }
        this.command = command;
        this.langs = langs;
    }

    @Override
    public String recognise(byte[] png, Duration timeout) {
        if (png == null || png.length == 0 || png.length > MAX_PNG) {
            throw new RagException("the page image is empty or too large for OCR");
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("wayang-ocr");
            Path in = dir.resolve("page.png");
            Files.write(in, png);
            Path outBase = dir.resolve("text");
            ProcessBuilder pb = new ProcessBuilder(List.of(command, in.toString(), outBase.toString(), "-l", langs));
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            Map<String, String> env = pb.environment();
            env.put("OMP_THREAD_LIMIT", "1"); // one page, one core: do not let a busy release starve itself
            Process p = pb.start();
            if (!p.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new RagException("OCR took longer than " + Math.max(1, timeout.toSeconds()) + " seconds for a page");
            }
            if (p.exitValue() != 0) {
                throw new RagException("OCR failed (the tesseract command exited with " + p.exitValue() + "); check WAYANG_OCR_LANGS and that the language data is installed");
            }
            Path txt = dir.resolve("text.txt");
            if (!Files.exists(txt)) {
                return "";
            }
            if (Files.size(txt) > MAX_TEXT) {
                throw new RagException("OCR produced more text than expected for one page");
            }
            return new String(Files.readAllBytes(txt), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new RagException("OCR is on but the tesseract command could not be run; install tesseract or set WAYANG_OCR=off");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagException("OCR was interrupted");
        } finally {
            if (dir != null) {
                deleteQuietly(dir);
            }
        }
    }

    private static void deleteQuietly(Path dir) {
        try (var files = Files.list(dir)) {
            files.forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                    // a temp file left behind is not worth failing the page for
                }
            });
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // same
        }
    }
}
