package tech.kayys.wayang.rag;

import java.util.List;

/** SPI for mapping mention candidates to canonical entities within one tenant and collection. */
@FunctionalInterface
public interface EntityResolver {
    List<EntityResolutionDecision> resolve(EntityResolutionRequest request);
}
