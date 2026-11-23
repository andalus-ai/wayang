package tech.kayys.wayang.api.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Mode;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.Role;
import tech.kayys.wayang.api.WayangApi;
import tech.kayys.wayang.api.definition.DefinitionKind;
import tech.kayys.wayang.api.definition.DefinitionStore;
import tech.kayys.wayang.api.run.EventBus;
import tech.kayys.wayang.api.run.ReferenceRunEngine;
import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.WaitRegistry;

/** A design-only server never starts runs, so triggers cannot be created or switched on, and a tick starts nothing. */
class TriggerDesignModeTest {

    private final Principal admin = new Principal("acme", "ann", Set.of(Role.ADMIN));
    private final DefinitionStore store = new DefinitionStore.InMemory();
    private final WaitRegistry.InMemory waits = new WaitRegistry.InMemory();
    private final EventBus bus = new EventBus();
    private final WayangApi api = new WayangApi(store, new ReferenceRunEngine(store, waits, Map.of(), (s, i) -> Map.of(), bus, Clock.systemUTC()),
            waits, bus, Clock.systemUTC());
    private int polls;
    private final Poller counting = (cfg, cursor) -> {
        polls++;
        return new Poller.Result(List.of(Map.of("n", polls)), "c" + polls);
    };
    private final TriggerService svc = new TriggerService(new TriggerStore.InMemory(), api, Map.of("fake", counting), (t, n) -> null, Clock.systemUTC());

    private void publish() {
        api.saveDraft(admin, DefinitionKind.PROCESS, "inbox", Map.of("id", "inbox", "name", "Inbox",
                "elements", List.of(Map.of("id", "s", "type", "START_EVENT"), Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "f", "source", "s", "target", "e"))), null);
        api.publish(admin, DefinitionKind.PROCESS, "inbox");
    }

    @Test
    void designModeRefusesToCreateOrEnableAndNeverTicks() {
        publish();
        svc.create(admin, "p1", Trigger.Kind.POLL, "inbox", Map.of("source", "fake", "everySeconds", 10)); // made while it executed
        assertTrue(svc.tick() >= 0);
        int before = polls;
        api.withMode(Mode.DESIGN);
        ApiException c = assertThrows(ApiException.class, () -> svc.create(admin, "p2", Trigger.Kind.POLL, "inbox", Map.of("source", "fake", "everySeconds", 10)));
        assertEquals(409, c.code().httpStatus());
        assertTrue(c.getMessage().contains("design mode"), c.getMessage());
        assertEquals(409, assertThrows(ApiException.class, () -> svc.setEnabled(admin, "p1", true)).code().httpStatus());
        svc.setEnabled(admin, "p1", false); // switching off is always allowed
        svc.delete(admin, "p1"); // and so is deleting
        assertEquals(0, svc.tick());
        assertEquals(before, polls, "nothing was polled in design mode");
    }
}
