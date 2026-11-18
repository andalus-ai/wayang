package tech.kayys.wayang.rag;

/** Turns the bytes of one file format into a normalised {@link Document} text. Implementations must not execute, fetch or follow anything inside the file. */
public interface DocumentExtractor {

    /** File extensions (lower case, no dot) this extractor reads. */
    java.util.Set<String> extensions();

    /** @return the normalised text; @throws RagException for a file that is damaged, over the limits or not really this format */
    String extract(byte[] content, Limits limits);
}
