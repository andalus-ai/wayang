package tech.kayys.wayang.adapter.gamelan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.quarkus.arc.lookup.LookupIfProperty;
import tech.kayys.gamelan.sdk.executor.core.Executor;

/**
 * Gamelan registers every CDI executor bean it can look up. {@code @LookupIfProperty} on each Wayang executor is what lets one image run as
 * separate pools (for example a service-only pool and an agent-only pool): every executor must carry its own switch, on unless switched off.
 * This does not start CDI (no container in the build); it guards the declarations so a new executor cannot silently skip the switch.
 */
class WorkerPoolsTest {

    private static final List<Class<? extends WayangNodeExecutor>> EXECUTORS = List.of(WayangAgentExecutor.class, WayangBpmnExecutor.class,
            WayangDmnExecutor.class, WayangEipExecutor.class, WayangHumanExecutor.class, WayangServiceExecutor.class);

    @Test
    void everyExecutorHasItsOwnSwitchThatIsOnByDefault() {
        Set<String> names = new HashSet<>();
        for (Class<? extends WayangNodeExecutor> c : EXECUTORS) {
            Executor e = c.getAnnotation(Executor.class);
            LookupIfProperty l = c.getAnnotation(LookupIfProperty.class);
            assertNotNull(e, c.getSimpleName() + " has no @Executor");
            assertNotNull(l, c.getSimpleName() + " has no @LookupIfProperty (it could not be left out of a pool)");
            String suffix = e.executorType().substring("wayang-".length());
            assertEquals("wayang.worker." + suffix + ".enabled", l.name());
            assertEquals("true", l.stringValue());
            assertTrue(l.lookupIfMissing(), "on unless switched off: existing deployments keep every executor");
            assertTrue(names.add(l.name()), "one switch per executor");
        }
        assertEquals(6, names.size());
    }
}
