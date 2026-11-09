package tech.kayys.wayang.api.testkit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import tech.kayys.wayang.api.audit.AuditEntry;
import tech.kayys.wayang.api.audit.AuditStore;

/** Behavioural contract for {@link AuditStore} implementations (pass a factory returning an empty store). */
public final class AuditStoreContract {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private AuditStoreContract() {
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("AuditStore contract violated: " + what);
        }
    }

    private static AuditEntry e(String tenant, int secs, String route) {
        return new AuditEntry(tenant, T.plusSeconds(secs), "ann", "POST", route, Map.of("id", "x", "token", "***"), 201, "OK");
    }

    public static void verify(Supplier<AuditStore> factory) {
        AuditStore s = factory.get();
        check(s.list("a", 10, null).isEmpty(), "empty store lists nothing");
        s.append(e("a", 1, "/r1"));
        s.append(e("a", 3, "/r3"));
        s.append(e("a", 2, "/r2"));
        s.append(e("b", 9, "/other"));
        List<AuditEntry> all = s.list("a", 10, null);
        check(all.size() == 3, "tenant isolation");
        check(all.get(0).route().equals("/r3") && all.get(2).route().equals("/r1"), "newest first");
        check(s.list("a", 2, null).size() == 2, "limit");
        List<AuditEntry> older = s.list("a", 10, T.plusSeconds(3));
        check(older.size() == 2 && older.get(0).route().equals("/r2"), "before is exclusive");
        AuditEntry got = all.get(0);
        check(got.tenant().equals("a") && got.actor().equals("ann") && got.method().equals("POST") && got.status() == 201
                && got.outcome().equals("OK") && got.time().equals(T.plusSeconds(3)), "fields round-trip");
        check(got.ids().equals(Map.of("id", "x", "token", "***")), "ids round-trip");
    }
}
