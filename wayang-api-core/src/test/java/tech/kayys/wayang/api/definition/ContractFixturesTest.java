package tech.kayys.wayang.api.definition;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.run.EventBus;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.run.ReferenceRunEngine;
import tech.kayys.wayang.api.Role;
import tech.kayys.wayang.api.run.RunEngine;
import tech.kayys.wayang.api.run.RunStatus;
import tech.kayys.wayang.api.WayangApi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.WaitRegistry;
import tech.kayys.wayang.runtime.json.Json;

/** The documents the Flutter Studio produces must be accepted and executed by the server. */
class ContractFixturesTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final DefinitionStore store = new DefinitionStore.InMemory();
    private final WaitRegistry waits = new WaitRegistry.InMemory();
    private final EventBus bus = new EventBus();
    private final RunEngine engine = new ReferenceRunEngine(store, waits, Map.of(), (spec, in) -> Map.of(), bus, clock);
    private final WayangApi api = new WayangApi(store, engine, waits, bus, clock);
    private final Principal designer = new Principal("acme", "dana", Set.of(Role.DESIGNER));

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fixture(String name) throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("docs/contract"))) {
            dir = dir.getParent();
        }
        assertNotNull(dir, "docs/contract not found above " + Path.of("").toAbsolutePath());
        return (Map<String, Object>) Json.read(Files.readString(dir.resolve("docs/contract").resolve(name)));
    }

    @Test
    void decisionFixtureValidatesPublishesAndEvaluates() throws Exception {
        var doc = fixture("decision-risk.json");
        assertTrue(api.validate(designer, DefinitionKind.DECISION, doc).valid(), api.validate(designer, DefinitionKind.DECISION, doc).toString());
        api.saveDraft(designer, DefinitionKind.DECISION, "risk-rules", doc, null);
        assertEquals(1, api.publish(designer, DefinitionKind.DECISION, "risk-rules").version());
        assertEquals("high", api.testDecision(designer, doc, Map.of("amount", 9000)).get("risk"));
        assertEquals("low", api.testDecision(designer, doc, Map.of("amount", 10)).get("risk"));
    }

    @Test
    void inputEntrySyntaxUsedByTheStudioEditorIsUnderstoodByTheEngine() throws Exception {
        var doc = fixture("decision-tier.json");
        assertTrue(api.validate(designer, DefinitionKind.DECISION, doc).valid());
        // gold + 250 -> 10 (tier) + 5 (range 100..500) = 15
        assertEquals(15, ((Number) api.testDecision(designer, doc, Map.of("tier", "gold", "amount", 250)).get("discount")).intValue());
        assertEquals(0, ((Number) api.testDecision(designer, doc, Map.of("tier", "basic", "amount", 50)).get("discount")).intValue());
        assertEquals(17, ((Number) api.testDecision(designer, doc, Map.of("tier", "platinum", "amount", 900)).get("discount")).intValue());
    }

    @Test
    void processFixtureValidatesPublishesAndRuns() throws Exception {
        var decision = fixture("decision-risk.json");
        api.saveDraft(designer, DefinitionKind.DECISION, "risk-rules", decision, null);
        api.publish(designer, DefinitionKind.DECISION, "risk-rules");

        var doc = fixture("process-loan.json");
        var report = api.validate(designer, DefinitionKind.PROCESS, doc);
        assertTrue(report.valid(), report.toString());
        api.saveDraft(designer, DefinitionKind.PROCESS, "loan-approval", doc, null);
        assertEquals(1, api.publish(designer, DefinitionKind.PROCESS, "loan-approval").version());

        // the layout and lane metadata survive the round trip
        var stored = api.getDraft(designer, DefinitionKind.PROCESS, "loan-approval").document();
        var pos = (Map<?, ?>) ((Map<?, ?>) Studio.of(stored).get("layout")).get("review");
        assertEquals(600.0, ((Number) pos.get("x")).doubleValue());
        assertEquals(60.0, ((Number) pos.get("y")).doubleValue());
        assertTrue(((Map<?, ?>) Studio.of(stored).get("meta")).containsKey("lanes"));

        // high risk -> the human review task is created and the run waits
        var run = api.startRun(designer, "loan-approval", null, Map.of("amount", 9000));
        assertEquals(RunStatus.WAITING, run.status(), run.toString());
        assertEquals(1, api.listTasks(designer, 10).size());
        assertEquals("underwriter", api.listTasks(designer, 10).get(0).assignee());

        // Known limitation: nested config (the http node's headers) cannot be written as BPMN properties, and the
        // exporter says so instead of dropping data silently. The Studio shows this message on Export.
        var e = assertThrows(ApiException.class, () -> api.exportBpmn(designer, "loan-approval", null));
        assertEquals(ApiException.Code.VALIDATION_FAILED, e.code());
        assertTrue(e.getMessage().contains("nested"), e.getMessage());
    }

    @Test
    void studioMetaIsBounded() throws Exception {
        var doc = new java.util.LinkedHashMap<>(fixture("process-loan.json"));
        Map<String, Object> deep = new java.util.LinkedHashMap<>();
        Map<String, Object> cur = deep;
        for (int i = 0; i < 12; i++) {
            Map<String, Object> next = new java.util.LinkedHashMap<>();
            cur.put("n", next);
            cur = next;
        }
        doc.put("studio", Map.of("meta", deep));
        assertThrows(ApiException.class, () -> api.saveDraft(designer, DefinitionKind.PROCESS, "x", doc, null));
        doc.put("studio", Map.of("meta", Map.of("s", "x".repeat(2001))));
        assertThrows(ApiException.class, () -> api.saveDraft(designer, DefinitionKind.PROCESS, "x", doc, null));
        doc.put("studio", Map.of("bogus", 1));
        assertThrows(ApiException.class, () -> api.saveDraft(designer, DefinitionKind.PROCESS, "x", doc, null));
    }
}
