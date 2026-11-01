package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.PlanNode;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;

class WaitsTest {

    private final WaitRegistry.InMemory waits = new WaitRegistry.InMemory();
    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

    private ReferenceEngine engine(Clock clock) {
        return new ReferenceEngine(new NodeRunner(List.of(new ControlHandler(waits, clock), new HumanHandler(waits),
                new ServiceHandler(Map.of("c", in -> Map.of("done", true))))), waits);
    }

    private static ProcessModel timerProcess(Map<String, Object> cfg) {
        return ProcessModel.builder("t").start("s").task("wait", ElementType.TIMER_EVENT, cfg)
                .task("after", ElementType.SERVICE_TASK, Map.of("connector", "c")).end("e")
                .flow("s", "wait").flow("wait", "after").flow("after", "e").build();
    }

    @Test
    void timerFiresOnlyWhenDue() {
        var eng = engine(Clock.fixed(t0, ZoneOffset.UTC));
        var run = eng.start(ProcessCompiler.compile(timerProcess(Map.of("duration", "PT1H"))), Map.of());
        assertEquals(ReferenceEngine.Status.WAITING, run.status());
        assertEquals(t0.plusSeconds(3600), run.waitingOn().dueAt());

        var svc = new TimerService(waits, eng.completerFor(run), Clock.fixed(t0, ZoneOffset.UTC));
        assertEquals(0, svc.fireDue(t0.plusSeconds(3599)));
        assertEquals(ReferenceEngine.Status.WAITING, run.status());
        assertEquals(1, svc.fireDue(t0.plusSeconds(3600)));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
        assertEquals(0, waits.size());
        assertEquals(0, svc.fireDue(t0.plusSeconds(7200)), "a fired timer must not fire twice");
    }

    @Test
    void untilTimerUsesAbsoluteTime() {
        var eng = engine(Clock.fixed(t0, ZoneOffset.UTC));
        var run = eng.start(ProcessCompiler.compile(timerProcess(Map.of("until", "2026-01-02T00:00:00+00:00"))), Map.of());
        assertEquals(Instant.parse("2026-01-02T00:00:00Z"), run.waitingOn().dueAt());
    }

    @Test
    void timerConfigIsValidatedAtCompileTime() {
        assertThrows(ProcessCompiler.InvalidProcessException.class, () -> ProcessCompiler.compile(timerProcess(Map.of())));
        assertThrows(ProcessCompiler.InvalidProcessException.class,
                () -> ProcessCompiler.compile(timerProcess(Map.of("duration", "soon"))));
        assertThrows(ProcessCompiler.InvalidProcessException.class,
                () -> ProcessCompiler.compile(timerProcess(Map.of("duration", "PT1H", "until", "2026-01-02T00:00:00Z"))));
    }

    private static ProcessModel messageProcess() {
        return ProcessModel.builder("m").start("s")
                .task("approval", ElementType.MESSAGE_CATCH_EVENT, Map.of("message", "paid", "correlation", "order.id"))
                .end("e").flow("s", "approval").flow("approval", "e").build();
    }

    @Test
    void messageIsCorrelatedByKey() {
        var eng = engine(Clock.systemUTC());
        var run = eng.start(ProcessCompiler.compile(messageProcess()), Map.of("order", Map.of("id", "A-1")));
        assertEquals("A-1", run.waitingOn().correlationKey());
        var svc = new TimerService(waits, eng.completerFor(run), Clock.systemUTC());
        assertEquals(TimerService.Delivery.BUFFERED, svc.deliver("paid", "B-2", Map.of("amount", 5)),
                "wrong key must not complete the run");
        assertEquals(ReferenceEngine.Status.WAITING, run.status());
        assertEquals(TimerService.Delivery.DELIVERED, svc.deliver("paid", "A-1", Map.of("amount", 10)));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
        assertEquals(10, run.variables().get("approval.amount"));
    }

    @Test
    void earlyMessageIsNotLost() {
        var eng = engine(Clock.systemUTC());
        var svc = new TimerService(waits, (t, o) -> false, Clock.systemUTC());
        assertEquals(TimerService.Delivery.BUFFERED, svc.deliver("paid", "A-1", Map.of("amount", 7)));
        var run = eng.start(ProcessCompiler.compile(messageProcess()), Map.of("order", Map.of("id", "A-1")));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status(), "buffered message completes the wait immediately");
        assertEquals(7, run.variables().get("approval.amount"));
    }

    @Test
    void redispatchedNodeReattachesToExistingTicket() {
        var handler = new HumanHandler(waits);
        PlanNode node = ProcessCompiler.compile(ProcessModel.builder("h").start("s")
                .task("review", ElementType.USER_TASK, Map.of("title", "Review")).end("e")
                .flow("s", "review").flow("review", "e").build()).node("review").get();
        var first = (NodeOutcome.Waiting) handler.handle("run-1", node, Map.of());
        var second = (NodeOutcome.Waiting) handler.handle("run-1", node, Map.of());
        assertEquals(first.ticket().token(), second.ticket().token());
        assertEquals(1, waits.size(), "no duplicate human task after re-dispatch");
        var other = (NodeOutcome.Waiting) handler.handle("run-2", node, Map.of());
        assertNotEquals(first.ticket().token(), other.ticket().token());
    }

    @Test
    void bufferedMessagesAreBounded() {
        for (int i = 0; i < 100; i++) {
            waits.bufferMessage("n", "k", Map.of());
        }
        assertThrows(IllegalStateException.class, () -> waits.bufferMessage("n", "k", Map.of()));
    }
}
