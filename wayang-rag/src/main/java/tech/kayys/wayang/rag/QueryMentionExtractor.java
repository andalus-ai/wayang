package tech.kayys.wayang.rag;

import java.util.List;

/** Extracts bounded, chunk-independent entity mentions from a user query. */
@FunctionalInterface
public interface QueryMentionExtractor {
    List<EntityCandidate> extract(String query);
}
