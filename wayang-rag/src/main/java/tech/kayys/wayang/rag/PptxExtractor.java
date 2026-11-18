package tech.kayys.wayang.rag;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** PowerPoint (.pptx): one {@code ## Slide N} section per slide, in slide-number order, with one line per text paragraph. Speaker notes are not read. */
public final class PptxExtractor implements DocumentExtractor {

    private static final Pattern SLIDE = Pattern.compile("ppt/slides/slide(\\d{1,5})\\.xml");

    @Override
    public Set<String> extensions() {
        return Set.of("pptx");
    }

    @Override
    public String extract(byte[] content, Limits limits) {
        Map<String, byte[]> parts = OoxmlZip.read(content, limits, n -> SLIDE.matcher(n).matches());
        if (parts.isEmpty()) {
            throw new RagException("not a PowerPoint file: it has no slides");
        }
        TreeMap<Integer, byte[]> slides = new TreeMap<>();
        parts.forEach((name, bytes) -> {
            Matcher m = SLIDE.matcher(name);
            if (m.matches()) {
                slides.put(Integer.parseInt(m.group(1)), bytes);
            }
        });
        StringBuilder out = new StringBuilder();
        for (var e : slides.entrySet()) {
            StringBuilder slide = new StringBuilder();
            StringBuilder para = new StringBuilder();
            boolean inT = false;
            try {
                XMLStreamReader r = Xml.reader(e.getValue());
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        if (r.getLocalName().equals("t")) {
                            inT = true;
                        } else if (r.getLocalName().equals("br")) {
                            para.append(' ');
                        }
                    } else if (ev == XMLStreamConstants.CHARACTERS && inT) {
                        para.append(r.getText());
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        if (r.getLocalName().equals("t")) {
                            inT = false;
                        } else if (r.getLocalName().equals("p")) {
                            String t = para.toString().strip();
                            if (!t.isEmpty()) {
                                slide.append(t).append('\n');
                            }
                            para.setLength(0);
                        }
                    }
                }
            } catch (XMLStreamException ex) {
                throw new RagException("slide " + e.getKey() + " is damaged", ex);
            }
            if (slide.length() > 0) {
                out.append("## Slide ").append(e.getKey()).append('\n').append(slide).append('\n');
            }
            if (out.length() > limits.maxChars()) {
                throw new RagException("the file holds more than " + limits.maxChars() + " characters of text");
            }
        }
        return out.toString().strip();
    }
}
