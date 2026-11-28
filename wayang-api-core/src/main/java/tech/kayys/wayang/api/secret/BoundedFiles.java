package tech.kayys.wayang.api.secret;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a small operator-supplied file (a keyring, a token) without trusting its size: at most {@code max} bytes are read, so a
 * wrong path that points at a huge file or an endless device cannot exhaust memory before a size check would run.
 */
public final class BoundedFiles {

    /** Keyrings, tokens and API keys are a few hundred bytes; 64 KiB is generous. */
    public static final int SMALL = 64 * 1024;

    private BoundedFiles() {
    }

    /** The file as UTF-8 text; an {@link IOException} when it is longer than {@code max} bytes. The path is not echoed. */
    public static String readString(Path file, int max) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = in.readNBytes(max + 1);
            if (b.length > max) {
                throw new IOException("file is larger than " + max + " bytes");
            }
            return new String(b, StandardCharsets.UTF_8);
        }
    }
}
