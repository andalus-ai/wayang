package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.PlanNode;

class NodeRunnerComponentDispatchTest {

    private static final String COMPONENT = "test.component.service";

    private ComponentExecutorCatalog catalog(ComponentExecutor executor) {
        ComponentDescriptor descriptor = new ComponentDescriptor(COMPONENT, "1.0",
                ComponentDescriptor.Kind.CONNECTOR, "test-service", "test", java.util.Set.of(),
                List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS, java.util.Set.of());
        ComponentRegistry registry = new ComponentRegistry(List.of(descriptor), Profile.FULL);
        return ComponentExecutorCatalog.resolve(registry, List.of(COMPONENT), List.of(
                new ComponentExecutorCatalog.ComponentExecutorBinding(COMPONENT, "1.0", executor)));
    }

    private PlanNode node(String componentId) {
        Map<String, Object> config = componentId == null ? Map.of()
                : Map.of(ComponentIdentity.ID_KEY, componentId);
        return new PlanNode("node-1", "Node", ElementType.SERVICE_TASK, "test-service",
                config, List.of(), List.of());
    }

    @Test
    void dispatchesExplicitComponentThroughCatalog() {
        ComponentExecutor executor = new ComponentExecutor() {
            @Override public String executorId() { return "test-service"; }
            @Override public ComponentExecutionResult execute(ExecutionRequest request) {
                assertEquals(COMPONENT, request.componentId());
                assertEquals("1.0", request.componentVersion());
                assertEquals("run-1", request.runId());
                assertEquals(Map.of("input", "value"), request.inputs());
                assertTrue(request.configuration().containsKey("wayang.planNode"));
                return new ComponentExecutionResult.Completed(Map.of("answer", 42));
            }
        };
        NodeRunner runner = new NodeRunner(List.of()).withProfile(Profile.FULL)
                .withComponentCatalog(catalog(executor));

        NodeOutcome outcome = runner.run("run-1", node(COMPONENT), Map.of("input", "value"));

        assertInstanceOf(NodeOutcome.Completed.class, outcome);
        assertEquals(42, ((NodeOutcome.Completed) outcome).outputs().get("answer"));
    }

    @Test
    void explicitComponentFailsClosedWhenCatalogIsMissingOrDoesNotContainIt() {
        NodeRunner withoutCatalog = new NodeRunner(List.of());
        NodeOutcome missingCatalog = withoutCatalog.run("run-1", node(COMPONENT), Map.of());
        assertInstanceOf(NodeOutcome.Failed.class, missingCatalog);
        assertTrue(((NodeOutcome.Failed) missingCatalog).error().contains("no component catalog"));

        ComponentExecutor other = new ComponentExecutor() {
            @Override public String executorId() { return "test-service"; } // the catalog requires the descriptor's executor family; what is missing below is the component id
            @Override public ComponentExecutionResult execute(ExecutionRequest request) {
                return new ComponentExecutionResult.Completed(Map.of());
            }
        };
        NodeRunner withCatalog = new NodeRunner(List.of()).withProfile(Profile.FULL)
                .withComponentCatalog(catalog(other));
        NodeOutcome unavailable = withCatalog.run("run-1", node("test.component.unknown"), Map.of());
        assertInstanceOf(NodeOutcome.Failed.class, unavailable);
        assertTrue(((NodeOutcome.Failed) unavailable).error().contains("not part of the resolved runtime catalog"));
    }

    @Test
    void preservesLegacyHandlerPathForNodesWithoutComponentId() {
        DomainHandler legacy = new DomainHandler() {
            @Override public String executorType() { return "test-service"; }
            @Override public NodeOutcome handle(String runId, PlanNode planNode, Map<String, Object> vars) {
                return new NodeOutcome.Completed(Map.of("legacy", true));
            }
        };
        ComponentExecutor unused = new ComponentExecutor() {
            @Override public String executorId() { return "test-service"; }
            @Override public ComponentExecutionResult execute(ExecutionRequest request) {
                fail("component executor must not be called for a legacy node");
                return new ComponentExecutionResult.Failed("unreachable", false);
            }
        };
        NodeRunner runner = new NodeRunner(List.of(legacy)).withProfile(Profile.FULL)
                .withComponentCatalog(catalog(unused));

        NodeOutcome outcome = runner.run("run-1", node(null), Map.of());

        assertInstanceOf(NodeOutcome.Completed.class, outcome);
        assertEquals(true, ((NodeOutcome.Completed) outcome).outputs().get("legacy"));
    }


    @Test
    void passesExplicitOperationAndRejectsVersionMismatch() {
        ComponentExecutor executor = new ComponentExecutor() {
            @Override public String executorId() { return "test-service"; }
            @Override public ComponentExecutionResult execute(ExecutionRequest request) {
                assertEquals("inspect", request.operation());
                return new ComponentExecutionResult.Completed(Map.of("ok", true));
            }
        };
        NodeRunner runner = new NodeRunner(List.of()).withProfile(Profile.FULL)
                .withComponentCatalog(catalog(executor));
        PlanNode operationNode = new PlanNode("node-1", "Node", ElementType.SERVICE_TASK, "test-service",
                Map.of(ComponentIdentity.ID_KEY, COMPONENT,
                        ComponentIdentity.VERSION_KEY, "1.0",
                        ComponentIdentity.OPERATION_KEY, "inspect"), List.of(), List.of());
        NodeOutcome operationOutcome = runner.run("run-1", operationNode, Map.of());
        assertInstanceOf(NodeOutcome.Completed.class, operationOutcome);

        PlanNode mismatchedVersion = new PlanNode("node-1", "Node", ElementType.SERVICE_TASK, "test-service",
                Map.of(ComponentIdentity.ID_KEY, COMPONENT, ComponentIdentity.VERSION_KEY, "2.0"),
                List.of(), List.of());
        NodeOutcome mismatch = runner.run("run-1", mismatchedVersion, Map.of());
        assertInstanceOf(NodeOutcome.Failed.class, mismatch);
        assertTrue(((NodeOutcome.Failed) mismatch).error().contains("Component version mismatch"));
    }

    @Test
    void rejectsCatalogProfileMismatch() {
        ComponentExecutor executor = new ComponentExecutor() {
            @Override public String executorId() { return "test-service"; }
            @Override public ComponentExecutionResult execute(ExecutionRequest request) {
                return new ComponentExecutionResult.Completed(Map.of());
            }
        };
        NodeRunner runner = new NodeRunner(List.of()).withComponentCatalog(catalog(executor));
        assertThrows(IllegalArgumentException.class, () -> runner.withProfile(Profile.AUTOMATION));
    }
}
