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
class TaskConnectorsTest {

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
    private static final String TEAM = "11111111-2222-3333-4444-555555555555";
    private static final String LIST = "0123456789abcdef01234567";

    private Map<String, Object> td(String op, Map<String, Object> in, Object allowed) throws Exception {
        return new Todoist.Sender(local).call(m("token", KEY, "operation", op, "allowedProjects", allowed, "baseUrl", base()), in);
    }

    @Test
    void todoistCreatesListsAndCloses() throws Exception {
        answer = "{\"id\":\"99\",\"url\":\"u\"}";
        assertEquals("99", td("createTask", m("projectId", "P1", "content", "Buy", "priority", 4, "dueString", "tomorrow"), List.of("P1")).get("id"));
        assertEquals("POST /tasks", reqs.get(0).method() + " " + reqs.get(0).uri());
        assertTrue(reqs.get(0).body().contains("\"project_id\":\"P1\"") && reqs.get(0).body().contains("\"priority\":4"), reqs.get(0).body());
        assertEquals("Bearer " + KEY, reqs.get(0).headers().get("authorization"));
        answer = "[{\"id\":\"1\",\"content\":\"a\",\"priority\":1,\"due\":{\"string\":\"today\"}},{\"id\":\"2\",\"content\":\"b\"}]";
        assertEquals(1, td("listTasks", m("projectId", "P1", "limit", 1), List.of("P1")).get("count"));
        assertEquals("/tasks?project_id=P1", reqs.get(1).uri());
        answer = "{\"id\":\"5\",\"project_id\":\"P1\"}";
        assertEquals(true, td("closeTask", m("taskId", "5"), List.of("P1")).get("closed"));
        assertEquals("POST /tasks/5/close", reqs.get(3).method() + " " + reqs.get(3).uri());
    }

    @Test
    void todoistEnforcesProjectsOnEveryOperation() throws Exception {
        assertThrows(ServiceHandler.ConnectorException.class, () -> td("createTask", m("projectId", "P2", "content", "x"), List.of("P1")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> td("createTask", m("projectId", "P1", "content", "x"), List.of()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> td("createTask", m("projectId", "P1", "content", "x", "priority", 9), List.of("P1")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> td("closeTask", m("taskId", "5/../6"), List.of("P1")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> td("listTasks", m("projectId", "P1&x=1"), List.of("P1&x=1")));
        assertEquals(0, reqs.size());
        answer = "{\"id\":\"5\",\"project_id\":\"OTHER\"}";
        assertThrows(ServiceHandler.ConnectorException.class, () -> td("closeTask", m("taskId", "5"), List.of("P1")));
        assertEquals(1, reqs.size(), "looked up the task, never closed it");
    }

    private Map<String, Object> ln(String op, Map<String, Object> in, Object allowed) throws Exception {
        return new Linear.Sender(local).call(m("token", KEY, "operation", op, "allowedTeams", allowed, "baseUrl", base()), in);
    }

    @Test
    void linearSendsVariablesNotSplicedText() throws Exception {
        answer = "{\"data\":{\"issueCreate\":{\"success\":true,\"issue\":{\"id\":\"i1\",\"identifier\":\"ENG-1\",\"url\":\"u\"}}}}";
        String evil = "x\") { id } } mutation { issueDelete(id: \"1\"";
        assertEquals("ENG-1", ln("createIssue", m("teamId", TEAM, "title", evil, "priority", 2), List.of(TEAM)).get("identifier"));
        Req r = reqs.get(0);
        assertEquals("POST /graphql", r.method() + " " + r.uri());
        assertEquals(KEY, r.headers().get("authorization"));
        var q = java.util.regex.Pattern.compile("\"query\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(r.body());
        assertTrue(q.find(), r.body());
        assertFalse(q.group(1).contains("issueDelete"), q.group(1));
        assertTrue(r.body().contains("\"variables\""), r.body());
    }

    @Test
    void linearCommentChecksTheIssuesTeamAndTeamAllowList() throws Exception {
        answer = "{\"data\":{\"issue\":{\"team\":{\"id\":\"other\"}}}}";
        assertThrows(ServiceHandler.ConnectorException.class, () -> ln("comment", m("issueId", TEAM, "body", "hi"), List.of(TEAM)));
        assertEquals(1, reqs.size(), "only the lookup was sent");
        answer = "{\"data\":{\"issue\":{\"team\":{\"id\":\"" + TEAM + "\"}},\"commentCreate\":{\"success\":true}}}";
        assertEquals(true, ln("comment", m("issueId", TEAM, "body", "hi"), List.of(TEAM)).get("commented"));
        assertThrows(ServiceHandler.ConnectorException.class, () -> ln("createIssue", m("teamId", "22222222-2222-3333-4444-555555555555", "title", "t"), List.of(TEAM)));
        assertThrows(ServiceHandler.ConnectorException.class, () -> ln("listIssues", m("teamId", "bad"), List.of("bad")));
        answer = "{\"errors\":[{\"message\":\"secret detail\"}]}";
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> ln("createIssue", m("teamId", TEAM, "title", "t"), List.of(TEAM)));
        assertFalse(e.getMessage().contains("secret detail"));
    }

    private Map<String, Object> tr(String op, Map<String, Object> in, Object allowed) throws Exception {
        return new Trello.Sender(local).call(m("apiKey", "key12345", "token", KEY, "operation", op, "allowedLists", allowed, "baseUrl", base()), in);
    }

    @Test
    void trelloUsesHeaderCredentialsAndListAllowList() throws Exception {
        answer = "{\"id\":\"c1\",\"shortUrl\":\"s\"}";
        assertEquals("c1", tr("createCard", m("listId", LIST, "name", "N", "desc", "D"), List.of(LIST)).get("id"));
        Req r = reqs.get(0);
        assertEquals("POST /cards", r.method() + " " + r.uri());
        assertEquals("OAuth oauth_consumer_key=\"key12345\", oauth_token=\"" + KEY + "\"", r.headers().get("authorization"));
        answer = "[{\"id\":\"a\"},{\"id\":\"b\"}]";
        assertEquals(1, tr("listCards", m("listId", LIST, "limit", 1), List.of(LIST)).get("count"));
        assertTrue(reqs.get(1).uri().startsWith("/lists/" + LIST + "/cards"), reqs.get(1).uri());
        assertFalse(reqs.get(1).uri().contains(KEY));
        for (String id : List.of("0123456789abcdef01234568", LIST + "/../x", "xyz")) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> tr("listCards", m("listId", id), List.of(LIST)), id);
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> tr("listCards", m("listId", LIST), List.of()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> tr("listCards", m("listId", LIST + "/../x"), List.of(LIST + "/../x")));
        assertEquals(2, reqs.size());
    }
}
