package tech.kayys.wayang.rag;

import java.util.List;
import java.util.Set;

/** Scoped, bounded lookup of possible canonical entities for query mention resolution. */
@FunctionalInterface
public interface GraphEntityLookup {
    List<KnowledgeEntity> findCandidates(
            String tenantId, String collectionId, Set<String> entityTypes, int limit);
}
