package tech.kayys.wayang.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates extractor output against a versioned schema without making persistence decisions. */
public final class KnowledgeCandidateValidator {

    public ValidatedKnowledgeCandidates validate(
            KnowledgeExtractionInput input,
            List<EntityCandidate> entities,
            List<RelationshipCandidate> relationships,
            KnowledgeSchema schema) {
        if (input == null) throw new RagException("knowledge extraction input is required");
        if (schema == null) throw new RagException("knowledge schema is required");

        List<EntityCandidate> suppliedEntities = entities == null ? List.of() : List.copyOf(entities);
        List<RelationshipCandidate> suppliedRelationships = relationships == null
                ? List.of() : List.copyOf(relationships);
        List<KnowledgeValidationIssue> issues = new ArrayList<>();
        Map<String, Long> entityCounts = counts(suppliedEntities.stream().map(EntityCandidate::candidateId).toList());
        Set<String> acceptedIds = new HashSet<>();
        Map<String, EntityCandidate> accepted = new HashMap<>();

        for (EntityCandidate candidate : suppliedEntities) {
            String id = candidate.candidateId();
            if (entityCounts.getOrDefault(id, 0L) > 1) {
                issue(issues, id, "DUPLICATE_ENTITY_CANDIDATE_ID", "Entity candidate ID is duplicated");
            } else if (!schema.allowsEntityType(candidate.entityType())) {
                issue(issues, id, "UNKNOWN_ENTITY_TYPE", "Entity type is not declared by the schema");
            } else if (!validRange(candidate.startOffset(), candidate.endOffset(), input.chunk().text().length())) {
                issue(issues, id, "ENTITY_OFFSETS_OUT_OF_RANGE", "Entity offsets exceed the source chunk");
            } else {
                acceptedIds.add(id);
                accepted.put(id, candidate);
            }
        }

        Map<String, Long> relationshipCounts = counts(
                suppliedRelationships.stream().map(RelationshipCandidate::candidateId).toList());
        List<RelationshipCandidate> validRelationships = new ArrayList<>();
        for (RelationshipCandidate candidate : suppliedRelationships) {
            String id = candidate.candidateId();
            if (relationshipCounts.getOrDefault(id, 0L) > 1) {
                issue(issues, id, "DUPLICATE_RELATIONSHIP_CANDIDATE_ID",
                        "Relationship candidate ID is duplicated");
                continue;
            }
            EntityCandidate from = accepted.get(candidate.fromCandidateId());
            EntityCandidate to = accepted.get(candidate.toCandidateId());
            if (!acceptedIds.contains(candidate.fromCandidateId())
                    || !acceptedIds.contains(candidate.toCandidateId())) {
                issue(issues, id, "DANGLING_RELATIONSHIP_ENDPOINT",
                        "Relationship endpoints must reference valid candidates in this extraction result");
                continue;
            }
            RelationshipTypeDefinition definition = schema.relationship(candidate.predicate());
            if (definition == null) {
                issue(issues, id, "UNKNOWN_RELATIONSHIP_PREDICATE",
                        "Relationship predicate is not declared by the schema");
                continue;
            }
            if (!definition.fromEntityTypes().contains(from.entityType())
                    || !definition.toEntityTypes().contains(to.entityType())) {
                issue(issues, id, "RELATIONSHIP_ENDPOINT_TYPE_MISMATCH",
                        "Relationship endpoint types do not satisfy the predicate definition");
                continue;
            }
            if (!validRange(candidate.startOffset(), candidate.endOffset(), input.chunk().text().length())) {
                issue(issues, id, "RELATIONSHIP_OFFSETS_OUT_OF_RANGE",
                        "Relationship offsets exceed the source chunk");
                continue;
            }
            validRelationships.add(candidate);
        }

        return new ValidatedKnowledgeCandidates(
                suppliedEntities.stream().filter(e -> acceptedIds.contains(e.candidateId())).toList(),
                validRelationships, issues);
    }

    private static boolean validRange(int start, int end, int length) {
        return start >= 0 && end > start && end <= length;
    }

    private static Map<String, Long> counts(List<String> ids) {
        Map<String, Long> counts = new HashMap<>();
        ids.forEach(id -> counts.merge(id, 1L, Long::sum));
        return counts;
    }

    private static void issue(List<KnowledgeValidationIssue> issues, String id, String code, String message) {
        issues.add(new KnowledgeValidationIssue(id, code, message));
    }
}
