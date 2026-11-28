package tech.kayys.wayang.rag;

import java.util.ArrayList;
import java.util.Set;

/** Conservative additive-compatibility check for successive knowledge schema versions. */
public final class KnowledgeSchemaCompatibility {

    private KnowledgeSchemaCompatibility() {
    }

    /**
     * A compatible update may add entity types, predicates and allowed endpoint types.
     * It may not remove or narrow anything accepted by the previous version.
     */
    public static KnowledgeSchemaCompatibilityResult check(KnowledgeSchema previous, KnowledgeSchema next) {
        if (previous == null || next == null) {
            throw new RagException("both schema versions are required");
        }
        var changes = new ArrayList<String>();
        if (!previous.schemaId().equals(next.schemaId())) {
            changes.add("schemaId changed");
        }
        if (previous.version().equals(next.version())) {
            changes.add("new version must differ from previous version");
        }
        requireSuperset(next.entityTypes(), previous.entityTypes(), "entity type removed: ", changes);
        previous.relationshipTypes().forEach((predicate, oldDefinition) -> {
            var newDefinition = next.relationship(predicate);
            if (newDefinition == null) {
                changes.add("relationship predicate removed: " + predicate);
                return;
            }
            requireSuperset(newDefinition.fromEntityTypes(), oldDefinition.fromEntityTypes(),
                    "relationship source types narrowed for " + predicate + ": ", changes);
            requireSuperset(newDefinition.toEntityTypes(), oldDefinition.toEntityTypes(),
                    "relationship target types narrowed for " + predicate + ": ", changes);
        });
        return new KnowledgeSchemaCompatibilityResult(changes.isEmpty(), changes);
    }

    private static void requireSuperset(
            Set<String> candidate,
            Set<String> required,
            String prefix,
            java.util.List<String> changes) {
        required.stream().filter(value -> !candidate.contains(value))
                .sorted().forEach(value -> changes.add(prefix + value));
    }
}
