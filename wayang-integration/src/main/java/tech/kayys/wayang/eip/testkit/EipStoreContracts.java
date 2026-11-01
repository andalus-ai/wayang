package tech.kayys.wayang.eip.testkit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import tech.kayys.wayang.eip.AggregationStore;
import tech.kayys.wayang.eip.ClaimStore;
import tech.kayys.wayang.eip.IdempotencyStore;

/** Behavioural contracts shared by the in-memory and JDBC stores (each takes a factory returning an empty store). */
public final class EipStoreContracts {

    private EipStoreContracts() {
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("EIP store contract violated: " + what);
        }
    }

    private static final Instant T = Instant.parse("2026-10-25T00:00:00Z");

    public static void claims(Supplier<ClaimStore> factory) {
        ClaimStore s = factory.get();
        check(s.get("a", "t1", T).isEmpty(), "unknown token");
        s.put("a", "t1", java.util.Map.of("k", List.of(1, "two", true, java.util.Map.of("n", java.util.Map.of()))), T.plusSeconds(10));
        Optional<Object> got = s.get("a", "t1", T.plusSeconds(5));
        check(got.isPresent(), "stored body comes back");
        check(String.valueOf(got.get()).contains("two"), "body content survives: " + got.get());
        check(s.get("b", "t1", T.plusSeconds(5)).isEmpty(), "namespaces are isolated");
        check(s.get("a", "t1", T.plusSeconds(10)).isEmpty(), "expired at the expiry instant");
        s.put("a", "t2", null, T.plusSeconds(10));
        check(s.get("a", "t2", T).isEmpty() || s.get("a", "t2", T).get() == null || true, "null body does not crash");
        s.put("a", "t3", "x", T.plusSeconds(10));
        s.delete("a", "t3");
        check(s.get("a", "t3", T).isEmpty(), "delete");
        s.delete("a", "never-existed");
        s.put("a", "t4", "first", T.plusSeconds(10));
        s.put("a", "t4", "second", T.plusSeconds(10));
        check("second".equals(s.get("a", "t4", T).orElse(null)), "put replaces");
    }

    public static void aggregation(Supplier<AggregationStore> factory) {
        AggregationStore s = factory.get();
        List<AggregationStore.Entry> g = s.append("a", "k", "{\"n\":1}", 1000, 3);
        check(g.size() == 1 && g.get(0).createdMs() == 1000 && g.get(0).json().equals("{\"n\":1}"), "first append");
        s.append("a", "k", "{\"n\":2}", 2000, 3);
        g = s.append("a", "k", "{\"n\":3}", 3000, 3);
        check(g.size() == 3, "group grows");
        check(g.get(0).seq() < g.get(1).seq() && g.get(1).seq() < g.get(2).seq(), "ordered by seq");
        boolean threw = false;
        try {
            s.append("a", "k", "{\"n\":4}", 4000, 3);
        } catch (RuntimeException e) {
            threw = true;
        }
        check(threw, "group size cap");
        check(s.append("b", "k", "{}", 1, 3).size() == 1, "namespaces are isolated");
        check(s.append("a", "other", "{}", 1, 3).size() == 1, "keys are isolated");
        long upTo = g.get(1).seq();
        check(s.claim("a", "k", upTo), "first claim wins");
        check(!s.claim("a", "k", upTo), "second claim loses");
        List<AggregationStore.Entry> rest = s.append("a", "k", "{\"n\":5}", 5000, 3);
        check(rest.size() == 2 && rest.get(0).json().contains("3"), "entries after the claimed seq remain and new ones join them");
        check(!s.claim("a", "nope", 1), "claim on an unknown group");
        AggregationStore p = factory.get(); // may share storage with s, so only entries made here are counted exactly
        p.append("a", "old", "{}", 10_100, 5);
        p.append("a", "old", "{}", 10_200, 5);
        p.append("a", "mixed", "{}", 10_150, 5);
        p.append("a", "mixed", "{}", 10_900, 5);
        p.append("b", "fresh", "{}", 11_000, 5);
        check(p.purge(10_100) >= 0 && p.append("a", "mixed", "{}", 10_950, 5).size() == 3, "purge keeps entries created at the cutoff");
        check(p.purge(10_500) >= 3, "purge removes entries older than the cutoff");
        check(p.append("a", "old", "{}", 11_000, 5).size() == 1, "an abandoned group starts over after purge");
        check(p.append("a", "mixed", "{}", 11_000, 5).size() == 3, "newer entries of a partly old group survive");
        check(p.append("b", "fresh", "{}", 11_000, 5).size() == 2, "other namespaces are untouched");

        // sweeping: groups are found by the age of their OLDEST message, per namespace, oldest first
        p.append("sw", "young", "{}", 50_000, 5);
        p.append("sw", "old1", "{}", 20_000, 5);
        p.append("sw", "old1", "{}", 90_000, 5);
        p.append("sw", "old0", "{}", 10_000, 5);
        p.append("other-sw", "x", "{}", 1, 5);
        check(p.namespaces().contains("sw") && p.namespaces().contains("other-sw"), "namespaces lists the ones holding groups");
        List<AggregationStore.Group> ex = p.expired("sw", 60_000, 10);
        check(ex.size() == 3 && ex.get(0).key().equals("old0") && ex.get(1).key().equals("old1") && ex.get(2).key().equals("young"),
                "expired: every group whose oldest message is before the cutoff, oldest first (a newer message does not keep it alive)");
        check(ex.get(1).entries().size() == 2 && ex.get(1).entries().get(0).seq() < ex.get(1).entries().get(1).seq(), "expired groups carry all their messages in order");
        check(p.expired("sw", 15_000, 10).size() == 1 && p.expired("sw", 10_000, 10).isEmpty(), "the cutoff is exclusive");
        check(p.expired("sw", 60_000, 2).size() == 2, "expired honours the limit");
        check(p.expired("nobody", 99_999, 10).isEmpty(), "expired is per namespace");
        for (AggregationStore.Group grp : ex) {
            p.claim("sw", grp.key(), grp.entries().get(grp.entries().size() - 1).seq());
        }
        p.claim("other-sw", "x", Long.MAX_VALUE);
        check(!p.namespaces().contains("sw") && !p.namespaces().contains("other-sw"), "a namespace with no groups left is not listed");
    }

    public static void aggregationRace(Supplier<AggregationStore> factory) throws InterruptedException {
        AggregationStore s = factory.get();
        List<AggregationStore.Entry> g = s.append("a", "race", "{}", 1, 10);
        long seq = g.get(0).seq();
        AtomicInteger winners = new AtomicInteger();
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                if (s.claim("a", "race", seq)) {
                    winners.incrementAndGet();
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        check(winners.get() == 1, "exactly one concurrent claim wins, got " + winners.get());
    }

    public static void idempotency(Supplier<IdempotencyStore> factory) {
        IdempotencyStore s = factory.get();
        check(s.markIfNew("k1"), "new key");
        check(!s.markIfNew("k1"), "seen key");
        check(s.markIfNew("k2"), "other key");
        String longKey = "x".repeat(5000);
        check(s.markIfNew(longKey) && !s.markIfNew(longKey), "very long keys work");
        check(s.markIfNew("tenant\0key") && s.markIfNew("tenant2\0key"), "namespaced keys differ");
    }
}
