package tech.kayys.wayang.control.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.wayang.schema.canvas.CanvasData;
import tech.kayys.wayang.schema.canvas.CanvasEdge;
import tech.kayys.wayang.schema.canvas.CanvasNode;
import tech.kayys.wayang.schema.canvas.CanvasValidationResult;
import tech.kayys.wayang.schema.validator.ValidationIssue;
import tech.kayys.wayang.schema.validator.ValidationSeverity;

/**
 * Comprehensive canvas validation service.
 */
@ApplicationScoped
public class CanvasValidationService {

    private static final Logger LOG = LoggerFactory.getLogger(CanvasValidationService.class);

    /**
     * Validate entire canvas.
     */
    public Uni<CanvasValidationResult> validate(CanvasData canvas) {
        LOG.info("Validating canvas structure and logic");
        return Uni.createFrom().item(() -> {
            CanvasValidationResult result = new CanvasValidationResult();
            result.validatedAt = Instant.now();

            validateStructure(canvas, result);
            validateNodes(canvas, result);
            validateEdges(canvas, result);
            validateConnectivity(canvas, result);
            validateNoCycles(canvas, result);
            validateBusinessLogic(canvas, result);

            result.isValid = result.errors.isEmpty();
            return result;
        });
    }

    private void validateStructure(CanvasData canvas, CanvasValidationResult result) {
        if (canvas.nodes.isEmpty()) {
            result.errors.add(new ValidationIssue(
                    ValidationSeverity.ERROR,
                    "EMPTY_CANVAS",
                    "Canvas must contain at least one node",
                    List.of(),
                    "Add a start node to begin your workflow"));
        }

        boolean hasStartNode = canvas.nodes.stream()
                .anyMatch(n -> "START".equalsIgnoreCase(n.type));

        if (!hasStartNode && !canvas.nodes.isEmpty()) {
            result.errors.add(new ValidationIssue(
                    ValidationSeverity.ERROR,
                    "NO_START_NODE",
                    "Canvas must have at least one start node",
                    List.of(),
                    "Add a start node to define the entry point"));
        }
    }

    private void validateNodes(CanvasData canvas, CanvasValidationResult result) {
        Set<String> nodeIds = new HashSet<>();
        for (CanvasNode node : canvas.nodes) {
            if (!nodeIds.add(node.id)) {
                result.errors.add(new ValidationIssue(
                        ValidationSeverity.ERROR,
                        "DUPLICATE_NODE_ID",
                        "Duplicate node ID: " + node.id,
                        List.of(node.id),
                        "Ensure all node IDs are unique"));
            }

            if (node.label == null || node.label.isBlank()) {
                result.warnings.add(new ValidationIssue(
                        ValidationSeverity.WARNING,
                        "MISSING_NODE_LABEL",
                        "Node missing label: " + node.id,
                        List.of(node.id),
                        "Add a descriptive label to the node"));
            }
            validateNodeConfig(node, result);
        }
    }

    private void validateNodeConfig(CanvasNode node, CanvasValidationResult result) {
        if (node.config == null || node.config.isEmpty()) {
            result.warnings.add(new ValidationIssue(
                    ValidationSeverity.WARNING,
                    "EMPTY_NODE_CONFIG",
                    "Node has no configuration: " + node.id,
                    List.of(node.id),
                    "Configure the node settings"));
            return;
        }

        switch (node.type.toUpperCase()) {
            case "DECISION" -> validateDecisionNode(node, result);
            case "HUMAN_TASK" -> validateHumanTaskNode(node, result);
            case "AI_AGENT" -> validateAIAgentNode(node, result);
            case "INTEGRATION" -> validateIntegrationNode(node, result);
        }
    }

    private void validateDecisionNode(CanvasNode node, CanvasValidationResult result) {
        if (!node.config.containsKey("condition")) {
            result.errors.add(new ValidationIssue(
                    ValidationSeverity.ERROR,
                    "MISSING_CONDITION",
                    "Decision node missing condition: " + node.id,
                    List.of(node.id),
                    "Add a condition expression to the decision node"));
        }
    }

    private void validateHumanTaskNode(CanvasNode node, CanvasValidationResult result) {
        if (!node.config.containsKey("assignee") && !node.config.containsKey("assigneeRole")) {
            result.warnings.add(new ValidationIssue(
                    ValidationSeverity.WARNING,
                    "NO_ASSIGNEE",
                    "Human task without assignee: " + node.id,
                    List.of(node.id),
                    "Specify an assignee or role for the task"));
        }
    }

    private void validateAIAgentNode(CanvasNode node, CanvasValidationResult result) {
        if (!node.config.containsKey("agentId") && !node.config.containsKey("llmConfig")) {
            result.errors.add(new ValidationIssue(
                    ValidationSeverity.ERROR,
                    "MISSING_AI_CONFIG",
                    "AI agent node missing configuration: " + node.id,
                    List.of(node.id),
                    "Configure the AI agent or LLM settings"));
        }
    }

    private void validateIntegrationNode(CanvasNode node, CanvasValidationResult result) {
        if (!node.config.containsKey("endpointUrl") && !node.config.containsKey("patternType")) {
            result.errors.add(new ValidationIssue(
                    ValidationSeverity.ERROR,
                    "MISSING_ENDPOINT",
                    "Integration node missing endpoint: " + node.id,
                    List.of(node.id),
                    "Configure the integration endpoint or pattern"));
        }
    }

    private void validateEdges(CanvasData canvas, CanvasValidationResult result) {
        Set<String> nodeIds = canvas.nodes.stream().map(n -> n.id).collect(Collectors.toSet());
        for (CanvasEdge edge : canvas.edges) {
            if (!nodeIds.contains(edge.source)) {
                result.errors.add(new ValidationIssue(
                        ValidationSeverity.ERROR,
                        "INVALID_SOURCE",
                        "Edge references non-existent source node: " + edge.source,
                        List.of(edge.id),
                        "Remove the edge or fix the source reference"));
            }
            if (!nodeIds.contains(edge.target)) {
                result.errors.add(new ValidationIssue(
                        ValidationSeverity.ERROR,
                        "INVALID_TARGET",
                        "Edge references non-existent target node: " + edge.target,
                        List.of(edge.id),
                        "Remove the edge or fix the target reference"));
            }
            if (edge.source.equals(edge.target)) {
                result.warnings.add(new ValidationIssue(
                        ValidationSeverity.WARNING,
                        "SELF_LOOP",
                        "Edge creates self-loop: " + edge.id,
                        List.of(edge.id),
                        "Consider if self-loop is intentional"));
            }
        }
    }

    private void validateConnectivity(CanvasData canvas, CanvasValidationResult result) {
        Set<String> connectedNodes = new HashSet<>();
        canvas.edges.forEach(edge -> {
            connectedNodes.add(edge.source);
            connectedNodes.add(edge.target);
        });

        canvas.nodes.stream()
                .filter(n -> !"START".equalsIgnoreCase(n.type))
                .filter(n -> !connectedNodes.contains(n.id))
                .forEach(node -> {
                    result.warnings.add(new ValidationIssue(
                            ValidationSeverity.WARNING,
                            "ORPHANED_NODE",
                            "Node not connected to workflow: " + node.id,
                            List.of(node.id),
                            "Connect the node to the workflow"));
                });
    }

    private void validateNoCycles(CanvasData canvas, CanvasValidationResult result) {
        Map<String, List<String>> graph = new HashMap<>();
        canvas.edges.forEach(edge -> graph.computeIfAbsent(edge.source, k -> new ArrayList<>()).add(edge.target));

        Set<String> visited = new HashSet<>();
        Set<String> recStack = new HashSet<>();

        for (CanvasNode node : canvas.nodes) {
            if (hasCycle(node.id, graph, visited, recStack)) {
                result.errors.add(new ValidationIssue(
                        ValidationSeverity.ERROR,
                        "CIRCULAR_DEPENDENCY",
                        "Circular dependency detected in workflow",
                        List.of(),
                        "Remove circular references or add explicit loop-back edges"));
                break;
            }
        }
    }

    private boolean hasCycle(String node, Map<String, List<String>> graph, Set<String> visited, Set<String> recStack) {
        if (recStack.contains(node))
            return true;
        if (visited.contains(node))
            return false;

        visited.add(node);
        recStack.add(node);

        List<String> neighbors = graph.getOrDefault(node, List.of());
        for (String neighbor : neighbors) {
            if (hasCycle(neighbor, graph, visited, recStack))
                return true;
        }

        recStack.remove(node);
        return false;
    }

    private void validateBusinessLogic(CanvasData canvas, CanvasValidationResult result) {
        boolean hasEndNode = canvas.nodes.stream().anyMatch(n -> "END".equalsIgnoreCase(n.type));
        if (!hasEndNode) {
            result.warnings.add(new ValidationIssue(
                    ValidationSeverity.WARNING,
                    "NO_END_NODE",
                    "Canvas has no explicit end node",
                    List.of(),
                    "Consider adding an end node for clarity"));
        }

        double complexity = calculateComplexity(canvas);
        result.metrics.put("complexity", complexity);
        if (complexity > 50) {
            result.warnings.add(new ValidationIssue(
                    ValidationSeverity.WARNING,
                    "HIGH_COMPLEXITY",
                    String.format("Workflow complexity is high (%.1f)", complexity),
                    List.of(),
                    "Consider breaking down into smaller workflows"));
        }
    }

    private double calculateComplexity(CanvasData canvas) {
        int nodes = canvas.nodes.size();
        int edges = canvas.edges.size();
        int decisions = (int) canvas.nodes.stream().filter(n -> "DECISION".equalsIgnoreCase(n.type)).count();
        return edges - nodes + 2 + decisions;
    }
}
