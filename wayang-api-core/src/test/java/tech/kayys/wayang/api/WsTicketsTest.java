package tech.kayys.wayang.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class WsTicketsTest {

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = new Clock() {
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(java.time.ZoneId z) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    };
    private final WsTickets tickets = new WsTickets(clock);
    private final Principal ann = new Principal("acme", "ann", Set.of(Role.ADMIN));
    private final Principal bob = new Principal("acme", "bob", Set.of(Role.VIEWER));

    private static ApiException.Code code(Runnable r) {
        return assertThrows(ApiException.class, r::run).code();
    }

    @Test
    void aTicketIsRedeemableOnceAndYieldsTheIssuingPrincipal() {
        var t = tickets.issue(ann);
        assertEquals(ann, tickets.redeem(t.value()));
        assertEquals(ApiException.Code.UNAUTHORIZED, code(() -> tickets.redeem(t.value())), "single use");
    }

    @Test
    void ticketsExpireAfterThirtySeconds() {
        var t = tickets.issue(ann);
        now = now.plusSeconds(29);
        var alive = tickets.issue(bob);
        assertEquals(bob, tickets.redeem(alive.value()));
        now = now.plusSeconds(2); // 31s after the first
        assertEquals(ApiException.Code.UNAUTHORIZED, code(() -> tickets.redeem(t.value())));
    }

    @Test
    void unknownMalformedAndNullTicketsAreRefusedIdentically() {
        for (String bad : new String[] {null, "", "nope", "x".repeat(500)}) {
            var e = assertThrows(ApiException.class, () -> tickets.redeem(bad));
            assertEquals(ApiException.Code.UNAUTHORIZED, e.code());
            assertEquals("Invalid or expired ticket", e.getMessage());
        }
    }

    @Test
    void ticketsAreUnguessableAndDistinct() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 15; i++) {
            var t = tickets.issue(bob.userId().equals("bob") && i % 2 == 0 ? bob : ann);
            assertTrue(t.value().length() >= 43);
            assertTrue(seen.add(t.value()));
        }
    }

    @Test
    void perPrincipalCapStopsAFloodButNotOthers() {
        for (int i = 0; i < WsTickets.MAX_PER_PRINCIPAL; i++) {
            tickets.issue(ann);
        }
        assertEquals(ApiException.Code.CONFLICT, code(() -> tickets.issue(ann)));
        assertDoesNotThrow(() -> tickets.issue(bob));
        now = now.plusSeconds(31); // expired ones are purged, capacity returns
        assertDoesNotThrow(() -> tickets.issue(ann));
    }

    @Test
    void unauthenticatedCannotGetATicket() {
        assertEquals(ApiException.Code.UNAUTHORIZED, code(() -> tickets.issue(null)));
    }

    @Test
    void aRedeemedTicketCannotBeReplayedAcrossTenantsOrUsers() {
        var t = tickets.issue(ann);
        Principal p = tickets.redeem(t.value());
        assertEquals("acme", p.tenantId());
        assertEquals("ann", p.userId());
        assertNotEquals(bob, p);
    }
}
