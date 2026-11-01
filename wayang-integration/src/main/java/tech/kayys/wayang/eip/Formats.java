package tech.kayys.wayang.eip;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Text format conversion for message bodies: JSON, CSV and XML to and from the JSON-like model
 * (Map / List / String / BigDecimal / Boolean / null). All parsers are size- and depth-bounded; XML
 * forbids DOCTYPE (no XXE, no entity expansion).
 */
public final class Formats {

    public static final int MAX_CHARS = 5_000_000;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_ROWS = 100_000;

    private Formats() {
    }

    public static Object parse(String format, String text, Map<String, Object> opts) {
        if (text == null) {
            throw new EipException("Cannot parse a null body as " + format);
        }
        if (text.length() > MAX_CHARS) {
            throw new EipException(format + " input exceeds " + MAX_CHARS + " characters");
        }
        return switch (format) {
            case "json" -> new Json(text).parse();
            case "csv" -> csvParse(text, opts);
            case "xml" -> xmlParse(text);
            default -> throw new EipException("Unknown format '" + format + "' (json, csv, xml)");
        };
    }

    public static String serialize(String format, Object body, Map<String, Object> opts) {
        String s = switch (format) {
            case "json" -> {
                StringBuilder b = new StringBuilder();
                jsonWrite(b, body, 0);
                yield b.toString();
            }
            case "csv" -> csvWrite(body, opts);
            case "xml" -> xmlWrite(body);
            default -> throw new EipException("Unknown format '" + format + "' (json, csv, xml)");
        };
        if (s.length() > MAX_CHARS) {
            throw new EipException(format + " output exceeds " + MAX_CHARS + " characters");
        }
        return s;
    }

    public static String contentType(String format) {
        return switch (format) {
            case "json" -> "application/json";
            case "csv" -> "text/csv";
            default -> "application/xml";
        };
    }

    // ---------------- JSON ----------------

    private static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object parse() {
            ws();
            Object v = value(0);
            ws();
            if (i != s.length()) {
                throw err("Trailing content");
            }
            return v;
        }

        private EipException err(String m) {
            return new EipException("Invalid JSON at offset " + i + ": " + m);
        }

        private void ws() {
            while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw err("nesting deeper than " + MAX_DEPTH);
            }
            if (i >= s.length()) {
                throw err("unexpected end");
            }
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (peek('}')) {
                    return m;
                }
                while (true) {
                    ws();
                    if (i >= s.length() || s.charAt(i) != '"') {
                        throw err("expected string key");
                    }
                    String k = str();
                    ws();
                    expect(':');
                    ws();
                    m.put(k, value(depth + 1));
                    ws();
                    if (peek('}')) {
                        return m;
                    }
                    expect(',');
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (peek(']')) {
                    return l;
                }
                while (true) {
                    ws();
                    l.add(value(depth + 1));
                    ws();
                    if (peek(']')) {
                        return l;
                    }
                    expect(',');
                }
            }
            if (c == '"') {
                return str();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (st == i) {
                throw err("unexpected '" + c + "'");
            }
            try {
                return new BigDecimal(s.substring(st, i));
            } catch (NumberFormatException e) {
                i = st;
                throw err("bad number");
            }
        }

        private boolean peek(char c) {
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw err("expected '" + c + "'");
            }
        }

        private String str() {
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw err("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c < 0x20) {
                    throw err("control character in string");
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    throw err("bad escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw err("bad \\u escape");
                        }
                        try {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw err("bad \\u escape");
                        }
                        i += 4;
                    }
                    default -> throw err("bad escape \\" + e);
                }
            }
        }
    }

    static void jsonWrite(StringBuilder b, Object v, int depth) {
        if (depth > MAX_DEPTH) {
            throw new EipException("Value nested deeper than " + MAX_DEPTH);
        }
        if (v == null) {
            b.append("null");
        } else if (v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof BigDecimal d) {
            b.append(d.stripTrailingZeros().scale() < 0 ? d.toPlainString() : d.stripTrailingZeros().toPlainString());
        } else if (v instanceof Number n) {
            b.append(new BigDecimal(n.toString()).toPlainString());
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                jsonStr(b, String.valueOf(e.getKey()));
                b.append(':');
                jsonWrite(b, e.getValue(), depth + 1);
            }
            b.append('}');
        } else if (v instanceof List<?> l) {
            b.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) {
                    b.append(',');
                }
                jsonWrite(b, l.get(k), depth + 1);
            }
            b.append(']');
        } else {
            jsonStr(b, tech.kayys.wayang.expression.Values.asString(v));
        }
    }

    private static void jsonStr(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }

    // ---------------- CSV ----------------

    private static char delim(Map<String, Object> opts) {
        Object d = opts.get("delimiter");
        if (d == null) {
            return ',';
        }
        String s = String.valueOf(d);
        if (s.equals("\\t")) {
            return '\t';
        }
        if (s.length() != 1 || s.equals("\"") || s.equals("\n") || s.equals("\r")) {
            throw new EipException("delimiter must be one character other than a quote or newline");
        }
        return s.charAt(0);
    }

    static List<List<String>> csvRows(String t, char d) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            if (quoted) {
                if (c == '"') {
                    if (k + 1 < t.length() && t.charAt(k + 1) == '"') {
                        cell.append('"');
                        k++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
                any = true;
            } else if (c == d) {
                row.add(cell.toString());
                cell.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && k + 1 < t.length() && t.charAt(k + 1) == '\n') {
                    k++;
                }
                if (any || cell.length() > 0 || !row.isEmpty()) {
                    row.add(cell.toString());
                    rows.add(row);
                    if (rows.size() > MAX_ROWS) {
                        throw new EipException("CSV has more than " + MAX_ROWS + " rows");
                    }
                }
                row = new ArrayList<>();
                cell.setLength(0);
                any = false;
            } else {
                cell.append(c);
            }
        }
        if (quoted) {
            throw new EipException("CSV has an unterminated quoted field");
        }
        if (any || cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private static Object csvParse(String text, Map<String, Object> opts) {
        char d = delim(opts);
        boolean header = !Boolean.FALSE.equals(opts.get("header")) && !"false".equals(String.valueOf(opts.get("header")));
        String t = text.startsWith("﻿") ? text.substring(1) : text;
        List<List<String>> rows = csvRows(t, d);
        if (!header) {
            return new ArrayList<Object>(rows);
        }
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> h = rows.get(0);
        if (new LinkedHashSet<>(h).size() != h.size()) {
            throw new EipException("CSV header has duplicate column names");
        }
        List<Object> out = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.size() != h.size()) {
                throw new EipException("CSV row " + (r + 1) + " has " + row.size() + " fields, header has " + h.size());
            }
            Map<String, Object> m = new LinkedHashMap<>();
            for (int c = 0; c < h.size(); c++) {
                m.put(h.get(c), row.get(c));
            }
            out.add(m);
        }
        return out;
    }

    private static String csvWrite(Object body, Map<String, Object> opts) {
        char d = delim(opts);
        boolean safe = !Boolean.FALSE.equals(opts.get("escapeFormulas"));
        if (!(body instanceof List<?> l)) {
            throw new EipException("CSV serialization needs a list but got " + (body == null ? "null" : body.getClass().getSimpleName()));
        }
        StringBuilder b = new StringBuilder();
        if (!l.isEmpty() && l.get(0) instanceof Map) {
            Set<String> cols = new LinkedHashSet<>();
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> m)) {
                    throw new EipException("CSV rows must all be objects or all be lists");
                }
                m.keySet().forEach(k -> cols.add(String.valueOf(k)));
            }
            csvLine(b, new ArrayList<Object>(cols), d, safe);
            for (Object o : l) {
                Map<?, ?> m = (Map<?, ?>) o;
                List<Object> row = new ArrayList<>();
                for (String c : cols) {
                    row.add(m.get(c));
                }
                csvLine(b, row, d, safe);
            }
        } else {
            for (Object o : l) {
                if (!(o instanceof List<?> r)) {
                    throw new EipException("CSV rows must all be objects or all be lists");
                }
                csvLine(b, r, d, safe);
            }
        }
        return b.toString();
    }

    private static void csvLine(StringBuilder b, List<?> cells, char d, boolean safe) {
        for (int k = 0; k < cells.size(); k++) {
            if (k > 0) {
                b.append(d);
            }
            Object v = cells.get(k);
            if (v instanceof Map || v instanceof List) {
                throw new EipException("CSV cells must be scalars; flatten nested values first");
            }
            String s = v == null ? "" : v instanceof BigDecimal n ? n.stripTrailingZeros().toPlainString()
                    : tech.kayys.wayang.expression.Values.asString(v);
            if (safe && v instanceof String && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
                s = "'" + s; // spreadsheet formula injection guard
            }
            if (s.indexOf(d) >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
                s = "\"" + s.replace("\"", "\"\"") + "\"";
            }
            b.append(s);
        }
        b.append("\r\n");
    }

    // ---------------- XML ----------------
    // Model: element -> {"@attr": "v", "#text": "t", "child": ..}; repeated children -> list; text-only -> string.

    private static Object xmlParse(String text) {
        try {
            var f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            var doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(doc.getDocumentElement().getTagName(), xmlNode(doc.getDocumentElement(), 0));
            return out;
        } catch (EipException e) {
            throw e;
        } catch (Exception e) {
            throw new EipException("Invalid XML: " + e.getMessage(), e);
        }
    }

    private static Object xmlNode(org.w3c.dom.Element el, int depth) {
        if (depth > MAX_DEPTH) {
            throw new EipException("XML nested deeper than " + MAX_DEPTH);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        var attrs = el.getAttributes();
        for (int k = 0; k < attrs.getLength(); k++) {
            m.put("@" + attrs.item(k).getNodeName(), attrs.item(k).getNodeValue());
        }
        StringBuilder txt = new StringBuilder();
        var kids = el.getChildNodes();
        for (int k = 0; k < kids.getLength(); k++) {
            var n = kids.item(k);
            if (n instanceof org.w3c.dom.Element ce) {
                Object v = xmlNode(ce, depth + 1);
                Object prev = m.get(ce.getTagName());
                if (prev == null && !m.containsKey(ce.getTagName())) {
                    m.put(ce.getTagName(), v);
                } else if (prev instanceof XList xl) {
                    xl.items.add(v);
                } else {
                    XList xl = new XList();
                    xl.items.add(prev);
                    xl.items.add(v);
                    m.put(ce.getTagName(), xl);
                }
            } else if (n.getNodeType() == org.w3c.dom.Node.TEXT_NODE || n.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                txt.append(n.getNodeValue());
            }
        }
        m.replaceAll((k, v) -> v instanceof XList xl ? xl.items : v);
        String t = txt.toString().trim();
        if (m.isEmpty()) {
            return t;
        }
        if (!t.isEmpty()) {
            m.put("#text", t);
        }
        return m;
    }

    private static final class XList {
        final List<Object> items = new ArrayList<>();
    }

    private static String xmlWrite(Object body) {
        if (!(body instanceof Map<?, ?> m) || m.size() != 1) {
            throw new EipException("XML serialization needs an object with exactly one root key");
        }
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        Map.Entry<?, ?> e = m.entrySet().iterator().next();
        xmlEl(b, String.valueOf(e.getKey()), e.getValue(), 0);
        return b.toString();
    }

    private static void xmlEl(StringBuilder b, String name, Object v, int depth) {
        if (depth > MAX_DEPTH) {
            throw new EipException("Value nested deeper than " + MAX_DEPTH);
        }
        if (v instanceof List<?> l) {
            for (Object o : l) {
                xmlEl(b, name, o, depth + 1);
            }
            return;
        }
        name(name);
        b.append('<').append(name);
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (k.startsWith("@")) {
                    name(k.substring(1));
                    b.append(' ').append(k.substring(1)).append("=\"").append(esc(str(e.getValue()))).append('"');
                }
            }
            b.append('>');
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (k.equals("#text")) {
                    b.append(esc(str(e.getValue())));
                } else if (!k.startsWith("@")) {
                    xmlEl(b, k, e.getValue(), depth + 1);
                }
            }
        } else {
            b.append('>').append(esc(str(v)));
        }
        b.append("</").append(name).append('>');
    }

    private static String str(Object v) {
        return v == null ? "" : v instanceof BigDecimal n ? n.stripTrailingZeros().toPlainString()
                : tech.kayys.wayang.expression.Values.asString(v);
    }

    private static void name(String n) {
        if (!n.matches("[A-Za-z_][A-Za-z0-9_.-]*(:[A-Za-z_][A-Za-z0-9_.-]*)?")) {
            throw new EipException("'" + n + "' is not a valid XML name");
        }
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                        throw new EipException("Control character not allowed in XML");
                    }
                    b.append(c);
                }
            }
        }
        return b.toString();
    }
}
