package tech.kayys.wayang.rag;

import java.util.List;

/**
 * Provider adapter for model-assisted query mention extraction.
 * Implementations must return offsets into the original query; callers validate every field.
 */
@FunctionalInterface
public interface QueryMentionModel {
    List<QueryMentionPrediction> extract(String query, int maxMentions);
}
