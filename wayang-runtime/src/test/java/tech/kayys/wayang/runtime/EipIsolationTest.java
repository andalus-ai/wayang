package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.eip.AggregationStore;
import tech.kayys.wayang.eip.ClaimStore;
import tech.kayys.wayang.eip.EipState;
import tech.kayys.wayang.eip.IdempotencyStore;

/** The EIP node keeps idempotency keys, claim tokens and aggregation groups private to tenant + process + node. */
class EipIsolationTest {

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], kv[i + 1]);
        }
        return r;
    }

    private final IdempotencyStore idem = new IdempotencyStore.InMemory(100);
    private final ClaimStore claims = new ClaimStore.InMemory(100);
    private final AggregationStore agg = new AggregationStore.InMemory(100);
    private final EipState state = new EipState();

    private ReferenceEngine engine(Map<String, ServiceHandler.Connector> connectors) {
        return new ReferenceEngine(new NodeRunner(List.of(new ControlHandler(new WaitRegistry.InMemory()),
                new EipHandler(idem, connectors, claims, agg, state))), new WaitRegistry.InMemory());
    }

    private ReferenceEngine.Run run(String tenant, String process, Map<String, Object> pipeline, Object input) {
        var p = ProcessModel.builder(process).start("s").task("eip", ElementType.SERVICE_TASK, m("wayang.executor", "wayang-eip")).end("e")
                .flow("s", "eip").flow("eip", "e").build();
        var plan = ProcessCompiler.compile(p);
        var nodes = new java.util.ArrayList<tech.kayys.wayang.bpmn.PlanNode>();
        for (var n : plan.nodes()) {
            nodes.add(n.id().equals("eip") ? new tech.kayys.wayang.bpmn.PlanNode(n.id(), n.name(), n.type(), "wayang-eip",
                    m("pipeline", pipeline), n.incoming(), n.outgoing()) : n);
        }
        var plan2 = new tech.kayys.wayang.bpmn.ExecutionPlan(plan.processId(), plan.processName(), nodes);
        return engine(Map.of()).start(plan2, m("input", input, SecretResolver.TENANT_VARIABLE, tenant, SecretResolver.PROCESS_VARIABLE, process));
    }

    private static Map<String, Object> idemPipeline() {
        return m("steps", List.of(m("type", "idempotent", "key", "body.id")));
    }

    private static int count(ReferenceEngine.Run r) {
        return ((Number) r.variables().get("eip.count")).intValue();
    }

    @Test
    void idempotencyKeysDoNotCrossTenantsOrProcesses() {
        assertEquals(1, count(run("A", "p1", idemPipeline(), m("id", "42"))));
        assertEquals(0, count(run("A", "p1", idemPipeline(), m("id", "42"))), "same tenant, process and node: a duplicate");
        assertEquals(1, count(run("B", "p1", idemPipeline(), m("id", "42"))), "another tenant must not be affected");
        assertEquals(1, count(run("A", "p2", idemPipeline(), m("id", "42"))), "another process must not be affected");
    }

    @Test
    void claimTokensAreWorthlessAcrossTenants() {
        var store = m("steps", List.of(m("type", "claimCheck", "action", "store")));
        var r = run("A", "p1", store, "secret payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> msg = (Map<String, Object>) ((List<?>) r.variables().get("eip.messages")).get(0);
        Object token = ((Map<?, ?>) msg.get("headers")).get("claimCheck");
        assertNotNull(token);
        // tenant B tries to redeem tenant A's token: the pipeline gets the token as its input body's header via a transform
        var steal = m("steps", List.of(m("type", "transform", "headers", m("claimCheck", "'" + token + "'")),
                m("type", "claimCheck", "action", "retrieve")));
        assertEquals(ReferenceEngine.Status.FAILED, run("B", "p1", steal, "x").status());
        assertEquals(ReferenceEngine.Status.COMPLETED, run("A", "p1", steal, "x").status());
    }

    @Test
    void aggregationGroupsAreSeparatePerTenant() {
        var collect = m("steps", List.of(m("type", "collect", "key", "'k'", "size", 2)));
        assertEquals(0, count(run("A", "p1", collect, 1)));
        assertEquals(0, count(run("B", "p1", collect, 1)), "tenant B's first message must not complete A's group");
        assertEquals(1, count(run("A", "p1", collect, 2)));
    }
}
