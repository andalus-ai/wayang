package tech.kayys.wayang.bpmn;

/** Supported BPMN-style element kinds. */
public enum ElementType {
    START_EVENT("wayang-bpmn", false),
    END_EVENT("wayang-bpmn", false),
    EXCLUSIVE_GATEWAY("wayang-bpmn", false),
    PARALLEL_GATEWAY("wayang-bpmn", false),
    INCLUSIVE_GATEWAY("wayang-bpmn", false),
    /** Calls an arbitrary connector/service; config {@code executorType} overrides the default. */
    SERVICE_TASK("wayang-service", false),
    /** Evaluates a DMN decision (see wayang-dmn). */
    BUSINESS_RULE_TASK("wayang-dmn", false),
    /** Runs an AI agent (executed by Andalus). */
    AGENT_TASK("wayang-agent", false),
    /** Runs an Enterprise Integration Pattern (see wayang-integration). */
    EIP_TASK("wayang-eip", false),
    /** Waits for a person to complete a form/approval. */
    USER_TASK("wayang-human", true),
    /** Waits for a duration or date. */
    TIMER_EVENT("wayang-bpmn", true),
    /** Waits for a correlated message/signal. */
    MESSAGE_CATCH_EVENT("wayang-bpmn", true);

    private final String executorType;
    private final boolean wait;

    ElementType(String executorType, boolean wait) {
        this.executorType = executorType;
        this.wait = wait;
    }

    /** Default Gamelan executor type that runs this element. */
    public String defaultExecutorType() {
        return executorType;
    }

    /** True for elements that complete later, outside the engine's own thread of control. */
    public boolean isWait() {
        return wait;
    }

    public boolean isGateway() {
        return this == EXCLUSIVE_GATEWAY || this == PARALLEL_GATEWAY || this == INCLUSIVE_GATEWAY;
    }

    public boolean isControl() {
        return this == START_EVENT || this == END_EVENT || isGateway();
    }
}
