package tech.kayys.wayang.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Metadata catalog for the executor capabilities shipped by the Wayang runtime. */
public final class BuiltInComponents {
    public static final String VERSION = "0.0.1";
    private static final List<ComponentDescriptor> COMPONENTS = List.of(
            component("wayang.bpmn.control-flow", "BPMN Control Flow", "Orchestration", "account_tree",
                    ComponentDescriptor.Kind.NODE, "wayang-bpmn", "BPMN events and gateways that coordinate workflow control flow",
                    Set.of(), Set.of("workflow.control-flow", "workflow.wait"), Map.of()),
            component("wayang.service.connector", "Service Connector", "Integration", "api",
                    ComponentDescriptor.Kind.CONNECTOR, "wayang-service", "Invoke a configured outbound service connector",
                    Set.of(Profile.Feature.SERVICE), Set.of("integration.outbound"),
                    Map.of("connector", Map.of("type", "string", "minLength", 1, "description", "Registered connector identifier"),
                            "operation", Map.of("type", "string", "description", "Connector operation"),
                            "timeoutMs", Map.of("type", "integer", "minimum", 100, "maximum", 60000)), List.of("connector")),
            component("wayang.eip.pipeline", "EIP Pipeline", "Integration", "workflow",
                    ComponentDescriptor.Kind.NODE, "wayang-eip", "Execute an Enterprise Integration Patterns pipeline",
                    Set.of(Profile.Feature.EIP), Set.of("integration.patterns"),
                    Map.of("pipeline", Map.of("type", "object", "description", "EIP pipeline definition"),
                            "message", Map.of("type", "string", "description", "Expression producing the message")), List.of("pipeline")),
            component("wayang.dmn.decision", "DMN Decision", "Decision", "table",
                    ComponentDescriptor.Kind.DECISION, "wayang-dmn", "Evaluate a registered DMN decision model",
                    Set.of(Profile.Feature.DMN), Set.of("business.decision"),
                    Map.of("model", Map.of("type", "string", "minLength", 1, "description", "Published decision model id"),
                            "inputs", Map.of("type", "object", "description", "Decision input expressions")), List.of("model")),
            component("wayang.agent.invoke", "Agent Invocation", "Agentic", "bot",
                    ComponentDescriptor.Kind.AGENT, "wayang-agent", "Invoke an agent through the configured AgentInvoker boundary",
                    Set.of(Profile.Feature.AGENT), Set.of("agent.invoke"),
                    Map.of("agent", Map.of("type", List.of("string", "object"), "description", "Published agent id or inline spec"),
                            "forget", Map.of("type", "boolean", "description", "Clear conversation memory for the supplied key")), List.of("agent")),
            component("wayang.human.task", "Human Task", "Human", "user-check",
                    ComponentDescriptor.Kind.HUMAN_TASK, "wayang-human", "Create and wait for a human task",
                    Set.of(Profile.Feature.HUMAN), Set.of("human.approval", "human.task"),
                    Map.of("title", Map.of("type", "string"), "assignee", Map.of("type", "string"),
                            "form", Map.of("type", "object", "description", "Opaque form definition")), List.of()));
    private BuiltInComponents() {}
    public static List<ComponentDescriptor> all() { return COMPONENTS; }

    private static ComponentDescriptor component(String id, String name, String category, String icon,
            ComponentDescriptor.Kind kind, String executor, String description, Set<Profile.Feature> features,
            Set<String> capabilities, Map<String, Object> properties) {
        return component(id, name, category, icon, kind, executor, description, features, capabilities, properties, List.of());
    }
    private static ComponentDescriptor component(String id, String name, String category, String icon,
            ComponentDescriptor.Kind kind, String executor, String description, Set<Profile.Feature> features,
            Set<String> capabilities, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object"); schema.put("properties", properties); schema.put("additionalProperties", false);
        if (!required.isEmpty()) schema.put("required", required);
        return new ComponentDescriptor(id, VERSION, kind, executor, description, features, List.of(),
                ComponentDescriptor.IsolationLevel.IN_PROCESS, capabilities, name, category, icon, schema,
                Map.of("layout", "form", "showAdvanced", false));
    }
}
