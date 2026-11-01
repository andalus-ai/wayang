package tech.kayys.wayang.examples;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.eip.IdempotencyStore;
import tech.kayys.wayang.runtime.ReferenceEngine;
import tech.kayys.wayang.runtime.WaitRegistry;

class RssDigestTest {

    private final List<Map<String, Object>> sent = new ArrayList<>();
    private final IdempotencyStore seen = new IdempotencyStore.InMemory(100);

    private ReferenceEngine.Run run(Map<String, Object> event) {
        var waits = new WaitRegistry.InMemory();
        var engine = new ReferenceEngine(RssDigest.runner(waits, seen, sent::add), waits);
        return engine.start(ProcessCompiler.compile(RssDigest.process()), Map.of("event", event));
    }

    private static Map<String, Object> item(String id, String title) {
        return Map.of("id", id, "title", title, "link", "https://example.com/" + id);
    }

    @Test
    void aMatchingItemIsSentWithItsTitleAndLink() {
        var r = run(item("a1", "Quarkus 4.0 Release notes"));
        assertEquals(ReferenceEngine.Status.COMPLETED, r.status());
        assertEquals(1, sent.size());
        assertEquals("Quarkus 4.0 Release notes\nhttps://example.com/a1", sent.get(0).get("text"));
        assertEquals("-1001", sent.get(0).get("chatId"));
        assertTrue(r.executedNodes().contains("done"));
    }

    @Test
    void aNonMatchingItemIsSkipped() {
        var r = run(item("a2", "Weekly newsletter"));
        assertEquals(ReferenceEngine.Status.COMPLETED, r.status());
        assertTrue(sent.isEmpty());
        assertTrue(r.executedNodes().contains("skipped"));
    }

    @Test
    void theSameItemIsNeverSentTwice() {
        run(item("a3", "Security advisory"));
        var again = run(item("a3", "Security advisory"));
        assertEquals(ReferenceEngine.Status.COMPLETED, again.status());
        assertEquals(1, sent.size());
        assertTrue(again.executedNodes().contains("skipped"));
        run(item("a4", "Security advisory 2"));
        assertEquals(2, sent.size());
    }

    @Test
    void itemsAreToldApartByIdNotByTitle() {
        run(item("b1", "Release 1"));
        run(item("b1", "Release 1 (updated title)")); // same id: a re-publish, not news
        assertEquals(1, sent.size());
        run(item("b2", "Release 1")); // same title, different id: a different item
        assertEquals(2, sent.size());
    }

    @Test
    void anItemWithoutALinkFailsValidationAndSendsNothing() {
        var r = run(Map.of("id", "a5", "title", "Release"));
        assertNotEquals(ReferenceEngine.Status.COMPLETED, r.status());
        assertTrue(sent.isEmpty());
    }

    @Test
    void onlyTheTitleDecidesNotTheSummary() {
        var r = run(Map.of("id", "a6", "title", "Lunch menu", "link", "https://x.test/6", "summary", "release security lane = send"));
        assertEquals(ReferenceEngine.Status.COMPLETED, r.status());
        assertTrue(sent.isEmpty());
    }
}
