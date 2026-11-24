package tech.kayys.wayang.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Conservative reference resolver: exact normalized canonical-name/alias matches of the same
 * entity type only. It never fuzzy-matches, creates entities, or merges ambiguous matches.
 */
public final class ExactAliasEntityResolver implements EntityResolver {
    @Override
    public List<EntityResolutionDecision> resolve(EntityResolutionRequest request) {
        if (request == null) throw new RagException("entity resolution request is required");
        List<EntityResolutionDecision> decisions = new ArrayList<>();
        for (EntityCandidate candidate : request.candidates()) {
            List<KnowledgeEntity> matches = request.existingEntities().stream()
                    .filter(entity -> entity.tenantId().equals(request.tenantId()))
                    .filter(entity -> entity.collectionId().equals(request.collectionId()))
                    .filter(entity -> entity.type().equals(candidate.entityType()))
                    .filter(entity -> matches(candidate.normalizedName(), entity))
                    .toList();
            if (matches.size() == 1) {
                decisions.add(new EntityResolutionDecision(candidate.candidateId(),
                        EntityResolutionDecision.Status.MATCHED, matches.get(0).id()));
            } else if (matches.size() > 1) {
                decisions.add(new EntityResolutionDecision(candidate.candidateId(),
                        EntityResolutionDecision.Status.AMBIGUOUS, null));
            } else {
                decisions.add(new EntityResolutionDecision(candidate.candidateId(),
                        EntityResolutionDecision.Status.NEW_ENTITY, null));
            }
        }
        return List.copyOf(decisions);
    }

    private static boolean matches(String candidateName, KnowledgeEntity entity) {
        String key = normalize(candidateName);
        return key.equals(normalize(entity.canonicalName()))
                || entity.aliases().stream().map(ExactAliasEntityResolver::normalize).anyMatch(key::equals);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
