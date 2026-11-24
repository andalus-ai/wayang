package tech.kayys.wayang.rag;

import java.util.List;

/**
 * Provider adapter that ranks the bounded, already tenant/collection-scoped entity candidates.
 * Return exactly one best-match prediction per mention; use a null canonicalEntityId when no
 * candidate is proposed. Scores are untrusted and are revalidated by the resolver.
 */
@FunctionalInterface
public interface EntityResolutionModel {
    List<EntityMatchPrediction> rank(EntityResolutionRequest request);
}
