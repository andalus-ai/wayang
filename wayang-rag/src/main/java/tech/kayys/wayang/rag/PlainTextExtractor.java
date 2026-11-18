package tech.kayys.wayang.rag;

import java.util.Set;

/** Plain text and text-like formats read as they are: txt, md, markdown, log, json, yaml, yml, xml, rst. */
public final class PlainTextExtractor implements DocumentExtractor {

    @Override
    public Set<String> extensions() {
        return Set.of("txt", "md", "markdown", "log", "json", "yaml", "yml", "xml", "rst", "text");
    }

    @Override
    public String extract(byte[] content, Limits limits) {
        String s = Text.normalise(Text.decode(content));
        Text.checkSize(s, limits);
        return s;
    }
}
