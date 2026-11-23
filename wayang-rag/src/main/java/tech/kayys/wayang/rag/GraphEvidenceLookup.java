package tech.kayys.wayang.rag;

import java.util.List;

/**
 * Resolves graph provenance pointers to document passages.
 * Implementations MUST enforce tenant/collection scope, caller document ACLs, source version
 * and chunk identity before returning any passage. Graph traversal is not authorization.
 */
@FunctionalInterface
public interface GraphEvidenceLookup {
    List<Hit> findAuthorizedPassages(String tenantId, String collectionId,
            List<GraphEvidenceReference> evidence, int limit);
}
