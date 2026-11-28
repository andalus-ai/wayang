package tech.kayys.wayang.rag;

import java.util.List;

/** Result of checking whether a new schema version is additive relative to a prior version. */
public record KnowledgeSchemaCompatibilityResult(
        boolean compatible,
        List<String> breakingChanges) {

    public KnowledgeSchemaCompatibilityResult {
        breakingChanges = breakingChanges == null ? List.of() : List.copyOf(breakingChanges);
        if (compatible != breakingingChangesEmpty(breakingChanges)) {
            throw new RagException("compatibility flag must agree with breaking change details");
        }
    }

    private static boolean breakingingChangesEmpty(List<String> changes) {
        return changes.isEmpty();
    }
}
