package tech.kayys.wayang.embed;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the release's HTTP service, triggers and command line need from the thing that runs the processes: the in-memory
 * {@link EmbeddedRuntime}, or an engine that keeps runs durably (Gamelan). One tenant, {@link #TENANT}.
 */
public interface ReleaseEngine {

    String TENANT = "release";

    /** What a caller may see of a run. Internal variables (those starting {@code wayang.}) are left out. */
    public record RunInfo(String id, String processId, String status, Map<String, Object> variables, List<String> executedNodes,
            List<Map<String, Object>> waiting, String error, Instant startedAt, Instant finishedAt) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("processId", processId);
            m.put("status", status);
            m.put("variables", variables);
            m.put("executedNodes", executedNodes);
            m.put("waiting", waiting);
            if (error != null) {
                m.put("error", error);
            }
            m.put("startedAt", startedAt.toString());
            if (finishedAt != null) {
                m.put("finishedAt", finishedAt.toString());
            }
            return m;
        }
    }

    /** The engine behind the release cannot be reached right now (a durable engine over the network). Never carries secrets. */
    final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Unavailable(String message) {
            super(message, null, false, false);
        }
    }

    ReleaseBundle bundle();

    Set<String> processIds();

    /** The published version of a process (0 when unknown). */
    int version(String processId);

    /** False while the engine is not able to take runs yet (for example, definitions not yet deployed); reported by {@code /readyz}. */
    default boolean ready() {
        return true;
    }

    /** Names this release needs that nothing provides right now (names only, never values). */
    List<String> missingSecrets();

    /** @throws IllegalArgumentException when there is no such process; @throws IllegalStateException when too many runs are parked; @throws Unavailable when the engine cannot be reached */
    RunInfo start(String processId, Map<String, Object> callerInput);

    Optional<RunInfo> get(String runId);

    /** Newest first. */
    List<RunInfo> list(String processId, int limit);

    /** @return false when no run is waiting on that token */
    boolean completeWait(String token, Map<String, Object> outputs);

    /** @return false unless the run exists and is not finished */
    boolean cancel(String runId);

    /** Completes every due timer wait. @return how many fired */
    int fireDueTimers();
}
