package tech.kayys.wayang.eip;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.kayys.wayang.expression.Scope;

class EipPatternsTest {

    /** A clock the test moves by hand; sleeping advances it, so waiting is observable and instant. */
    static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-25T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public synchronized Instant instant() {
            return now;
        }

        synchronized void advance(long ms) {
            now = now.plusMillis(ms);
        }
    }

    private TestClock clock;
    private final List<Long> slept = new ArrayList<>();
    private EipState state;
    private ClaimStore claims;
    private AggregationStore agg;

    @BeforeEach
    void setup() {
        clock = new TestClock();
        slept.clear();
        state = new EipState();
        claims = new ClaimStore.InMemory(1000);
        agg = new AggregationStore.InMemory(1000);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private Services svc(String ns, Invoker inv) {
        return new Services(new IdempotencyStore.InMemory(100), inv, claims, agg, state, ns, clock, millis -> {
            slept.add(millis);
            clock.advance(millis);
        });
    }

    private Pipeline load(String ns, Invoker inv, Object... steps) {
        return EipLoader.build(map("steps", List.of(steps)), svc(ns, inv));
    }

    private Pipeline load(Object... steps) {
        return load("t|p|n", null, steps);
    }

    private static List<Message> run(Pipeline p, Object body) {
        return p.run(Message.of(body), Scope.empty());
    }

    private static Map<String, Object> obj(Object... kv) {
        return map(kv);
    }

    // ------------------------------------------------------------------ validate

    @Test
    void validateChecksRequiredTypesAndRules() {
        var p = load(map("type", "validate", "required", List.of("id", "a.b"), "types", map("amount", "number", "tags", "array"),
                "rules", List.of(map("when", "body.amount > 0", "message", "amount must be positive"))));
        assertEquals(1, run(p, obj("id", "x", "a", obj("b", 1), "amount", 5, "tags", List.of())).size());
        var e = assertThrows(EipException.class, () -> run(p, obj("a", obj(), "amount", "5", "tags", "no")));
        assertTrue(e.permanent());
        for (String part : List.of("'id' is required", "'a.b' is required", "'amount' must be number", "'tags' must be array")) {
            assertTrue(e.getMessage().contains(part), e.getMessage());
        }
        var e2 = assertThrows(EipException.class, () -> run(p, obj("id", "x", "a", obj("b", 1), "amount", -1)));
        assertTrue(e2.getMessage().contains("amount must be positive"));
    }

    @Test
    void validateModesDropAndRoute() {
        var drop = load(map("type", "validate", "required", List.of("id"), "onInvalid", "drop"));
        assertEquals(0, run(drop, obj()).size());
        var route = load(map("type", "validate", "required", List.of("id"), "onInvalid", "route", "deadLetter", "bad"));
        Message m = run(route, obj()).get(0);
        assertEquals("bad", m.headers().get("routeTo"));
        assertTrue(String.valueOf(m.headers().get("error")).contains("'id' is required"));
        assertThrows(EipException.class, () -> load(map("type", "validate")));
        assertThrows(EipException.class, () -> load(map("type", "validate", "required", List.of("x"), "types", map("x", "date"))));
    }

    // ------------------------------------------------------------------ content filter / envelope

    @Test
    void contentFilterKeepsAndRemovesNestedPaths() {
        var p = load(map("type", "contentFilter", "keep", List.of("id", "user.name", "user.address.city"), "remove", List.of("user.address.city")));
        Map<String, Object> body = obj("id", 1, "secret", "s", "user", obj("name", "n", "pw", "p", "address", obj("city", "c", "zip", "z")));
        Object out = run(p, body).get(0).body();
        assertEquals(obj("id", 1, "user", obj("name", "n", "address", obj())), out);
        assertThrows(EipException.class, () -> run(p, "text"));
    }

    @Test
    void envelopeWrapsAndUnwraps() {
        var wrap = load(map("type", "envelope", "wrap", "payload", "with", map("source", map("$const", "wayang"))));
        Object wrapped = run(wrap, obj("a", 1)).get(0).body();
        assertEquals(obj("source", "wayang", "payload", obj("a", 1)), wrapped);
        var un = load(map("type", "envelope", "unwrap", "payload"));
        assertEquals(obj("a", 1), run(un, wrapped).get(0).body());
        assertThrows(EipException.class, () -> run(un, obj("other", 1)));
        assertThrows(EipException.class, () -> load(map("type", "envelope")));
        assertThrows(EipException.class, () -> run(load(map("type", "envelope", "wrap", "p", "with", map("p", map("$const", 1)))), 1));
    }

    // ------------------------------------------------------------------ claim check

    @Test
    void claimCheckRoundTripIsScopedAndExpires() {
        var store = load("tenantA|p|n", null, map("type", "claimCheck", "action", "store", "ttlSeconds", 60));
        Message parked = run(store, obj("big", "data")).get(0);
        assertNull(parked.body());
        String token = (String) parked.headers().get("claimCheck");
        assertTrue(token.matches("[0-9a-f]{32}"));

        var get = load("tenantA|p|n", null, map("type", "claimCheck", "action", "retrieve"));
        assertEquals(obj("big", "data"), get.run(parked, Scope.empty()).get(0).body());

        var other = load("tenantB|p|n", null, map("type", "claimCheck", "action", "retrieve"));
        var e = assertThrows(EipException.class, () -> other.run(parked, Scope.empty()));
        assertTrue(e.getMessage().contains("unknown or expired"));

        assertTrue(assertThrows(EipException.class, () -> get.run(Message.of(1).withHeader("claimCheck", "../../etc/passwd"), Scope.empty()))
                .getMessage().contains("does not hold a claim token"));
        assertThrows(EipException.class, () -> get.run(Message.of(1), Scope.empty()));

        clock.advance(61_000);
        assertThrows(EipException.class, () -> get.run(parked, Scope.empty()));
        assertEquals(0, load("tenantA|p|n", null, map("type", "claimCheck", "action", "retrieve", "dropIfMissing", true)).run(parked, Scope.empty()).size());
    }

    @Test
    void claimCheckConsumeAndSizeCap() {
        var store = load(map("type", "claimCheck", "action", "store"));
        Message parked = run(store, "x").get(0);
        var take = load(map("type", "claimCheck", "action", "retrieve", "consume", true));
        assertEquals("x", take.run(parked, Scope.empty()).get(0).body());
        assertThrows(EipException.class, () -> take.run(parked, Scope.empty()));
        var small = load(map("type", "claimCheck", "action", "store", "maxBytes", 10));
        assertThrows(EipException.class, () -> run(small, "x".repeat(100)));
        assertThrows(EipException.class, () -> load(map("type", "claimCheck", "action", "keep")));
        assertThrows(EipException.class, () -> EipLoader.build(map("steps", List.of(map("type", "claimCheck", "action", "store"))),
                Services.basic(new IdempotencyStore.InMemory(1), null)));
    }

    // ------------------------------------------------------------------ loop, slip, switch, balance

    @Test
    void loopTimesWhileUntilAndLimits() {
        var inc = List.of(map("type", "map", "merge", true, "mapping", map("n", "body.n + 1")));
        assertEquals(new BigDecimal(3), ((Map<?, ?>) run(load(map("type", "loop", "times", 3, "steps", inc)), obj("n", 0)).get(0).body()).get("n"));
        assertEquals(new BigDecimal(5), ((Map<?, ?>) run(load(map("type", "loop", "while", "body.n < 5", "steps", inc)), obj("n", 0)).get(0).body()).get("n"));
        assertEquals(new BigDecimal(4), ((Map<?, ?>) run(load(map("type", "loop", "until", "body.n >= 4", "steps", inc)), obj("n", 0)).get(0).body()).get("n"));
        var never = load(map("type", "loop", "while", "true", "maxIterations", 5, "steps", inc));
        assertTrue(assertThrows(EipException.class, () -> run(never, obj("n", 0))).getMessage().contains("maxIterations"));
        var fan = load(map("type", "loop", "times", 2, "steps", List.of(map("type", "splitter", "items", "[1,2]"))));
        assertThrows(EipException.class, () -> run(fan, obj()));
        assertThrows(EipException.class, () -> load(map("type", "loop", "steps", inc)));
        var iter = load(map("type", "loop", "times", 3, "steps", List.of(map("type", "map", "merge", true, "mapping", map("last", "headers.iteration")))));
        assertEquals(new BigDecimal(2), ((Map<?, ?>) run(iter, obj()).get(0).body()).get("last"));
    }

    @Test
    void routingSlipFollowsTheMessagesOwnItinerary() {
        var p = load(map("type", "routingSlip", "slip", "headers.slip", "skipUnknown", true, "stages", map(
                "a", List.of(map("type", "map", "merge", true, "mapping", map("trail", "body.trail + 'a'"))),
                "b", List.of(map("type", "map", "merge", true, "mapping", map("trail", "body.trail + 'b'"))))));
        assertEquals("bab", ((Map<?, ?>) p.run(Message.of(obj("trail", "")).withHeader("slip", List.of("b", "a", "zzz", "b")), Scope.empty()).get(0).body()).get("trail"));
        assertEquals("ab", ((Map<?, ?>) p.run(Message.of(obj("trail", "")).withHeader("slip", "a, b"), Scope.empty()).get(0).body()).get("trail"));
        var strict = load(map("type", "routingSlip", "slip", "headers.slip", "stages", map("a", List.of())));
        assertTrue(assertThrows(EipException.class, () -> strict.run(Message.of(1).withHeader("slip", List.of("nope")), Scope.empty())).getMessage().contains("unknown stage"));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            many.add("a");
        }
        assertThrows(EipException.class, () -> strict.run(Message.of(1).withHeader("slip", many), Scope.empty()));
    }

    @Test
    void switchPicksByValueIncludingNumbers() {
        var p = load(map("type", "switch", "on", "body.kind", "cases", map(
                "csv", List.of(map("type", "map", "mapping", map("via", map("$const", "csv")))),
                "1", List.of(map("type", "map", "mapping", map("via", map("$const", "one"))))),
                "default", List.of(map("type", "map", "mapping", map("via", map("$const", "other"))))));
        assertEquals("csv", ((Map<?, ?>) run(p, obj("kind", "csv")).get(0).body()).get("via"));
        assertEquals("one", ((Map<?, ?>) run(p, obj("kind", 1.0)).get(0).body()).get("via"));
        assertEquals("other", ((Map<?, ?>) run(p, obj("kind", "zzz")).get(0).body()).get("via"));
        var strict = load(map("type", "switch", "on", "body", "cases", map("a", List.of())));
        assertThrows(EipException.class, () -> run(strict, "b"));
        assertEquals(0, run(load(map("type", "switch", "on", "body", "cases", map("a", List.of()), "onNoMatch", "drop")), "b").size());
    }

    @Test
    void loadBalanceRoundRobinSharesStateAcrossLoadsAndFailsOver() {
        Object[] step = {map("type", "loadBalance", "name", "lb", "failover", true, "targets", map(
                "one", List.of(map("type", "map", "merge", true, "mapping", map("t", map("$const", "one")))),
                "two", List.of(map("type", "map", "merge", true, "mapping", map("t", map("$const", "two"))))))};
        List<Object> seen = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            seen.add(run(load(step), obj()).get(0).headers().get("target"));
        }
        assertEquals(List.of("one", "two", "one", "two"), seen);

        Invoker flaky = (n, c, in) -> {
            if (n.equals("bad")) {
                throw new Exception("down");
            }
            return Map.of("ok", true);
        };
        var p = load("t|p|n2", flaky, map("type", "loadBalance", "failover", true, "targets", map(
                "first", List.of(map("type", "call", "connector", "bad")),
                "second", List.of(map("type", "call", "connector", "good")))));
        assertEquals("second", run(p, obj()).get(0).headers().get("target"));
        var nofail = load("t|p|n3", flaky, map("type", "loadBalance", "targets", map("first", List.of(map("type", "call", "connector", "bad")))));
        assertThrows(EipException.class, () -> run(nofail, obj()));

        var hash = load(map("type", "loadBalance", "strategy", "hash", "key", "body.id", "targets", map("a", List.of(), "b", List.of(), "c", List.of())));
        Object t1 = run(hash, obj("id", "same")).get(0).headers().get("target");
        for (int i = 0; i < 5; i++) {
            assertEquals(t1, run(hash, obj("id", "same")).get(0).headers().get("target"));
        }
        assertThrows(EipException.class, () -> load(map("type", "loadBalance", "strategy", "hash", "targets", map("a", List.of()))));
    }

    // ------------------------------------------------------------------ retry, breaker, throttle, delay

    @Test
    void retryBacksOffAndStopsOnPermanentFailures() {
        AtomicInteger calls = new AtomicInteger();
        Invoker flaky = (n, c, in) -> {
            if (calls.incrementAndGet() < 3) {
                throw new Exception("blip");
            }
            return Map.of("ok", true);
        };
        var p = load("t|p|r", flaky, map("type", "retry", "attempts", 4, "backoffMs", 100, "steps", List.of(map("type", "call", "connector", "x"))));
        run(p, obj());
        assertEquals(3, calls.get());
        assertEquals(List.of(100L, 200L), slept);

        calls.set(0);
        Invoker perm = (n, c, in) -> {
            calls.incrementAndGet();
            throw new Invoker.Permanent("bad credentials");
        };
        var q = load("t|p|r2", perm, map("type", "retry", "attempts", 5, "backoffMs", 100, "steps", List.of(map("type", "call", "connector", "x"))));
        var e = assertThrows(EipException.class, () -> run(q, obj()));
        assertEquals(1, calls.get());
        assertTrue(e.permanent());

        calls.set(0);
        Invoker always = (n, c, in) -> {
            calls.incrementAndGet();
            throw new Exception("down");
        };
        var withHandler = load("t|p|r3", always, map("type", "retry", "attempts", 2, "deadLetter", "dlq", "steps", List.of(map("type", "call", "connector", "x")),
                "handler", List.of(map("type", "transform", "headers", map("handled", "'yes'")))));
        Message m = run(withHandler, obj()).get(0);
        assertEquals(2, calls.get());
        assertEquals("dlq", m.headers().get("routeTo"));
        assertEquals("yes", m.headers().get("handled"));
        assertThrows(EipException.class, () -> load(map("type", "retry", "attempts", 11, "steps", List.of())));
    }

    @Test
    void circuitBreakerOpensProbesAndIsSharedButNamespaced() {
        AtomicInteger calls = new AtomicInteger();
        boolean[] healthy = {false};
        Invoker inv = (n, c, in) -> {
            calls.incrementAndGet();
            if (!healthy[0]) {
                throw new Exception("down");
            }
            return Map.of();
        };
        Object[] cb = {map("type", "circuitBreaker", "name", "crm", "failureThreshold", 2, "openSeconds", 30,
                "steps", List.of(map("type", "call", "connector", "x")))};
        for (int i = 0; i < 2; i++) {
            assertThrows(EipException.class, () -> run(load("A|p|n", inv, cb), obj()));
        }
        assertEquals(2, calls.get());
        var open = assertThrows(EipException.class, () -> run(load("A|p|n", inv, cb), obj()));
        assertTrue(open.getMessage().contains("is open"));
        assertEquals(2, calls.get(), "no call while open, even from a freshly loaded pipeline");
        assertThrows(EipException.class, () -> run(load("B|p|n", inv, cb), obj()));
        assertEquals(3, calls.get(), "another namespace has its own breaker");

        clock.advance(31_000);
        assertThrows(EipException.class, () -> run(load("A|p|n", inv, cb), obj())); // the probe fails: open again
        assertEquals(4, calls.get());
        assertThrows(EipException.class, () -> run(load("A|p|n", inv, cb), obj()));
        assertEquals(4, calls.get());
        clock.advance(31_000);
        healthy[0] = true;
        run(load("A|p|n", inv, cb), obj()); // probe succeeds: closed
        run(load("A|p|n", inv, cb), obj());
        assertEquals(6, calls.get());
    }

    @Test
    void halfOpenAllowsExactlyOneProbeAtATime() {
        boolean[] healthy = {false};
        String[] reentrant = {null};
        Object[] cb = {map("type", "circuitBreaker", "name", "p", "failureThreshold", 1, "openSeconds", 30, "steps", List.of(map("type", "call", "connector", "x")))};
        Invoker[] inv = new Invoker[1];
        inv[0] = (n, c, in) -> {
            if (!healthy[0]) {
                throw new Exception("down");
            }
            try { // a second caller arrives while the probe is still in flight
                run(load("A|p|n", inv[0], cb), obj());
                reentrant[0] = "let through";
            } catch (EipException e) {
                reentrant[0] = e.getMessage();
            }
            return Map.of();
        };
        assertThrows(EipException.class, () -> run(load("A|p|n", inv[0], cb), obj()));
        clock.advance(31_000);
        healthy[0] = true;
        run(load("A|p|n", inv[0], cb), obj());
        assertTrue(String.valueOf(reentrant[0]).contains("is open"), String.valueOf(reentrant[0]));
    }

    @Test
    void circuitBreakerFallback() {
        Invoker down = (n, c, in) -> {
            throw new Exception("down");
        };
        var p = load("A|p|fb", down, map("type", "circuitBreaker", "name", "x", "failureThreshold", 1,
                "steps", List.of(map("type", "call", "connector", "x")), "fallback", List.of(map("type", "map", "mapping", map("from", map("$const", "cache"))))));
        assertEquals("cache", ((Map<?, ?>) run(p, obj()).get(0).body()).get("from"));
        assertEquals("cache", ((Map<?, ?>) run(p, obj()).get(0).body()).get("from"));
    }

    @Test
    void throttleBurstRefillDropAndWait() {
        var p = load(map("type", "throttle", "name", "api", "perSecond", 2, "burst", 2));
        assertEquals(1, run(p, 1).size());
        assertEquals(1, run(p, 1).size());
        assertTrue(assertThrows(EipException.class, () -> run(p, 1)).getMessage().contains("rate limit"));
        clock.advance(500);
        assertEquals(1, run(p, 1).size());
        var drop = load("t|p|d", null, map("type", "throttle", "name", "d", "perSecond", 1, "burst", 1, "onExceed", "drop"));
        assertEquals(1, run(drop, 1).size());
        assertEquals(0, run(drop, 1).size());
        var wait = load("t|p|w", null, map("type", "throttle", "name", "w", "perSecond", 1, "burst", 1, "onExceed", "wait", "maxWaitMs", 1500));
        run(wait, 1);
        slept.clear();
        assertEquals(1, run(wait, 1).size());
        assertEquals(List.of(1000L), slept);
        var tooLong = load("t|p|w2", null, map("type", "throttle", "name", "w2", "perSecond", 0.1, "burst", 1, "onExceed", "wait", "maxWaitMs", 1500));
        run(tooLong, 1);
        assertThrows(EipException.class, () -> run(tooLong, 1));
        assertThrows(EipException.class, () -> load(map("type", "throttle", "name", "x", "perSecond", 0)));
    }

    @Test
    void delaySleepsOncePerBatchOrStampsNotBefore() {
        var p = load(map("type", "splitter", "items", "[1,2,3]"), map("type", "delay", "ms", 250));
        assertEquals(3, run(p, obj()).size());
        assertEquals(List.of(250L), slept);
        var stamp = load(map("type", "delay", "notBefore", "1000 + 5"));
        assertEquals(new BigDecimal(1005), run(stamp, 1).get(0).headers().get("notBefore"));
        assertEquals(1, slept.size());
        assertThrows(EipException.class, () -> load(map("type", "delay", "ms", 10_001)));
        assertThrows(EipException.class, () -> load(map("type", "delay")));
    }

    // ------------------------------------------------------------------ batch, correlate, label, lookup, tokenize

    @Test
    void batchCorrelateLabelLookupTokenize() {
        var b = load(map("type", "splitter", "items", "[1,2,3,4,5]"), map("type", "batch", "size", 2));
        List<Message> out = run(b, obj());
        assertEquals(3, out.size());
        assertEquals(List.of(new BigDecimal(5)), out.get(2).body());
        assertEquals(new BigDecimal(1), out.get(2).headers().get("batchSize"));

        var c = load(map("type", "correlate", "key", "body.order"));
        assertEquals("o1", run(c, obj("order", "o1")).get(0).headers().get("correlationId"));
        Message keep = c.run(Message.of(obj("order", "o2")).withHeader("correlationId", "fixed"), Scope.empty()).get(0);
        assertEquals("fixed", keep.headers().get("correlationId"));
        assertTrue(String.valueOf(run(load(map("type", "correlate")), 1).get(0).headers().get("correlationId")).length() >= 32);

        var l = load(map("type", "label", "name", "a"), map("type", "label", "name", "b"));
        assertEquals(List.of("a", "b"), run(l, 1).get(0).headers().get("history"));

        var lk = load(map("type", "lookup", "table", map("ID", "Indonesia", "1", "one"), "key", "body.cc", "into", "country.name", "default", "unknown"));
        assertEquals("Indonesia", path(run(lk, obj("cc", "ID")).get(0).body(), "country", "name"));
        assertEquals("unknown", path(run(lk, obj("cc", "XX")).get(0).body(), "country", "name"));
        assertEquals("one", path(run(lk, obj("cc", 1.0)).get(0).body(), "country", "name"));
        var strict = load(map("type", "lookup", "table", map("a", 1), "key", "body.k", "into", "v", "failIfMissing", true));
        assertThrows(EipException.class, () -> run(strict, obj("k", "b")));

        var t = load(map("type", "tokenize", "separator", "\n", "trim", true, "skipEmpty", true));
        List<Message> parts = run(t, "a\n\n b \nc\n");
        assertEquals(List.of("a", "b", "c"), parts.stream().map(Message::body).toList());
        assertThrows(EipException.class, () -> run(load(map("type", "tokenize", "separator", ",", "limit", 2)), "a,b,c"));
        assertThrows(EipException.class, () -> load(map("type", "tokenize", "separator", "")));
    }

    private static Object path(Object body, String... p) {
        Object cur = body;
        for (String s : p) {
            cur = ((Map<?, ?>) cur).get(s);
        }
        return cur;
    }

    // ------------------------------------------------------------------ collect

    @Test
    void collectEmitsOnceWhenComplete() {
        Object[] step = {map("type", "collect", "key", "body.order", "size", 3)};
        assertEquals(0, run(load(step), obj("order", "o1", "n", 1)).size());
        assertEquals(0, run(load(step), obj("order", "o2", "n", 9)).size());
        assertEquals(0, run(load(step), obj("order", "o1", "n", 2)).size());
        List<Message> done = run(load(step), obj("order", "o1", "n", 3));
        assertEquals(1, done.size());
        assertEquals("o1", done.get(0).headers().get("correlationId"));
        assertEquals(new BigDecimal(3), done.get(0).headers().get("aggregateSize"));
        assertEquals(List.of(1, 2, 3), ((List<?>) done.get(0).body()).stream().map(b -> ((Map<?, ?>) b).get("n")).map(x -> ((BigDecimal) x).intValue()).toList());
        assertEquals(0, run(load(step), obj("order", "o1", "n", 4)).size(), "a new group starts after the old one was emitted");
        assertEquals(0, run(load("other|p|n", null, step), obj("order", "o2", "n", 1)).size(), "groups are per namespace");
    }

    @Test
    void collectCompleteWhenAndTimeout() {
        var p = load(map("type", "collect", "key", "'k'", "completeWhen", "last.done == true", "timeoutSeconds", 60));
        assertEquals(0, run(p, obj("done", false)).size());
        assertEquals(0, run(p, obj("done", false)).size());
        List<Message> r = run(p, obj("done", true));
        assertEquals(3, ((List<?>) r.get(0).body()).size());
        assertEquals(0, run(p, obj("done", false)).size(), "the emitted group is gone");

        var q = load("t|p|q", null, map("type", "collect", "key", "'k'", "size", 5, "timeoutSeconds", 60));
        run(q, 1);
        run(q, 2);
        clock.advance(61_000);
        List<Message> out = run(q, 3);
        assertEquals(1, out.size());
        assertEquals(Boolean.TRUE, out.get(0).headers().get("incomplete"));
        assertEquals(3, ((List<?>) out.get(0).body()).size());

        var d = load("t|p|d", null, map("type", "collect", "key", "'k'", "size", 5, "timeoutSeconds", 10, "onTimeout", "drop"));
        run(d, 1);
        clock.advance(11_000);
        assertEquals(0, run(d, 2).size());
        run(d, 3);
        assertEquals(0, run(d, 4).size(), "the dropped group is gone; a new one started with message 3");
    }

    @Test
    void collectLosesTheRaceGracefullyAndCapsGroups() {
        // a store whose claim always loses: another replica closed the group first
        AggregationStore loser = new AggregationStore() {
            private final AggregationStore.InMemory inner = new AggregationStore.InMemory(10);

            @Override
            public List<Entry> append(String ns, String key, String json, long now, int max) {
                return inner.append(ns, key, json, now, max);
            }

            @Override
            public boolean claim(String ns, String key, long upTo) {
                return false;
            }

            @Override
            public int purge(long cutoffMs) {
                return 0;
            }

            @Override
            public java.util.Set<String> namespaces() {
                return inner.namespaces();
            }

            @Override
            public List<Group> expired(String ns, long cutoffMs, int limit) {
                return inner.expired(ns, cutoffMs, limit);
            }
        };
        var p = EipLoader.build(map("steps", List.of(map("type", "collect", "key", "'k'", "size", 1))),
                new Services(null, null, claims, loser, state, "x", clock, null));
        assertEquals(0, p.run(Message.of(1), Scope.empty()).size());

        var capped = load("t|p|cap", null, map("type", "collect", "key", "'k'", "size", 100, "maxGroup", 2));
        run(capped, 1);
        run(capped, 2);
        assertThrows(EipException.class, () -> run(capped, 3));
        assertThrows(EipException.class, () -> load(map("type", "collect", "key", "'k'")));
        assertThrows(EipException.class, () -> run(load("t|p|k", null, map("type", "collect", "key", "body", "size", 1)), "x".repeat(201)));
    }

    // ------------------------------------------------------------------ loader

    @Test
    void loaderRejectsBadConfiguration() {
        assertThrows(EipException.class, () -> load(map("type", "batch", "size", 0)));
        assertThrows(EipException.class, () -> load(map("type", "batch", "size", "x")));
        assertThrows(EipException.class, () -> load(map("type", "batch", "size", 1.5)));
        assertThrows(EipException.class, () -> load(map("type", "switch", "on", "body", "cases", map())));
        assertThrows(EipException.class, () -> load(map("type", "circuitBreaker", "steps", List.of())));
        assertThrows(EipException.class, () -> load(map("type", "routingSlip", "slip", "body")));
        assertThrows(EipException.class, () -> load(map("type", "lookup", "table", map(), "key", "body", "into", "x")));
    }

    @Test
    void permanentFlagSurvivesCallWrapping() {
        Invoker inv = (n, c, in) -> {
            throw new Invoker.Permanent("nope");
        };
        var e = assertThrows(EipException.class, () -> run(load("t|p|c", inv, map("type", "call", "connector", "x")), obj()));
        assertTrue(e.permanent());
        Invoker inv2 = (n, c, in) -> {
            throw new Exception("transient");
        };
        assertFalse(assertThrows(EipException.class, () -> run(load("t|p|c2", inv2, map("type", "call", "connector", "x")), obj())).permanent());
    }

    // ------------------------------------------------------------------ store contracts (in-memory implementations)

    @Test
    void inMemoryStoresSatisfyTheContracts() throws Exception {
        tech.kayys.wayang.eip.testkit.EipStoreContracts.claims(() -> new ClaimStore.InMemory(100));
        tech.kayys.wayang.eip.testkit.EipStoreContracts.aggregation(() -> new AggregationStore.InMemory(100));
        tech.kayys.wayang.eip.testkit.EipStoreContracts.aggregationRace(() -> new AggregationStore.InMemory(100));
        tech.kayys.wayang.eip.testkit.EipStoreContracts.idempotency(() -> new IdempotencyStore.InMemory(100));
    }

    // ------------------------------------------------------------------ parallel multicast

    private static Map<String, Object> callStep(String connector) {
        return map("type", "call", "connector", connector, "into", "r");
    }

    @Test
    void parallelMulticastReallyRunsBranchesAtTheSameTime() {
        // each branch waits until the other has started: only possible if both are in flight together
        var both = new java.util.concurrent.CountDownLatch(2);
        Invoker meet = (n, c, in) -> {
            both.countDown();
            if (!both.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new Exception("branches did not overlap");
            }
            return Map.of("who", n);
        };
        var p = load("t|p|par1", meet, map("type", "multicast", "parallel", true, "gather", true,
                "branches", map("a", List.of(callStep("one")), "b", List.of(callStep("two")))));
        var out = run(p, obj("x", 1));
        assertEquals(1, out.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) out.get(0).body();
        assertEquals(List.of("a", "b"), List.copyOf(g.keySet()), "declaration order, not completion order");
        // and the same step without parallel: the second branch could never start while the first waits, so the first fails
        var seq = load("t|p|par2", (n, c, in) -> {
            throw new Exception("sequential");
        }, map("type", "multicast", "branches", map("a", List.of(callStep("one")))));
        assertThrows(EipException.class, () -> run(seq, obj()));
    }

    @Test
    void parallelAndSequentialMulticastGiveTheSameResult() {
        Invoker echo = (n, c, in) -> Map.of("n", n);
        Object[] branches = {"a", List.of(map("type", "map", "mapping", map("v", "body.x + 1"))),
                "b", List.of(map("type", "map", "mapping", map("v", "body.x * 10")), callStep("svc")),
                "c", List.of(map("type", "splitter", "items", "body.items"))};
        for (boolean gather : new boolean[] { true, false }) {
            var seq = load("t|p|s", echo, map("type", "multicast", "gather", gather, "branches", map(branches)));
            var par = load("t|p|s", echo, map("type", "multicast", "gather", gather, "parallel", true, "branches", map(branches)));
            var in = obj("x", 2, "items", List.of(1, 2, 3));
            assertEquals(run(seq, in), run(par, in), "gather=" + gather);
        }
    }

    @Test
    void parallelMulticastFailsWithTheFailureOfTheFirstFailingBranchInOrder() {
        Invoker inv = (n, c, in) -> {
            if (n.equals("slow")) {
                Thread.sleep(300);
                throw new Exception("slow failed");
            }
            throw new Invoker.Permanent("fast failed");
        };
        var p = load("t|p|par3", inv, map("type", "multicast", "parallel", true,
                "branches", map("a", List.of(callStep("slow")), "b", List.of(callStep("fast")))));
        var e = assertThrows(EipException.class, () -> run(p, obj()));
        assertTrue(e.getMessage().contains("slow") || e.getMessage().contains("failed"), e.getMessage());
    }

    @Test
    void parallelMulticastTimesOutAndStopsStragglers() throws Exception {
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Invoker hang = (n, c, in) -> {
            try {
                Thread.sleep(20_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
            }
            return Map.of();
        };
        var p = load("t|p|par4", hang, map("type", "multicast", "parallel", true, "timeoutSeconds", 1,
                "branches", map("a", List.of(callStep("h")))));
        long t0 = System.nanoTime();
        var e = assertThrows(EipException.class, () -> run(p, obj()));
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertTrue((System.nanoTime() - t0) < 10_000_000_000L, "returned long before the branch would finish");
        for (int i = 0; i < 50 && !interrupted.get(); i++) {
            Thread.sleep(20);
        }
        assertTrue(interrupted.get(), "the straggler was interrupted");
    }

    @Test
    void parallelMulticastLimitsBranchesAndTimeoutIsBounded() {
        Map<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i < 17; i++) {
            many.put("b" + i, List.of());
        }
        assertThrows(EipException.class, () -> load(map("type", "multicast", "parallel", true, "branches", many)));
        many.remove("b0");
        assertEquals(1, run(load(map("type", "multicast", "parallel", true, "gather", true, "branches", many)), obj()).size());
        assertThrows(EipException.class, () -> load(map("type", "multicast", "parallel", true, "timeoutSeconds", 9999, "branches", map("a", List.of()))));
    }
}
