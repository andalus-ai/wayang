package tech.kayys.wayang.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies validated chunk candidates to a graph store. The service is deterministic and
 * source-aware; it does not run extraction, perform fuzzy matching, or manage transactions.
 */
public final class KnowledgeGraphIngestionService {
    private final GraphStore graphStore;

    public KnowledgeGraphIngestionService(GraphStore graphStore) {
        if (graphStore == null) throw new RagException("graphStore is required");
        this.graphStore = graphStore;
    }

    public KnowledgeGraphIngestionResult ingest(
            KnowledgeExtractionInput input,
            ValidatedKnowledgeCandidates validated,
            List<EntityResolutionDecision> resolutions,
            String extractor) {
        if (input == null || validated == null) throw new RagException("input and validated candidates are required");
        if (extractor == null || extractor.isBlank() || extractor.length() > 200) {
            throw new RagException("extractor must contain 1..200 characters");
        }
        List<KnowledgeValidationIssue> issues = new ArrayList<>(validated.issues());
        Map<String, EntityResolutionDecision> decisions = new HashMap<>();
        if (resolutions != null) {
            for (EntityResolutionDecision decision : resolutions) {
                if (decision == null || decisions.putIfAbsent(decision.candidateId(), decision) != null) {
                    throw new RagException("resolution decisions must be non-null and unique by candidate ID");
                }
            }
        }

        Map<String, String> canonicalIds = new HashMap<>();
        int entitiesUpserted = 0;
        int unresolvedEntities = 0;
        for (EntityCandidate candidate : validated.entities()) {
            EntityResolutionDecision decision = decisions.get(candidate.candidateId());
            if (decision == null) {
                issue(issues, candidate.candidateId(), "MISSING_ENTITY_RESOLUTION",
                        "No resolution decision was supplied for this entity candidate");
                unresolvedEntities++;
                continue;
            }
            if (decision.status() == EntityResolutionDecision.Status.AMBIGUOUS) {
                issue(issues, candidate.candidateId(), "AMBIGUOUS_ENTITY_RESOLUTION",
                        "Entity mention requires explicit resolution and was not written");
                unresolvedEntities++;
                continue;
            }

            String entityId;
            if (decision.status() == EntityResolutionDecision.Status.MATCHED) {
                entityId = decision.canonicalEntityId();
                if (graphStore.findEntity(input.tenantId(), input.collectionId(), entityId).isEmpty()) {
                    issue(issues, candidate.candidateId(), "RESOLVED_ENTITY_NOT_FOUND",
                            "Matched canonical entity does not exist in this tenant and collection");
                    unresolvedEntities++;
                    continue;
                }
            } else {
                entityId = stableId("entity", input.tenantId(), input.collectionId(),
                        candidate.entityType(), candidate.normalizedName());
            }

            KnowledgeEvidence evidence = new KnowledgeEvidence(
                    input.document().id(), input.sourceVersion(), input.chunk().id(),
                    candidate.startOffset(), candidate.endOffset(), extractor, candidate.confidence());
            KnowledgeEntity entity = new KnowledgeEntity(
                    input.tenantId(), input.collectionId(), entityId, candidate.entityType(),
                    candidate.surfaceText(), List.of(candidate.normalizedName()),
                    Map.of(), List.of(evidence));
            graphStore.upsertEntity(entity);
            canonicalIds.put(candidate.candidateId(), entityId);
            entitiesUpserted++;
        }

        int relationshipsUpserted = 0;
        int skippedRelationships = 0;
        for (RelationshipCandidate candidate : validated.relationships()) {
            String fromId = canonicalIds.get(candidate.fromCandidateId());
            String toId = canonicalIds.get(candidate.toCandidateId());
            if (fromId == null || toId == null) {
                issue(issues, candidate.candidateId(), "UNRESOLVED_RELATIONSHIP_ENDPOINT",
                        "Relationship was not written because one or both entity mentions were unresolved");
                skippedRelationships++;
                continue;
            }
            String relationshipId = stableId("relationship", input.tenantId(), input.collectionId(),
                    fromId, candidate.predicate(), toId);
            KnowledgeEvidence evidence = new KnowledgeEvidence(
                    input.document().id(), input.sourceVersion(), input.chunk().id(),
                    candidate.startOffset(), candidate.endOffset(), extractor, candidate.confidence());
            graphStore.upsertRelationship(new KnowledgeRelationship(
                    input.tenantId(), input.collectionId(), relationshipId,
                    fromId, candidate.predicate(), toId, Map.of(), List.of(evidence), candidate.confidence()));
            relationshipsUpserted++;
        }

        return new KnowledgeGraphIngestionResult(
                entitiesUpserted, relationshipsUpserted, unresolvedEntities, skippedRelationships, issues);
    }

    /** Retracts only evidence for the exact source version; other versions and sources remain intact. */
    public EvidenceRetractionResult retractSourceVersion(
            String tenantId, String collectionId, String sourceId, String sourceVersion) {
        return graphStore.retractEvidence(tenantId, collectionId, sourceId, sourceVersion);
    }

    private static String stableId(String kind, String tenant, String collection, String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, kind);
            update(digest, tenant);
            update(digest, collection);
            for (String part : parts) update(digest, part);
            return kind + "-" + HexFormat.of().formatHex(digest.digest(), 0, 20);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static void issue(List<KnowledgeValidationIssue> issues, String id, String code, String message) {
        issues.add(new KnowledgeValidationIssue(id, code, message));
    }
}
