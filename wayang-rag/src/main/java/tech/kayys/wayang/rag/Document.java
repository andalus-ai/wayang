package tech.kayys.wayang.rag;

import java.util.Map;

/**
 * Extracted text of one source, normalised to light Markdown so every chunker sees the same shape: headings are {@code #} lines, table and
 * spreadsheet rows are one line each ({@code header=value; ...}), slides are {@code ##} sections.
 *
 * @param id       stable identifier chosen by the caller (re-adding the same id replaces the document)
 * @param name     display name, normally the file name; used in citations
 * @param metadata free-form string attributes (e.g. jurisdiction, year) usable as exact-match search filters
 */
public record Document(String id, String name, String text, Map<String, String> metadata) {

    public Document {
        if (id == null || id.isBlank() || id.length() > 200) {
            throw new RagException("a document needs an id of 1..200 characters");
        }
        name = name == null || name.isBlank() ? id : name;
        text = text == null ? "" : text;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
