package tech.kayys.wayang.rag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves query mentions against a bounded, tenant/collection-scoped graph candidate set. */
public final class QueryEntityLinker {
    public static final int MAX_QUERY_MENTIONS = 100;
    public static final int MAX_GRAPH_CANDIDATES = 2_000;

    private final QueryMentionExtractor mentionExtractor;
    private final GraphEntityLookup entityLookup;
    private final EntityResolver resolver;

    public QueryEntityLinker(QueryMentionExtractor mentionExtractor,
            GraphEntityLookup entityLookup, EntityResolver resolver) {
        if (mentionExtractor == null || entityLookup == null || resolver == null)
            throw new RagException("query entity linker requires mention extraction, graph lookup and resolution");
        this.mentionExtractor = mentionExtractor;
        this.entityLookup = entityLookup;
        this.resolver = resolver;
    }

    public QueryEntityLinkingResult link(String tenantId, String collectionId, String query) {
        String tenant = required(tenantId, "tenantId");
        String collection = required(collectionId, "collectionId");
        if (query == null || query.isBlank()) return new QueryEntityLinkingResult(List.of(), List.of(), Set.of());
        List<EntityCandidate> mentions = mentionExtractor.extract(query);
        if (mentions == null || mentions.isEmpty())
            return new QueryEntityLinkingResult(List.of(), List.of(), Set.of());
        if (mentions.size() > MAX_QUERY_MENTIONS)
            throw new RagException("query mention extractor exceeded the 100 mention limit");
        Set<String> types = mentions.stream().map(EntityCandidate::entityType)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<KnowledgeEntity> existing = entityLookup.findCandidates(tenant, collection, types, MAX_GRAPH_CANDIDATES);
        if (existing == null) existing = List.of();
        if (existing.size() > MAX_GRAPH_CANDIDATES)
            throw new RagException("graph entity lookup exceeded the 2000 candidate limit");
        for (KnowledgeEntity entity : existing) {
            if (entity == null || !tenant.equals(entity.tenantId()) || !collection.equals(entity.collectionId())
                    || !types.contains(entity.type()))
                throw new RagException("graph entity lookup returned an entity outside the requested scope or type");
        }
        List<EntityResolutionDecision> decisions = resolver.resolve(
                new EntityResolutionRequest(tenant, collection, mentions, existing));
        if (decisions == null) decisions = List.of();
        Set<String> expected = mentions.stream().map(EntityCandidate::candidateId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> resolved = new LinkedHashSet<>();
        Set<String> seeds = new LinkedHashSet<>();
        for (EntityResolutionDecision decision : decisions) {
            if (decision == null || !expected.contains(decision.candidateId()) || !resolved.add(decision.candidateId()))
                throw new RagException("entity resolver returned an unknown or duplicate candidate decision");
            if (decision.status() == EntityResolutionDecision.Status.MATCHED) {
                boolean inScope = existing.stream().anyMatch(e -> e.id().equals(decision.canonicalEntityId()));
                if (!inScope) throw new RagException("entity resolver matched an entity outside its candidate set");
                seeds.add(decision.canonicalEntityId());
            }
        }
        if (!resolved.equals(expected))
            throw new RagException("entity resolver must return exactly one decision per query mention");
        return new QueryEntityLinkingResult(mentions, decisions, seeds);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 200)
            throw new RagException(field + " must contain 1..200 characters");
        return value.strip();
    }
}
