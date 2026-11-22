package tech.kayys.wayang.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tech.kayys.wayang.api.definition.DefinitionKind;
import tech.kayys.wayang.api.definition.DefinitionStore;
import tech.kayys.wayang.api.run.EventBus;
import tech.kayys.wayang.api.run.ReferenceRunEngine;
import tech.kayys.wayang.runtime.WaitRegistry;

class ModeTest {

    private static final Map<String, Object> PROCESS = Map.of("id", "p", "name", "P",
            "elements", List.of(Map.of("id", "s", "type", "START_EVENT"), Map.of("id", "e", "type", "END_EVENT")),
            "flows", List.of(Map.of("id", "f", "source", "s", "target", "e")));

    private final Principal admin = new Principal("acme", "ann", Set.of(Role.ADMIN));

    private WayangApi api(Mode mode) {
        DefinitionStore store = new DefinitionStore.InMemory();
        WaitRegistry.InMemory waits = new WaitRegistry.InMemory();
        EventBus bus = new EventBus();
        return new WayangApi(store, new ReferenceRunEngine(store, waits, Map.of(), (spec, in) -> Map.of(), bus, Clock.systemUTC()), waits, bus,
                Clock.systemUTC()).withMode(mode);
    }

    @Test
    void parsingIsStrictAndCommunityIsTheDefault() {
        assertEquals(Mode.COMMUNITY, Mode.parse(null));
        assertEquals(Mode.COMMUNITY, Mode.parse(" "));
        assertEquals(Mode.COMMUNITY, Mode.parse("All-In-One"));
        assertEquals(Mode.DESIGN, Mode.parse("design"));
        assertEquals(Mode.DESIGN, Mode.parse("DESIGN-ONLY"));
        assertThrows(IllegalArgumentException.class, () -> Mode.parse("prod"));
        assertThrows(IllegalArgumentException.class, () -> Mode.parse("desing"));
    }

    @Test
    void communityDesignsAndExecutes() {
        WayangApi api = api(Mode.COMMUNITY);
        api.saveDraft(admin, DefinitionKind.PROCESS, "p", PROCESS, null);
        api.publish(admin, DefinitionKind.PROCESS, "p");
        assertEquals("COMPLETED", api.startRun(admin, "p", null, Map.of()).status().name());
    }

    @Test
    void designModeStillDesignsButNeverExecutes() {
        WayangApi api = api(Mode.DESIGN);
        api.saveDraft(admin, DefinitionKind.PROCESS, "p", PROCESS, null);
        api.publish(admin, DefinitionKind.PROCESS, "p");
        assertEquals(1, api.list(admin, DefinitionKind.PROCESS).size());
        for (Runnable call : List.<Runnable>of(() -> api.startRun(admin, "p", null, Map.of()),
                () -> api.completeTask(admin, "t", Map.of()),
                () -> api.sendMessage(admin, "m", "k", Map.of()),
                () -> api.fireTimers(admin))) {
            ApiException e = assertThrows(ApiException.class, call::run);
            assertEquals(409, e.code().httpStatus());
            assertTrue(e.getMessage().contains("design mode"), e.getMessage());
        }
        assertEquals(0, api.listRuns(admin, "p", 10).size());
    }
}
