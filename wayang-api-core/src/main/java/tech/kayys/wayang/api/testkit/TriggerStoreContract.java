package tech.kayys.wayang.api.testkit;

import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Trigger;
import tech.kayys.wayang.api.TriggerStore;

/** Behavioural contract for {@link TriggerStore} implementations (pass a factory returning an empty store). */
public final class TriggerStoreContract {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private TriggerStoreContract() {
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("TriggerStore contract violated: " + what);
        }
    }

    /** Deep equality where 30 and 30L are the same JSON number. */
    private static boolean same(Object x, Object y) {
        if (x instanceof Number a && y instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        if (x instanceof Map<?, ?> m && y instanceof Map<?, ?> n) {
            return m.size() == n.size() && m.keySet().stream().allMatch(k -> n.containsKey(k) && same(m.get(k), n.get(k)));
        }
        if (x instanceof java.util.List<?> l && y instanceof java.util.List<?> k) {
            if (l.size() != k.size()) {
                return false;
            }
            for (int i = 0; i < l.size(); i++) {
                if (!same(l.get(i), k.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return java.util.Objects.equals(x, y);
    }

    private static Trigger poll(String tenant, String id, boolean enabled) {
        return new Trigger(tenant, id, Trigger.Kind.POLL, "proc", enabled,
                Map.of("source", "telegram", "allowedChatIds", java.util.List.of(111, 222), "everySeconds", 30, "botToken", "${secret:TG}"),
                null, null, null, null, 0, "u", T);
    }

    public static void verify(Supplier<TriggerStore> fresh) {
        TriggerStore s = fresh.get();
        Trigger a = poll("a", "t1", true);
        s.create(a);
        var got = s.get("a", "t1").orElseThrow();
        check(got.kind() == Trigger.Kind.POLL && got.processId().equals("proc") && got.enabled(), "create round-trips");
        check(same(got.config(), a.config()), "config round-trips, nested lists and numbers included: " + got.config());
        check(got.cursor() == null && got.lastPolledAt() == null && got.lastError() == null, "nulls stay null");
        try {
            s.create(poll("a", "t1", true));
            throw new AssertionError("TriggerStore contract violated: duplicate must fail");
        } catch (ApiException e) {
            check(e.code() == ApiException.Code.CONFLICT, "duplicate is CONFLICT");
        }
        s.create(poll("b", "t1", true));
        check(s.get("b", "t1").isPresent() && s.get("c", "t1").isEmpty(), "ids are per tenant");
        s.create(new Trigger("a", "hook", Trigger.Kind.WEBHOOK, "proc", true, Map.of(), null, "abcd", null, null, 0, "u", T.plusSeconds(1)));
        s.create(poll("a", "off", false));
        s.create(new Trigger("a", "daily", Trigger.Kind.SCHEDULE, "proc", true, Map.of("cron", "0 8 * * 1-5", "timezone", "Asia/Jakarta", "graceSeconds", 600,
                "input", Map.of("topic", "news")), null, null, null, null, 0, "u", T.plusSeconds(2)));
        check(s.get("a", "daily").orElseThrow().kind() == Trigger.Kind.SCHEDULE && s.get("a", "daily").orElseThrow().config().get("cron").equals("0 8 * * 1-5"),
                "a schedule round-trips");
        check(s.list("a").size() == 4 && s.list("b").size() == 1 && s.list("zzz").isEmpty(), "list is tenant scoped");
        check(s.pollable().size() == 3, "pollable = enabled POLL and SCHEDULE triggers of every tenant (a/t1, a/daily, b/t1), never webhooks");

        Trigger changed = got.with(true, "42", T.plusSeconds(60), "boom", 3);
        s.update(changed);
        var after = s.get("a", "t1").orElseThrow();
        check("42".equals(after.cursor()) && T.plusSeconds(60).equals(after.lastPolledAt()) && "boom".equals(after.lastError())
                && after.consecutiveFailures() == 3, "update stores cursor, time, error, failures");
        check(s.get("b", "t1").orElseThrow().cursor() == null, "update touches only its own tenant's row");
        s.update(after.with(false, "42", T, null, 0));
        check(s.pollable().size() == 2 && s.get("a", "hook").orElseThrow().secretHash().equals("abcd"), "disabled drops out; hash round-trips");

        check(!s.delete("c", "t1") && s.delete("a", "t1") && s.get("a", "t1").isEmpty() && s.get("b", "t1").isPresent(), "delete is tenant scoped");
    }
}
