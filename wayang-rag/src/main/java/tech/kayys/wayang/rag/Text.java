package tech.kayys.wayang.rag;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Character decoding and size checks shared by the plain-text extractors. */
final class Text {

    private Text() {
    }

    static String decode(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        return new String(b, Charset.forName("UTF-8"));
    }

    static void checkSize(String s, Limits limits) {
        if (s.length() > limits.maxChars()) {
            throw new RagException("the file holds more than " + limits.maxChars() + " characters of text");
        }
    }

    static String normalise(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "");
    }
}
