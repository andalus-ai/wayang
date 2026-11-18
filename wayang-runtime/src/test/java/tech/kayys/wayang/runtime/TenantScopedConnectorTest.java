package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;

class TenantScopedConnectorTest {

    private static final class Probe implements ServiceHandler.Connector {
        final boolean scoped;
        final AtomicReference<Map<String, Object>> seen = new AtomicReference<>();

        Probe(boolean scoped) {
            this.scoped = scoped;
        }

        @Override
        public boolean tenantScoped() {
            return scoped;
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) {
            return Map.of();
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) {
            seen.set(Map.copyOf(config));
            return Map.of();
        }
    }

    private static void run(Probe p, Map<String, Object> taskCfg, Map<String, Object> input) {
        ProcessModel m = ProcessModel.builder("p").start("s").task("call", ElementType.SERVICE_TASK, taskCfg).end("e")
                .flow("s", "call").flow("call", "e").build();
        var engine = new ReferenceEngine(new NodeRunner(List.of(new ControlHandler(new WaitRegistry.InMemory()),
                new ServiceHandler(Map.of("probe", p)))), new WaitRegistry.InMemory());
        assertEquals(ReferenceEngine.Status.COMPLETED, engine.start(ProcessCompiler.compile(m), input).status());
    }

    @Test
    void aTenantScopedConnectorGetsTheEnginesTenantNotTheAuthors() {
        Probe p = new Probe(true);
        run(p, Map.of("connector", "probe", "wayang.tenant", "evil"), Map.of(SecretResolver.TENANT_VARIABLE, "acme"));
        assertEquals("acme", p.seen.get().get(SecretResolver.TENANT_VARIABLE));
    }

    @Test
    void withoutATenantTheNamespaceIsEmptyAndAuthorsCannotChooseOne() {
        Probe p = new Probe(true);
        run(p, Map.of("connector", "probe", "wayang.tenant", "evil"), Map.of());
        assertEquals("", p.seen.get().get(SecretResolver.TENANT_VARIABLE));
    }

    @Test
    void otherConnectorsNeverSeeTheTenant() {
        Probe p = new Probe(false);
        run(p, Map.of("connector", "probe"), Map.of(SecretResolver.TENANT_VARIABLE, "acme"));
        assertFalse(p.seen.get().containsKey(SecretResolver.TENANT_VARIABLE));
    }
}
