package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WebhookProvider;

/** Twilio, Resend, Airtable, RSS and the GitHub webhook against one fake HTTP server that records what it receives. */
class MoreConnectorsTest {

    record Req(String method, String uri, Map<String, String> headers, String body) {
    }

    private HttpServer server;
    private final List<Req> reqs = new CopyOnWriteArrayList<>();
    private volatile String answer = "{}";
    private volatile int status = 200;
    private volatile String ctype = "application/json";
    private final UrlGuard local = new UrlGuard(true, Set.of());

    @BeforeEach
    void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<String, String> h = new java.util.HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
            reqs.add(new Req(ex.getRequestMethod(), ex.getRequestURI().toString(), h,
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] b = answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", ctype);
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                ex.getResponseBody().write(b);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void down() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static final String KEY = "secret_0123456789abcdef";
    private static final String SID = "AC" + "0123456789abcdef0123456789abcdef";

    // ---------------------------------------------------------------- Twilio

    private Map<String, Object> tw(Map<String, Object> inputs, Object allowed, String from) throws Exception {
        return new Twilio.Sender(local).call(m("accountSid", SID, "authToken", KEY, "from", from, "allowedTo", allowed, "baseUrl", base()), inputs);
    }

    @Test
    void twilioSendsFormEncodedWithBasicAuth() throws Exception {
        answer = "{\"sid\":\"SM1\",\"status\":\"queued\"}";
        Map<String, Object> out = tw(m("to", "+628123456789", "body", "a&b=c d"), List.of("+628123456789"), "+15005550006");
        assertEquals("SM1", out.get("sid"));
        Req r = reqs.get(0);
        assertEquals("POST /2010-04-01/Accounts/" + SID + "/Messages.json", r.method() + " " + r.uri());
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString((SID + ":" + KEY).getBytes(StandardCharsets.UTF_8)), r.headers().get("authorization"));
        assertTrue(r.headers().get("content-type").startsWith("application/x-www-form-urlencoded"), r.headers().get("content-type"));
        assertEquals("To=%2B628123456789&From=%2B15005550006&Body=a%26b%3Dc+d", r.body());
    }

    @Test
    void twilioRefusesUnlistedAndMalformedRecipients() throws Exception {
        for (String to : List.of("+628000000000", "+62812345678&To=+1555", "08123456789", "+62 812 3456 789", "whatsapp:+628123456789")) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> tw(m("to", to, "body", "x"), List.of("+628123456789"), "+15005550006"), to);
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> tw(m("to", "+628123456789", "body", "x"), List.of(), "+15005550006"));
        assertThrows(ServiceHandler.ConnectorException.class, () -> tw(m("to", "+628123456789", "body", "x".repeat(1601)), List.of("+628123456789"), "+15005550006"));
        assertEquals(0, reqs.size());
    }

    @Test
    void twilioNumberFormatIsCheckedEvenIfTheAllowListIsSloppy() throws Exception {
        for (String to : List.of("+62812345678&To=+1555", "+62812345678\nX", "+6281 2345678")) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> tw(m("to", to, "body", "x"), List.of(to), "+15005550006"), to);
        }
        assertEquals(0, reqs.size());
    }

    @Test
    void twilioWhatsAppSenderOnlyReachesWhatsAppRecipients() throws Exception {
        answer = "{\"sid\":\"SM2\",\"status\":\"queued\"}";
        assertThrows(ServiceHandler.ConnectorException.class, () -> tw(m("to", "+628123456789", "body", "x"), List.of("+628123456789"), "whatsapp:+14155238886"));
        tw(m("to", "whatsapp:+628123456789", "body", "x"), List.of("whatsapp:+628123456789"), "whatsapp:+14155238886");
        assertEquals(1, reqs.size());
        tw(m("to", "+628123456789", "body", "x"), List.of("+628123456789"), "MG" + "0123456789abcdef0123456789abcdef");
        assertTrue(reqs.get(1).body().contains("MessagingServiceSid=MG"), reqs.get(1).body());
    }

    @Test
    void twilioRejectsBadAccountSidAndNeverEchoesToken() throws Exception {
        assertThrows(ServiceHandler.ConnectorException.class, () -> new Twilio.Sender(local).call(
                m("accountSid", "AC../x", "authToken", KEY, "from", "+15005550006", "allowedTo", List.of("+628123456789"), "baseUrl", base()),
                m("to", "+628123456789", "body", "x")));
        status = 401;
        answer = "{\"message\":\"nope\"}";
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> tw(m("to", "+628123456789", "body", "x"), List.of("+628123456789"), "+15005550006"));
        assertFalse(String.valueOf(e.getMessage()).contains(KEY));
    }

    // ---------------------------------------------------------------- Resend

    private Map<String, Object> rs(Map<String, Object> inputs, Object allowed) throws Exception {
        return new Resend.Sender(local).call(m("apiKey", KEY, "from", "Bot <bot@example.org>", "allowedRecipients", allowed, "baseUrl", base()), inputs);
    }

    @Test
    void resendSendsToAllowedRecipients() throws Exception {
        answer = "{\"id\":\"em_1\"}";
        Map<String, Object> out = rs(m("to", List.of("a@corp.com", "B@Corp.com"), "subject", "Hi", "text", "t", "replyTo", "r@corp.com"), List.of("@corp.com"));
        assertEquals("em_1", out.get("id"));
        Req r = reqs.get(0);
        assertEquals("POST /emails", r.method() + " " + r.uri());
        assertEquals("Bearer " + KEY, r.headers().get("authorization"));
        assertTrue(r.body().contains("\"to\":[\"a@corp.com\",\"B@Corp.com\"]") && r.body().contains("\"reply_to\":\"r@corp.com\""), r.body());
    }

    @Test
    void resendRecipientAllowListIsStrict() throws Exception {
        for (String to : List.of("x@evil.com", "a@corp.com.evil.com", "a@notcorp.com", "a@corp.com,x@evil.com", "a@corp.com\r\nBcc: x@evil.com", "<a@corp.com>")) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> rs(m("to", to, "subject", "s", "text", "t"), List.of("@corp.com")), to);
        }
        for (String to : List.of("x@evil.com,a@corp.com", "x@evil.com a@corp.com", "<x@evil.com>@corp.com", "a@corp.com>")) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> rs(m("to", to, "subject", "s", "text", "t"), List.of("@corp.com")), to);
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> rs(m("to", List.of("a@corp.com", "x@evil.com"), "subject", "s", "text", "t"), List.of("@corp.com")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> rs(m("to", "a@corp.com", "subject", "s", "text", "t"), null));
        assertThrows(ServiceHandler.ConnectorException.class, () -> rs(m("to", "a@corp.com", "subject", "s\nBcc: x", "text", "t"), List.of("@corp.com")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> rs(m("to", "a@corp.com", "subject", "s"), List.of("@corp.com")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> rs(m("to", java.util.Collections.nCopies(21, "a@corp.com"), "subject", "s", "text", "t"), List.of("@corp.com")));
        assertEquals(0, reqs.size());
    }

    // ---------------------------------------------------------------- Airtable

    private static final String APP = "appABCDEFGHIJKLMN";

    private Map<String, Object> at(String op, Map<String, Object> inputs, Object allowed) throws Exception {
        return new Airtable.Sender(local).call(m("token", KEY, "operation", op, "allowedTables", allowed, "baseUrl", base()), inputs);
    }

    @Test
    void airtableCreatesUpdatesAndLists() throws Exception {
        answer = "{\"id\":\"recABCDEFGHIJKLMN\",\"fields\":{\"Name\":\"x\"}}";
        Map<String, Object> out = at("createRecord", m("baseId", APP, "table", "My Tasks", "fields", m("Name", "x")), List.of(APP + "/My Tasks"));
        assertEquals("recABCDEFGHIJKLMN", out.get("id"));
        assertEquals("POST /v0/" + APP + "/My%20Tasks", reqs.get(0).method() + " " + reqs.get(0).uri());
        assertEquals("{\"fields\":{\"Name\":\"x\"}}", reqs.get(0).body());
        at("updateRecord", m("baseId", APP, "table", "T", "recordId", "recABCDEFGHIJKLMN", "fields", m("A", 1)), List.of(APP + "/*"));
        assertEquals("PATCH /v0/" + APP + "/T/recABCDEFGHIJKLMN", reqs.get(1).method() + " " + reqs.get(1).uri());
        answer = "{\"records\":[{\"id\":\"recABCDEFGHIJKLMN\",\"fields\":{\"A\":1}}]}";
        Map<String, Object> l = at("listRecords", m("baseId", APP, "table", "T", "limit", 1000, "filterByFormula", "{A}=1"), List.of(APP + "/T"));
        assertEquals(1, l.get("count"));
        assertTrue(reqs.get(2).uri().contains("pageSize=100") && reqs.get(2).uri().contains("filterByFormula="), reqs.get(2).uri());
        assertEquals("GET", reqs.get(2).method());
    }

    @Test
    void airtableAllowListAndPathTricks() throws Exception {
        for (String table : List.of("Other", "T/../x", "..", "T?x=1", "T#f")) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> at("listRecords", m("baseId", APP, "table", table), List.of(APP + "/T")), table);
        }
        for (String table : List.of("..", ".", "T/../x", "T/x", "a%2Fb", "T?x", "T\n")) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> at("listRecords", m("baseId", APP, "table", table), List.of(APP + "/*")), table);
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> at("listRecords", m("baseId", "appX/../y", "table", "T"), List.of("*")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> at("listRecords", m("baseId", "appZZZZZZZZZZZZZZ", "table", "T"), List.of(APP + "/*")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> at("listRecords", m("baseId", APP, "table", "T"), List.of()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> at("updateRecord", m("baseId", APP, "table", "T", "recordId", "rec/../x", "fields", m("A", 1)), List.of(APP + "/T")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> at("createRecord", m("baseId", APP, "table", "T", "fields", m()), List.of(APP + "/T")));
        assertEquals(0, reqs.size());
    }

    // ---------------------------------------------------------------- RSS

    private static final String RSS = "<?xml version=\"1.0\"?><rss><channel><title>Feed</title>"
            + "<item><title>Two</title><link>https://x/2</link><guid>g2</guid><description>d2</description><pubDate>Tue</pubDate></item>"
            + "<item><title>One</title><link>https://x/1</link><guid>g1</guid></item></channel></rss>";

    private List<Map<String, Object>> ev(Poller.Result r) {
        return r.events();
    }

    @Test
    void rssFirstPollRemembersThenEmitsOnlyNewOldestFirst() throws Exception {
        ctype = "application/rss+xml";
        answer = RSS;
        Rss.Feed feed = new Rss.Feed(local);
        Map<String, Object> cfg = m("url", base() + "/feed");
        Poller.Result first = feed.poll(cfg, null);
        assertEquals(0, first.events().size());
        assertNotNull(first.nextCursor());
        assertEquals(0, feed.poll(cfg, first.nextCursor()).events().size());
        answer = RSS.replace("<item><title>Two", "<item><title>Three</title><guid>g3</guid></item><item><title>Two");
        Poller.Result next = feed.poll(cfg, first.nextCursor());
        assertEquals(1, next.events().size());
        assertEquals("Three", next.events().get(0).get("title"));
        assertEquals(0, feed.poll(cfg, next.nextCursor()).events().size());
    }

    @Test
    void rssEmitExistingAndLimitDefersTheRest() throws Exception {
        ctype = "application/rss+xml";
        answer = RSS;
        Rss.Feed feed = new Rss.Feed(local);
        Poller.Result a = feed.poll(m("url", base() + "/f", "emitExisting", true, "limit", 1), null);
        assertEquals(List.of("One"), ev(a).stream().map(e -> e.get("title")).toList());
        Poller.Result b = feed.poll(m("url", base() + "/f", "limit", 1), a.nextCursor());
        assertEquals(List.of("Two"), ev(b).stream().map(e -> e.get("title")).toList());
        assertEquals("https://x/2", b.events().get(0).get("link"));
        assertEquals(0, feed.poll(m("url", base() + "/f"), b.nextCursor()).events().size());
    }

    @Test
    void rssParsesAtomAndRefusesDoctype() throws Exception {
        ctype = "application/rss+xml";
        answer = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><title>A</title><id>urn:1</id><updated>2026-01-01</updated>"
                + "<link rel=\"self\" href=\"https://x/self\"/><link rel=\"alternate\" href=\"https://x/a\"/><summary>s</summary></entry></feed>";
        Poller.Result r = new Rss.Feed(local).poll(m("url", base() + "/a", "emitExisting", true), null);
        assertEquals("https://x/a", r.events().get(0).get("link"));
        assertEquals("urn:1", r.events().get(0).get("id"));
        answer = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rss><channel><item><title>&x;</title><guid>1</guid></item></channel></rss>";
        assertThrows(ServiceHandler.ConnectorException.class, () -> new Rss.Feed(local).poll(m("url", base() + "/a", "emitExisting", true), null));
    }

    @Test
    void rssBlocksPrivateHostsWithStrictGuard() {
        assertThrows(Exception.class, () -> new Rss.Feed(UrlGuard.strict()).poll(m("url", base() + "/feed"), null));
    }

    // ---------------------------------------------------------------- GitHub webhook

    private final GitHubWebhook ghw = new GitHubWebhook();
    private static final String WH = "whsec_0123456789";

    private static String sig(String secret, String body) {
        return "sha256=" + Hmac.sha256Hex(secret, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void githubWebhookVerifiesSignature() throws Exception {
        String body = "{\"a\":1}";
        Map<String, Object> cfg = m("secret", WH);
        ghw.verify(cfg, Map.of("x-hub-signature-256", sig(WH, body)), body.getBytes(StandardCharsets.UTF_8), java.time.Instant.now());
        for (Map<String, String> h : List.of(Map.<String, String>of(), Map.of("x-hub-signature-256", sig("wrong-secret-1", body)),
                Map.of("x-hub-signature-256", sig(WH, body + " ")), Map.of("x-hub-signature-256", "sha256="), Map.of("x-hub-signature-256", sig(WH, body).replace("sha256=", "")))) {
            assertThrows(WebhookProvider.Rejected.class, () -> ghw.verify(cfg, h, body.getBytes(StandardCharsets.UTF_8), java.time.Instant.now()));
        }
        assertThrows(WebhookProvider.Rejected.class, () -> ghw.verify(m("secret", ""), Map.of("x-hub-signature-256", "sha256=00"), body.getBytes(StandardCharsets.UTF_8), java.time.Instant.now()));
        assertThrows(WebhookProvider.Rejected.class, () -> ghw.verify(m(), Map.of("x-hub-signature-256", "sha256=00"), body.getBytes(StandardCharsets.UTF_8), java.time.Instant.now()));
    }

    private static Map<String, Object> payload(String repo, String senderType, Map<String, Object> extra) {
        Map<String, Object> p = m("repository", m("full_name", repo), "sender", m("login", "u", "type", senderType), "action", "opened");
        p.putAll(extra);
        return p;
    }

    @Test
    void githubWebhookEventsAreFilteredAndDeduplicable() {
        Map<String, Object> cfg = m("allowedRepos", List.of("o/r"));
        var issue = ghw.events(cfg, payload("o/r", "User", m("issue", m("number", 3, "title", "T", "body", "B", "updated_at", "u1", "html_url", "h"))));
        assertEquals(1, issue.size());
        assertEquals("issues", issue.get(0).data().get("type"));
        assertEquals(3, issue.get(0).data().get("number"));
        var again = ghw.events(cfg, payload("o/r", "User", m("issue", m("number", 3, "title", "T", "updated_at", "u1"))));
        assertEquals(issue.get(0).id(), again.get(0).id());
        var comment = ghw.events(cfg, payload("o/r", "User", m("issue", m("number", 3, "title", "T"), "comment", m("id", 9, "body", "hi", "html_url", "c"))));
        assertEquals("issue_comment", comment.get(0).data().get("type"));
        var pr = ghw.events(cfg, payload("o/r", "User", m("pull_request", m("number", 4, "title", "P", "updated_at", "u"))));
        assertEquals("pull_request", pr.get(0).data().get("type"));
        var push = ghw.events(cfg, m("repository", m("full_name", "o/r"), "sender", m("login", "u", "type", "User"), "ref", "refs/heads/main",
                "after", "abc", "commits", List.of(m()), "head_commit", m("message", "fix"), "compare", "cmp"));
        assertEquals("push", push.get(0).data().get("type"));
        assertEquals("fix", push.get(0).data().get("title"));
    }

    @Test
    void githubWebhookDropsUnlistedReposBotsAndUnwantedTypes() {
        var issue = m("issue", m("number", 1, "title", "T"));
        assertEquals(0, ghw.events(m("allowedRepos", List.of("o/r")), payload("o/other", "User", issue)).size());
        assertEquals(0, ghw.events(m("allowedRepos", List.of("o/r")), payload("o/r/../x", "User", issue)).size());
        assertEquals(0, ghw.events(m(), payload("o/r", "User", issue)).size());
        assertEquals(0, ghw.events(m("allowedRepos", List.of()), payload("o/r", "User", issue)).size());
        assertEquals(0, ghw.events(m("allowedRepos", List.of("o/r")), payload("o/r", "Bot", issue)).size());
        assertEquals(0, ghw.events(m("allowedRepos", List.of("o/r"), "events", List.of("push")), payload("o/r", "User", issue)).size());
        assertEquals(1, ghw.events(m("allowedRepos", List.of("o/*"), "events", List.of("issues")), payload("o/r", "User", issue)).size());
        assertEquals(0, ghw.events(m("allowedRepos", List.of("o/r")), m()).size());
    }

    @Test
    void githubWebhookAnswersPing() throws Exception {
        assertEquals("pong", ghw.handshake(m(), Map.of(), m("zen", "Keep it logically awesome.", "hook_id", 1)).orElse(null));
        assertTrue(ghw.handshake(m(), Map.of(), m("action", "opened")).isEmpty());
    }
}
