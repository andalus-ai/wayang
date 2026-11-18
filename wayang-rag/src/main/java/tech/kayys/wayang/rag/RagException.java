package tech.kayys.wayang.rag;

/** A document or configuration the RAG building blocks refuse; the message is safe to show (it never contains document text). */
public final class RagException extends RuntimeException {
    public RagException(String message) {
        super(message);
    }

    public RagException(String message, Throwable cause) {
        super(message, cause);
    }
}
