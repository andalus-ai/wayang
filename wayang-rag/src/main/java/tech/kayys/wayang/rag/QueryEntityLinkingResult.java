package tech.kayys.wayang.rag;

import java.util.List;
import java.util.Set;

/** Query mention resolution outcome; ambiguous mentions never produce graph seed IDs. */
public record QueryEntityLinkingResult(
        List<EntityCandidate> mentions,
        List<EntityResolutionDecision> decisions,
        Set<String> seedEntityIds) {
    public QueryEntityLinkingResult {
        mentions = mentions == null ? List.of() : List.copyOf(mentions);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        seedEntityIds = seedEntityIds == null ? Set.of() : Set.copyOf(seedEntityIds);
    }

    public long ambiguousCount() {
        return decisions.stream().filter(d -> d.status() == EntityResolutionDecision.Status.AMBIGUOUS).count();
    }
}
