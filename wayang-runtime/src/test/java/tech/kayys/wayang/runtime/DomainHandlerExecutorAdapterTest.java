package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.PlanNode;

class DomainHandlerExecutorAdapterTest {

    @Test
    void adaptsCompletedHandlerOutcome() {
        DomainHandler handler = new DomainHandler() {
            public String executorType() { return "legacy-test"; }
            public NodeOutcome handle(String runId, PlanNode node, Map<String, Object> vars) {
                assertEquals("run-1", runId);
                assertEquals("task-1", node.id());
                assertEquals("input", vars.get("key"));
                return new NodeOutcome.Completed(Map.of("result", "ok"));
            }
        };
        var adapter = new DomainHandlerExecutorAdapter(handler, "test.component", "1.0");
        var result = adapter.execute(request());
        assertEquals("legacy-test", adapter.executorId());
        assertEquals("ok", ((ComponentExecutionResult.Completed) result).outputs().get("result"));
    }

    @Test
    void rejectsComponentAndVersionMismatchBeforeInvokingHandler() {
        DomainHandler handler = new DomainHandler() {
            public String executorType() { return "legacy-test"; }
            public NodeOutcome handle(String runId, PlanNode node, Map<String, Object> vars) {
                fail("handler must not be called");
                return new NodeOutcome.Failed("unreachable", false);
            }
        };
        var adapter = new DomainHandlerExecutorAdapter(handler, "test.component", "1.0");
        var wrongComponent = new ExecutionRequest("run-1", "other.component", "1.0", "execute",
                Map.of(), request().configuration(), ExecutionMetadata.empty());
        assertEquals("COMPONENT_MISMATCH",
                ((ComponentExecutionResult.Rejected) adapter.execute(wrongComponent)).code());
        var wrongVersion = new ExecutionRequest("run-1", "test.component", "2.0", "execute",
                Map.of(), request().configuration(), ExecutionMetadata.empty());
        assertEquals("VERSION_MISMATCH",
                ((ComponentExecutionResult.Rejected) adapter.execute(wrongVersion)).code());
    }

    private static ExecutionRequest request() {
        Map<String, Object> node = Map.of(
                "wayang.element.id", "task-1",
                "wayang.element.name", "Task",
                "wayang.element.type", ElementType.SERVICE_TASK.name(),
                "wayang.element.config", Map.of("executorType", "legacy-test"),
                "wayang.element.incoming", java.util.List.of(),
                "wayang.element.outgoing", java.util.List.of());
        return new ExecutionRequest("run-1", "test.component", "1.0", "execute",
                Map.of("key", "input"), Map.of("wayang.planNode", node), ExecutionMetadata.empty());
    }
}
