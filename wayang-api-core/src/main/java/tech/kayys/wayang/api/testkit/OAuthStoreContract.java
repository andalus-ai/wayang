package tech.kayys.wayang.api.testkit;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.oauth.OAuthConnection;
import tech.kayys.wayang.api.oauth.OAuthStore;

/** Behavioural contract for {@link OAuthStore} implementations (pass a factory returning an empty store). */
public final class OAuthStoreContract {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private OAuthStoreContract() {
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("OAuthStore contract violated: " + what);
        }
    }

    private static OAuthConnection conn(String tenant, String id) {
        return new OAuthConnection(tenant, id, "google", "client", "SECRET_NAME",
                List.of("https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/gmail.send"),
                OAuthConnection.Status.PENDING, "admin", T, null);
    }

    public static void verify(Supplier<OAuthStore> factory) {
        OAuthStore s = factory.get();
        check(s.get("a", "work").isEmpty(), "unknown connection");
        s.create(conn("a", "work"));
        s.create(conn("a", "home"));
        s.create(conn("b", "work"));
        try {
            s.create(conn("a", "work"));
            check(false, "duplicate id must be CONFLICT");
        } catch (ApiException e) {
            check(e.code() == ApiException.Code.CONFLICT, "duplicate id must be CONFLICT");
        }
        OAuthConnection got = s.get("a", "work").orElseThrow();
        check(got.scopes().size() == 2 && got.scopes().get(1).endsWith("gmail.send"), "scopes round-trip in order");
        check(got.status() == OAuthConnection.Status.PENDING && got.connectedAt() == null, "initial status");
        check(got.createdAt().equals(T) && got.clientSecretName().equals("SECRET_NAME"), "fields round-trip");
        check(s.list("a").size() == 2 && s.list("b").size() == 1 && s.list("c").isEmpty(), "list is tenant scoped");
        check(s.get("b", "home").isEmpty(), "tenant isolation on get");

        Instant later = T.plusSeconds(60);
        s.update(got.with(OAuthConnection.Status.CONNECTED, later));
        OAuthConnection up = s.get("a", "work").orElseThrow();
        check(up.status() == OAuthConnection.Status.CONNECTED && up.connectedAt().equals(later), "update");
        check(s.get("b", "work").orElseThrow().status() == OAuthConnection.Status.PENDING, "update does not leak across tenants");

        // states: single use, expiry, deletion with the connection
        s.saveState(new OAuthStore.State("st1", "a", "work", "ver", T.plusSeconds(600)));
        check(s.takeState("nope", T).isEmpty(), "unknown state");
        OAuthStore.State st = s.takeState("st1", T).orElseThrow();
        check(st.tenant().equals("a") && st.connectionId().equals("work") && st.verifier().equals("ver"), "state round-trip");
        check(s.takeState("st1", T).isEmpty(), "a state can be taken once");
        s.saveState(new OAuthStore.State("st2", "a", "work", "v", T.plusSeconds(600)));
        check(s.takeState("st2", T.plusSeconds(600)).isEmpty(), "expired at the deadline");
        check(s.takeState("st2", T).isEmpty(), "an expired state is consumed too");
        s.saveState(new OAuthStore.State("st3", "a", "home", "v", T.plusSeconds(600)));

        check(s.delete("a", "home"), "delete");
        check(!s.delete("a", "home"), "delete twice");
        check(s.takeState("st3", T).isEmpty(), "deleting a connection drops its pending states");
        check(s.get("b", "work").isPresent() && s.get("a", "work").isPresent(), "delete is scoped");
    }
}
