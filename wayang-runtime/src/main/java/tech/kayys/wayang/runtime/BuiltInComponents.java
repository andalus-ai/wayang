package tech.kayys.wayang.runtime;

import java.util.List;
import java.util.Set;

/**
 * Metadata catalog for the executor capabilities shipped by the Wayang runtime.
 *
 * <p>The version is the Wayang distribution version, not a Maven artifact version
 * inferred at runtime. Keep it aligned with the parent release version when cutting
 * a release. Component descriptors are metadata only; the actual implementations
 * remain the handlers registered with {@link NodeRunner}.</p>
 */
public final class BuiltInComponents {

    /** Current Wayang distribution version from the root Maven POM. */
    public static final String VERSION = "0.0.1";

    private static final List<ComponentDescriptor> COMPONENTS = List.of(
            component("wayang.bpmn.control-flow", ComponentDescriptor.Kind.NODE, "wayang-bpmn",
                    "BPMN events and gateways that coordinate workflow control flow",
                    Set.of(), Set.of("workflow.control-flow", "workflow.wait")),
            component("wayang.service.connector", ComponentDescriptor.Kind.CONNECTOR, "wayang-service",
                    "Invoke a configured outbound service connector",
                    Set.of(Profile.Feature.SERVICE), Set.of("integration.outbound")),
            component("wayang.eip.pipeline", ComponentDescriptor.Kind.NODE, "wayang-eip",
                    "Execute an Enterprise Integration Patterns pipeline",
                    Set.of(Profile.Feature.EIP), Set.of("integration.patterns")),
            component("wayang.dmn.decision", ComponentDescriptor.Kind.DECISION, "wayang-dmn",
                    "Evaluate a registered DMN decision model",
                    Set.of(Profile.Feature.DMN), Set.of("business.decision")),
            component("wayang.agent.invoke", ComponentDescriptor.Kind.AGENT, "wayang-agent",
                    "Invoke an agent through the configured AgentInvoker boundary",
                    Set.of(Profile.Feature.AGENT), Set.of("agent.invoke")),
            component("wayang.human.task", ComponentDescriptor.Kind.HUMAN_TASK, "wayang-human",
                    "Create and wait for a human task",
                    Set.of(Profile.Feature.HUMAN), Set.of("human.approval", "human.task")));

    private BuiltInComponents() {
    }

    /** All built-in descriptors, including components disabled by a particular profile. */
    public static List<ComponentDescriptor> all() {
        return COMPONENTS;
    }

    private static ComponentDescriptor component(
            String id,
            ComponentDescriptor.Kind kind,
            String executorType,
            String description,
            Set<Profile.Feature> requiredFeatures,
            Set<String> capabilities) {
        return new ComponentDescriptor(id, VERSION, kind, executorType, description, requiredFeatures,
                List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS, capabilities);
    }
}
