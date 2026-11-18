package tech.kayys.wayang.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The built-in chunking strategies. Every chunk's text is non-blank; sizes are in characters and bounded. */
public final class Chunkers {

    public static final int MAX_CHARS = 20_000;

    private Chunkers() {
    }

    /** Fixed windows with overlap, cut at the last whitespace inside the window so words stay whole. Best for unstructured prose. */
    public static Chunker fixed(int size, int overlap) {
        check("size", size, 50, MAX_CHARS);
        if (overlap < 0 || overlap >= size / 2 + 1) {
            throw new RagException("overlap must be between 0 and half the size");
        }
        return new Chunker() {
            public String type() {
                return "fixed";
            }

            public List<Chunk> chunk(Document d) {
                List<Chunk> out = new ArrayList<>();
                for (String piece : window(d.text().strip(), size, overlap)) {
                    out.add(newChunk(d, out.size(), piece, "", null));
                }
                return out;
            }
        };
    }

    /** Whole paragraphs packed up to a size; a paragraph longer than the size is cut by words. Good default for articles and notes. */
    public static Chunker paragraph(int maxChars) {
        check("maxChars", maxChars, 100, MAX_CHARS);
        return new Chunker() {
            public String type() {
                return "paragraph";
            }

            public List<Chunk> chunk(Document d) {
                List<Chunk> out = new ArrayList<>();
                for (String piece : pack(paragraphs(d.text()), maxChars)) {
                    out.add(newChunk(d, out.size(), piece, "", null));
                }
                return out;
            }
        };
    }

    /** One chunk per Markdown section (so per Word heading, slide or sheet), packed up to a size, each prefixed with its heading path. Keeps a section's context with it. */
    public static Chunker heading(int maxChars) {
        check("maxChars", maxChars, 200, MAX_CHARS);
        return new Chunker() {
            public String type() {
                return "heading";
            }

            public List<Chunk> chunk(Document d) {
                List<Chunk> out = new ArrayList<>();
                for (Section s : sections(d.text())) {
                    String prefix = s.path.isEmpty() ? "" : s.path + "\n";
                    for (String piece : pack(paragraphs(s.body), Math.max(100, maxChars - prefix.length()))) {
                        out.add(newChunk(d, out.size(), prefix + piece, s.path, null));
                    }
                }
                return out;
            }
        };
    }

    private static final Pattern CLAUSE = Pattern.compile(
            "^(?:(?:article|section|clause|chapter|part|rule|pasal|bab|bagian|ayat|artikel|§)\\s*[\\dIVXLCivxlc]+[.:)]?(?:\\s.*)?|\\d+(?:\\.\\d+){0,4}[.)]\\s+\\S.*|\\([a-z0-9]{1,4}\\)\\s+\\S.*)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * For statutes, contracts and policies: starts a new chunk at each clause marker ({@code Article 5}, {@code Section 2.1}, {@code Pasal 3}, {@code 4.2.1 Title},
     * {@code (a) ...}), and prefixes a sub-clause with the clause it belongs to, so a retrieved "(b)" is not cut loose from its article.
     * Text without markers falls back to paragraphs.
     */
    public static Chunker clause(int maxChars) {
        check("maxChars", maxChars, 200, MAX_CHARS);
        return new Chunker() {
            public String type() {
                return "clause";
            }

            public List<Chunk> chunk(Document d) {
                List<Chunk> out = new ArrayList<>();
                String top = "";
                StringBuilder cur = new StringBuilder();
                String curHead = "";
                for (String line : d.text().split("\n", -1)) {
                    String t = line.strip();
                    boolean marker = !t.isEmpty() && t.length() <= 300 && CLAUSE.matcher(t).matches();
                    boolean sub = marker && t.startsWith("(");
                    if (marker && cur.length() > 0) {
                        flush(d, out, cur, curHead, maxChars);
                    }
                    if (marker) {
                        if (!sub) {
                            top = firstWords(t);
                        }
                        curHead = sub && !top.isEmpty() ? top + " > " + firstWords(t) : firstWords(t);
                        cur.append(sub && !top.isEmpty() ? top + " " : "");
                    }
                    if (!t.isEmpty() || cur.length() > 0) {
                        cur.append(line).append('\n');
                    }
                }
                flush(d, out, cur, curHead, maxChars);
                return out;
            }
        };
    }

    /**
     * Small chunks for precise matching that carry their larger parent window as {@code context}; with parent expansion on, retrieval returns the window,
     * so the model sees the surrounding text of a precise hit.
     */
    public static Chunker parentChild(int childChars, int parentChars) {
        check("childChars", childChars, 50, MAX_CHARS);
        check("parentChars", parentChars, childChars, MAX_CHARS);
        return new Chunker() {
            public String type() {
                return "parent-child";
            }

            public List<Chunk> chunk(Document d) {
                List<Chunk> out = new ArrayList<>();
                for (String parent : pack(paragraphs(d.text()), parentChars)) {
                    for (String child : pack(paragraphs(parent), childChars)) {
                        out.add(newChunk(d, out.size(), child, "", parent));
                    }
                }
                return out;
            }
        };
    }

    // ---- shared helpers ----

    private static void flush(Document d, List<Chunk> out, StringBuilder cur, String head, int maxChars) {
        String text = cur.toString().strip();
        cur.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        for (String piece : pack(paragraphs(text), maxChars)) {
            out.add(newChunk(d, out.size(), piece, head, null));
        }
    }

    private static String firstWords(String s) {
        String t = s.strip();
        return t.length() <= 80 ? t : t.substring(0, 80).strip() + "…";
    }

    static Chunk newChunk(Document d, int ordinal, String text, String heading, String context) {
        return new Chunk(d.id() + "#" + ordinal, d.id(), d.name(), ordinal, text.strip(), heading, context, d.metadata());
    }

    private static void check(String what, int v, int min, int max) {
        if (v < min || v > max) {
            throw new RagException(what + " must be between " + min + " and " + max);
        }
    }

    static List<String> paragraphs(String text) {
        List<String> out = new ArrayList<>();
        for (String p : text.split("\\n\\s*\\n")) {
            String t = p.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Packs paragraphs into pieces of at most max characters; an over-long paragraph is cut by words. */
    static List<String> pack(List<String> paras, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String p : paras) {
            if (p.length() > max) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.addAll(window(p, max, 0));
            } else if (cur.length() + p.length() + 2 > max && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
                cur.append(p);
            } else {
                if (cur.length() > 0) {
                    cur.append("\n\n");
                }
                cur.append(p);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** Windows of at most size characters with the given overlap, cut at whitespace when there is one in the second half of the window. */
    static List<String> window(String text, int size, int overlap) {
        List<String> out = new ArrayList<>();
        int n = text.length();
        int start = 0;
        while (start < n) {
            int end = Math.min(n, start + size);
            if (end < n) {
                int cut = lastSpace(text, start + size / 2, end);
                if (cut > start) {
                    end = cut;
                }
            }
            String piece = text.substring(start, end).strip();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            if (end >= n) {
                break;
            }
            int next = end - overlap;
            start = Math.max(next, start + 1);
        }
        return out;
    }

    private static int lastSpace(String s, int from, int to) {
        for (int i = to; i > from; i--) {
            if (Character.isWhitespace(s.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    // ---- sections for the heading chunker ----

    private record Section(String path, String body) {
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    private static List<Section> sections(String text) {
        List<Section> out = new ArrayList<>();
        String[] stack = new String[7];
        StringBuilder body = new StringBuilder();
        String path = "";
        for (String line : text.split("\n", -1)) {
            Matcher m = HEADING.matcher(line.strip());
            if (m.matches()) {
                if (body.toString().strip().length() > 0) {
                    out.add(new Section(path, body.toString()));
                }
                body.setLength(0);
                int level = m.group(1).length();
                stack[level] = m.group(2).strip();
                for (int i = level + 1; i < stack.length; i++) {
                    stack[i] = null;
                }
                List<String> parts = new ArrayList<>();
                for (int i = 1; i < stack.length; i++) {
                    if (stack[i] != null) {
                        parts.add(stack[i]);
                    }
                }
                path = String.join(" > ", parts);
            } else {
                body.append(line).append('\n');
            }
        }
        if (body.toString().strip().length() > 0) {
            out.add(new Section(path, body.toString()));
        }
        return out;
    }

    /** Builds a chunker from configuration, e.g. {@code {type: heading, maxChars: 1500}}. */
    public static Chunker fromConfig(Map<String, Object> c) {
        String type = c.get("type") instanceof String s ? s : "paragraph";
        String unknown = c.keySet().stream().filter(k -> !List.of("type", "size", "overlap", "maxChars", "childChars", "parentChars").contains(k)).findFirst().orElse(null);
        if (unknown != null) {
            throw new RagException("chunking has an unknown setting '" + unknown + "'");
        }
        return switch (type) {
            case "fixed" -> fixed(intOf(c, "size", 800), intOf(c, "overlap", 100));
            case "paragraph" -> paragraph(intOf(c, "maxChars", 1000));
            case "heading" -> heading(intOf(c, "maxChars", 1500));
            case "clause" -> clause(intOf(c, "maxChars", 2000));
            case "parent-child" -> parentChild(intOf(c, "childChars", 300), intOf(c, "parentChars", 1500));
            default -> throw new RagException("unknown chunking type '" + type + "' (fixed, paragraph, heading, clause, parent-child)");
        };
    }

    static int intOf(Map<String, Object> c, String key, int dflt) {
        Object v = c.get(key);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        throw new RagException("'" + key + "' must be a whole number");
    }
}
