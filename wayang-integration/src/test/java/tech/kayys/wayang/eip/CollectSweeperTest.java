package tech.kayys.wayang.eip;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.wayang.expression.Scope;

class CollectSweeperTest {

    private static final class Now extends Clock {
        long ms = 1_000_000;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId z) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(ms);
        }
    }

    private final Now clock = new Now();
    private final AggregationStore store = new AggregationStore.InMemory(100);
    private final List<String> started = new ArrayList<>();
    private final List<Map<String, Object>> inputs = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private boolean failStart;

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private CollectSweeper sweeper(CollectSweeper.Resolver r) {
        return new CollectSweeper(store, r, (tenant, pid, in) -> {
            if (failStart) {
                throw new IllegalStateException("secret-looking detail");
            }
            started.add(tenant + "/" + pid);
            inputs.add(in);
        }, clock, errors::add);
    }

    private final CollectSweeper.Resolver acmeOnly = (tenant, process, node) -> tenant.equals("acme") && process.equals("orders") && node.equals("gather")
            ? new CollectSweeper.Target("acme", "orders", "gather", 60_000, "orders-late") : null;

    private void add(String ns, String key, String body, long at) {
        store.append(ns, key, "{\"body\":\"" + body + "\"}", at, 100);
    }

    @Test
    void aGroupPastItsTimeoutStartsTheNamedProcessOnceWithEverythingCollected() {
        add("acme|orders|gather", "o1", "a", clock.ms - 120_000);
        add("acme|orders|gather", "o1", "b", clock.ms - 1_000); // the newer message does not keep the group alive
        add("acme|orders|gather", "o2", "fresh", clock.ms - 10_000);
        var s = sweeper(acmeOnly);
        assertEquals(1, s.sweep());
        assertEquals(List.of("acme/orders-late"), started);
        var agg = (Map<?, ?>) inputs.get(0).get("aggregate");
        assertEquals("o1", agg.get("key"));
        assertEquals(List.of("a", "b"), agg.get("bodies"));
        assertEquals(2L, agg.get("size"));
        assertEquals(Boolean.TRUE, agg.get("incomplete"));
        assertEquals(0, s.sweep(), "closed groups are not started twice, and the fresh one is not due yet");
        clock.ms += 60_000;
        assertEquals(1, s.sweep());
        assertEquals(List.of("fresh"), ((Map<?, ?>) inputs.get(1).get("aggregate")).get("bodies"));
        assertTrue(store.namespaces().isEmpty());
    }

    @Test
    void aProcessThatCannotStartPutsTheGroupBackAndIsRetried() {
        add("acme|orders|gather", "o1", "a", clock.ms - 120_000);
        add("acme|orders|gather", "o1", "b", clock.ms - 100_000);
        var s = sweeper(acmeOnly);
        failStart = true;
        assertEquals(0, s.sweep());
        assertEquals(1, errors.size());
        assertFalse(errors.get(0).contains("secret-looking detail"), "only the kind of failure is reported, not its message");
        var back = store.expired("acme|orders|gather", clock.ms, 10);
        assertEquals(1, back.size());
        assertEquals(2, back.get(0).entries().size());
        assertEquals(clock.ms - 120_000, back.get(0).entries().get(0).createdMs(), "the group keeps its age");
        failStart = false;
        assertEquals(1, s.sweep());
        assertEquals(List.of("a", "b"), ((Map<?, ?>) inputs.get(0).get("aggregate")).get("bodies"));
    }

    @Test
    void onlyNodesThatAskForASweepAreTouched() {
        add("acme|orders|other-node", "k", "a", 1);
        add("acme|orders|gather", "k", "a", 1);
        add("globex|orders|gather", "k", "a", 1); // another tenant
        add("not-a-triple", "k", "a", 1);
        var s = sweeper(acmeOnly);
        assertEquals(1, s.sweep());
        assertEquals(List.of("acme/orders-late"), started);
        assertEquals(3, store.namespaces().size());
    }

    @Test
    void aClaimLostToAnotherReplicaStartsNothing() {
        add("acme|orders|gather", "o1", "a", 1);
        AggregationStore loser = new AggregationStore() {
            @Override
            public List<Entry> append(String ns, String key, String json, long now, int max) {
                return store.append(ns, key, json, now, max);
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
                return store.namespaces();
            }

            @Override
            public List<Group> expired(String ns, long cutoffMs, int limit) {
                return store.expired(ns, cutoffMs, limit);
            }
        };
        var s = new CollectSweeper(loser, acmeOnly, (t, p, in) -> started.add(p), clock, errors::add);
        assertEquals(0, s.sweep());
        assertTrue(started.isEmpty());
    }

    @Test
    void theSweepTargetIsReadFromTheProcessDefinitionEvenWhenNested() {
        Map<String, Object> collect = m("type", "collect", "key", "'k'", "size", 5, "timeoutSeconds", 30, "sweepTo", "late");
        Map<String, Object> lazy = m("type", "collect", "key", "'k'", "size", 5, "timeoutSeconds", 30);
        Map<String, Object> plainEl = m("id", "plain", "type", "EIP_TASK", "config", m("pipeline", m("steps", List.of(lazy))));
        Map<String, Object> branch = m("type", "route", "branches", List.of(m("steps", List.of(collect))));
        Map<String, Object> filter = m("type", "filter", "when", "true");
        Map<String, Object> gatherEl = m("id", "gather", "type", "EIP_TASK", "config", m("pipeline", m("steps", List.of(filter, branch))));
        Map<String, Object> doc = m("id", "orders", "elements", List.of(plainEl, gatherEl));
        var t = CollectSweeper.find("acme", doc, "gather");
        assertEquals(new CollectSweeper.Target("acme", "orders", "gather", 30_000, "late"), t);
        assertEquals("acme|orders|gather", t.namespace());
        assertNull(CollectSweeper.find("acme", doc, "plain"), "a collect without sweepTo is lazy only");
        assertNull(CollectSweeper.find("acme", doc, "missing"));
        assertNull(CollectSweeper.find("acme", m("id", "x"), "gather"));
    }

    @Test
    void sweepToIsCheckedWhenThePipelineLoads() {
        Services svc = new Services(null, null, null, store, null, "acme|orders|gather", clock, null);
        Map<String, Object> ok = m("type", "collect", "key", "'k'", "size", 2, "timeoutSeconds", 30, "sweepTo", "late");
        assertNotNull(EipLoader.build(m("steps", List.of(ok)), svc));
        for (Map<String, Object> bad : List.of(
                m("type", "collect", "key", "'k'", "size", 2, "sweepTo", "late"),
                m("type", "collect", "key", "'k'", "size", 2, "timeoutSeconds", 30, "onTimeout", "drop", "sweepTo", "late"),
                m("type", "collect", "key", "'k'", "size", 2, "timeoutSeconds", 30, "sweepTo", "../x"),
                m("type", "collect", "key", "'k'", "size", 2, "timeoutSeconds", 30, "sweepTo", 7))) {
            assertThrows(EipException.class, () -> EipLoader.build(m("steps", List.of(bad)), svc), bad.toString());
        }
    }

    @Test
    void theCollectStepAndTheSweeperAgreeOnWhatIsWaiting() {
        Services svc = new Services(null, null, null, store, null, "acme|orders|gather", clock, null);
        var p = EipLoader.build(m("steps", List.of(m("type", "collect", "key", "'k'", "size", 3, "timeoutSeconds", 60, "sweepTo", "late"))), svc);
        assertEquals(0, p.run(Message.of("x"), Scope.empty()).size());
        assertEquals(0, p.run(Message.of("y"), Scope.empty()).size());
        clock.ms += 61_000;
        assertEquals(1, sweeper(acmeOnly).sweep());
        assertEquals(List.of("x", "y"), ((Map<?, ?>) inputs.get(0).get("aggregate")).get("bodies"));
    }
}
