package tech.kayys.wayang.runtime;

import java.util.Map;

import tech.kayys.wayang.bpmn.PlanNode;

/**
 * Executes the <em>work</em> of an active node for one executor type. Handlers
 * return only the node's own outputs; flow flags and dead-path handling are
 * added by {@link NodeRunner}. A handler must not throw for expected failures;
 * return {@link NodeOutcome.Failed} instead.
 */
public interface DomainHandler {

    /** The Gamelan executor type this handler serves, e.g. {@code wayang-dmn}. */
    String executorType();

    /**
     * @param runId identifies the process instance (used to key waits)
     * @param vars  flat workflow variables ({@code nodeId.key} naming)
     */
    NodeOutcome handle(String runId, PlanNode node, Map<String, Object> vars);
}
