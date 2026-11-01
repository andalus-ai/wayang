package tech.kayys.wayang.runtime;

import java.util.Map;

/** Result of running one plan node. */
public sealed interface NodeOutcome {

    /** Finished; {@code outputs} include the outgoing-flow flags. */
    record Completed(Map<String, Object> outputs) implements NodeOutcome {
        public Completed {
            outputs = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(outputs));
        }
    }

    /** Parked until an external party completes {@code ticket}; nothing has been published yet. */
    record Waiting(WaitTicket ticket) implements NodeOutcome {
    }

    /** Failed; {@code retryable} hints whether the engine's retry policy should try again. */
    record Failed(String error, boolean retryable) implements NodeOutcome {
    }
}
