package tech.kayys.wayang.rag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded reference implementation of {@link GraphStore}.
 *
 * <p>Suitable for tests and single-process development only. It is not durable, distributed,
 * or a substitute for a persistent graph adapter.
 */
public final class InMemoryGraphStore implements GraphStore {

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

    public InMemoryGraphStore() {
        this(DEFAULT_MAX_ENTITIES, DEFAULT_MAX_RELATIONSHIPS);
    }

    public InMemoryGraphStore(int maxEntities, int maxRelationships) {
        if (maxEntities < 1 || maxRelationships < 1) {
            throw new RagException("graph store limits must be positive");
        }
        this.maxEntities = maxEntities;
        this.maxRelationships = maxRelationships;
    }

    @Override
    public synchronized void upsertEntity(KnowledgeEntity entity) {
        if (entity == null) throw new RagException("entity is required");
        EntityKey key = new EntityKey(scope(entity.tenantId(), entity.collectionId()), entity.id());
        if (!entities.containsKey(key) && entities.size() >= maxEntities) {
            throw new RagException("the in-memory graph has reached its entity limit");
        }
        entities.put(key, entity);
    }

    @Override
    public synchronized void upsertRelationship(KnowledgeRelationship relationship) {
        if (relationship == null) throw new RagException("relationship is required");
        Scope scope = scope(relationship.tenantId(), relationship.collectionId());
        EntityKey from = new EntityKey(scope, relationship.fromEntityId());
        EntityKey to = new EntityKey(scope, relationship.toEntityId());
        if (!entities.containsKey(from) || !entities.containsKey(to)) {
            throw new RagException("both relationship endpoints must exist in the same tenant and collection");
        }
        RelationshipKey key = new RelationshipKey(scope, relationship.id());
        if (!relationships.containsKey(key) && relationships.size() >= maxRelationships) {
            throw new RagException("the in-memory graph has reached its relationship limit");
        }
        relationships.put(key, relationship);
    }

    @Override
    public synchronized Optional<KnowledgeEntity> findEntity(String tenantId, String collectionId, String entityId) {
        if (entityId == null || entityId.isBlank()) return Optional.empty();
        return Optional.ofNullable(entities.get(new EntityKey(scope(tenantId, collectionId), entityId)));
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
                if (foundEntities.size() >= query.maxNodes()) {
                    truncated = true;
                    break;
                }
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
                    .sorted(Comparator.comparing(KnowledgeRelationship::id))
                    .toList();

            for (KnowledgeRelationship edge : adjacent) {
                if (!foundRelationships.containsKey(edge.id())) {
                    if (foundRelationships.size() >= query.maxEdges()) {
                        truncated = true;
                        break;
                    }
                    foundRelationships.put(edge.id(), edge);
                }
                String next = nextEntity(edge, current.entityId(), query.direction());
                if (next == null || visited.contains(next)) continue;
                EntityKey nextKey = new EntityKey(scope, next);
                KnowledgeEntity entity = entities.get(nextKey);
                if (entity == null) continue;
                if (foundEntities.size() >= query.maxNodes()) {
                    truncated = true;
                    break;
                }
                visited.add(next);
                foundEntities.put(next, entity);
                queue.addLast(new Visit(next, current.depth() + 1));
            }
            if (truncated) break;
        }

        return new GraphTraversalResult(new ArrayList<>(foundEntities.values()),
                new ArrayList<>(foundRelationships.values()), truncated);
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
        return new Scope(tenantId, collectionId);
    }
}
