package tech.kayys.wayang.rag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Bounded process-local reference implementation of {@link GraphStore}. */
public final class InMemoryGraphStore implements GraphStore, GraphEntityLookup {
    public static final int DEFAULT_MAX_ENTITIES = 20_000;
    public static final int DEFAULT_MAX_RELATIONSHIPS = 100_000;

    private record Scope(String tenantId, String collectionId) {}
    private record EntityKey(Scope scope, String id) {}
    private record RelationshipKey(Scope scope, String id) {}
    private record Visit(String entityId, int depth) {}

    private final int maxEntities;
    private final int maxRelationships;
    private final Map<EntityKey, KnowledgeEntity> entities = new LinkedHashMap<>();
    private final Map<RelationshipKey, KnowledgeRelationship> relationships = new LinkedHashMap<>();

    public InMemoryGraphStore() { this(DEFAULT_MAX_ENTITIES, DEFAULT_MAX_RELATIONSHIPS); }

    public InMemoryGraphStore(int maxEntities, int maxRelationships) {
        if (maxEntities < 1 || maxRelationships < 1) {
            throw new RagException("graph store limits must be positive");
        }
        this.maxEntities = maxEntities;
        this.maxRelationships = maxRelationships;
    }

    @Override
    public synchronized void upsertEntity(KnowledgeEntity entity) {
        upsertGraph(List.of(entity), List.of());
    }

    @Override
    public synchronized void upsertRelationship(KnowledgeRelationship relationship) {
        upsertGraph(List.of(), List.of(relationship));
    }

    @Override
    public synchronized void upsertGraph(
            List<KnowledgeEntity> entityBatch, List<KnowledgeRelationship> relationshipBatch) {
        if (entityBatch == null || relationshipBatch == null) {
            throw new RagException("graph mutation entity and relationship lists are required");
        }
        Map<EntityKey, KnowledgeEntity> stagedEntities = new LinkedHashMap<>(entities);
        Map<RelationshipKey, KnowledgeRelationship> stagedRelationships = new LinkedHashMap<>(relationships);

        for (KnowledgeEntity entity : entityBatch) {
            if (entity == null) throw new RagException("entity is required");
            EntityKey key = new EntityKey(scope(entity.tenantId(), entity.collectionId()), entity.id());
            KnowledgeEntity existing = stagedEntities.get(key);
            stagedEntities.put(key, existing == null ? entity : merge(existing, entity));
        }

        for (KnowledgeRelationship relationship : relationshipBatch) {
            if (relationship == null) throw new RagException("relationship is required");
            Scope scope = scope(relationship.tenantId(), relationship.collectionId());
            EntityKey from = new EntityKey(scope, relationship.fromEntityId());
            EntityKey to = new EntityKey(scope, relationship.toEntityId());
            if (!stagedEntities.containsKey(from) || !stagedEntities.containsKey(to)) {
                throw new RagException("both relationship endpoints must exist in the same tenant and collection");
            }
            RelationshipKey key = new RelationshipKey(scope, relationship.id());
            KnowledgeRelationship existing = stagedRelationships.get(key);
            stagedRelationships.put(key, existing == null ? relationship : merge(existing, relationship));
        }

        if (stagedEntities.size() > maxEntities) {
            throw new RagException("the in-memory graph has reached its entity limit");
        }
        if (stagedRelationships.size() > maxRelationships) {
            throw new RagException("the in-memory graph has reached its relationship limit");
        }
        entities.clear();
        entities.putAll(stagedEntities);
        relationships.clear();
        relationships.putAll(stagedRelationships);
    }

    @Override
    public synchronized Optional<KnowledgeEntity> findEntity(String tenantId, String collectionId, String entityId) {
        if (entityId == null || entityId.isBlank()) return Optional.empty();
        return Optional.ofNullable(entities.get(new EntityKey(scope(tenantId, collectionId), entityId)));
    }

    @Override
    public synchronized List<KnowledgeEntity> findCandidates(
            String tenantId, String collectionId, Set<String> entityTypes, int limit) {
        Scope requested = scope(tenantId, collectionId);
        if (entityTypes == null || entityTypes.isEmpty()) return List.of();
        if (limit < 1 || limit > 2_000) throw new RagException("graph candidate limit must be 1..2000");
        List<KnowledgeEntity> matches = entities.values().stream()
                .filter(entity -> entity.tenantId().equals(requested.tenantId())
                        && entity.collectionId().equals(requested.collectionId()))
                .filter(entity -> entityTypes.contains(entity.type()))
                .sorted(Comparator.comparing(KnowledgeEntity::id))
                .limit((long) limit + 1).toList();
        if (matches.size() > limit)
            throw new RagException("graph entity candidate set exceeds the configured lookup limit");
        return List.copyOf(matches);
    }

    @Override
    public synchronized EvidenceRetractionResult retractEvidence(
            String tenantId, String collectionId, String sourceId, String sourceVersion) {
        Scope scope = scope(tenantId, collectionId);
        if (sourceId == null || sourceId.isBlank() || sourceVersion == null || sourceVersion.isBlank()) {
            throw new RagException("sourceId and sourceVersion are required for evidence retraction");
        }
        int entityRemoved = 0;
        for (Map.Entry<EntityKey, KnowledgeEntity> entry : entities.entrySet()) {
            if (!entry.getKey().scope().equals(scope)) continue;
            KnowledgeEntity entity = entry.getValue();
            List<KnowledgeEvidence> retained = entity.evidence().stream()
                    .filter(e -> !matches(e, sourceId, sourceVersion)).toList();
            entityRemoved += entity.evidence().size() - retained.size();
            if (retained.size() != entity.evidence().size()) {
                entry.setValue(new KnowledgeEntity(entity.tenantId(), entity.collectionId(), entity.id(),
                        entity.type(), entity.canonicalName(), entity.aliases(), entity.properties(), retained));
            }
        }
        int relationshipEvidenceRemoved = 0;
        int relationshipsRemoved = 0;
        var iterator = relationships.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RelationshipKey, KnowledgeRelationship> entry = iterator.next();
            if (!entry.getKey().scope().equals(scope)) continue;
            KnowledgeRelationship relationship = entry.getValue();
            List<KnowledgeEvidence> retained = relationship.evidence().stream()
                    .filter(e -> !matches(e, sourceId, sourceVersion)).toList();
            relationshipEvidenceRemoved += relationship.evidence().size() - retained.size();
            if (retained.size() != relationship.evidence().size()) {
                if (retained.isEmpty()) {
                    iterator.remove();
                    relationshipsRemoved++;
                } else {
                    entry.setValue(new KnowledgeRelationship(relationship.tenantId(), relationship.collectionId(),
                            relationship.id(), relationship.fromEntityId(), relationship.predicate(),
                            relationship.toEntityId(), relationship.properties(), retained, relationship.confidence()));
                }
            }
        }
        return new EvidenceRetractionResult(entityRemoved, relationshipEvidenceRemoved, relationshipsRemoved);
    }

    @Override
    public synchronized GraphTraversalResult traverse(GraphQuery query) {
        if (query == null) throw new RagException("graph query is required");
        Scope scope = scope(query.tenantId(), query.collectionId());
        Map<String, KnowledgeEntity> foundEntities = new LinkedHashMap<>();
        Map<String, KnowledgeRelationship> foundRelationships = new LinkedHashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<Visit> queue = new ArrayDeque<>();
        boolean truncated = false;
        for (String seed : query.seedEntityIds()) {
            if (entities.containsKey(new EntityKey(scope, seed))) {
                if (foundEntities.size() >= query.maxNodes()) { truncated = true; break; }
                visited.add(seed);
                foundEntities.put(seed, entities.get(new EntityKey(scope, seed)));
                queue.add(new Visit(seed, 0));
            }
        }
        while (!queue.isEmpty()) {
            Visit current = queue.removeFirst();
            if (current.depth() >= query.maxDepth()) continue;
            List<KnowledgeRelationship> adjacent = relationships.values().stream()
                    .filter(r -> r.tenantId().equals(scope.tenantId()) && r.collectionId().equals(scope.collectionId()))
                    .filter(r -> query.predicates().isEmpty() || query.predicates().contains(r.predicate()))
                    .filter(r -> touches(r, current.entityId(), query.direction()))
                    .sorted(Comparator.comparing(KnowledgeRelationship::id)).toList();
            for (KnowledgeRelationship edge : adjacent) {
                String next = nextEntity(edge, current.entityId(), query.direction());
                if (next == null) continue;
                KnowledgeEntity nextEntity = entities.get(new EntityKey(scope, next));
                if (nextEntity == null) continue;
                if (!visited.contains(next) && foundEntities.size() >= query.maxNodes()) {
                    truncated = true; break;
                }
                if (!foundRelationships.containsKey(edge.id())) {
                    if (foundRelationships.size() >= query.maxEdges()) { truncated = true; break; }
                    foundRelationships.put(edge.id(), edge);
                }
                if (visited.add(next)) {
                    foundEntities.put(next, nextEntity);
                    queue.addLast(new Visit(next, current.depth() + 1));
                }
            }
            if (truncated) break;
        }
        return new GraphTraversalResult(new ArrayList<>(foundEntities.values()),
                new ArrayList<>(foundRelationships.values()), truncated);
    }

    private static KnowledgeEntity merge(KnowledgeEntity existing, KnowledgeEntity incoming) {
        if (!existing.type().equals(incoming.type())) {
            throw new RagException("entity ID collision: canonical entity type cannot change during upsert");
        }
        Set<String> aliases = new LinkedHashSet<>(existing.aliases());
        aliases.addAll(incoming.aliases());
        Map<String, String> properties = new LinkedHashMap<>(existing.properties());
        incoming.properties().forEach(properties::putIfAbsent);
        Set<KnowledgeEvidence> evidence = new LinkedHashSet<>(existing.evidence());
        evidence.addAll(incoming.evidence());
        return new KnowledgeEntity(existing.tenantId(), existing.collectionId(), existing.id(),
                existing.type(), existing.canonicalName(), new ArrayList<>(aliases), properties, new ArrayList<>(evidence));
    }

    private static KnowledgeRelationship merge(KnowledgeRelationship existing, KnowledgeRelationship incoming) {
        if (!existing.fromEntityId().equals(incoming.fromEntityId())
                || !existing.toEntityId().equals(incoming.toEntityId())
                || !existing.predicate().equals(incoming.predicate())) {
            throw new RagException("relationship ID collision: endpoints and predicate cannot change during upsert");
        }
        Map<String, String> properties = new LinkedHashMap<>(existing.properties());
        incoming.properties().forEach(properties::putIfAbsent);
        Set<KnowledgeEvidence> evidence = new LinkedHashSet<>(existing.evidence());
        evidence.addAll(incoming.evidence());
        return new KnowledgeRelationship(existing.tenantId(), existing.collectionId(), existing.id(),
                existing.fromEntityId(), existing.predicate(), existing.toEntityId(), properties,
                new ArrayList<>(evidence), Math.max(existing.confidence(), incoming.confidence()));
    }

    private static boolean matches(KnowledgeEvidence evidence, String sourceId, String sourceVersion) {
        return evidence.sourceId().equals(sourceId) && evidence.sourceVersion().equals(sourceVersion);
    }

    private static boolean touches(KnowledgeRelationship edge, String id, GraphQuery.Direction direction) {
        return switch (direction) {
            case OUTGOING -> edge.fromEntityId().equals(id);
            case INCOMING -> edge.toEntityId().equals(id);
            case BOTH -> edge.fromEntityId().equals(id) || edge.toEntityId().equals(id);
        };
    }

    private static String nextEntity(KnowledgeRelationship edge, String id, GraphQuery.Direction direction) {
        if ((direction == GraphQuery.Direction.OUTGOING || direction == GraphQuery.Direction.BOTH)
                && edge.fromEntityId().equals(id)) return edge.toEntityId();
        if ((direction == GraphQuery.Direction.INCOMING || direction == GraphQuery.Direction.BOTH)
                && edge.toEntityId().equals(id)) return edge.fromEntityId();
        return null;
    }

    private static Scope scope(String tenantId, String collectionId) {
        if (tenantId == null || tenantId.isBlank() || collectionId == null || collectionId.isBlank()) {
            throw new RagException("tenantId and collectionId are required for graph access");
        }
        return new Scope(tenantId.strip(), collectionId.strip());
    }
}
