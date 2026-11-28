package tech.kayys.wayang.rag;

import java.util.List;
import java.util.Optional;

/**
 * Tenant-isolated registry contract for immutable knowledge schema versions.
 *
 * <p>Every operation requires an explicit tenant. Implementations must never
 * infer a tenant, fall back to a global namespace, or resolve an implicit
 * "latest" version.</p>
 */
public interface TenantKnowledgeSchemaRegistry {

    /** Registers an immutable schema version in the tenant namespace. */
    void register(String tenantId, KnowledgeSchema schema);

    /** Finds an exact schema version within one tenant namespace. */
    Optional<KnowledgeSchema> find(String tenantId, String schemaId, String version);

    /** Lists all versions of a schema within one tenant namespace. */
    List<KnowledgeSchema> versions(String tenantId, String schemaId);
}
