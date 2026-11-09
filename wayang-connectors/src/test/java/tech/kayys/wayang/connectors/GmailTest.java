package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

import tech.kayys.wayang.runtime.OAuthTokenEndpoint;
import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler;

class GmailTest {

    private HttpServer server;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private volatile String listAnswer = "{\"messages\":[]}";
    private volatile String sentRaw;

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** internalDate in ms; auth = Authentication-Results value or null for none. */
    private static String msg(String id, long date, String from, String subject, String auth, String bodyText) {
        String hs = "[{\"name\":\"From\",\"value\":\"" + from + "\"},{\"name\":\"Subject\",\"value\":\"" + subject + "\"},"
                + "{\"name\":\"Message-ID\",\"value\":\"<" + id + "@mail.example>\"}"
                + (auth == null ? "" : ",{\"name\":\"Authentication-Results\",\"value\":\"" + auth + "\"}")
                + (auth == null ? "" : ",{\"name\":\"Authentication-Results\",\"value\":\"forged; dkim=pass\"}") + "]";
        String part = bodyText == null ? "" : ",\"mimeType\":\"multipart/alternative\",\"parts\":[{\"mimeType\":\"text/html\",\"body\":{\"data\":\""
                + b64("<b>html</b>") + "\"}},{\"mimeType\":\"text/plain\",\"body\":{\"data\":\"" + b64(bodyText) + "\"}}]";
        return "{\"id\":\"" + id + "\",\"threadId\":\"t" + id + "\",\"internalDate\":\"" + date + "\",\"snippet\":\"snip " + id
                + "\",\"payload\":{\"headers\":" + hs + part + "}}";
    }

    private void reply(HttpExchange ex, String body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length());
        ex.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
        ex.close();
    }

    private final java.util.Map<String, String> messages = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gmail/v1/users/me/messages", ex -> {
            String path = ex.getRequestURI().getPath();
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            if (path.endsWith("/send")) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                sentRaw = body;
                reply(ex, "{\"id\":\"sent1\",\"threadId\":\"th9\"}");
            } else if (path.equals("/gmail/v1/users/me/messages")) {
                reply(ex, listAnswer);
            } else {
                String m = messages.get(path.substring(path.lastIndexOf('/') + 1));
                if (m == null) {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                } else {
                    reply(ex, m);
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Map<String, Object> cfg(Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("accessToken", "ya29.token");
        m.put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private final Gmail.Inbox inbox = new Gmail.Inbox(new UrlGuard(true, Set.of()));
    private final Gmail.Sender sender = new Gmail.Sender(new UrlGuard(true, Set.of()));
    private static final String PASS = "mx.google.com; dkim=pass header.i=@boss.example; spf=pass";

    @Test
    void addressParsingIsStrict() {
        assertEquals("boss@x.com", Gmail.address("Big Boss <Boss@X.com>"));
        assertEquals("boss@x.com", Gmail.address("boss@x.com"));
        assertNull(Gmail.address("\"boss@x.com\" <evil@y.com> extra"), "display-name tricks");
        assertNull(Gmail.address("not an address"));
        assertNull(Gmail.address(null));
    }

    @Test
    void firstPollRecordsNowAndReturnsNothing() throws Exception {
        long before = System.currentTimeMillis();
        Poller.Result r = inbox.poll(cfg("allowedSenders", List.of("boss@x.com")), null);
        assertTrue(r.events().isEmpty());
        assertTrue(Long.parseLong(r.nextCursor()) >= before);
        assertTrue(seen.isEmpty(), "no mailbox read on the first poll");
    }

    @Test
    void refusesToRunWithoutAnAllowListOrToken() {
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> inbox.poll(cfg(), "1000"));
        assertTrue(e.getMessage().contains("allowedSenders"));
        assertThrows(ServiceHandler.ConnectorException.class,
                () -> inbox.poll(cfg("allowedSenders", List.of("a@b.co"), "accessToken", " "), "1000"));
        assertThrows(ServiceHandler.ConnectorException.class,
                () -> inbox.poll(cfg("allowedSenders", List.of("nonsense")), "1000"), "an allow-list with no valid address is empty");
    }

    @Test
    void deliversOnlyAllowedAuthenticatedNewMessagesInOrderAndAdvancesPastTheRest() throws Exception {
        listAnswer = "{\"messages\":[{\"id\":\"m3\"},{\"id\":\"m2\"},{\"id\":\"m1\"},{\"id\":\"m4\"},{\"id\":\"m5\"},{\"id\":\"old\"}]}";
        messages.put("old", msg("old", 900, "boss@x.com", "old", PASS, null));
        messages.put("m1", msg("m1", 2000, "Big Boss <boss@x.com>", "Plan", PASS, null));
        messages.put("m2", msg("m2", 3000, "stranger@evil.com", "Hi", PASS, null));
        messages.put("m3", msg("m3", 5000, "boss@x.com", "Later", PASS, null));
        messages.put("m4", msg("m4", 4000, "boss@x.com", "Spoofed", "mx.google.com; dkim=fail; spf=fail", null));
        messages.put("m5", msg("m5", 4500, "boss@x.com", "No results header", null, null));
        Poller.Result r = inbox.poll(cfg("allowedSenders", List.of("BOSS@x.com")), "1000");

        assertEquals(2, r.events().size());
        assertEquals("m1", r.events().get(0).get("messageId"));
        assertEquals("m3", r.events().get(1).get("messageId"));
        Map<String, Object> e = r.events().get(0);
        assertEquals("boss@x.com", e.get("from"));
        assertEquals("Plan", e.get("subject"));
        assertEquals("snip m1", e.get("snippet"));
        assertEquals("<m1@mail.example>", e.get("rfcMessageId"));
        assertEquals("tm1", e.get("threadId"));
        assertFalse(e.containsKey("body"), "metadata only by default");
        assertEquals("5000", r.nextCursor(), "advances past rejected messages too");
        assertTrue(seen.get(0).contains("auth=Bearer ya29.token"));
        assertTrue(seen.get(0).contains("q=in%3Ainbox+after%3A0"), seen.get(0));
    }

    @Test
    void theFirstAuthenticationResultsHeaderIsTheOneThatCounts() throws Exception {
        // msg() appends a sender-forged "dkim=pass" header after the real one; when the real one says fail it must not win.
        listAnswer = "{\"messages\":[{\"id\":\"m1\"}]}";
        messages.put("m1", msg("m1", 2000, "boss@x.com", "x", "mx.google.com; dkim=fail", null));
        assertTrue(inbox.poll(cfg("allowedSenders", List.of("boss@x.com")), "1000").events().isEmpty());
        assertEquals(1, inbox.poll(cfg("allowedSenders", List.of("boss@x.com"), "requireAuthenticated", false), "1000").events().size());
    }

    @Test
    void bodyIsOptInPlainTextAndClipped() throws Exception {
        listAnswer = "{\"messages\":[{\"id\":\"m1\"}]}";
        messages.put("m1", msg("m1", 2000, "boss@x.com", "x", PASS, "Please call me. " + "z".repeat(9000)));
        Poller.Result r = inbox.poll(cfg("allowedSenders", List.of("boss@x.com"), "includeBody", true), "1000");
        String body = (String) r.events().get(0).get("body");
        assertTrue(body.startsWith("Please call me."));
        assertEquals(8000, body.length());
    }

    @Test
    void acceptAnySenderIsAnExplicitOptIn() throws Exception {
        listAnswer = "{\"messages\":[{\"id\":\"m2\"}]}";
        messages.put("m2", msg("m2", 3000, "stranger@evil.com", "Hi", PASS, null));
        assertEquals(1, inbox.poll(cfg("acceptAnySender", true), "1000").events().size());
    }

    @Test
    void sendBuildsASafeRfc822Message() throws Exception {
        Map<String, Object> out = sender.call(cfg(), Map.of("to", "ann@x.com", "subject", "Re: Plan", "text", "Héllo\nworld",
                "threadId", "th9", "inReplyTo", "<m1@mail.example>"));
        assertEquals("sent1", out.get("messageId"));
        assertEquals("th9", out.get("threadId"));
        assertTrue(seen.get(0).startsWith("POST /gmail/v1/users/me/messages/send"));
        String json = sentRaw;
        assertTrue(json.contains("\"threadId\":\"th9\""));
        String raw = json.replaceAll(".*\"raw\":\"([^\"]+)\".*", "$1");
        String mime = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.US_ASCII);
        assertTrue(mime.startsWith("To: ann@x.com\r\nSubject: Re: Plan\r\n"), mime);
        assertTrue(mime.contains("In-Reply-To: <m1@mail.example>\r\n"));
        assertTrue(mime.contains("Content-Transfer-Encoding: base64"));
        String payload = mime.substring(mime.indexOf("\r\n\r\n") + 4).replace("\r\n", "");
        assertEquals("Héllo\nworld", new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    @Test
    void nonAsciiSubjectsAreEncodedAndInjectionIsRefused() throws Exception {
        sender.call(cfg(), Map.of("to", "ann@x.com", "subject", "Café", "text", "hi"));
        String raw = sentRaw.replaceAll(".*\"raw\":\"([^\"]+)\".*", "$1");
        String mime = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.US_ASCII);
        assertTrue(mime.contains("Subject: =?UTF-8?B?"), mime);

        seen.clear();
        for (Map<String, Object> in : List.of(
                Map.<String, Object>of("to", "ann@x.com\r\nBcc: spy@evil.com", "subject", "s", "text", "t"),
                Map.<String, Object>of("to", "ann@x.com", "subject", "s\r\nBcc: spy@evil.com", "text", "t"),
                Map.<String, Object>of("to", "ann@x.com", "subject", "s", "text", "t", "inReplyTo", "<a@b>\r\nBcc: x@y.zz"),
                Map.<String, Object>of("to", "a@x.com, b@x.com", "subject", "s", "text", "t"),
                Map.<String, Object>of("to", "ann@x.com", "subject", "s", "text", ""),
                Map.<String, Object>of("to", "ann@x.com", "subject", "s", "text", "t", "threadId", "../x"))) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg(), in), in.toString());
        }
        assertTrue(seen.isEmpty(), "nothing was sent");
    }

    @Test
    void allowedRecipientsRestrictsWhoCanBeEmailed() throws Exception {
        Map<String, Object> c = cfg("allowedRecipients", List.of("Ann@x.com"));
        sender.call(c, Map.of("to", "ann@x.com", "subject", "s", "text", "t"));
        var e = assertThrows(ServiceHandler.ConnectorException.class,
                () -> sender.call(c, Map.of("to", "evil@y.com", "subject", "s", "text", "t")));
        assertTrue(e.getMessage().contains("allowedRecipients"));
    }

    @Test
    void oauthHttpSurfacesProviderErrorsAndParsesTokens() throws Exception {
        server.createContext("/token", ex -> {
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean bad = form.contains("bad");
            String body = bad ? "{\"error\":\"invalid_grant\",\"error_description\":\"gone\"}" : "{\"access_token\":\"A\",\"expires_in\":5}";
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(bad ? 400 : 200, body.length());
            ex.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            ex.close();
        });
        var http = new OAuthHttp(new UrlGuard(true, Set.of()));
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        assertEquals("A", http.post(url, Map.of("refresh_token", "good")).get("access_token"));
        var e = assertThrows(OAuthTokenEndpoint.OAuthError.class, () -> http.post(url, Map.of("refresh_token", "bad")));
        assertEquals("invalid_grant", e.error());
    }

    @Test
    void searchAndReadAreReadOnlyAndBounded() throws Exception {
        listAnswer = "{\"messages\":[{\"id\":\"m1\"},{\"id\":\"../evil\"},{\"id\":\"m2\"}]}";
        messages.put("m1", msg("m1", 5000, "Big Boss <boss@x.com>", "Plan", PASS, "Hello plan"));
        messages.put("m2", msg("m2", 6000, "spam@y.com", "Win", null, "click here"));
        @SuppressWarnings("unchecked")
        var found = (java.util.List<Map<String, Object>>) sender.call(cfg("operation", "search"), Map.of("q", "from:boss", "max", 99)).get("messages");
        assertEquals(2, found.size(), "a malformed id is skipped, never fetched");
        assertEquals("Plan", found.get(0).get("subject"));
        assertEquals(true, found.get(0).get("authenticated"));
        assertEquals(false, found.get(1).get("authenticated"));
        assertFalse(found.get(0).containsKey("body"));
        assertTrue(seen.stream().anyMatch(x -> x.contains("maxResults=10") && x.contains("auth=Bearer ya29.token")), seen.toString());
        assertTrue(seen.stream().allMatch(x -> x.startsWith("GET ")), "search never writes");

        Map<String, Object> one = sender.call(cfg("operation", "read"), Map.of("messageId", "m1"));
        assertEquals("Hello plan", one.get("body"));
        messages.put("big", msg("big", 7000, "a@x.com", "Long", PASS, "x".repeat(20000)));
        assertEquals(8000, ((String) sender.call(cfg("operation", "read"), Map.of("messageId", "big")).get("body")).length());
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of(), Map.of("messageId", "../x"), Map.of("messageId", "a b"))) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg("operation", "read"), bad), bad.toString());
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg("operation", "search"), Map.of("q", "a\nb")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg("operation", "search"), Map.of("q", "x".repeat(201))));
    }
}
