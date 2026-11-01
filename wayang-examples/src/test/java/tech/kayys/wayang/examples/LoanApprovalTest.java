package tech.kayys.wayang.examples;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.runtime.ReferenceEngine;
import tech.kayys.wayang.runtime.WaitRegistry;

class LoanApprovalTest {

    private final WaitRegistry.InMemory waits = new WaitRegistry.InMemory();
    private final ReferenceEngine engine = new ReferenceEngine(LoanApproval.runner(waits), waits);

    @Test
    void lowRiskIsPaidAutomatically() {
        var run = engine.start(ProcessCompiler.compile(LoanApproval.process()), Map.of(
                "applicant", Map.of("name", "Ana", "age", 31), "amount", 1200,
                "application", Map.of("items", List.of("laptop", "desk"))));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
        assertEquals(Boolean.TRUE, run.variables().get("pay.paid"));
        assertEquals("[summary] Applicant Ana asks 1200 in 2 items", run.variables().get("summary.output"));
        assertFalse(run.executedNodes().contains("review"));
    }

    @Test
    void highRiskWaitsForReviewer() {
        var run = engine.start(ProcessCompiler.compile(LoanApproval.process()), Map.of(
                "applicant", Map.of("name", "Bo", "age", 22), "amount", 9000,
                "application", Map.of("items", List.of("car"))));
        assertEquals(ReferenceEngine.Status.WAITING, run.status());
        assertEquals("Review Bo: [summary] Applicant Bo asks 9000 in 1 items", run.waitingOn().info().get("title"));
        engine.complete(run, run.waitingOn().token(), Map.of("approved", true));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
        assertNull(run.variables().get("pay.paid"));
    }
}
