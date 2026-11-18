package tech.kayys.wayang.rag;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML to light Markdown with a small tag stripper (no browser, no script execution, no resource fetching). Headings become {@code #} lines, list items
 * {@code -} lines; scripts, styles and comments are dropped.
 */
public final class HtmlExtractor implements DocumentExtractor {

    private static final Pattern DROP = Pattern.compile("(?is)<(script|style|noscript|template)\\b.*?</\\1\\s*>|<!--.*?-->");
    private static final Pattern HEADING = Pattern.compile("(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1\\s*>");
    private static final Pattern BLOCK = Pattern.compile("(?i)</?(p|div|section|article|header|footer|main|ul|ol|table|tr|br|blockquote|pre)\\b[^>]*>");
    private static final Pattern LI = Pattern.compile("(?i)<li\\b[^>]*>");
    private static final Pattern CELL = Pattern.compile("(?i)</t[dh]\\s*>");
    private static final Pattern TAG = Pattern.compile("<[^>]{0,2000}>");

    @Override
    public Set<String> extensions() {
        return Set.of("html", "htm", "xhtml");
    }

    @Override
    public String extract(byte[] content, Limits limits) {
        String s = Text.normalise(Text.decode(content));
        s = DROP.matcher(s).replaceAll(" ");
        Matcher h = HEADING.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (h.find()) {
            String inner = TAG.matcher(h.group(2)).replaceAll("").strip();
            h.appendReplacement(sb, Matcher.quoteReplacement("\n" + "#".repeat(Integer.parseInt(h.group(1))) + " " + inner + "\n"));
        }
        h.appendTail(sb);
        s = sb.toString();
        s = LI.matcher(s).replaceAll("\n- ");
        s = CELL.matcher(s).replaceAll(" | ");
        s = BLOCK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        s = decodeEntities(s);
        s = s.replaceAll("[ \\t\\x0B\\f]+", " ").replaceAll(" ?\\n ?", "\n").replaceAll("\\n{3,}", "\n\n").strip();
        Text.checkSize(s, limits);
        return s;
    }

    static String decodeEntities(String s) {
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});").matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String r;
            if (e.startsWith("#x")) {
                r = cp(Integer.parseInt(e.substring(2), 16), m.group());
            } else if (e.startsWith("#")) {
                r = cp(Integer.parseInt(e.substring(1)), m.group());
            } else {
                r = switch (e) {
                    case "amp" -> "&";
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "quot" -> "\"";
                    case "apos" -> "'";
                    case "nbsp" -> " ";
                    default -> m.group();
                };
            }
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String cp(int code, String original) {
        return Character.isValidCodePoint(code) && code != 0 ? new String(Character.toChars(code)) : original;
    }
}
