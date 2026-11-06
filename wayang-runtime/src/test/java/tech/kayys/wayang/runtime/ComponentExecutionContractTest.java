package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComponentExecutionContractTest {

    @Test
    void requestSnapshotsInputAndConfigurationMaps() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("customerId", "c-42");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("timeout", 1500);

        var request = new ExecutionRequest("run-1", "wayang.connector.http", "1.0.0",
                "invoke", inputs, config, ExecutionMetadata.empty());
        inputs.put("customerId", "changed");
        config.put("timeout", 0);

        assertEquals("c-42", request.inputs().get("customerId"));
        assertEquals(1500, request.configuration().get("timeout"));
        assertThrows(UnsupportedOperationException.class,
                () -> request.inputs().put("new", "value"));
    }

    @Test
    void validatesRequiredRequestIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionRequest(
                "run-1", " ", "1.0.0", "invoke", Map.of(), Map.of(), ExecutionMetadata.empty()));
        assertThrows(NullPointerException.class, () -> new ExecutionRequest(
                "run-1", "component", "1.0.0", "invoke", null, Map.of(), ExecutionMetadata.empty()));
    }

    @Test
    void resultTypesRepresentCompletedSuspendedRejectedAndFailed() {
        assertInstanceOf(ComponentExecutionResult.Completed.class,
                new ComponentExecutionResult.Completed(Map.of("ok", true)));
        assertInstanceOf(ComponentExecutionResult.Suspended.class,
                new ComponentExecutionResult.Suspended("resume-1", Map.of()));
        assertInstanceOf(ComponentExecutionResult.Rejected.class,
                new ComponentExecutionResult.Rejected("INVALID", "invalid request"));
        assertInstanceOf(ComponentExecutionResult.Failed.class,
                new ComponentExecutionResult.Failed("temporary", true));
        assertThrows(IllegalArgumentException.class,
                () -> new ComponentExecutionResult.Suspended(" ", Map.of()));
    }
}
