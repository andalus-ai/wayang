package tech.kayys.wayang.connectors;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Just enough MIME to turn a fetched message into readable text: header unfolding, RFC 2047 words, multipart, base64 / quoted-printable. */
final class MimeText {

    private static final int MAX_DEPTH = 5;
    private static final int MAX_PARTS = 50;
    private static final Pattern WORD = Pattern.compile("=\\?([^?\\s]+)\\?([BbQq])\\?([^?\\s]*)\\?=");

    private MimeText() {
    }

    /** Header name (lower case) to its values in order of appearance, unfolded. */
    static Map<String, List<String>> headers(String block) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        String name = null;
        StringBuilder val = new StringBuilder();
        for (String raw : block.split("\r?\n")) {
            if (raw.isEmpty()) {
                break;
            }
            if ((raw.charAt(0) == ' ' || raw.charAt(0) == '\t') && name != null) {
                val.append(' ').append(raw.trim());
                continue;
            }
            if (name != null) {
                m.computeIfAbsent(name, k -> new ArrayList<>()).add(val.toString().trim());
            }
            int c = raw.indexOf(':');
            if (c <= 0) {
                name = null;
                continue;
            }
            name = raw.substring(0, c).trim().toLowerCase(Locale.ROOT);
            val.setLength(0);
            val.append(raw.substring(c + 1));
        }
        if (name != null) {
            m.computeIfAbsent(name, k -> new ArrayList<>()).add(val.toString().trim());
        }
        return m;
    }

    static String first(Map<String, List<String>> h, String name) {
        List<String> l = h.get(name);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    /** Decodes RFC 2047 encoded-words; anything malformed is left as it was. */
    static String decodeWords(String s) {
        if (s == null || !s.contains("=?")) {
            return s;
        }
        Matcher m = WORD.matcher(s);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean prevWord = false;
        while (m.find()) {
            String between = s.substring(last, m.start());
            if (!(prevWord && between.isBlank())) {
                out.append(between);
            }
            String decoded;
            try {
                Charset cs = charset(m.group(1));
                byte[] raw = m.group(2).equalsIgnoreCase("B")
                        ? Base64.getDecoder().decode(m.group(3))
                        : qp(m.group(3).replace('_', ' '), true);
                decoded = new String(raw, cs);
            } catch (IllegalArgumentException e) {
                decoded = m.group();
            }
            out.append(decoded);
            last = m.end();
            prevWord = true;
        }
        return out.append(s.substring(last)).toString();
    }

    static String param(String headerValue, String name) {
        if (headerValue == null) {
            return null;
        }
        Matcher m = Pattern.compile("(?i)[;\\s]" + name + "\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))").matcher(headerValue);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
    }

    /** Best plain-text rendering of a message body; HTML only when there is no text/plain, with tags stripped. */
    static String text(String contentType, String transferEncoding, byte[] body) {
        String[] html = new String[1];
        String t = walk(contentType, transferEncoding, body, 0, new int[1], html);
        if (t != null && !t.isBlank()) {
            return t;
        }
        return html[0] == null ? "" : stripHtml(html[0]);
    }

    private static String walk(String contentType, String cte, byte[] body, int depth, int[] parts, String[] htmlFallback) {
        String ct = contentType == null ? "text/plain" : contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("multipart/") && depth < MAX_DEPTH) {
            String boundary = param(contentType, "boundary");
            if (boundary == null || boundary.isEmpty()) {
                return null;
            }
            for (byte[] part : split(body, boundary)) {
                if (++parts[0] > MAX_PARTS) {
                    return null;
                }
                int[] cut = headerEnd(part);
                Map<String, List<String>> ph = headers(new String(part, 0, cut[0], StandardCharsets.ISO_8859_1));
                byte[] pb = java.util.Arrays.copyOfRange(part, cut[1], part.length);
                String r = walk(first(ph, "content-type"), first(ph, "content-transfer-encoding"), pb, depth + 1, parts, htmlFallback);
                if (r != null && !r.isBlank()) {
                    return r;
                }
            }
            return null;
        }
        byte[] raw = decode(cte, body);
        String text = new String(raw, charset(param(contentType, "charset")));
        if (ct.startsWith("text/html")) {
            if (htmlFallback[0] == null) {
                htmlFallback[0] = text;
            }
            return null;
        }
        return ct.startsWith("text/plain") || contentType == null ? text : null;
    }

    private static int[] headerEnd(byte[] a) {
        for (int i = 0; i + 1 < a.length; i++) {
            if (a[i] == '\n' && a[i + 1] == '\n') {
                return new int[] {i, i + 2};
            }
            if (a[i] == '\r' && i + 3 < a.length && a[i + 1] == '\n' && a[i + 2] == '\r' && a[i + 3] == '\n') {
                return new int[] {i, i + 4};
            }
        }
        return new int[] {a.length, a.length};
    }

    /** The parts between {@code --boundary} delimiter lines (preamble and epilogue dropped). */
    private static List<byte[]> split(byte[] body, String boundary) {
        List<byte[]> out = new ArrayList<>();
        String s = new String(body, StandardCharsets.ISO_8859_1);
        String delim = "--" + boundary;
        int idx = s.indexOf(delim);
        while (idx >= 0) {
            int lineEnd = s.indexOf('\n', idx);
            if (lineEnd < 0 || s.startsWith("--", idx + delim.length())) {
                break;
            }
            int next = s.indexOf("\n" + delim, lineEnd);
            int end = next < 0 ? s.length() : next;
            {
                String chunk = s.substring(lineEnd + 1, Math.max(lineEnd + 1, end));
                if (chunk.endsWith("\r")) {
                    chunk = chunk.substring(0, chunk.length() - 1);
                }
                out.add(chunk.getBytes(StandardCharsets.ISO_8859_1));
            }
            idx = next < 0 ? -1 : next + 1;
        }
        return out;
    }

    private static byte[] decode(String cte, byte[] body) {
        String e = cte == null ? "" : cte.trim().toLowerCase(Locale.ROOT);
        try {
            if (e.equals("base64")) {
                return Base64.getMimeDecoder().decode(body);
            }
            if (e.equals("quoted-printable")) {
                return qp(new String(body, StandardCharsets.ISO_8859_1), false);
            }
        } catch (IllegalArgumentException ex) {
            return body;
        }
        return body;
    }

    private static byte[] qp(String s, boolean word) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' && i + 1 < s.length() && (s.charAt(i + 1) == '\n' || s.charAt(i + 1) == '\r')) {
                i += s.charAt(i + 1) == '\r' && i + 2 < s.length() && s.charAt(i + 2) == '\n' ? 2 : 1;
            } else if (c == '=' && i + 2 < s.length() && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                o.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)));
                i += 2;
            } else {
                o.write(c & 0xFF);
            }
        }
        return o.toByteArray();
    }

    private static int hex(char c) {
        return Character.digit(c, 16);
    }

    private static Charset charset(String name) {
        try {
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(name.trim());
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    static String stripHtml(String h) {
        return h.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("(?s)<[^>]*>", " ")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
                .replaceAll("[ \\t]+", " ").trim();
    }
}
