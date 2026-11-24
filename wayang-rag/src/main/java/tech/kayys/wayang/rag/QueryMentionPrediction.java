package tech.kayys.wayang.rag;

/** Untrusted, provider-neutral structured mention prediction returned by a query model. */
public record QueryMentionPrediction(
        String entityType, String surfaceText, int startOffset, int endOffset, double confidence) {
}
