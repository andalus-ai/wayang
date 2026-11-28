package tech.kayys.wayang.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded process-local registry for tests and development.
 * It is not durable, distributed, or an authorization boundary.
 */
public final class InMemoryKnowledgeSchemaRegistry implements KnowledgeSchemaRegistry {

    private final int maxSchemas;
    private final Map<Key, KnowledgeSchema> schemas = new HashMap<>();

    public InMemoryKnowledgeSchemaRegistry() {
        this(1_000);
    }

    public InMemoryKnowledgeSchemaRegistry(int maxSchemas) {
        if (maxSchemas < 1 || maxSchemas > 100_000) {
            throw new RagException("maxSchemas must be between 1 and 100000");
        }
        this.maxSchemas = maxSchemas;
    }

    @Override
    public synchronized void register(KnowledgeSchema schema) {
        if (schema == null) throw new RagException("knowledge schema is required");
        Key key = new Key(schema.schemaId(), schema.version());
        KnowledgeSchema existing = schemas.get(key);
        if (existing != null) {
            if (!existing.equals(schema)) {
                throw new RagException("schema version is immutable and already registered");
            }
            return;
        }
        if (schemas.size() >= maxSchemas) {
            throw new RagException("knowledge schema registry capacity exceeded");
        }
        schemas.put(key, schema);
    }

    @Override
    public synchronized Optional<KnowledgeSchema> find(String schemaId, String version) {
        if (schemaId == null || version == null) return Optional.empty();
        return Optional.ofNullable(schemas.get(new Key(schemaId, version)));
    }

    @Override
    public synchronized List<KnowledgeSchema> versions(String schemaId) {
        if (schemaId == null || schemaId.isBlank()) return List.of();
        List<KnowledgeSchema> result = new ArrayList<>();
        schemas.forEach((key, schema) -> {
            if (key.schemaId().equals(schemaId)) result.add(schema);
        });
        result.sort(Comparator.comparing(KnowledgeSchema::version));
        return List.copyOf(result);
    }

    private record Key(String schemaId, String version) {
    }
}
