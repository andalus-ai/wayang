package tech.kayys.wayang.adapter.andalus;

import java.util.List;
import java.util.Map;

import tech.kayys.andalus.plugin.spi.node.NodeDefinition;
import tech.kayys.andalus.plugin.spi.node.NodeProvider;

/**
 * Contributes Wayang's BPMN, DMN and EIP nodes to the Andalus node catalog
 * (discovered through {@link java.util.ServiceLoader}), so the Studio palette
 * can offer them next to the agent nodes. The schemas are JSON Schema strings
 * describing each node's {@code configuration}; they mirror what the Wayang
 * handlers read.
 */
public final class WayangNodeProvider implements NodeProvider {

    private static final String BPMN = "Wayang / Process";
    private static final String DMN = "Wayang / Decisions";
    private static final String EIP = "Wayang / Integration";

    @Override
    public String id() {
        return "tech.kayys.wayang.nodes";
    }

    @Override
    public String name() {
        return "Wayang";
    }

    @Override
    public String version() {
        return "0.0.1";
    }

    @Override
    public String description() {
        return "BPMN process elements, DMN decisions and EIP patterns.";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public List<NodeDefinition> nodes() {
        return List.of(
                node("wayang.bpmn.start", "Start", BPMN, "play", "#16A34A", "{}", Map.of()),
                node("wayang.bpmn.end", "End", BPMN, "stop", "#DC2626", "{}", Map.of()),
                node("wayang.bpmn.exclusive-gateway", "Exclusive gateway (XOR)", BPMN, "git-branch", "#F59E0B", "{}", Map.of()),
                node("wayang.bpmn.parallel-gateway", "Parallel gateway (AND)", BPMN, "git-merge", "#F59E0B", "{}", Map.of()),
                node("wayang.bpmn.inclusive-gateway", "Inclusive gateway (OR)", BPMN, "git-fork", "#F59E0B", "{}", Map.of()),
                node("wayang.bpmn.user-task", "User task", BPMN, "user",  "#2563EB",
                        "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"assignee\":{\"type\":\"string\"},\"form\":{\"type\":\"object\"}}}",
                        Map.of("title", "")),
                node("wayang.bpmn.service-task", "Service task", BPMN, "plug", "#2563EB",
                        "{\"type\":\"object\",\"required\":[\"connector\"],\"properties\":{\"connector\":{\"type\":\"string\"},\"inputs\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}}}",
                        Map.of("connector", "")),
                node("wayang.bpmn.agent-task", "Agent task", BPMN, "bot", "#7C3AED",
                        "{\"type\":\"object\",\"required\":[\"agent\"],\"properties\":{\"agent\":{}}}", Map.of()),
                node("wayang.bpmn.timer", "Timer", BPMN, "clock", "#0EA5E9",
                        "{\"type\":\"object\",\"properties\":{\"duration\":{\"type\":\"string\",\"description\":\"ISO-8601, e.g. PT1H\"}}}",
                        Map.of("duration", "PT1H")),
                node("wayang.bpmn.message-catch", "Wait for message", BPMN, "mail", "#0EA5E9", "{}", Map.of()),
                node("wayang.dmn.decision", "Business rule (DMN)", DMN, "table", "#0D9488",
                        "{\"type\":\"object\",\"required\":[\"model\"],\"properties\":{\"model\":{\"type\":\"string\"},\"decisions\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"inputs\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}}}",
                        Map.of()),
                node("wayang.eip.pipeline", "Integration pipeline (EIP)", EIP, "shuffle", "#DB2777",
                        "{\"type\":\"object\",\"required\":[\"pipeline\"],\"properties\":{\"message\":{\"type\":\"string\"},\"pipeline\":{\"type\":\"object\"}}}",
                        Map.of("message", "input")));
    }

    private static NodeDefinition node(String type, String label, String category, String icon, String color,
            String configSchema, Map<String, Object> defaults) {
        return new NodeDefinition(type, label, category, "", label, icon, color, configSchema, "", "", defaults);
    }
}
