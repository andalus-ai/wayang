package tech.kayys.wayang.api.testkit;

import java.time.Instant;
import java.util.function.Supplier;

import tech.kayys.wayang.api.ConfigEntry;
import tech.kayys.wayang.api.config.ConfigStore;

/** Behavioural contract for {@link ConfigStore} implementations (pass a factory returning an empty store). */
public final class ConfigStoreContract {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private ConfigStoreContract() {
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("ConfigStore contract violated: " + what);
        }
    }

    private static ConfigEntry e(String tenant, String scope, String key, String value, int v) {
        return new ConfigEntry(tenant, scope, key, value, v, T, "u");
    }

    public static void verify(Supplier<ConfigStore> fresh) {
        ConfigStore st = fresh.get();
        check(st.get("a", "", "K").isEmpty(), "empty at start");
        check(st.put(e("a", "", "K", "one", 1), 0), "create with expected 0");
        check(!st.put(e("a", "", "K", "two", 1), 0), "create again with expected 0 fails");
        check(st.get("a", "", "K").orElseThrow().value().equals("one"), "failed put changed nothing");
        check(!st.put(e("a", "", "K", "x", 3), 2), "wrong expected version fails");
        check(st.put(e("a", "", "K", "two", 2), 1), "update with matching expected version");
        var got = st.get("a", "", "K").orElseThrow();
        check(got.version() == 2 && got.value().equals("two") && got.updatedAt().equals(T) && got.updatedBy().equals("u"), "fields round-trip");
        check(st.put(e("a", "", "N", "multi\nline é中", 1), 0), "unicode and newline values");
        check(st.get("a", "", "N").orElseThrow().value().equals("multi\nline é中"), "unicode round-trips");
        check(st.get("b", "", "K").isEmpty(), "other tenant cannot see it");
        check(st.put(e("b", "", "K", "bee", 1), 0), "same key in another tenant is independent");
        check(st.put(e("a", "proj", "K", "scoped", 1), 0), "same key in a project scope is independent");
        check(st.get("a", "", "K").orElseThrow().value().equals("two"), "project scope does not touch tenant scope");
        check(st.list("a", "").stream().map(ConfigEntry::key).toList().equals(java.util.List.of("K", "N")), "list is by key and one scope only");
        check(st.list("a", "proj").size() == 1, "list of a project scope");
        check(st.list("c", "").isEmpty(), "list of an unknown tenant is empty");
        check(!st.delete("a", "", "missing"), "deleting a missing key is false");
        check(st.delete("a", "", "N") && st.get("a", "", "N").isEmpty(), "delete");
        check(st.deleteScope("a", "proj") == 1 && st.list("a", "proj").isEmpty(), "deleteScope");
        check(st.get("a", "", "K").isPresent() && st.get("b", "", "K").isPresent(), "deleteScope touched nothing else");
    }
}
