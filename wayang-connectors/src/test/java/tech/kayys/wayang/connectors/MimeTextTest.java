package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MimeTextTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void unfoldsHeadersAndKeepsOrder() {
        Map<String, List<String>> h = MimeText.headers("Subject: a\r\n b\r\n\tc\r\nX-A: 1\r\nX-A: 2\r\n\r\nbody: ignored");
        assertEquals("a b c", MimeText.first(h, "subject"));
        assertEquals(List.of("1", "2"), h.get("x-a"));
        assertNull(h.get("body"));
    }

    @Test
    void decodesEncodedWords() {
        assertEquals("Café", MimeText.decodeWords("=?UTF-8?Q?Caf=C3=A9?="));
        assertEquals("Halo ☕ dunia", MimeText.decodeWords("=?UTF-8?B?SGFsbyDimJU=?= dunia"));
        assertEquals("ab", MimeText.decodeWords("=?UTF-8?Q?a?= =?UTF-8?Q?b?="), "whitespace between adjacent words is dropped");
        assertEquals("=?x?B?!!!?=", MimeText.decodeWords("=?x?B?!!!?="), "garbage stays as is");
        assertEquals("plain", MimeText.decodeWords("plain"));
    }

    @Test
    void plainBase64AndCharsets() {
        assertEquals("hello", MimeText.text("text/plain", "base64", b("aGVsbG8=")));
        assertEquals("é", MimeText.text("text/plain; charset=iso-8859-1", null, new byte[] {(byte) 0xE9}));
        assertEquals("x", MimeText.text("text/plain; charset=nonsense", null, b("x")), "unknown charset falls back to UTF-8");
        assertEquals("raw", MimeText.text(null, null, b("raw")));
    }

    @Test
    void htmlOnlyIsStripped() {
        String t = MimeText.text("text/html", null, b("<style>p{}</style><p>Hi &amp; <b>bye</b></p><script>x()</script>"));
        assertEquals("Hi & bye", t);
    }

    @Test
    void nestedMultipartPrefersPlainText() {
        String inner = "--in\r\nContent-Type: text/plain\r\n\r\ninner text\r\n--in--\r\n";
        String outer = "preamble\r\n--out\r\nContent-Type: multipart/alternative; boundary=in\r\n\r\n" + inner
                + "--out\r\nContent-Type: application/pdf\r\n\r\n%PDF\r\n--out--\r\nepilogue";
        assertEquals("inner text", MimeText.text("multipart/mixed; boundary=\"out\"", null, b(outer)));
    }

    @Test
    void hostileInputDoesNotBlowUp() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            deep.append("--b\r\nContent-Type: text/plain\r\n\r\n\r\n");
        }
        assertEquals("", MimeText.text("multipart/mixed; boundary=b", null, b(deep.toString())));
        assertEquals("", MimeText.text("multipart/mixed", null, b("x")));
        assertDoesNotThrow(() -> MimeText.text("text/plain", "quoted-printable", b("=ZZ=\r\n=4")));
    }
}
