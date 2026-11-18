package tech.kayys.wayang.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Word (.docx): body text with {@code Heading N} / {@code Title} paragraphs as {@code #} headings and tables as one {@code a | b | c} line per row. */
public final class DocxExtractor implements DocumentExtractor {

    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)heading\\s*([1-9])");

    @Override
    public Set<String> extensions() {
        return Set.of("docx");
    }

    @Override
    public String extract(byte[] content, Limits limits) {
        Map<String, byte[]> parts = OoxmlZip.read(content, limits, n -> n.equals("word/document.xml"));
        byte[] doc = parts.get("word/document.xml");
        if (doc == null) {
            throw new RagException("not a Word document: word/document.xml is missing");
        }
        StringBuilder out = new StringBuilder();
        StringBuilder para = new StringBuilder();
        StringBuilder cell = new StringBuilder();
        List<String> cells = new ArrayList<>();
        int heading = 0;
        int tables = 0;
        boolean inText = false;
        XMLStreamReader r = Xml.reader(doc);
        try {
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "p" -> {
                            para.setLength(0);
                            heading = 0;
                        }
                        case "pStyle" -> heading = headingLevel(Xml.attr(r, "val"));
                        case "t" -> inText = true;
                        case "tab" -> para.append('\t');
                        case "br", "cr" -> para.append(' ');
                        case "tbl" -> tables++;
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS) {
                    if (inText) {
                        para.append(r.getText());
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "t" -> inText = false;
                        case "p" -> {
                            String t = para.toString().strip();
                            if (!t.isEmpty()) {
                                if (tables > 0) {
                                    if (cell.length() > 0) {
                                        cell.append(' ');
                                    }
                                    cell.append(t);
                                } else if (heading > 0) {
                                    out.append("\n").append("#".repeat(heading)).append(' ').append(t).append("\n\n");
                                } else {
                                    out.append(t).append("\n\n");
                                }
                            }
                            para.setLength(0);
                        }
                        case "tc" -> {
                            cells.add(cell.toString());
                            cell.setLength(0);
                        }
                        case "tr" -> {
                            if (cells.stream().anyMatch(c -> !c.isBlank())) {
                                out.append(String.join(" | ", cells)).append('\n');
                            }
                            cells.clear();
                        }
                        case "tbl" -> {
                            tables = Math.max(0, tables - 1);
                            if (tables == 0) {
                                out.append('\n');
                            }
                        }
                        default -> { }
                    }
                    if (out.length() > limits.maxChars()) {
                        throw new RagException("the file holds more than " + limits.maxChars() + " characters of text");
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new RagException("the Word document is damaged", e);
        }
        return out.toString().strip();
    }

    private static int headingLevel(String style) {
        if (style == null) {
            return 0;
        }
        if (style.equalsIgnoreCase("Title")) {
            return 1;
        }
        Matcher m = HEADING_STYLE.matcher(style);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
