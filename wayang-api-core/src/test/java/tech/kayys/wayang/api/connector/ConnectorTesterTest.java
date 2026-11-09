package tech.kayys.wayang.api.connector;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Permission;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.Role;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.ServiceHandler;

class ConnectorTesterTest {

    private final List<Map<String, Object>> configs = new ArrayList<>();
    private final List<Map<String, Object>> inputs = new ArrayList<>();
    @FunctionalInterface
    interface Behaviour {
        Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) throws Exception;
    }

    private Behaviour behaviour = (c, in) -> Map.of("ok", true);
    private final Principal designer = new Principal("acme", "dana", Set.of(Role.DESIGNER));
    private final Principal viewer = new Principal("acme", "vic", Set.of(Role.VIEWER));

    private ServiceHandler.Connector recorder() {
        return new ServiceHandler.Connector() {
            @Override
            public Map<String, Object> call(Map<String, Object> in) throws Exception {
                return call(Map.of(), in);
            }

            @Override
            public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) throws Exception {
                configs.add(config);
                inputs.add(in);
                return behaviour.call(config, in);
            }
        };
    }

    private ConnectorTester tester() {
        var c = recorder();
        return new ConnectorTester(Map.of("github", c, "slack", c, "http", c, "soap", c),
                (tenant, name) -> tenant.equals("acme") && name.equals("GH_TOKEN") ? "ghp_supersecretvalue" : null);
    }

    private static Map<String, Object> req(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void aReadRunsWithSecretsResolvedInConfigOnly() {
        var out = tester().test(designer, req("connector", "github", "confirm", true,
                "config", req("operation", "getIssue", "token", "${secret:GH_TOKEN}", "allowedRepos", List.of("o/r")),
                "inputs", req("repo", "o/r", "number", 1, "note", "${secret:GH_TOKEN}")));
        assertEquals(Map.of("ok", true), out.get("output"));
        assertEquals("ghp_supersecretvalue", configs.get(0).get("token"));
        assertEquals("${secret:GH_TOKEN}", inputs.get(0).get("note"), "secrets are not resolved in inputs");
    }

    @Test
    void anythingThatCouldSendOrChangeIsRefusedBeforeAnyCall() {
        for (Map<String, Object> r : List.of(
                req("connector", "github", "confirm", true, "config", req("operation", "createIssue")),
                req("connector", "github", "confirm", true, "config", req("operation", "comment")),
                req("connector", "slack", "confirm", true, "config", req("operation", "send")),
                req("connector", "http", "confirm", true, "config", req("url", "https://x", "method", "POST")),
                req("connector", "http", "confirm", true, "config", req("url", "https://x", "method", "DELETE")),
                req("connector", "soap", "confirm", true, "config", req("operation", "Delete", "wsdl", "w")),
                req("connector", "github", "confirm", true, "config", req()))) {
            assertThrows(ApiException.class, () -> tester().test(designer, r), String.valueOf(r));
        }
        assertEquals(0, configs.size());
        tester().test(designer, req("connector", "http", "confirm", true, "config", req("url", "https://x")));
        tester().test(designer, req("connector", "soap", "confirm", true, "config", req("mode", "describe", "wsdl", "w")));
        assertEquals(2, configs.size());
    }

    @Test
    void confirmIsRequiredAndPermissionsAreChecked() {
        Map<String, Object> ok = req("connector", "github", "config", req("operation", "getIssue"));
        assertThrows(ApiException.class, () -> tester().test(designer, ok));
        ok.put("confirm", "yes");
        assertThrows(ApiException.class, () -> tester().test(designer, ok));
        ok.put("confirm", true);
        assertThrows(ApiException.class, () -> tester().test(viewer, ok));
        assertThrows(ApiException.class, () -> tester().test(null, ok));
        assertThrows(ApiException.class, () -> tester().test(designer, req("connector", "nope", "confirm", true, "config", req())));
        assertEquals(0, configs.size());
    }

    @Test
    void bothDefinitionWriteAndRunStartAreNeeded() {
        Map<String, Object> ok = req("connector", "github", "confirm", true, "config", req("operation", "getIssue"));
        Principal onlyWrite = new Principal("acme", "w", Set.of(), Set.of(Permission.DEFINITION_WRITE));
        Principal onlyRun = new Principal("acme", "r", Set.of(), Set.of(Permission.RUN_START));
        Principal both = new Principal("acme", "b", Set.of(), Set.of(Permission.DEFINITION_WRITE, Permission.RUN_START));
        assertThrows(ApiException.class, () -> tester().test(onlyWrite, ok));
        assertThrows(ApiException.class, () -> tester().test(onlyRun, ok));
        assertEquals(0, configs.size());
        tester().test(both, ok);
        assertEquals(1, configs.size());
    }

    @Test
    void secretsOfAnotherTenantOrMissingOnesAreNotResolved() {
        Principal other = new Principal("globex", "gus", Set.of(Role.DESIGNER));
        var e = assertThrows(ApiException.class, () -> tester().test(other, req("connector", "github", "confirm", true,
                "config", req("operation", "getIssue", "token", "${secret:GH_TOKEN}"))));
        assertTrue(e.getMessage().contains("not set"), e.getMessage());
        assertEquals(0, configs.size());
    }

    @Test
    void secretValuesNeverComeBackInErrorsOrOutput() {
        behaviour = (c, in) -> {
            throw new ServiceHandler.ConnectorException("HTTP 401: bad token ghp_supersecretvalue", false);
        };
        var e = assertThrows(ApiException.class, () -> tester().test(designer, req("connector", "github", "confirm", true,
                "config", req("operation", "getIssue", "token", "${secret:GH_TOKEN}"))));
        assertFalse(e.getMessage().contains("ghp_supersecretvalue"), e.getMessage());
        assertTrue(e.getMessage().contains("***"), e.getMessage());
        behaviour = (c, in) -> Map.of("echo", List.of(Map.of("t", "x ghp_supersecretvalue y")));
        var out = tester().test(designer, req("connector", "github", "confirm", true,
                "config", req("operation", "getIssue", "token", "${secret:GH_TOKEN}")));
        assertFalse(String.valueOf(out).contains("ghp_supersecretvalue"), String.valueOf(out));
        assertTrue(String.valueOf(out).contains("x *** y"), String.valueOf(out));
    }

    @Test
    void hugeAnswersAreRefusedAndUnexpectedFailuresAreSanitised() {
        behaviour = (c, in) -> Map.of("blob", "x".repeat(ConnectorTester.MAX_OUTPUT_CHARS + 10));
        assertThrows(ApiException.class, () -> tester().test(designer, req("connector", "github", "confirm", true, "config", req("operation", "listIssues"))));
        behaviour = (c, in) -> {
            throw new IllegalStateException("internal path /etc/secret");
        };
        var e = assertThrows(ApiException.class, () -> tester().test(designer, req("connector", "github", "confirm", true, "config", req("operation", "listIssues"))));
        assertFalse(e.getMessage().contains("/etc/secret"), e.getMessage());
    }

    @Test
    void theStudioOnlyOffersWhatTheServerAccepts() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
        while (dir != null && !java.nio.file.Files.exists(dir.resolve("studio/lib/data/node_test_model.dart"))) {
            dir = dir.getParent();
        }
        assertNotNull(dir, "studio sources not found");
        String dart = java.nio.file.Files.readString(dir.resolve("studio/lib/data/node_test_model.dart"));
        var m = java.util.regex.Pattern.compile("case '([a-z]+)':\\s*return ([^;]+);").matcher(dart);
        int offered = 0;
        while (m.find()) {
            String connector = m.group(1);
            var lits = java.util.regex.Pattern.compile("'([A-Za-z]+)'").matcher(m.group(2));
            while (lits.find()) {
                String lit = lits.group(1);
                if (connector.equals("http")) {
                    if (lit.equals("GET")) {
                        assertNull(ConnectorTester.refusal("http", Map.of("method", lit)), "http " + lit);
                        offered++;
                    }
                    continue;
                } else if (connector.equals("soap")) {
                    if (lit.equals("describe")) {
                        assertNull(ConnectorTester.refusal("soap", Map.of("mode", lit)), "soap " + lit);
                        offered++;
                    }
                    continue;
                } else {
                    assertNull(ConnectorTester.refusal(connector, Map.of("operation", lit)), connector + " " + lit);
                }
                offered++;
            }
        }
        assertTrue(offered >= 11, "parsed the Dart cases: " + offered);
    }
}
