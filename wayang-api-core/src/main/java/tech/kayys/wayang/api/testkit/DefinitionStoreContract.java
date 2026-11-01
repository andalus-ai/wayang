package tech.kayys.wayang.api.testkit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.DefinitionKind;
import tech.kayys.wayang.api.DefinitionStore;
import tech.kayys.wayang.api.StoredDefinition;

/**
 * Behavioural contract every {@link DefinitionStore} must satisfy. Run it
 * against your implementation with a factory returning a store that is empty.
 */
public final class DefinitionStoreContract {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private DefinitionStoreContract() {
    }

    public static void verify(Supplier<DefinitionStore> fresh) {
        draftsUseOptimisticConcurrency(fresh.get());
        publishCreatesImmutableVersions(fresh.get());
        tenantsAndKindsAreSeparate(fresh.get());
        listShowsDraftAndVersionState(fresh.get());
        deleteDraftKeepsVersions(fresh.get());
        documentsRoundTrip(fresh.get());
        concurrentPublishesGetDistinctVersions(fresh.get());
        summariesCarryTheProjectId(fresh.get());
    }

    private static void summariesCarryTheProjectId(DefinitionStore s) {
        s.putDraft("a", DefinitionKind.PROCESS, "p1", Map.of("id", "p1", "studio", Map.of("projectId", "proj-9")), null, "u", T);
        s.putDraft("a", DefinitionKind.PROCESS, "p2", Map.of("id", "p2"), null, "u", T);
        s.publish("a", DefinitionKind.PROCESS, "p2", "u", T);
        s.deleteDraft("a", DefinitionKind.PROCESS, "p2");
        var l = s.list("a", DefinitionKind.PROCESS);
        check("proj-9".equals(l.get(0).projectId()), "draft summary carries studio.projectId");
        check(l.get(1).projectId() == null, "no project means null projectId");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("DefinitionStore contract violated: " + what);
        }
    }

    private static Map<String, Object> doc(String name) {
        return Map.of("id", "x", "name", name);
    }

    private static void expectConflict(Runnable r, String what) {
        try {
            r.run();
        } catch (ApiException e) {
            check(e.code() == ApiException.Code.CONFLICT, what + " must be CONFLICT but was " + e.code());
            return;
        }
        throw new AssertionError("DefinitionStore contract violated: " + what + " must fail");
    }

    private static void draftsUseOptimisticConcurrency(DefinitionStore s) {
        StoredDefinition d1 = s.putDraft("a", DefinitionKind.PROCESS, "x", doc("one"), null, "u", T);
        check(d1.revision() == 1 && d1.version() == 0, "first save is revision 1, version 0");
        expectConflict(() -> s.putDraft("a", DefinitionKind.PROCESS, "x", doc("dup"), null, "u", T), "creating over an existing draft");
        expectConflict(() -> s.putDraft("a", DefinitionKind.PROCESS, "x", doc("stale"), 5L, "u", T), "saving with a wrong revision");
        expectConflict(() -> s.putDraft("a", DefinitionKind.PROCESS, "nope", doc("n"), 3L, "u", T), "updating a draft that does not exist");
        StoredDefinition d2 = s.putDraft("a", DefinitionKind.PROCESS, "x", doc("two"), 1L, "u2", T.plusSeconds(1));
        check(d2.revision() == 2, "update bumps the revision");
        check(s.getDraft("a", DefinitionKind.PROCESS, "x").orElseThrow().document().get("name").equals("two"), "latest draft is returned");
        check(s.getDraft("a", DefinitionKind.PROCESS, "x").orElseThrow().updatedBy().equals("u2"), "updatedBy is stored");
    }

    private static void publishCreatesImmutableVersions(DefinitionStore s) {
        try {
            s.publish("a", DefinitionKind.PROCESS, "x", "u", T);
            throw new AssertionError("DefinitionStore contract violated: publishing a missing draft must fail");
        } catch (ApiException e) {
            check(e.code() == ApiException.Code.NOT_FOUND, "publishing a missing draft is NOT_FOUND");
        }
        s.putDraft("a", DefinitionKind.PROCESS, "x", doc("v1"), null, "u", T);
        check(s.publish("a", DefinitionKind.PROCESS, "x", "u", T).version() == 1, "first publish is version 1");
        s.putDraft("a", DefinitionKind.PROCESS, "x", doc("v2"), 1L, "u", T);
        check(s.publish("a", DefinitionKind.PROCESS, "x", "u", T).version() == 2, "second publish is version 2");
        check(s.getVersion("a", DefinitionKind.PROCESS, "x", 1).orElseThrow().document().get("name").equals("v1"),
                "published versions are frozen");
        check(s.getLatest("a", DefinitionKind.PROCESS, "x").orElseThrow().version() == 2, "latest is the newest version");
        check(s.getVersion("a", DefinitionKind.PROCESS, "x", 9).isEmpty(), "unknown version is empty");
        check(s.getLatest("a", DefinitionKind.PROCESS, "never").isEmpty(), "never published has no latest");
    }

    private static void tenantsAndKindsAreSeparate(DefinitionStore s) {
        s.putDraft("a", DefinitionKind.PROCESS, "x", doc("a"), null, "u", T);
        s.publish("a", DefinitionKind.PROCESS, "x", "u", T);
        check(s.getDraft("b", DefinitionKind.PROCESS, "x").isEmpty(), "other tenant sees no draft");
        check(s.getLatest("b", DefinitionKind.PROCESS, "x").isEmpty(), "other tenant sees no version");
        check(s.getDraft("a", DefinitionKind.DECISION, "x").isEmpty(), "other kind sees no draft");
        check(s.list("b", DefinitionKind.PROCESS).isEmpty(), "other tenant lists nothing");
        s.putDraft("b", DefinitionKind.PROCESS, "x", doc("b"), null, "u", T);
        check(s.publish("b", DefinitionKind.PROCESS, "x", "u", T).version() == 1, "version numbers are per tenant");
    }

    private static void listShowsDraftAndVersionState(DefinitionStore s) {
        s.putDraft("a", DefinitionKind.AGENT, "b", Map.of("id", "b", "name", "Bee"), null, "u", T);
        s.putDraft("a", DefinitionKind.AGENT, "a", Map.of("id", "a"), null, "u", T);
        s.publish("a", DefinitionKind.AGENT, "a", "u", T);
        s.deleteDraft("a", DefinitionKind.AGENT, "a");
        var l = s.list("a", DefinitionKind.AGENT);
        check(l.size() == 2 && l.get(0).id().equals("a") && l.get(1).id().equals("b"), "list is ordered by id and includes published-only items");
        check(!l.get(0).hasDraft() && l.get(0).latestVersion() == 1, "published-only item has no draft and latestVersion 1");
        check(l.get(1).hasDraft() && l.get(1).latestVersion() == 0 && l.get(1).name().equals("Bee"), "draft-only item shows its name");
    }

    private static void deleteDraftKeepsVersions(DefinitionStore s) {
        s.putDraft("a", DefinitionKind.DECISION, "d", doc("d"), null, "u", T);
        s.publish("a", DefinitionKind.DECISION, "d", "u", T);
        check(s.deleteDraft("a", DefinitionKind.DECISION, "d"), "deleting an existing draft returns true");
        check(!s.deleteDraft("a", DefinitionKind.DECISION, "d"), "deleting again returns false");
        check(s.getLatest("a", DefinitionKind.DECISION, "d").isPresent(), "published versions survive draft deletion");
    }

    private static void documentsRoundTrip(DefinitionStore s) {
        Map<String, Object> d = Map.of("id", "r", "list", List.of(1, "two", Map.of("k", true)), "n", new java.math.BigDecimal("1.50"),
                "nested", Map.of("deep", List.of(List.of("x"))), "text", "line1\nline2 \"q\" é");
        s.putDraft("a", DefinitionKind.PIPELINE, "r", d, null, "u", T);
        Map<String, Object> back = s.getDraft("a", DefinitionKind.PIPELINE, "r").orElseThrow().document();
        check(back.get("text").equals(d.get("text")), "text with escapes and unicode round-trips");
        check(((List<?>) back.get("list")).size() == 3, "lists round-trip");
        check(new java.math.BigDecimal(String.valueOf(back.get("n"))).compareTo(new java.math.BigDecimal("1.5")) == 0, "numbers round-trip");
        Instant t = Instant.parse("2026-05-06T07:08:09.123Z");
        s.putDraft("a", DefinitionKind.PIPELINE, "t", Map.of("id", "t"), null, "u", t);
        check(s.getDraft("a", DefinitionKind.PIPELINE, "t").orElseThrow().updatedAt().equals(t), "timestamps keep millisecond precision");
    }

    private static void concurrentPublishesGetDistinctVersions(DefinitionStore s) {
        s.putDraft("a", DefinitionKind.PROCESS, "c", doc("c"), null, "u", T);
        Set<Integer> versions = ConcurrentHashMap.newKeySet();
        List<Thread> ts = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                try {
                    versions.add(s.publish("a", DefinitionKind.PROCESS, "c", "u", T).version());
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            });
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) {
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        check(errors.isEmpty(), "concurrent publishes must not fail: " + errors);
        check(versions.size() == 8, "8 concurrent publishes yield 8 distinct versions, got " + versions);
        check(s.getLatest("a", DefinitionKind.PROCESS, "c").orElseThrow().version() == 8, "latest is version 8");
    }
}
