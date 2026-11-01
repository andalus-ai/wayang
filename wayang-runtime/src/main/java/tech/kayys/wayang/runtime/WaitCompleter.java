package tech.kayys.wayang.runtime;

import java.util.Map;

/** Completes a parked node; implemented by the engine adapter (Gamelan, reference engine). */
@FunctionalInterface
public interface WaitCompleter {

    /** @return false when the token is unknown to this engine instance (retry later) */
    boolean complete(String token, Map<String, Object> outputs);
}
