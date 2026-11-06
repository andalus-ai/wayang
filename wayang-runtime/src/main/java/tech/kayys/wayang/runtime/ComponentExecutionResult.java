package tech.kayys.wayang.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Runtime-neutral outcome returned by an execution component. */
public sealed interface ComponentExecutionResult
        permits ComponentExecutionResult.Completed, ComponentExecutionResult.Suspended,
                ComponentExecutionResult.Rejected, ComponentExecutionResult.Failed {

    record Completed(Map<String, Object> outputs) implements ComponentExecutionResult {
        public Completed {
            outputs = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(outputs, "outputs")));
        }
    }

    /** Component has parked and exposes a stable token for later resumption. */
    record Suspended(String resumeToken, Map<String, Object> details) implements ComponentExecutionResult {
        public Suspended {
            if (resumeToken == null || resumeToken.isBlank())
                throw new IllegalArgumentException("resumeToken must not be blank");
            details = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(details, "details")));
        }
    }

    /** Request cannot be executed due to a caller/component contract violation. */
    record Rejected(String code, String message) implements ComponentExecutionResult {
        public Rejected {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
            Objects.requireNonNull(message, "message");
        }
    }

    /** Execution failed; retryable is advisory and policy remains owned by the runtime. */
    record Failed(String message, boolean retryable) implements ComponentExecutionResult {
        public Failed { Objects.requireNonNull(message, "message"); }
    }
}
