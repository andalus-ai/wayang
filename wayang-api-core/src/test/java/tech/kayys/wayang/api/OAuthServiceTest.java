package tech.kayys.wayang.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.connectors.OAuthHttp;
import tech.kayys.wayang.connectors.UrlGuard;

class OAuthServiceTest {

    private static final String REDIRECT = "https://wayang.example/oauth/callback";
    private static final List<String> GMAIL_READ = List.of("https://www.googleapis.com/auth/gmail.readonly");

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId z) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    private final Principal adminA = new Principal("a", "admin", Set.of(Role.ADMIN));
    private final Principal designerA = new Principal("a", "des", Set.of(Role.DESIGNER));
    private HttpServer server;
    private final List<Map<String, String>> forms = new CopyOnWriteArrayList<>();
    private volatile String lastChallenge;
    private volatile String answer;
    private volatile int status = 200;
    private int issued;

    private SecretService secrets;
    private OAuthService oauth;

    private static byte[] key() {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, (byte) 7);
        return k;
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", ex -> {
            Map<String, String> f = new LinkedHashMap<>();
            for (String kv : new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                String[] p = kv.split("=", 2);
                f.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), URLDecoder.decode(p.length > 1 ? p[1] : "", StandardCharsets.UTF_8));
            }
            forms.add(f);
            String body = answer != null ? answer
                    : "{\"access_token\":\"AT" + (++issued) + "\",\"expires_in\":3600,\"refresh_token\":\"RT-first\"}";
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length());
            ex.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            ex.close();
        });
        server.start();
        String tokenUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        secrets = new SecretService(new InMemorySecretStore(), new SecretCipher(Map.of("k", key()), "k"), clock);
        oauth = new OAuthService(new OAuthStore.InMemory(), secrets, new OAuthHttp(new UrlGuard(true, Set.of())),
                List.of(new OAuthService.Provider("google", "https://accounts.example/auth", tokenUrl,
                        OAuthService.Provider.google().allowedScopes(), Map.of("access_type", "offline"))),
                REDIRECT, clock);
        secrets.put(adminA, "GOOGLE_CLIENT_SECRET", "cs-123");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Map<String, String> query(String url) {
        Map<String, String> q = new LinkedHashMap<>();
        for (String kv : URI.create(url).getRawQuery().split("&")) {
            String[] p = kv.split("=", 2);
            q.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), URLDecoder.decode(p[1], StandardCharsets.UTF_8));
        }
        return q;
    }

    private String connect(String id) {
        oauth.create(adminA, id, "google", "client-1", "GOOGLE_CLIENT_SECRET", GMAIL_READ);
        Map<String, String> q = query(oauth.authorize(adminA, id));
        lastChallenge = q.get("code_challenge");
        oauth.callback(q.get("state"), "the-code", null);
        return q.get("state");
    }

    @Test
    void authorizationCodeFlowWithPkceThenCachedRefreshingAccessTokens() throws Exception {
        oauth.create(adminA, "work", "google", "client-1", "GOOGLE_CLIENT_SECRET", GMAIL_READ);
        String url = oauth.authorize(adminA, "work");
        Map<String, String> q = query(url);
        assertTrue(url.startsWith("https://accounts.example/auth?"));
        assertEquals("code", q.get("response_type"));
        assertEquals("client-1", q.get("client_id"));
        assertEquals(REDIRECT, q.get("redirect_uri"), "redirect is fixed by configuration");
        assertEquals("S256", q.get("code_challenge_method"));
        assertEquals(GMAIL_READ.get(0), q.get("scope"));
        assertEquals("offline", q.get("access_type"));

        assertNull(oauth.resolve("a", "oauth.work"), "not connected yet");
        OAuthConnection c = oauth.callback(q.get("state"), "the-code", null);
        assertEquals(OAuthConnection.Status.CONNECTED, c.status());

        Map<String, String> ex = forms.get(0);
        assertEquals("authorization_code", ex.get("grant_type"));
        assertEquals("the-code", ex.get("code"));
        assertEquals(REDIRECT, ex.get("redirect_uri"));
        assertEquals("cs-123", ex.get("client_secret"));
        byte[] d = MessageDigest.getInstance("SHA-256").digest(ex.get("code_verifier").getBytes(StandardCharsets.US_ASCII));
        assertEquals(q.get("code_challenge"), Base64.getUrlEncoder().withoutPadding().encodeToString(d), "verifier matches challenge");

        assertEquals("AT1", oauth.resolve("a", "oauth.work"));
        assertEquals("AT1", oauth.resolve("a", "oauth.work"));
        assertEquals(1, forms.size(), "cached");
        now = now.plusSeconds(3600 - 30); // inside the 60 s skew
        assertEquals("AT2", oauth.resolve("a", "oauth.work"));
        Map<String, String> rf = forms.get(1);
        assertEquals("refresh_token", rf.get("grant_type"));
        assertEquals("RT-first", rf.get("refresh_token"));
    }

    @Test
    void rotatedRefreshTokensArePersisted() {
        connect("work");
        now = now.plusSeconds(3600);
        answer = "{\"access_token\":\"AT-x\",\"expires_in\":10,\"refresh_token\":\"RT-second\"}";
        assertEquals("AT-x", oauth.resolve("a", "oauth.work"));
        now = now.plusSeconds(20);
        assertEquals("AT-x", oauth.resolve("a", "oauth.work"));
        assertEquals("RT-second", forms.get(forms.size() - 1).get("refresh_token"), "the rotated token is used next time");
    }

    @Test
    void stateIsSingleUseExpiringAndBoundToItsConnection() {
        oauth.create(adminA, "work", "google", "client-1", "GOOGLE_CLIENT_SECRET", GMAIL_READ);
        String st = query(oauth.authorize(adminA, "work")).get("state");
        oauth.callback(st, "c", null);
        assertEquals(ApiException.Code.BAD_REQUEST, assertThrows(ApiException.class, () -> oauth.callback(st, "c", null)).code(), "replay");
        assertThrows(ApiException.class, () -> oauth.callback("forged", "c", null));
        assertThrows(ApiException.class, () -> oauth.callback(null, "c", null));

        String st2 = query(oauth.authorize(adminA, "work")).get("state");
        now = now.plusSeconds(601);
        assertThrows(ApiException.class, () -> oauth.callback(st2, "c", null), "expired");

        String st3 = query(oauth.authorize(adminA, "work")).get("state");
        assertThrows(ApiException.class, () -> oauth.callback(st3, "c", "access_denied"));
        assertThrows(ApiException.class, () -> oauth.callback(st3, "c", null), "an error answer still burns the state");
    }

    @Test
    void refreshTokensAreNeverReadableAndAccessIsTenantScoped() {
        connect("work");
        assertNull(oauth.resolve("a", "oauth-refresh.work"), "workflows cannot read the refresh token");
        assertNotNull(secrets.resolve("a", "oauth-refresh.work"), "but it is stored");
        assertNull(oauth.resolve("b", "oauth.work"), "another tenant has no such connection");
        assertEquals("cs-123", oauth.resolve("a", "GOOGLE_CLIENT_SECRET"), "plain secrets still resolve");
        assertEquals(ApiException.Code.BAD_REQUEST,
                assertThrows(ApiException.class, () -> secrets.put(adminA, "oauth-refresh.work", "x")).code());
        assertThrows(ApiException.class, () -> secrets.put(adminA, "oauth.work", "x"));
        assertThrows(ApiException.class, () -> secrets.delete(adminA, "oauth-refresh.work"));
        assertTrue(secrets.list(adminA).stream().noneMatch(m -> m.name().startsWith("oauth")), "hidden from listings");
        assertNotNull(secrets.resolve("a", "oauth-refresh.work"));
    }

    @Test
    void invalidGrantRevokesAndFailsClosed() {
        connect("work");
        now = now.plusSeconds(7200);
        answer = "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}";
        status = 400;
        assertNull(oauth.resolve("a", "oauth.work"));
        assertEquals(OAuthConnection.Status.REVOKED, oauth.list(adminA).get(0).status());
        int n = forms.size();
        answer = null;
        status = 200;
        assertNull(oauth.resolve("a", "oauth.work"), "revoked connections are not retried");
        assertEquals(n, forms.size());
    }

    @Test
    void transientFailureDoesNotRevoke() {
        connect("work");
        now = now.plusSeconds(7200);
        status = 503;
        answer = "{}";
        assertNull(oauth.resolve("a", "oauth.work"));
        assertEquals(OAuthConnection.Status.CONNECTED, oauth.list(adminA).get(0).status());
        status = 200;
        answer = null;
        assertNotNull(oauth.resolve("a", "oauth.work"));
    }

    @Test
    void validationAndRbac() {
        assertThrows(ApiException.class, () -> oauth.create(designerA, "x", "google", "c", "GOOGLE_CLIENT_SECRET", GMAIL_READ));
        assertThrows(ApiException.class, () -> oauth.create(adminA, "x", "nope", "c", "GOOGLE_CLIENT_SECRET", GMAIL_READ));
        assertThrows(ApiException.class, () -> oauth.create(adminA, "x", "google", "c", "GOOGLE_CLIENT_SECRET",
                List.of("https://mail.google.com/")), "full-mailbox scope is not offered");
        assertThrows(ApiException.class, () -> oauth.create(adminA, "x", "google", "c", "GOOGLE_CLIENT_SECRET", List.of()));
        assertThrows(ApiException.class, () -> oauth.create(adminA, "x", "google", "c", "oauth-refresh.work", GMAIL_READ));
        assertThrows(ApiException.class, () -> oauth.create(adminA, "bad id", "google", "c", "GOOGLE_CLIENT_SECRET", GMAIL_READ));
        assertThrows(ApiException.class, () -> oauth.authorize(adminA, "missing"));
        oauth.create(adminA, "ok", "google", "c", "GOOGLE_CLIENT_SECRET", GMAIL_READ);
        assertEquals(ApiException.Code.CONFLICT,
                assertThrows(ApiException.class, () -> oauth.create(adminA, "ok", "google", "c", "GOOGLE_CLIENT_SECRET", GMAIL_READ)).code());
        assertThrows(ApiException.class, () -> oauth.authorize(designerA, "ok"));
    }

    @Test
    void callbackWithoutRefreshTokenOrClientSecretFails() {
        oauth.create(adminA, "work", "google", "client-1", "GOOGLE_CLIENT_SECRET", GMAIL_READ);
        answer = "{\"access_token\":\"AT\",\"expires_in\":3600}";
        String st = query(oauth.authorize(adminA, "work")).get("state");
        assertThrows(ApiException.class, () -> oauth.callback(st, "c", null));
        assertNull(oauth.resolve("a", "oauth.work"), "stays pending");

        oauth.create(adminA, "w2", "google", "client-1", "MISSING", GMAIL_READ);
        String st2 = query(oauth.authorize(adminA, "w2")).get("state");
        assertThrows(ApiException.class, () -> oauth.callback(st2, "c", null));
    }

    @Test
    void deleteRemovesTheRefreshTokenAndAccess() {
        connect("work");
        assertNotNull(oauth.resolve("a", "oauth.work"));
        oauth.delete(adminA, "work");
        assertNull(oauth.resolve("a", "oauth.work"));
        assertNull(secrets.resolve("a", "oauth-refresh.work"));
        assertThrows(ApiException.class, () -> oauth.delete(adminA, "work"));
    }
}
