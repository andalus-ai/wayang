package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;

class ConfigRuntimeTest {

    private final Map<String, Object> seen = new HashMap<>();
    private final List<String> asked = new java.util.ArrayList<>();

    private ReferenceEngine engine(ConfigResolver cfg, SecretResolver sec) {
        Map<String, ServiceHandler.Connector> c = Map.of("call", in -> {
            seen.putAll(in);
            return Map.of("ok", true);
        }, "leak", in -> {
            throw new IllegalStateException("bad " + in.get("url"));
        });
        var reg = new WaitRegistry.InMemory();
        var runner = new NodeRunner(List.of(new ControlHandler(reg), new ServiceHandler(c)));
        if (cfg != null) {
            runner.withConfig(cfg);
        }
        if (sec != null) {
            runner.withSecrets(sec);
        }
        return new ReferenceEngine(runner, reg);
    }

    private static ProcessModel proc(String connector, Map<String, Object> cfg) {
        Map<String, Object> node = new java.util.LinkedHashMap<>(cfg);
        node.put("connector", connector);
        return ProcessModel.builder("p").start("s").task("t", ElementType.SERVICE_TASK, node).end("e").flow("s", "t").flow("t", "e").build();
    }

    private ConfigResolver store() {
        return (tenant, process, name) -> {
            asked.add(tenant + "/" + process + "/" + name);
            return tenant.equals("a") && name.equals("BASE_URL") ? "https://api.test/v1" : null;
        };
    }

    @Test
    void configIsInjectedForTheOwningTenantAndTheProcessIsPassedOn() {
        var run = engine(store(), null).start(ProcessCompiler.compile(proc("call", Map.of("inputs", Map.of("url", "'${config:BASE_URL}/items'")))),
                Map.of("wayang.tenant", "a", "wayang.process", "p"));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
        assertEquals("https://api.test/v1/items", seen.get("url"));
        assertEquals(List.of("a/p/BASE_URL"), asked);
    }

    @Test
    void anotherTenantDoesNotSeeIt() {
        var run = engine(store(), null).start(ProcessCompiler.compile(proc("call", Map.of("inputs", Map.of("url", "'${config:BASE_URL}'")))),
                Map.of("wayang.tenant", "b"));
        assertEquals(ReferenceEngine.Status.FAILED, run.status());
        assertTrue(run.error().contains("Config 'BASE_URL'"), run.error());
    }

    @Test
    void noTenantVariableMeansNoConfig() {
        var run = engine(store(), null).start(ProcessCompiler.compile(proc("call", Map.of("inputs", Map.of("url", "'${config:BASE_URL}'")))), Map.of());
        assertEquals(ReferenceEngine.Status.FAILED, run.status());
        assertTrue(asked.isEmpty());
    }

    @Test
    void aConfigValueIsNotScannedAgainSoItCannotPullInASecret() {
        ConfigResolver evil = (t, p, n) -> "${secret:TOK}";
        SecretResolver sec = (t, n) -> "hunter2";
        var run = engine(evil, sec).start(ProcessCompiler.compile(proc("call", Map.of("inputs", Map.of("url", "'${config:X}'")))), Map.of("wayang.tenant", "a"));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
        assertEquals("${secret:TOK}", seen.get("url"));
    }

    @Test
    void configAndSecretsMixInOneValueAndOnlyTheSecretIsRedacted() {
        ConfigResolver cfg = (t, p, n) -> "https://host.test";
        SecretResolver sec = (t, n) -> "hunter2";
        var run = engine(cfg, sec).start(ProcessCompiler.compile(proc("leak", Map.of("inputs", Map.of("url", "'${config:H}/?k=${secret:K}'")))), Map.of("wayang.tenant", "a"));
        assertEquals(ReferenceEngine.Status.FAILED, run.status());
        assertTrue(run.error().contains("bad https://host.test/?k=***"), run.error());
        assertFalse(run.error().contains("hunter2"));
    }

    @Test
    void aQuoteInAConfigValueIsRefusedInsideInputsExpressions() {
        ConfigResolver quote = (t, p, n) -> "x' + secretThing + '";
        var bad = engine(quote, null).start(ProcessCompiler.compile(proc("call", Map.of("inputs", Map.of("v", "'${config:Q}'")))), Map.of("wayang.tenant", "a"));
        assertEquals(ReferenceEngine.Status.FAILED, bad.status());
        assertTrue(bad.error().contains("not allowed"), bad.error());
        var fine = engine(quote, null).start(ProcessCompiler.compile(proc("call", Map.of("note", "${config:Q}", "inputs", Map.of("v", "1")))), Map.of("wayang.tenant", "a"));
        assertEquals(ReferenceEngine.Status.COMPLETED, fine.status(), "outside inputs it is just text");
    }

    @Test
    void withoutAResolverTheReferenceIsLeftAsWritten() {
        var run = engine(null, (t, n) -> "s").start(ProcessCompiler.compile(proc("call", Map.of("inputs", Map.of("url", "'${config:BASE_URL}'")))), Map.of("wayang.tenant", "a"));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
        assertEquals("${config:BASE_URL}", seen.get("url"));
    }
}
