package tech.kayys.wayang.api.run;

import tech.kayys.wayang.api.definition.DefinitionStore;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.Role;
import tech.kayys.wayang.api.WayangApi;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RoomsTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final DefinitionStore store = new DefinitionStore.InMemory();
    private final WaitRegistryHolder h = new WaitRegistryHolder();
    private final Rooms rooms = new Rooms();

    /** Minimal api: rooms never touch the engine, but WsSession needs one. */
    static final class WaitRegistryHolder {
        final tech.kayys.wayang.runtime.WaitRegistry waits = new tech.kayys.wayang.runtime.WaitRegistry.InMemory();
        final EventBus bus = new EventBus();
    }

    private final WayangApi api = new WayangApi(store, new ReferenceRunEngine(store, h.waits, Map.of(),
            (spec, in) -> Map.of(), h.bus, clock), h.waits, h.bus, clock);

    private final Principal ann = new Principal("acme", "ann", Set.of(Role.DESIGNER));
    private final Principal bob = new Principal("acme", "bob", Set.of(Role.DESIGNER));
    private final Principal viewer = new Principal("acme", "vic", Set.of(Role.VIEWER));
    private final Principal evil = new Principal("globex", "eve", Set.of(Role.ADMIN));

    private WsSession open(Principal p, List<String> out) {
        return new WsSession(api, p, out::add, clock, rooms);
    }

    @Test
    void membersSeeJoinMessagesAndLeaveWithServerSideIdentity() {
        List<String> a = new ArrayList<>(), b = new ArrayList<>();
        try (WsSession sa = open(ann, a); WsSession sb = open(bob, b)) {
            sa.onText("{\"id\":\"1\",\"op\":\"join\",\"room\":\"definition:process:loan\"}");
            assertTrue(a.get(0).contains("\"members\":[\"ann\"]"), a.toString());
            sb.onText("{\"id\":\"1\",\"op\":\"join\",\"room\":\"definition:process:loan\"}");
            assertTrue(b.get(0).contains("ann") && b.get(0).contains("bob"));
            assertTrue(a.stream().anyMatch(s -> s.contains("\"type\":\"joined\"") && s.contains("bob")));
            // a client-supplied "user" field is ignored: the server stamps the principal
            sa.onText("{\"id\":\"2\",\"op\":\"say\",\"room\":\"definition:process:loan\",\"user\":\"mallory\",\"payload\":{\"cursor\":3}}");
            assertTrue(b.stream().anyMatch(s -> s.contains("\"type\":\"msg\"") && s.contains("\"user\":\"ann\"") && s.contains("cursor")));
            assertTrue(b.stream().noneMatch(s -> s.contains("mallory")));
            assertTrue(a.stream().noneMatch(s -> s.contains("\"type\":\"msg\"")), "sender does not get its own echo");
            sb.close();
            assertTrue(a.stream().anyMatch(s -> s.contains("\"type\":\"left\"") && s.contains("bob")), "close leaves rooms");
        }
        assertEquals(0, rooms.roomCount());
    }

    @Test
    void roomsAreTenantNamespaced() {
        List<String> a = new ArrayList<>(), e = new ArrayList<>();
        try (WsSession sa = open(ann, a); WsSession se = open(evil, e)) {
            sa.onText("{\"op\":\"join\",\"room\":\"r\"}");
            se.onText("{\"op\":\"join\",\"room\":\"r\"}");
            se.onText("{\"op\":\"say\",\"room\":\"r\",\"payload\":{\"x\":1}}");
            assertTrue(a.stream().noneMatch(s -> s.contains("eve")), a.toString());
            assertEquals(List.of("ann"), rooms.who(ann, "r"));
            assertEquals(List.of("eve"), rooms.who(evil, "r"));
        }
    }

    @Test
    void viewersObserveButCannotSpeakAndNonMembersCannotSpeak() {
        List<String> v = new ArrayList<>(), a = new ArrayList<>();
        try (WsSession sv = open(viewer, v); WsSession sa = open(ann, a)) {
            sv.onText("{\"id\":\"1\",\"op\":\"join\",\"room\":\"r\"}");
            assertTrue(v.get(0).contains("\"ok\":true"));
            sv.onText("{\"id\":\"2\",\"op\":\"say\",\"room\":\"r\",\"payload\":{}}");
            assertTrue(v.get(1).contains("\"ok\":false") && v.get(1).contains("DEFINITION_WRITE"), v.toString());
            sa.onText("{\"id\":\"3\",\"op\":\"say\",\"room\":\"r\",\"payload\":{}}");
            assertTrue(a.get(0).contains("Join the room first"), a.toString());
        }
    }

    @Test
    void validationAndLimits() {
        List<String> a = new ArrayList<>();
        try (WsSession sa = open(ann, a)) {
            sa.onText("{\"id\":\"1\",\"op\":\"join\",\"room\":\"bad room!\"}");
            assertTrue(a.get(0).contains("\"ok\":false"));
            sa.onText("{\"id\":\"2\",\"op\":\"join\",\"room\":\"r\"}");
            String big = "x".repeat(Rooms.MAX_PAYLOAD_CHARS + 1);
            sa.onText("{\"id\":\"3\",\"op\":\"say\",\"room\":\"r\",\"payload\":{\"d\":\"" + big + "\"}}");
            // oversize frame is rejected at the frame limit before the room sees it
            assertTrue(a.get(a.size() - 1).contains("\"ok\":false"));
            for (int i = 0; i < 12; i++) {
                sa.onText("{\"op\":\"join\",\"room\":\"room" + i + "\"}");
            }
            assertTrue(a.get(a.size() - 1).contains("Too many rooms"), a.get(a.size() - 1));
            for (int i = 0; i < WsSession.SAY_PER_SECOND + 1; i++) {
                sa.onText("{\"op\":\"say\",\"room\":\"r\",\"payload\":{}}");
            }
            assertTrue(a.get(a.size() - 1).contains("slow down"), a.get(a.size() - 1));
        }
    }

    @Test
    void roomOpsAreUnknownWhenRoomsAreNotEnabled() {
        List<String> a = new ArrayList<>();
        try (WsSession s = new WsSession(api, ann, a::add, clock)) {
            s.onText("{\"id\":\"1\",\"op\":\"join\",\"room\":\"r\"}");
            assertTrue(a.get(0).contains("Unknown op"));
        }
    }

    @Test
    void aBrokenPeerDoesNotStopDeliveryToOthers() {
        List<String> good = new ArrayList<>();
        rooms.join(ann, "c1", "r", s -> {
            throw new IllegalStateException("socket closed");
        });
        rooms.join(bob, "c2", "r", good::add);
        assertEquals(0, rooms.say(bob, "c2", "r", Map.of("k", 1)), "the broken peer is skipped without an exception");
    }
}
