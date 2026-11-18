package tech.kayys.wayang.rag;

import java.util.Map;

/**
 * A retrievable piece of a document.
 *
 * @param heading the section path it sits under, e.g. {@code Chapter 2 > Article 5}, or empty
 * @param context the wider text (the parent window) to hand to the model when parent expansion is on, or null
 */
public record Chunk(String id, String docId, String docName, int ordinal, String text, String heading, String context, Map<String, String> metadata) {
}
