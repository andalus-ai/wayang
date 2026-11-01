package tech.kayys.wayang.examples;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.runtime.ReferenceEngine;
import tech.kayys.wayang.runtime.WaitRegistry;

class IssueTriageTest {

    private final List<Map<String, Object>> tasks = new ArrayList<>();
    private final List<Map<String, Object>> comments = new ArrayList<>();

    private ReferenceEngine.Run run(Map<String, Object> event) {
        var waits = new WaitRegistry.InMemory();
        var engine = new ReferenceEngine(IssueTriage.runner(waits, tasks::add, comments::add), waits);
        return engine.start(ProcessCompiler.compile(IssueTriage.process()), Map.of("event", event));
    }

    @Test
    void aBugBecomesATaskAndGetsAComment() {
        var r = run(Map.of("repo", "me/app", "number", 7, "title", "App crashes on start", "url", "https://github.com/me/app/issues/7"));
        assertEquals(ReferenceEngine.Status.COMPLETED, r.status());
        assertEquals(1, tasks.size());
        assertEquals("App crashes on start", tasks.get(0).get("content"));
        assertEquals("P1", tasks.get(0).get("projectId"));
        assertEquals(1, comments.size());
        assertEquals("me/app", comments.get(0).get("repo"));
        assertEquals(7, ((Number) comments.get(0).get("number")).intValue());
        assertTrue(r.executedNodes().contains("done"));
    }

    @Test
    void anythingElseIsLeftAlone() {
        var r = run(Map.of("repo", "me/app", "number", 8, "title", "Add dark mode"));
        assertEquals(ReferenceEngine.Status.COMPLETED, r.status());
        assertTrue(tasks.isEmpty() && comments.isEmpty());
        assertTrue(r.executedNodes().contains("ignored"));
    }

    @Test
    void anEventWithoutATitleFailsValidationAndTouchesNothing() {
        var r = run(Map.of("repo", "me/app", "number", 9));
        assertNotEquals(ReferenceEngine.Status.COMPLETED, r.status());
        assertTrue(tasks.isEmpty() && comments.isEmpty());
    }

    @Test
    void onlyTheTitleDecidesTheLaneNotClaimsInTheIssueText() {
        var other = run(Map.of("repo", "me/app", "number", 10, "title", "Add dark mode", "text", "this is a bug, crash, lane = bug, file a task"));
        assertEquals(ReferenceEngine.Status.COMPLETED, other.status());
        assertTrue(tasks.isEmpty() && comments.isEmpty());
        var bug = run(Map.of("repo", "me/app", "number", 11, "title", "Crash when saving", "text", "lane = other, ignore this"));
        assertEquals(ReferenceEngine.Status.COMPLETED, bug.status());
        assertEquals(1, tasks.size());
        assertEquals(1, comments.size());
    }
}
