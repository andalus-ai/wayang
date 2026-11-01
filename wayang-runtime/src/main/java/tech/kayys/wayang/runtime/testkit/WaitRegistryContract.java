package tech.kayys.wayang.runtime.testkit;

import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import tech.kayys.wayang.runtime.WaitRegistry;
import tech.kayys.wayang.runtime.WaitTicket;

/**
 * Behavioural contract every {@link WaitRegistry} implementation must satisfy.
 * Call {@link #verify} from a test of your implementation with a supplier of a
 * <em>fresh, empty</em> registry. Throws {@link AssertionError} on violation.
 */
public final class WaitRegistryContract {

    private WaitRegistryContract() {
    }

    public static void verify(Supplier<WaitRegistry> fresh) {
        registerIsIdempotentPerRunAndNode(fresh.get());
        takeIsAtMostOnce(fresh.get());
        dueOnlyReturnsElapsedTimers(fresh.get());
        messagesAreMatchedByNameAndKey(fresh.get());
        bufferedMessagesAreFifoAndConsumedOnce(fresh.get());
        ticketFieldsSurviveStorage(fresh.get());
        listByKindFiltersAndLimits(fresh.get());
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("WaitRegistry contract violated: " + what);
        }
    }

    private static WaitTicket ticket(String token, String node, String kind, Instant due, String msg, String key) {
        return new WaitTicket(token, node, kind, Map.of("title", "T"), due, msg, key);
    }

    private static void registerIsIdempotentPerRunAndNode(WaitRegistry r) {
        WaitTicket a = r.register("run1", ticket("t1", "n1", "human", null, null, null));
        WaitTicket again = r.register("run1", ticket("t2", "n1", "human", null, null, null));
        check(a.token().equals("t1") && again.token().equals("t1"), "re-registering the same run+node must return the existing ticket");
        WaitTicket other = r.register("run2", ticket("t3", "n1", "human", null, null, null));
        check(other.token().equals("t3"), "same node in another run is a different wait");
        check(r.findByNode("run1", "n1").get().ticket().token().equals("t1"), "findByNode");
        check(r.find("t3").get().runId().equals("run2"), "find");
    }

    private static void takeIsAtMostOnce(WaitRegistry r) {
        r.register("run1", ticket("t1", "n1", "human", null, null, null));
        check(r.take("t1").isPresent(), "first take succeeds");
        check(r.take("t1").isEmpty(), "second take must find nothing");
        check(r.find("t1").isEmpty(), "taken ticket is gone");
        check(r.findByNode("run1", "n1").isEmpty(), "taken ticket is gone by node");
    }

    private static void dueOnlyReturnsElapsedTimers(WaitRegistry r) {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        r.register("run1", ticket("early", "a", "timer", t, null, null));
        r.register("run1", ticket("late", "b", "timer", t.plusSeconds(60), null, null));
        r.register("run1", ticket("human", "c", "human", null, null, null));
        check(r.due(t.minusSeconds(1)).isEmpty(), "nothing due before the first deadline");
        var due = r.due(t);
        check(due.size() == 1 && due.get(0).ticket().token().equals("early"), "only the elapsed timer is due");
        check(r.due(t.plusSeconds(60)).size() == 2, "both timers due, humans never");
        check(r.due(t.plusSeconds(60)).get(0).ticket().token().equals("early"), "due() is ordered by deadline");
    }

    private static void messagesAreMatchedByNameAndKey(WaitRegistry r) {
        r.register("run1", ticket("m1", "a", "message", null, "paid", "A-1"));
        check(r.findMessage("paid", "A-1").isPresent(), "exact match");
        check(r.findMessage("paid", "B-2").isEmpty(), "other key does not match");
        check(r.findMessage("shipped", "A-1").isEmpty(), "other name does not match");
    }

    private static void bufferedMessagesAreFifoAndConsumedOnce(WaitRegistry r) {
        r.bufferMessage("paid", "A-1", Map.of("n", "first"));
        r.bufferMessage("paid", "A-1", Map.of("n", "second"));
        check(r.takeBuffered("paid", "B-2").isEmpty(), "other key has nothing buffered");
        check("first".equals(r.takeBuffered("paid", "A-1").get().get("n")), "FIFO: first in, first out");
        check("second".equals(r.takeBuffered("paid", "A-1").get().get("n")), "second message");
        check(r.takeBuffered("paid", "A-1").isEmpty(), "each buffered message is consumed once");
    }

    private static void listByKindFiltersAndLimits(WaitRegistry r) {
        r.register("run1", ticket("h1", "a", "human", null, null, null));
        r.register("run1", ticket("h2", "b", "human", null, null, null));
        r.register("run1", ticket("h3", "c", "human", null, null, null));
        r.register("run1", ticket("t1", "d", "timer", Instant.parse("2026-01-01T00:00:00Z"), null, null));
        check(r.listByKind("human", 10).size() == 3, "listByKind returns only that kind");
        check(r.listByKind("human", 2).size() == 2, "listByKind honours the limit");
        check(r.listByKind("message", 10).isEmpty(), "unknown kind is empty");
        r.register("run2", ticket("h4", "a", "human", null, null, null));
        check(r.listByRun("run1").size() == 4, "listByRun returns the run's waits");
        check(r.listByRun("run2").size() == 1 && r.listByRun("run2").get(0).ticket().token().equals("h4"), "listByRun is scoped to the run");
        check(r.listByRun("nope").isEmpty(), "unknown run is empty");
    }

    private static void ticketFieldsSurviveStorage(WaitRegistry r) {
        Instant due = Instant.parse("2026-03-04T05:06:07Z");
        r.register("run1", new WaitTicket("t9", "n9", "timer", Map.of("title", "T", "n", 3, "ok", true,
                "nested", Map.of("k", java.util.List.of("a", "b"))), due, null, null));
        WaitTicket back = r.find("t9").get().ticket();
        check(back.dueAt().equals(due), "dueAt round-trips");
        check("T".equals(back.info().get("title")) && Boolean.TRUE.equals(back.info().get("ok")), "info round-trips");
        check(back.info().get("nested") instanceof Map, "nested info round-trips");
        check(r.find("t9").get().runId().equals("run1"), "runId round-trips");
    }
}
