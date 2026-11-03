package tech.kayys.wayang.api.trigger;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.definition.DefinitionKind;
import tech.kayys.wayang.api.definition.DefinitionStore;
import tech.kayys.wayang.api.run.EventBus;
import tech.kayys.wayang.api.secret.InMemorySecretStore;
import tech.kayys.wayang.api.oauth.OAuthService;
import tech.kayys.wayang.api.oauth.OAuthStore;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.run.ReferenceRunEngine;
import tech.kayys.wayang.api.Role;
import tech.kayys.wayang.api.run.RunEngine;
import tech.kayys.wayang.api.secret.SecretCipher;
import tech.kayys.wayang.api.secret.SecretService;
import tech.kayys.wayang.api.WayangApi;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.connectors.Connectors;
import tech.kayys.wayang.connectors.OAuthHttp;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.WaitRegistry;

/** Gmail trigger end to end: OAuth connection -> ${secret:oauth.work} -> poller -> run, against fake Google endpoints. */
class EmailTriggerTest {

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

    private HttpServer server;
    private final List<String> gmailAuth = new CopyOnWriteArrayList<>();
    private volatile long mailDate;
    private int tokens;

    private final WaitRegistry waits = new WaitRegistry.InMemory();
    private final DefinitionStore store = new DefinitionStore.InMemory();
    private final EventBus bus = new EventBus();
    private final RunEngine engine = new ReferenceRunEngine(store, waits, Map.of(), (spec, in) -> Map.of(), bus, clock);
    private final WayangApi api = new WayangApi(store, engine, waits, bus, clock);
    private final Principal admin = new Principal("acme", "ann", Set.of(Role.ADMIN));
    private final TriggerStore triggers = new TriggerStore.InMemory();

    private static String reply(HttpExchange ex, String body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length());
        ex.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
        ex.close();
        return body;
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", ex -> {
            ex.getRequestBody().readAllBytes();
            reply(ex, "{\"access_token\":\"AT" + (++tokens) + "\",\"expires_in\":3600,\"refresh_token\":\"RT\"}");
        });
        server.createContext("/gmail/v1/users/me/messages", ex -> {
            gmailAuth.add(ex.getRequestHeaders().getFirst("Authorization"));
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/messages")) {
                reply(ex, "{\"messages\":[{\"id\":\"m1\"},{\"id\":\"m2\"}]}");
            } else if (path.endsWith("/m1")) {
                reply(ex, "{\"id\":\"m1\",\"threadId\":\"t1\",\"internalDate\":\"" + mailDate + "\",\"snippet\":\"hello\",\"payload\":{\"headers\":["
                        + "{\"name\":\"From\",\"value\":\"Boss <boss@example.com>\"},{\"name\":\"Subject\",\"value\":\"Budget\"},"
                        + "{\"name\":\"Authentication-Results\",\"value\":\"mx.google.com; dkim=pass\"}]}}");
            } else {
                reply(ex, "{\"id\":\"m2\",\"threadId\":\"t2\",\"internalDate\":\"" + (mailDate + 1) + "\",\"snippet\":\"win a prize\",\"payload\":{\"headers\":["
                        + "{\"name\":\"From\",\"value\":\"spam@evil.com\"},{\"name\":\"Subject\",\"value\":\"Prize\"},"
                        + "{\"name\":\"Authentication-Results\",\"value\":\"mx.google.com; dkim=pass\"}]}}");
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void anAllowedAuthenticatedEmailStartsOneRunUsingAFreshTokenAndNeverLeaksIt() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        SecretService secrets = new SecretService(new InMemorySecretStore(),
                new SecretCipher(Map.of("k", new byte[32]), "k"), clock);
        UrlGuard guard = new UrlGuard(true, Set.of());
        OAuthService oauth = new OAuthService(new OAuthStore.InMemory(), secrets, new OAuthHttp(guard),
                List.of(new OAuthService.Provider("google", "https://accounts.example/auth", base + "/token",
                        OAuthService.Provider.google().allowedScopes(), Map.of())),
                "https://wayang.example/oauth/callback", clock);
        secrets.put(admin, "GOOGLE_CLIENT_SECRET", "cs");
        oauth.create(admin, "work", "google", "cid", "GOOGLE_CLIENT_SECRET",
                List.of("https://www.googleapis.com/auth/gmail.readonly"));
        String state = URI.create(oauth.authorize(admin, "work")).getRawQuery().replaceAll(".*state=([^&]+).*", "$1");
        oauth.callback(URLDecoder.decode(state, StandardCharsets.UTF_8), "code", null);

        Map<String, Object> proc = Map.of("id", "mail", "name", "Mail", "elements", List.of(
                Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "t", "type", "USER_TASK", "config", Map.of("title", "Reply")),
                Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "f1", "source", "s", "target", "t"), Map.of("id", "f2", "source", "t", "target", "e")));
        api.saveDraft(admin, DefinitionKind.PROCESS, "mail", proc, null);
        api.publish(admin, DefinitionKind.PROCESS, "mail");

        TriggerService svc = new TriggerService(triggers, api, Connectors.pollers(guard), Connectors.webhookProviders(), oauth, clock);
        svc.create(admin, "inbox", Trigger.Kind.POLL, "mail", Map.of("source", "gmail", "baseUrl", base,
                "accessToken", "${secret:oauth.work}", "allowedSenders", List.of("boss@example.com")));
        // a literal token in the trigger config is refused: only a secret reference may carry credentials
        assertThrows(ApiException.class, () -> svc.create(admin, "bad", Trigger.Kind.POLL, "mail",
                Map.of("source", "gmail", "accessToken", "ya29.literal", "allowedSenders", List.of("boss@example.com"))));

        assertEquals(0, svc.tick(), "first poll only records where 'now' is");
        assertTrue(gmailAuth.isEmpty());
        mailDate = System.currentTimeMillis() + 60_000;
        now = now.plusSeconds(120);
        assertEquals(1, svc.tick(), "boss's mail starts a run; the stranger's does not");
        assertEquals(1, api.listRuns(admin, "mail", 100).size());
        assertEquals("Bearer AT1", gmailAuth.get(0));
        assertEquals(1, tokens, "one token exchange served callback and poll");

        Trigger t = triggers.get("acme", "inbox").orElseThrow();
        assertNull(t.lastError());
        assertEquals(Long.toString(mailDate + 1), t.cursor(), "cursor moved past the rejected message as well");
        assertFalse(t.config().toString().contains("AT1"), "the token never lands in the stored trigger");
        now = now.plusSeconds(120);
        assertEquals(0, svc.tick(), "nothing new, nothing re-delivered");
    }
}
