package tech.kayys.wayang.rag;

import java.util.List;

/** Partial-validation result: valid candidates remain usable alongside reported issues. */
public record ValidatedKnowledgeCandidates(
        List<EntityCandidate> entities,
        List<RelationshipCandidate> relationships,
        List<KnowledgeValidationIssue> issues) {
    public ValidatedKnowledgeCandidates {
        entities = entities == null ? List.of() : List.copyOf(entities);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
    public boolean hasIssues() { return !issues.isEmpty(); }
}
