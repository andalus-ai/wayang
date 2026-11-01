package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.eip.IdempotencyStore;

class MappingTest {

    private static final List<Map<String, Object>> SEEN = new java.util.ArrayList<>();

    private static ReferenceEngine engine() {
        Map<String, ServiceHandler.Connector> c = Map.of("crm", in -> {
            SEEN.add(in);
            return Map.of("status", 200, "data", Map.of("tier", "gold", "secret", "x"));
        });
        return new ReferenceEngine(new NodeRunner(List.of(new ControlHandler(new WaitRegistry.InMemory()),
                new ServiceHandler(c), new EipHandler(new IdempotencyStore.InMemory(10), c))), new WaitRegistry.InMemory());
    }

    private static ReferenceEngine.Run run(Map<String, Object> cfg, Map<String, Object> vars) {
        var p = ProcessModel.builder("p").start("s").task("call", ElementType.SERVICE_TASK, cfg).end("e")
                .flow("s", "call").flow("call", "e").build();
        return engine().start(ProcessCompiler.compile(p), vars);
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], kv[i + 1]);
        }
        return r;
    }

    @Test
    void inputAndOutputMappingShapeTheConnectorCall() {
        SEEN.clear();
        var r = run(m("connector", "crm", "inputMapping", m("customer.id", "order.customerId", "mode", m("$const", "lookup")),
                "outputMapping", m("tier", "result.data.tier", "ok", "result.status = 200")), m("order", m("customerId", "c9")));
        assertEquals(ReferenceEngine.Status.COMPLETED, r.status());
        assertEquals(m("customer", m("id", "c9"), "mode", "lookup"), SEEN.get(0));
        assertEquals("gold", r.variables().get("call.tier"));
        assertEquals(true, r.variables().get("call.ok"));
        assertNull(r.variables().get("call.data"));
    }

    @Test
    void outputModeMergeKeepsRawOutputToo() {
        var r = run(m("connector", "crm", "outputMode", "merge", "outputMapping", m("tier", "result.data.tier")), Map.of());
        assertEquals("gold", r.variables().get("call.tier"));
        assertEquals(200, ((Number) r.variables().get("call.status")).intValue());
    }

    @Test
    void badMappingsFailTheNodeWithoutRetry() {
        var r = run(m("connector", "crm", "inputMapping", "1 + 1"), Map.of());
        assertEquals(ReferenceEngine.Status.FAILED, r.status());
        assertTrue(r.error().contains("inputMapping"));
        var r2 = run(m("connector", "crm", "outputMapping", m("a", "result.")), Map.of());
        assertEquals(ReferenceEngine.Status.FAILED, r2.status());
        assertTrue(r2.error().contains("Mapping failed"));
    }

    @Test
    void eipCallStepUsesTheConnectors() {
        SEEN.clear();
        var p = ProcessModel.builder("p").start("s").task("e1", ElementType.EIP_TASK, m("message", "input",
                "pipeline", m("steps", List.of(m("type", "call", "connector", "crm", "input", m("id", "body.id"),
                        "output", m("tier", "result.data.tier"), "into", "crm"))))).end("e")
                .flow("s", "e1").flow("e1", "e").build();
        var r = engine().start(ProcessCompiler.compile(p), m("input", m("id", "c1")));
        assertEquals(ReferenceEngine.Status.COMPLETED, r.status());
        assertEquals(m("id", "c1"), SEEN.get(0));
        assertTrue(String.valueOf(r.variables()).contains("gold"));
    }
}
