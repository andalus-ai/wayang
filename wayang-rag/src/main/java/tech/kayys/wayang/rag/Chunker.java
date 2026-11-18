package tech.kayys.wayang.rag;

import java.util.List;

/** Splits a document into retrievable chunks. Strategies differ in what boundary they respect; pick the one that matches the document's structure. */
public interface Chunker {

    /** Stable strategy name used in configuration. */
    String type();

    List<Chunk> chunk(Document doc);
}
