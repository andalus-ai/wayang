package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ComponentExecutorRegistryTest {

    @Test
    void dispatchesByComponentIdAndReturnsResult() {
        ComponentExecutor executor = executor("family-a", new ComponentExecutionResult.Completed(Map.of("v", 1)));
        var registry = ComponentExecutorRegistry.of(Map.of("component.alpha", executor));
        assertEquals(1, ((ComponentExecutionResult.Completed) registry.execute(request("component.alpha"))).outputs().get("v"));
        assertTrue(registry.contains("component.alpha"));
        assertFalse(registry.contains("component.beta"));
    }

    @Test
    void unknownComponentIsRejectedWithoutThrowing() {
        var result = ComponentExecutorRegistry.empty().execute(request("unknown.component"));
        assertEquals("EXECUTOR_NOT_FOUND", ((ComponentExecutionResult.Rejected) result).code());
    }

    @Test
    void rejectsDuplicateBindings() {
        var binding = new ComponentExecutorRegistry.ComponentExecutorBinding("same", executor("x",
                new ComponentExecutionResult.Completed(Map.of())));
        assertThrows(IllegalStateException.class, () -> ComponentExecutorRegistry.from(java.util.List.of(
                binding, binding)));
    }

    @Test
    void registrySnapshotCannotBeModified() {
        var registry = ComponentExecutorRegistry.of(Map.of("component.alpha",
                executor("family-a", new ComponentExecutionResult.Completed(Map.of()))));
        assertThrows(UnsupportedOperationException.class,
                () -> registry.executors().put("component.beta", executor("b",
                        new ComponentExecutionResult.Completed(Map.of()))));
    }

    private static ComponentExecutor executor(String family, ComponentExecutionResult result) {
        return new ComponentExecutor() {
            public String executorId() { return family; }
            public ComponentExecutionResult execute(ExecutionRequest request) { return result; }
        };
    }

    private static ExecutionRequest request(String componentId) {
        return new ExecutionRequest("run-1", componentId, "1.0", "execute",
                Map.of(), Map.of(), ExecutionMetadata.empty());
    }
}
