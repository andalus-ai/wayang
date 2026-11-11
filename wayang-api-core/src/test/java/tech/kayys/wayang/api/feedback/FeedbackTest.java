package tech.kayys.wayang.api.feedback;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.RateLimiter;
import tech.kayys.wayang.api.Role;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class FeedbackTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC);
    private final Principal viewer = new Principal("acme", "vic", Set.of(Role.VIEWER));
    private final Principal other = new Principal("globex", "gus", Set.of(Role.VIEWER));
    private final Principal admin = new Principal("acme", "ada", Set.of(Role.ADMIN));
    private final FeedbackStore store = new FeedbackStore.InMemory(100);
    private final FeedbackService service = new FeedbackService(store, clock, null);

    private static Map<String, Object> req(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void aViewerCanSendFeedbackButOnlyAnAdminCanReadIt() {
        Object id = service.submit(viewer, req("kind", "bug", "message", "The run list is empty")).get("id");
        assertNotNull(id);
        assertThrows(ApiException.class, () -> service.list(viewer, null, 10, null));
        List<FeedbackEntry> all = service.list(admin, null, 10, null);
        assertEquals(1, all.size());
        assertEquals("vic", all.get(0).user());
        assertEquals("bug", all.get(0).kind());
    }

    @Test
    void tenantsNeverSeeEachOthersFeedback() {
        service.submit(viewer, req("kind", "idea", "message", "dark mode"));
        service.submit(other, req("kind", "idea", "message", "export"));
        var acme = service.list(admin, null, 10, null);
        assertEquals(1, acme.size());
        assertEquals("dark mode", acme.get(0).message());
    }

    @Test
    void aRatingNeedsUpOrDownAndNoMessage() {
        service.submit(viewer, req("kind", "rating", "rating", "down", "context", Map.of("runId", "r1")));
        assertThrows(ApiException.class, () -> service.submit(viewer, req("kind", "rating")));
        assertThrows(ApiException.class, () -> service.submit(viewer, req("kind", "rating", "rating", "meh")));
        var e = service.list(admin, "rating", 10, null).get(0);
        assertEquals("down", e.rating());
        assertEquals("r1", e.context().get("runId"));
    }

    @Test
    void otherKindsNeedAMessage() {
        assertThrows(ApiException.class, () -> service.submit(viewer, req("kind", "comment", "message", "  ")));
        assertThrows(ApiException.class, () -> service.submit(viewer, req("kind", "nope", "message", "x")));
        assertThrows(ApiException.class, () -> service.submit(null, req("kind", "comment", "message", "x")));
    }

    @Test
    void credentialsAreMaskedInTheMessageAndTheStack() {
        String stack = "Exception: failed https://u:hunter2@api.example.com/v1?api_key=abc123 Authorization: Bearer abcdefgh12345678\n"
                + "at x token=supersecretvalue and password: \"p4ss\" jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sgn "
                + "bot 123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw0 key sk-abcdefghijklmnopqrstuv";
        service.submit(viewer, req("kind", "error", "message", "boom " + stack, "context", Map.of("stack", stack, "screen", "runs")));
        FeedbackEntry e = service.list(admin, "error", 10, null).get(0);
        for (String text : List.of(e.message(), e.context().get("stack"))) {
            for (String leaked : List.of("hunter2", "abc123", "abcdefgh12345678", "supersecretvalue", "p4ss", "eyJhbGciOiJIUzI1NiJ9",
                    "AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw0", "sk-abcdefghijklmnopqrstuv")) {
                assertFalse(text.contains(leaked), leaked + " leaked in: " + text);
            }
            assertTrue(text.contains("***"));
        }
        assertEquals("runs", e.context().get("screen"));
    }

    @Test
    void scrubKeepsOrdinaryText() {
        assertEquals("The process 'report' failed at step 3 (HTTP 502)", FeedbackService.scrub("The process 'report' failed at step 3 (HTTP 502)"));
        assertEquals("id 123e4567-e89b-12d3-a456-426614174000 ok", FeedbackService.scrub("id 123e4567-e89b-12d3-a456-426614174000 ok"));
        assertEquals("ab", FeedbackService.scrub("a\u0000b"));
    }

    @Test
    void sizesAndShapesAreChecked() {
        assertThrows(ApiException.class, () -> service.submit(viewer, req("kind", "bug", "message", "x".repeat(FeedbackService.MAX_MESSAGE + 1))));
        assertThrows(ApiException.class, () -> service.submit(viewer, req("kind", "bug", "message", "x", "context", Map.of("bad key", "v"))));
        assertThrows(ApiException.class, () -> service.submit(viewer, req("kind", "bug", "message", "x", "context", Map.of("a", Map.of("n", 1)))));
        assertThrows(ApiException.class,
                () -> service.submit(viewer, req("kind", "bug", "message", "x", "context", Map.of("stack", "s".repeat(FeedbackService.MAX_STACK + 1)))));
        Map<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i <= FeedbackService.MAX_CONTEXT; i++) {
            many.put("k" + i, "v");
        }
        assertThrows(ApiException.class, () -> service.submit(viewer, req("kind", "bug", "message", "x", "context", many)));
        assertTrue(service.list(admin, null, 10, null).isEmpty(), "nothing invalid is stored");
    }

    @Test
    void submissionsAreRateLimitedPerUser() {
        var limited = new FeedbackService(store, clock, new RateLimiter(2, 60_000, clock));
        limited.submit(viewer, req("kind", "comment", "message", "1"));
        limited.submit(viewer, req("kind", "comment", "message", "2"));
        var ex = assertThrows(ApiException.class, () -> limited.submit(viewer, req("kind", "comment", "message", "3")));
        assertEquals(ApiException.Code.TOO_MANY_REQUESTS, ex.code());
        limited.submit(other, req("kind", "comment", "message", "someone else is not affected"));
    }

    @Test
    void listFiltersByKindAndPages() {
        var tick = new java.util.concurrent.atomic.AtomicLong(0);
        Clock c = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return Instant.parse("2026-11-09T10:00:00Z").plusSeconds(tick.incrementAndGet()); }
        };
        var s = new FeedbackService(new FeedbackStore.InMemory(100), c, null);
        s.submit(viewer, req("kind", "bug", "message", "one"));
        s.submit(viewer, req("kind", "idea", "message", "two"));
        s.submit(viewer, req("kind", "bug", "message", "three"));
        assertEquals(List.of("three", "one"), s.list(admin, "bug", 10, null).stream().map(FeedbackEntry::message).toList());
        var newest = s.list(admin, null, 1, null).get(0);
        assertEquals("three", newest.message());
        assertEquals("two", s.list(admin, null, 1, newest.time()).get(0).message());
        assertThrows(ApiException.class, () -> s.list(admin, "nope", 10, null));
    }

    @Test
    void theMemoryStoreIsBounded() {
        var small = new FeedbackService(new FeedbackStore.InMemory(2), clock, null);
        for (int i = 0; i < 5; i++) {
            small.submit(viewer, req("kind", "comment", "message", "m" + i));
        }
        assertEquals(2, small.list(admin, null, 10, null).size());
    }

    @Test
    void purgeDeletesOnlyOlderEntriesOfTheCallersTenantAndNeedsAdmin() {
        var tick = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-01T00:00:00Z"));
        Clock c = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return tick.get(); }
        };
        var s = new FeedbackService(new FeedbackStore.InMemory(100), c, null);
        s.submit(viewer, req("kind", "comment", "message", "old"));
        s.submit(other, req("kind", "comment", "message", "other tenant, old"));
        tick.set(Instant.parse("2026-11-05T00:00:00Z"));
        s.submit(viewer, req("kind", "comment", "message", "new"));
        tick.set(Instant.parse("2026-11-09T00:00:00Z"));
        Principal globexAdmin = new Principal("globex", "gia", Set.of(Role.ADMIN));
        assertThrows(ApiException.class, () -> s.purge(viewer, Instant.parse("2026-11-01T00:00:00Z")));
        assertEquals(1, s.purge(admin, Instant.parse("2026-11-01T00:00:00Z")).get("deleted"));
        assertEquals(List.of("new"), s.list(admin, null, 10, null).stream().map(FeedbackEntry::message).toList());
        assertEquals(1, s.list(globexAdmin, null, 10, null).size(), "another tenant is untouched");
        assertEquals(0, s.purge(admin, Instant.parse("2026-11-01T00:00:00Z")).get("deleted"));
    }

    @Test
    void purgeNeedsACutOffAtLeastADayOld() {
        assertThrows(ApiException.class, () -> service.purge(admin, null));
        assertThrows(ApiException.class, () -> service.purge(admin, Instant.parse("2026-11-09T09:00:00Z")));
        assertThrows(ApiException.class, () -> service.purge(admin, Instant.parse("2027-01-01T00:00:00Z")));
        service.purge(admin, Instant.parse("2026-11-08T09:00:00Z"));
    }
}
