package tech.kayys.wayang.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Excel (.xlsx): every sheet becomes a {@code ## name} section whose first row is the header and whose later rows are {@code header=value; ...} lines.
 * Values are read as stored: dates are stored by Excel as serial numbers and are shown as such, and formulas show their cached result.
 */
public final class XlsxExtractor implements DocumentExtractor {

    @Override
    public Set<String> extensions() {
        return Set.of("xlsx");
    }

    @Override
    public String extract(byte[] content, Limits limits) {
        Map<String, byte[]> parts = OoxmlZip.read(content, limits, n -> n.equals("xl/workbook.xml") || n.equals("xl/sharedStrings.xml")
                || n.equals("xl/_rels/workbook.xml.rels") || n.startsWith("xl/worksheets/") && n.endsWith(".xml"));
        byte[] wb = parts.get("xl/workbook.xml");
        if (wb == null) {
            throw new RagException("not an Excel workbook: xl/workbook.xml is missing");
        }
        List<String> shared = parts.containsKey("xl/sharedStrings.xml") ? sharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
        Map<String, String> targets = relationships(parts.get("xl/_rels/workbook.xml.rels"));
        StringBuilder out = new StringBuilder();
        try {
            XMLStreamReader r = Xml.reader(wb);
            int index = 0;
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")) {
                    index++;
                    String name = Xml.attr(r, "name");
                    String target = targets.get(Xml.attr(r, "id"));
                    String path = target == null ? "xl/worksheets/sheet" + index + ".xml"
                            : target.startsWith("/") ? target.substring(1) : "xl/" + target;
                    byte[] sheet = parts.get(path);
                    if (sheet != null) {
                        Tabular.emit(out, name == null ? "Sheet " + index : name, sheetRows(sheet, shared, limits), limits);
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new RagException("the workbook is damaged", e);
        }
        return out.toString().strip();
    }

    private static List<String> sharedStrings(byte[] xml) {
        List<String> out = new ArrayList<>();
        try {
            XMLStreamReader r = Xml.reader(xml);
            StringBuilder cur = null;
            boolean inT = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    if (r.getLocalName().equals("si")) {
                        cur = new StringBuilder();
                    } else if (r.getLocalName().equals("t")) {
                        inT = true;
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS && inT && cur != null) {
                    cur.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if (r.getLocalName().equals("t")) {
                        inT = false;
                    } else if (r.getLocalName().equals("si") && cur != null) {
                        out.add(cur.toString());
                        cur = null;
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new RagException("the workbook's shared strings are damaged", e);
        }
        return out;
    }

    private static Map<String, String> relationships(byte[] xml) {
        Map<String, String> out = new java.util.HashMap<>();
        if (xml == null) {
            return out;
        }
        try {
            XMLStreamReader r = Xml.reader(xml);
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("Relationship")) {
                    String id = Xml.attr(r, "Id");
                    String target = Xml.attr(r, "Target");
                    if (id != null && target != null) {
                        out.put(id, target);
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new RagException("the workbook's relationships are damaged", e);
        }
        return out;
    }

    private static List<List<String>> sheetRows(byte[] xml, List<String> shared, Limits limits) {
        List<List<String>> rows = new ArrayList<>();
        try {
            XMLStreamReader r = Xml.reader(xml);
            List<String> row = null;
            String type = null;
            int col = 0;
            StringBuilder value = new StringBuilder();
            boolean inV = false;
            boolean inT = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "row" -> {
                            if (rows.size() >= limits.maxRows()) {
                                throw new RagException("the sheet has more than " + limits.maxRows() + " rows");
                            }
                            row = new ArrayList<>();
                        }
                        case "c" -> {
                            type = Xml.attr(r, "t");
                            col = columnIndex(Xml.attr(r, "r"), row == null ? 0 : row.size());
                            value.setLength(0);
                        }
                        case "v" -> inV = true;
                        case "t" -> inT = true;
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS) {
                    if (inV || inT) {
                        value.append(r.getText());
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "v" -> inV = false;
                        case "t" -> inT = false;
                        case "c" -> {
                            if (row != null) {
                                while (row.size() < col) {
                                    row.add("");
                                }
                                row.add(cellText(type, value.toString(), shared));
                            }
                        }
                        case "row" -> {
                            if (row != null) {
                                rows.add(row);
                            }
                            row = null;
                        }
                        default -> { }
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new RagException("a worksheet is damaged", e);
        }
        return rows;
    }

    private static String cellText(String type, String raw, List<String> shared) {
        if ("s".equals(type)) {
            try {
                int i = Integer.parseInt(raw.strip());
                return i >= 0 && i < shared.size() ? shared.get(i) : "";
            } catch (NumberFormatException e) {
                return "";
            }
        }
        if ("b".equals(type)) {
            return raw.strip().equals("1") ? "TRUE" : "FALSE";
        }
        return raw;
    }

    /** Zero-based column of a cell reference such as {@code C7}; falls back to the next free column. */
    static int columnIndex(String ref, int fallback) {
        if (ref == null) {
            return fallback;
        }
        int n = 0;
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            n = n * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
            if (n > 16384) {
                return fallback;
            }
        }
        return i == 0 ? fallback : n - 1;
    }
}
