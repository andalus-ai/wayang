package tech.kayys.wayang.api.testkit;

import java.time.Instant;
import java.util.function.Supplier;

import tech.kayys.wayang.api.secret.SealedSecret;
import tech.kayys.wayang.api.secret.SecretStore;

/** Behavioural contract for {@link SecretStore} implementations (pass a factory returning an empty store). */
public final class SecretStoreContract {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private SecretStoreContract() {
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("SecretStore contract violated: " + what);
        }
    }

    private static SealedSecret s(String tenant, String name, int v, String key, String sealed) {
        return new SealedSecret(tenant, name, v, key, sealed, T, "u");
    }

    public static void verify(Supplier<SecretStore> fresh) {
        SecretStore st = fresh.get();
        check(st.get("a", "K").isEmpty(), "empty at start");
        check(st.put(s("a", "K", 1, "k1", "v1:k1:AAA"), 0), "create with expected 0");
        check(!st.put(s("a", "K", 1, "k1", "v1:k1:BBB"), 0), "create again with expected 0 fails");
        check(st.get("a", "K").orElseThrow().sealed().equals("v1:k1:AAA"), "failed put changed nothing");
        check(!st.put(s("a", "K", 3, "k1", "x"), 2), "wrong expected version fails");
        check(st.put(s("a", "K", 2, "k1", "v1:k1:CCC"), 1), "update with matching expected version");
        var got = st.get("a", "K").orElseThrow();
        check(got.version() == 2 && got.updatedAt().equals(T) && got.updatedBy().equals("u") && got.keyId().equals("k1"),
                "fields round-trip");
        check(st.get("b", "K").isEmpty(), "other tenant cannot see it");
        check(st.put(s("b", "K", 1, "k1", "v1:k1:ZZZ"), 0), "same name in another tenant is independent");
        st.put(s("a", "A", 1, "k1", "v1:k1:AAA"), 0);
        var l = st.list("a");
        check(l.size() == 2 && l.get(0).name().equals("A") && l.get(1).name().equals("K"), "list sorted by name, tenant scoped");
        check(st.reseal(s("a", "K", 2, "k2", "v1:k2:NEW")), "reseal same version");
        check(st.get("a", "K").orElseThrow().keyId().equals("k2"), "reseal changed the key id");
        check(!st.reseal(s("a", "K", 9, "k2", "x")), "reseal with a different version is refused");
        check(!st.reseal(s("a", "missing", 1, "k2", "x")), "reseal of a missing secret is refused");
        check(st.delete("a", "K") && !st.delete("a", "K"), "delete once");
        check(st.get("b", "K").isPresent(), "delete is tenant scoped");
    }
}
