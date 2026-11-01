package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;

class SecretAccessAuditTest {

    private final List<String> seen = new ArrayList<>();

    private ReferenceEngine engine(SecretAccessListener l) {
        var reg = new WaitRegistry.InMemory();
        var runner = new NodeRunner(List.of(new ControlHandler(reg), new ServiceHandler(Map.of("call", in -> Map.of("ok", true)))))
                .withSecrets((t, n) -> n.equals("MISSING") ? null : "hunter2").withConfig((t, p, n) -> "cfg");
        if (l != null) {
            runner.withSecretAccessListener(l);
        }
        return new ReferenceEngine(runner, reg);
    }

    private static ProcessModel proc(String expr) {
        return ProcessModel.builder("p").start("s").task("t", ElementType.SERVICE_TASK, Map.of("connector", "call", "inputs", Map.of("v", expr)))
                .end("e").flow("s", "t").flow("t", "e").build();
    }

    private final SecretAccessListener record = (tenant, process, run, node, name, found) ->
            seen.add(tenant + "|" + process + "|" + (run != null && !run.isEmpty()) + "|" + node + "|" + name + "|" + found);

    @Test
    void everySecretReferenceIsReportedWithItsContextAndNeverItsValue() {
        var run = engine(record).start(ProcessCompiler.compile(proc("'${secret:A}-${secret:B}-${config:C}'")),
                Map.of("wayang.tenant", "acme", "wayang.process", "orders"));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
        assertEquals(List.of("acme|orders|true|t|A|true", "acme|orders|true|t|B|true"), seen, "config is not a secret and is not reported");
        assertFalse(seen.toString().contains("hunter2"));
    }

    @Test
    void aMissingSecretIsReportedAsNotFound() {
        var run = engine(record).start(ProcessCompiler.compile(proc("'${secret:MISSING}'")), Map.of("wayang.tenant", "acme", "wayang.process", "orders"));
        assertEquals(ReferenceEngine.Status.FAILED, run.status());
        assertEquals(List.of("acme|orders|true|t|MISSING|false"), seen);
    }

    @Test
    void aListenerThatThrowsDoesNotFailTheRun() {
        var run = engine((t, p, r, n, s, f) -> {
            throw new IllegalStateException("audit store down");
        }).start(ProcessCompiler.compile(proc("'${secret:A}'")), Map.of("wayang.tenant", "acme"));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
    }

    @Test
    void noSecretsMeansNoReports() {
        var run = engine(record).start(ProcessCompiler.compile(proc("'plain'")), Map.of("wayang.tenant", "acme"));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
        assertTrue(seen.isEmpty());
    }

    @Test
    void withoutATenantTheAttemptIsStillReportedAsNotFound() {
        var run = engine(record).start(ProcessCompiler.compile(proc("'${secret:A}'")), Map.of());
        assertEquals(ReferenceEngine.Status.FAILED, run.status());
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).endsWith("|A|false"), seen.toString());
    }
}
