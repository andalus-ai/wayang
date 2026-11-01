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

import tech.kayys.wayang.runtime.ServiceHandler;

/** GitHub, Notion, Teams, Google Calendar and Sheets against one fake HTTP server that records what it receives. */
class ApiConnectorsTest {

    record Req(String method, String uri, Map<String, String> headers, String body) {
    }

    private HttpServer server;
    private final List<Req> reqs = new CopyOnWriteArrayList<>();
    private volatile String answer = "{}";
    private volatile int status = 200;
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
            ex.getResponseHeaders().add("Content-Type", "application/json");
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

    private static final String TOKEN = "ghp_0123456789abcdefghij";

    // ---------------------------------------------------------------- GitHub

    private Map<String, Object> gh(String op, Map<String, Object> inputs, Object allowed) throws Exception {
        return new GitHub.Sender(local).call(m("token", TOKEN, "operation", op, "baseUrl", base(), "allowedRepos", allowed), inputs);
    }

    @Test
    void githubCreatesIssue() throws Exception {
        answer = "{\"number\":7,\"html_url\":\"https://github.com/o/r/issues/7\",\"title\":\"Bug\",\"state\":\"open\"}";
        Map<String, Object> out = gh("createIssue", m("repo", "o/r", "title", "Bug", "body", "details", "labels", List.of("bug")), List.of("o/r"));
        assertEquals(7, ((Number) out.get("number")).intValue());
        Req r = reqs.get(0);
        assertEquals("POST /repos/o/r/issues", r.method() + " " + r.uri());
        assertEquals("Bearer " + TOKEN, r.headers().get("authorization"));
        assertEquals("application/vnd.github+json", r.headers().get("accept"));
        assertTrue(r.body().contains("\"title\":\"Bug\"") && r.body().contains("\"labels\":[\"bug\"]"), r.body());
    }

    @Test
    void githubRepoAllowListAndPathTricks() throws Exception {
        for (String repo : List.of("other/repo", "o/r/../../x", "o/..", "../r", "o/r?x=1", "o r/x")) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> gh("getIssue", m("repo", repo, "number", 1), List.of("o/r", "o/*")), repo);
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> gh("getIssue", m("repo", "o/r", "number", 1), List.of()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> gh("getIssue", m("repo", "o/r", "number", 1), null));
        assertThrows(ServiceHandler.ConnectorException.class, () -> gh("getIssue", m("repo", "o/r", "number", "1/../2"), List.of("o/r")));
        assertEquals(0, reqs.size());
        answer = "{\"number\":1,\"title\":\"t\",\"state\":\"open\"}";
        gh("getIssue", m("repo", "o/another", "number", 1), List.of("o/*"));
        assertEquals("/repos/o/another/issues/1", reqs.get(0).uri());
    }

    @Test
    void githubListSkipsPullRequests() throws Exception {
        answer = "[{\"number\":1,\"title\":\"a\",\"state\":\"open\"},{\"number\":2,\"title\":\"pr\",\"state\":\"open\",\"pull_request\":{}}]";
        Map<String, Object> out = gh("listIssues", m("repo", "o/r", "labels", List.of("x"), "limit", 500), List.of("o/r"));
        assertEquals(1, out.get("count"));
        assertTrue(reqs.get(0).uri().contains("per_page=50") && reqs.get(0).uri().contains("labels=x"), reqs.get(0).uri());
        assertThrows(ServiceHandler.ConnectorException.class, () -> gh("listIssues", m("repo", "o/r", "state", "bogus"), List.of("o/r")));
    }

    @Test
    void githubErrorsDoNotLeakTheToken() {
        status = 401;
        answer = "{\"message\":\"Bad credentials\"}";
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> gh("getIssue", m("repo", "o/r", "number", 1), List.of("o/r")));
        assertFalse(e.retryable());
        assertFalse(e.getMessage().contains(TOKEN));
    }

    // ---------------------------------------------------------------- Notion

    private Map<String, Object> notion(String op, Map<String, Object> inputs, String key, Object allowed) throws Exception {
        return new Notion.Sender(local).call(m("token", "secret_0123456789abcdefghij", "operation", op, "baseUrl", base(), key, allowed), inputs);
    }

    private static final String DB = "12345678-1234-1234-1234-123456789abc";

    @Test
    void notionCreatesRowAndChunksText() throws Exception {
        answer = "{\"id\":\"p1\",\"url\":\"https://notion.so/p1\"}";
        String longPara = "x".repeat(4500);
        Map<String, Object> out = notion("createPage", m("databaseId", DB.replace("-", "").toUpperCase(), "title", "Hello",
                "text", "first\n\n" + longPara, "properties", m("Status", m("select", m("name", "Todo")), "Name", "evil override")),
                "allowedDatabases", List.of(DB));
        assertEquals("p1", out.get("pageId"));
        Req r = reqs.get(0);
        assertEquals("POST /v1/pages", r.method() + " " + r.uri());
        assertEquals("2022-06-28", r.headers().get("notion-version"));
        assertTrue(r.body().contains("\"database_id\":\"12345678123412341234123456789abc\""), r.body());
        assertTrue(r.body().contains("\"Status\""));
        assertFalse(r.body().contains("evil override"), "the title cannot be replaced through properties");
        assertEquals(5, r.body().split("\"type\":\"paragraph\"", -1).length, "4 blocks: 'first' plus 4500 chars in chunks of 2000, 2000, 500");
    }

    @Test
    void notionAllowListsAndIds() throws Exception {
        assertThrows(ServiceHandler.ConnectorException.class, () -> notion("createPage", m("databaseId", DB, "title", "t"), "allowedDatabases", List.of()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> notion("createPage", m("databaseId", DB, "title", "t"), "x", "y"));
        assertThrows(ServiceHandler.ConnectorException.class, () -> notion("queryDatabase", m("databaseId", "../v1/users"), "allowedDatabases", List.of(DB)));
        assertThrows(ServiceHandler.ConnectorException.class, () -> notion("appendText", m("pageId", DB, "text", "t"), "allowedPages", List.of()));
        assertEquals(0, reqs.size());
    }

    @Test
    void notionQueryAndAppend() throws Exception {
        answer = "{\"results\":[{\"id\":\"a\",\"url\":\"u\",\"properties\":{\"Name\":{\"type\":\"title\",\"title\":[{\"plain_text\":\"Row \"},{\"plain_text\":\"one\"}]}}}],\"has_more\":true}";
        Map<String, Object> out = notion("queryDatabase", m("databaseId", DB, "limit", 5, "filter", m("property", "Status")), "allowedDatabases", List.of(DB));
        assertEquals(1, out.get("count"));
        assertEquals(true, out.get("hasMore"));
        assertEquals("Row one", ((Map<?, ?>) ((List<?>) out.get("results")).get(0)).get("title"));
        assertTrue(reqs.get(0).body().contains("\"page_size\":5"));
        answer = "{\"results\":[{},{}]}";
        assertEquals(2, notion("appendText", m("pageId", DB, "text", "a\n\nb"), "allowedPages", List.of(DB)).get("appended"));
        assertEquals("PATCH /v1/blocks/12345678123412341234123456789abc/children", reqs.get(1).method() + " " + reqs.get(1).uri());
    }

    // ---------------------------------------------------------------- Teams

    @Test
    void teamsSendsAdaptiveCardAndChecksHost() throws Exception {
        answer = "";
        status = 202;
        Map<String, Object> out = new Teams.Sender(local, true).call(m("webhookUrl", base() + "/workflows/abc"), m("text", "Deploy done", "title", "CI"));
        assertEquals(true, out.get("ok"));
        String b = reqs.get(0).body();
        assertTrue(b.contains("application/vnd.microsoft.card.adaptive") && b.contains("Deploy done") && b.contains("\"CI\""), b);
        var s = new Teams.Sender(local);
        for (String bad : List.of(base() + "/x", "https://evil.example/x", "https://logic.azure.com.evil.example/x", "https://u@logic.azure.com/x", "http://logic.azure.com/x")) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(m("webhookUrl", bad), m("text", "x")), bad);
        }
        assertEquals(1, reqs.size());
        assertThrows(ServiceHandler.ConnectorException.class, () -> new Teams.Sender(local, true).call(m("webhookUrl", base()), m("text", "x".repeat(10_001))));
    }

    // ---------------------------------------------------------------- Calendar

    private Map<String, Object> cal(String op, Map<String, Object> inputs, Object... cfg) throws Exception {
        Map<String, Object> c = m("accessToken", "ya29.token-token", "operation", op, "baseUrl", base());
        for (int i = 0; i < cfg.length; i += 2) {
            c.put((String) cfg[i], cfg[i + 1]);
        }
        return new GoogleApis.Calendar(local).call(c, inputs);
    }

    @Test
    void calendarCreateDoesNotNotifyByDefault() throws Exception {
        answer = "{\"id\":\"e1\",\"summary\":\"Lunch\",\"start\":{\"dateTime\":\"2026-10-26T12:00:00+07:00\"},\"end\":{\"dateTime\":\"2026-10-26T13:00:00+07:00\"},\"htmlLink\":\"u\"}";
        Map<String, Object> in = m("summary", "Lunch", "start", "2026-10-26T12:00:00+07:00", "end", "2026-10-26T13:00:00+07:00",
                "attendees", List.of("Bob <bob@example.org>"), "timeZone", "Asia/Jakarta");
        assertEquals("e1", cal("create", in).get("id"));
        Req r = reqs.get(0);
        assertEquals("POST", r.method());
        assertTrue(r.uri().startsWith("/calendars/primary/events?") && r.uri().contains("sendUpdates=none"), r.uri());
        assertTrue(r.body().contains("\"email\":\"bob@example.org\"") && r.body().contains("Asia/Jakarta"), r.body());
        cal("create", in, "notifyAttendees", true);
        assertTrue(reqs.get(1).uri().contains("sendUpdates=all"));
    }

    @Test
    void calendarValidation() throws Exception {
        Map<String, Object> ok = m("summary", "x", "start", "2026-10-26", "end", "2026-10-27");
        for (Map<String, Object> bad : List.of(
                m("summary", "x", "start", "tomorrow", "end", "2026-10-27"),
                m("summary", "x", "start", "2026-10-26T12:00:00", "end", "2026-10-27"),
                m("summary", "x", "start", "2026-10-26", "end", "2026-10-27", "attendees", List.of("a@example.org\r\nBcc: x@y.zz")),
                m("summary", "x", "start", "2026-10-26", "end", "2026-10-27", "attendees", List.of("victim@example.org\r\n<evil@example.net>")),
                m("summary", "x", "start", "2026-10-26", "end", "2026-10-27", "calendarId", "primary/../x"),
                m("summary", "x", "start", "2026-10-26", "end", "2026-10-27", "timeZone", "../x"),
                m("start", "2026-10-26", "end", "2026-10-27"))) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> cal("create", bad), String.valueOf(bad));
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> cal("create", m("summary", "x", "start", "2026-10-26", "end", "2026-10-27", "calendarId", "work@group.calendar.google.com"),
                "allowedCalendars", List.of("primary")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> cal("delete", m("eventId", "../../acl")));
        assertEquals(0, reqs.size());
        answer = "{}";
        cal("create", ok);
        cal("create", m("summary", "x", "start", "2026-10-26", "end", "2026-10-27", "calendarId", "me@example.org"));
        assertTrue(reqs.get(1).uri().startsWith("/calendars/me%40example.org/events"), reqs.get(1).uri());
    }

    @Test
    void calendarListAndDelete() throws Exception {
        answer = "{\"items\":[{\"id\":\"a\",\"summary\":\"One\",\"start\":{\"date\":\"2026-10-26\"},\"end\":{\"date\":\"2026-10-27\"}}]}";
        Map<String, Object> out = cal("list", m("timeMin", "2026-10-25T00:00:00+07:00", "limit", 3, "q", "one"));
        assertEquals(1, out.get("count"));
        assertEquals("2026-10-26", ((Map<?, ?>) ((List<?>) out.get("events")).get(0)).get("start"));
        assertTrue(reqs.get(0).uri().contains("timeMin=2026-10-24T17%3A00%3A00Z") && reqs.get(0).uri().contains("maxResults=3"), reqs.get(0).uri());
        status = 204;
        answer = "";
        assertEquals(true, cal("delete", m("eventId", "abc123def456")).get("deleted"));
        assertEquals("DELETE", reqs.get(1).method());
    }

    // ---------------------------------------------------------------- Sheets

    private static final String SHEET = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";

    private Map<String, Object> sheets(String op, Map<String, Object> inputs, Object allowed) throws Exception {
        return new GoogleApis.Sheets(local).call(m("accessToken", "ya29.token-token", "operation", op, "baseUrl", base(), "allowedSpreadsheets", allowed), inputs);
    }

    @Test
    void sheetsAppendIsRawAndAllowListed() throws Exception {
        answer = "{\"updates\":{\"updatedRange\":\"Sheet1!A5:B5\"}}";
        Map<String, Object> out = sheets("append", m("spreadsheetId", SHEET, "range", "Sheet1!A:B", "values", List.of(List.of("=HYPERLINK(\"x\")", 5, true, m("a", 1)))), List.of(SHEET));
        assertEquals(1, out.get("rows"));
        assertEquals("Sheet1!A5:B5", out.get("updatedRange"));
        Req r = reqs.get(0);
        assertTrue(r.uri().startsWith("/spreadsheets/" + SHEET + "/values/Sheet1%21A%3AB:append?"), r.uri());
        assertTrue(r.uri().contains("valueInputOption=RAW"), r.uri());
        assertTrue(r.body().contains("=HYPERLINK") && !r.body().contains("{\"a\":1}"), r.body());
        assertThrows(ServiceHandler.ConnectorException.class, () -> sheets("append", m("spreadsheetId", SHEET, "range", "A1", "values", List.of(List.of("x"))), List.of()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> sheets("append", m("spreadsheetId", SHEET, "range", "A1", "values", List.of(List.of("x"))), null));
        assertThrows(ServiceHandler.ConnectorException.class, () -> sheets("read", m("spreadsheetId", SHEET, "range", "A1/../../x"), List.of(SHEET)));
        assertEquals(1, reqs.size());
    }

    @Test
    void sheetsCellCapAndRead() throws Exception {
        List<Object> row = new java.util.ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            row.add(i);
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> sheets("append", m("spreadsheetId", SHEET, "range", "A1", "values", List.of(row)), List.of(SHEET)));
        assertThrows(ServiceHandler.ConnectorException.class, () -> sheets("append", m("spreadsheetId", SHEET, "range", "A1", "values", List.of("not a row")), List.of(SHEET)));
        answer = "{\"range\":\"Sheet1!A1:B2\",\"values\":[[\"a\",\"b\"],[\"c\"]]}";
        Map<String, Object> out = sheets("read", m("spreadsheetId", SHEET, "range", "Sheet1!A1:B2"), List.of(SHEET));
        assertEquals(2, out.get("rows"));
    }
}
