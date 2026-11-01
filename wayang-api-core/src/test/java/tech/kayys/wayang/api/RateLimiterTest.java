package tech.kayys.wayang.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId z) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };
    private final Principal ann = new Principal("acme", "ann", Set.of(Role.DESIGNER));
    private final Principal bob = new Principal("acme", "bob", Set.of(Role.DESIGNER));
    private final Principal annGlobex = new Principal("globex", "ann", Set.of(Role.DESIGNER));

    @Test
    void allowsTheLimitThenRefusesWithATryAgainTime() {
        var l = new RateLimiter(3, 60_000, clock);
        for (int i = 0; i < 3; i++) {
            l.check(ann);
        }
        var e = assertThrows(ApiException.class, () -> l.check(ann));
        assertEquals(ApiException.Code.TOO_MANY_REQUESTS, e.code());
        assertEquals(429, e.code().httpStatus());
        assertTrue(e.getMessage().contains("60 s"), e.getMessage());
        now = now.plusSeconds(20);
        assertTrue(assertThrows(ApiException.class, () -> l.check(ann)).getMessage().contains("40 s"));
        now = now.plusMillis(500); // 39.5 s left: rounded up, never "0 s"
        assertTrue(assertThrows(ApiException.class, () -> l.check(ann)).getMessage().contains("40 s"));
        now = now.plusMillis(39_499); // 1 ms left
        assertTrue(assertThrows(ApiException.class, () -> l.check(ann)).getMessage().contains("1 s"));
    }

    @Test
    void theWindowResetsAfterItsLength() {
        var l = new RateLimiter(1, 60_000, clock);
        l.check(ann);
        assertThrows(ApiException.class, () -> l.check(ann));
        now = now.plusSeconds(59);
        assertThrows(ApiException.class, () -> l.check(ann));
        now = now.plusSeconds(1);
        l.check(ann);
        assertThrows(ApiException.class, () -> l.check(ann));
    }

    @Test
    void callersAreCountedSeparatelyAndTenantsDoNotShareUsers() {
        var l = new RateLimiter(1, 60_000, clock);
        l.check(ann);
        l.check(bob);
        l.check(annGlobex);
        assertThrows(ApiException.class, () -> l.check(ann));
        assertThrows(ApiException.class, () -> l.check(bob));
    }

    @Test
    void aClockThatGoesBackwardsDoesNotLockAnyoneOut() {
        var l = new RateLimiter(1, 60_000, clock);
        l.check(ann);
        now = now.minusSeconds(3600);
        l.check(ann);
    }

    @Test
    void theTableOfCallersIsBounded() {
        var l = new RateLimiter(1, 60_000, clock, 3);
        for (int i = 0; i < 10; i++) {
            l.check(new Principal("t", "u" + i, Set.of(Role.DESIGNER)));
        }
        // the oldest callers were forgotten (their count restarts), the newest are still limited
        l.check(new Principal("t", "u0", Set.of(Role.DESIGNER)));
        assertThrows(ApiException.class, () -> l.check(new Principal("t", "u9", Set.of(Role.DESIGNER))));
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0, 1, clock));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(1, 0, clock));
    }

    @Test
    void testerAndInspectorUseTheLimitButRefusedRequestsDoNotConsumeIt() {
        var limiter = new RateLimiter(2, 60_000, clock);
        var tester = new ConnectorTester(Map.of("github", (ServiceHandlerShim) in -> Map.of("ok", true)), null, limiter);
        Map<String, Object> read = Map.of("connector", "github", "confirm", true, "config", Map.of("operation", "getIssue"));
        Map<String, Object> write = Map.of("connector", "github", "confirm", true, "config", Map.of("operation", "createIssue"));
        for (int i = 0; i < 5; i++) {
            assertThrows(ApiException.class, () -> tester.test(ann, write)); // refused: not counted
        }
        tester.test(ann, read);
        tester.test(ann, read);
        assertEquals(ApiException.Code.TOO_MANY_REQUESTS, assertThrows(ApiException.class, () -> tester.test(ann, read)).code());
        var inspector = new ConnectorInspector(Map.of("soap", (ServiceHandlerShim) in -> Map.of()), limiter);
        assertEquals(ApiException.Code.TOO_MANY_REQUESTS, assertThrows(ApiException.class,
                () -> inspector.describe(ann, Map.of("connector", "soap", "config", Map.of("wsdl", "<x/>")))).code(), "describe shares the same budget");
        now = now.plusSeconds(61);
        tester.test(ann, read);
    }

    @FunctionalInterface
    interface ServiceHandlerShim extends tech.kayys.wayang.runtime.ServiceHandler.Connector {
    }
}
