package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;

class ConnectorInputTest {

    private static final class Declared implements ServiceHandler.Connector {
        final AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
        final Map<String, Object> answer;

        Declared(Map<String, Object> answer) {
            this.answer = answer;
        }

        @Override
        public List<ConnectorInput> inputs() {
            return List.of(ConnectorInput.required("who", "string", ""), ConnectorInput.optional("note", "string", ""));
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) {
            seen.set(new java.util.HashMap<>(inputs));
            return answer;
        }
    }

    private static ReferenceEngine.Run run(Declared c, Map<String, Object> inputs, Map<String, Object> vars) {
        ProcessModel m = ProcessModel.builder("p").start("s").task("call", ElementType.SERVICE_TASK, Map.of("connector", "x", "inputs", inputs))
                .end("e").flow("s", "call").flow("call", "e").build();
        var engine = new ReferenceEngine(new NodeRunner(List.of(new ControlHandler(new WaitRegistry.InMemory()),
                new ServiceHandler(Map.of("x", c)))), new WaitRegistry.InMemory());
        return engine.start(ProcessCompiler.compile(m), vars);
    }

    @Test
    void aMissingMandatoryInputWarnsAndTheRunCompletes() {
        Declared c = new Declared(Map.of("ok", true));
        var run = run(c, Map.of("who", "who", "note", "note"), Map.of());
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status(), String.valueOf(run.error()));
        assertNull(c.seen.get().get("who"));
        assertEquals(List.of("mandatory input 'who' was not provided"), run.variables().get("call.warnings"));
        assertEquals(true, run.variables().get("call.ok"));
    }

    @Test
    void anEmptyValueCountsAsMissingAndAnOptionalOneDoesNot() {
        var run = run(new Declared(Map.of()), Map.of("who", "who"), Map.of("who", "   "));
        assertEquals(List.of("mandatory input 'who' was not provided"), run.variables().get("call.warnings"));
    }

    @Test
    void nothingIsAddedWhenEverythingIsProvidedOrTheConnectorSaysItsOwn() {
        assertFalse(run(new Declared(Map.of("ok", true)), Map.of("who", "who"), Map.of("who", "ann")).variables().containsKey("call.warnings"));
        var own = run(new Declared(Map.of("warnings", List.of("mine"))), Map.of("who", "who"), Map.of());
        assertEquals(List.of("mine"), own.variables().get("call.warnings"));
    }
}
