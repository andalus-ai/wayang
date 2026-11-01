package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;

class SecretsRuntimeTest {

    private final Map<String, Object> seen = new HashMap<>();

    private ReferenceEngine engine(Map<String, Map<String, String>> secrets) {
        Map<String, ServiceHandler.Connector> c = Map.of("call", in -> {
            seen.putAll(in);
            return Map.of("echo", String.valueOf(in.get("token")));
        }, "leak", in -> {
            throw new IllegalStateException("bad token " + in.get("token"));
        });
        SecretResolver r = (t, n) -> secrets.getOrDefault(t, Map.of()).get(n);
        var reg = new WaitRegistry.InMemory();
        return new ReferenceEngine(new NodeRunner(List.of(new ControlHandler(reg), new ServiceHandler(c))).withSecrets(r), reg);
    }

    private static ProcessModel proc(String connector, String tokenExpr) {
        return ProcessModel.builder("p").start("s")
                .task("t", ElementType.SERVICE_TASK, Map.of("connector", connector, "inputs", Map.of("token", tokenExpr)))
                .end("e").flow("s", "t").flow("t", "e").build();
    }

    @Test
    void secretIsInjectedForTheOwningTenantOnly() {
        var e = engine(Map.of("a", Map.of("TOK", "abc123"), "b", Map.of("TOK", "other")));
        var run = e.start(ProcessCompiler.compile(proc("call", "'${secret:TOK}'")), Map.of("wayang.tenant", "a"));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
        assertEquals("abc123", seen.get("token"));
    }

    @Test
    void missingSecretFailsWithoutRetryAndWithoutTenantItFailsClosed() {
        var e = engine(Map.of("a", Map.of()));
        var run = e.start(ProcessCompiler.compile(proc("call", "'${secret:TOK}'")), Map.of("wayang.tenant", "a"));
        assertEquals(ReferenceEngine.Status.FAILED, run.status());
        assertTrue(run.error().contains("TOK"));
        var noTenant = engine(Map.of("a", Map.of("TOK", "x")))
                .start(ProcessCompiler.compile(proc("call", "'${secret:TOK}'")), Map.of());
        assertEquals(ReferenceEngine.Status.FAILED, noTenant.status(), "no tenant variable → no secret");
    }

    @Test
    void secretValuesAreRedactedFromErrors() {
        var e = engine(Map.of("a", Map.of("TOK", "abc123")));
        var run = e.start(ProcessCompiler.compile(proc("leak", "'${secret:TOK}'")), Map.of("wayang.tenant", "a"));
        assertEquals(ReferenceEngine.Status.FAILED, run.status());
        assertFalse(run.error().contains("abc123"), run.error());
        assertTrue(run.error().contains("***"));
    }

    @Test
    void quotesInSecretsAreRefusedInsideExpressionInputs() {
        var e = engine(Map.of("a", Map.of("TOK", "x' + 'y")));
        var run = e.start(ProcessCompiler.compile(proc("call", "'${secret:TOK}'")), Map.of("wayang.tenant", "a"));
        assertEquals(ReferenceEngine.Status.FAILED, run.status());
        assertTrue(seen.isEmpty());
    }

    @Test
    void nodesOutsideTheAllowListNeverSeeSecrets() {
        // a gateway/user-task config is not resolved: the literal text stays
        var reg = new WaitRegistry.InMemory();
        var runner = new NodeRunner(List.of(new ControlHandler(reg), new HumanHandler(reg))).withSecrets((t, n) -> "LEAK");
        var model = ProcessModel.builder("p").start("s").task("u", ElementType.USER_TASK, Map.of("form", "${secret:X}"))
                .end("e").flow("s", "u").flow("u", "e").build();
        var run = new ReferenceEngine(runner, reg).start(ProcessCompiler.compile(model), Map.of("wayang.tenant", "a"));
        assertEquals(ReferenceEngine.Status.WAITING, run.status());
        assertFalse(String.valueOf(reg.listByRun(run.id())).contains("LEAK"));
    }
}
