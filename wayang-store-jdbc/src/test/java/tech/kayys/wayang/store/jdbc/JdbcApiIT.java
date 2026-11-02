package tech.kayys.wayang.store.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.api.definition.DefinitionKind;
import tech.kayys.wayang.api.run.EventBus;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.run.ReferenceRunEngine;
import tech.kayys.wayang.api.Role;
import tech.kayys.wayang.api.run.RunStatus;
import tech.kayys.wayang.api.run.RunView;
import tech.kayys.wayang.api.WayangApi;

/**
 * The API on durable storage: definitions in {@link JdbcDefinitionStore}, task tickets in
 * {@link JdbcWaitRegistry}. Skipped unless {@code WAYANG_TEST_JDBC_URL} is set.
 */
class JdbcApiIT {

    @Test
    void lifecycleOnDatabaseStorage() throws Exception {
        String url = System.getenv("WAYANG_TEST_JDBC_URL");
        if (url == null || url.isBlank()) {
            return;
        }
        var ds = new JdbcWaitRegistryIT.DriverManagerDataSource(url);
        JdbcSchema.apply(ds);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM wayang_definition_draft");
            st.execute("DELETE FROM wayang_definition_version");
            st.execute("DELETE FROM wayang_wait");
            st.execute("DELETE FROM wayang_project");
            st.execute("DELETE FROM wayang_buffered_message");
        }
        Principal acme = new Principal("acme", "ann", Set.of(Role.ADMIN));
        Principal globex = new Principal("globex", "gus", Set.of(Role.ADMIN));

        var store = new JdbcDefinitionStore(ds);
        var waits = new JdbcWaitRegistry(ds);
        var bus = new EventBus();
        var engine = new ReferenceRunEngine(store, waits, Map.of(), (s, i) -> Map.of(), bus, Clock.systemUTC());
        var api = new WayangApi(store, new JdbcProjectStore(ds), engine, waits, bus, Clock.systemUTC());

        var project = api.createProject(acme, "Lending", null);
        api.saveDraft(acme, DefinitionKind.DECISION, "risk", Map.of("id", "risk", "studio", Map.of("projectId", project.id(),
                "layout", Map.of("rule", Map.of("x", 1.5, "y", 2))), "decisions", List.of(Map.of("name", "level",
                "type", "table", "hitPolicy", "FIRST", "inputs", List.of("amount"), "outputs", List.of("level"),
                "rules", List.of(Map.of("when", List.of(">= 1000"), "then", List.of("\"high\"")),
                        Map.of("when", List.of("-"), "then", List.of("\"low\"")))))), null);
        api.publish(acme, DefinitionKind.DECISION, "risk");
        api.saveDraft(acme, DefinitionKind.PROCESS, "loan", Map.of("id", "loan", "elements", List.of(
                Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "rule", "type", "BUSINESS_RULE_TASK", "config", Map.of("model", "risk")),
                Map.of("id", "ok", "type", "USER_TASK", "config", Map.of("title", "Approve")),
                Map.of("id", "e", "type", "END_EVENT")), "flows", List.of(
                        Map.of("id", "a", "source", "s", "target", "rule"), Map.of("id", "b", "source", "rule", "target", "ok"),
                        Map.of("id", "c", "source", "ok", "target", "e"))), null);
        api.publish(acme, DefinitionKind.PROCESS, "loan");

        RunView run = api.startRun(acme, "loan", null, Map.of("amount", 5000));
        assertEquals(RunStatus.WAITING, run.status());
        assertEquals("high", run.variables().get("rule.level"));

        // a second API instance on the same database sees the definitions and the open task (a restart in effect)
        var api2 = new WayangApi(new JdbcDefinitionStore(ds), new JdbcProjectStore(ds), engine, new JdbcWaitRegistry(ds), bus, Clock.systemUTC());
        assertEquals(1, api2.list(acme, DefinitionKind.PROCESS).size());
        assertEquals(1, api2.list(acme, DefinitionKind.DECISION, project.id()).size());
        assertEquals("Lending", new JdbcProjectStore(ds).get("acme", project.id()).orElseThrow().name());
        assertEquals(409, assertThrows(tech.kayys.wayang.api.ApiException.class, () -> api2.deleteProject(acme, project.id())).code().httpStatus());
        var tasks = api2.listTasks(acme, 10);
        assertEquals(1, tasks.size());
        assertEquals("Approve", tasks.get(0).title());
        assertTrue(api2.listTasks(globex, 10).isEmpty());
        assertTrue(api2.list(globex, DefinitionKind.PROCESS).isEmpty());

        api2.completeTask(acme, tasks.get(0).token(), Map.of("approved", true));
        assertEquals(RunStatus.COMPLETED, api2.getRun(acme, run.id()).status());
        assertTrue(api2.listTasks(acme, 10).isEmpty());
    }
}
