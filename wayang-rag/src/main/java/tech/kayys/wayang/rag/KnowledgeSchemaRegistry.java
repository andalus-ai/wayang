package tech.kayys.wayang.rag;

import java.util.List;
import java.util.Optional;

/**
 * Provider-neutral, immutable-version registry for knowledge graph schemas.
 *
 * Implementations must not replace an existing (schemaId, version) definition.
 * Registry persistence and tenant authorization are deployment responsibilities.
 */
public interface KnowledgeSchemaRegistry {

    /** Registers a new immutable schema version; an identical registration is idempotent. */
    void register(KnowledgeSchema schema);

    /** Finds one exact schema version. */
    Optional<KnowledgeSchema> find(String schemaId, String version);

    /** Lists registered versions for a schema ID in implementation-defined stable order. */
    List<KnowledgeSchema> versions(String schemaId);
}
