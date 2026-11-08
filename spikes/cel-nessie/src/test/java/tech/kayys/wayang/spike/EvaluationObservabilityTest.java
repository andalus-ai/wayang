package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class EvaluationObservabilityTest {
    private static EvaluationExecutionRequest request() {
        return new EvaluationExecutionRequest(new EvaluationRequestContext(
                "tenant-secret", "correlation-secret", null, new EvaluationCancellationToken()), null);
    }

    @Test
    void emitsAdmissionAndExecutionEventsWithMonotonicDurations() {
        var events = new CopyOnWriteArrayList<EvaluationEvent>();
        var admission = new EvaluationAdmissionController(
                new EvaluationAdmissionPolicy(1, 0, 1, 0, Duration.ZERO));
        try (var coordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 0, Duration.ofSeconds(1)), events::add)) {
            var outcome = coordinator.execute(req -> new EvaluationExecutionOutcome.Completed("ok"), request());
            assertInstanceOf(EvaluationExecutionOutcome.Completed.class, outcome);
            assertTrue(events.stream().anyMatch(e -> e.type() == EvaluationEvent.Type.ADMISSION_GRANTED));
            assertTrue(events.stream().anyMatch(e -> e.type() == EvaluationEvent.Type.EXECUTION_SUBMITTED));
            assertTrue(events.stream().anyMatch(e -> e.type() == EvaluationEvent.Type.EXECUTION_STARTED));
            assertTrue(events.stream().anyMatch(e -> e.type() == EvaluationEvent.Type.EXECUTION_COMPLETED));
            assertTrue(events.stream().filter(e -> e.type() == EvaluationEvent.Type.ADMISSION_GRANTED)
                    .allMatch(e -> e.durationNanos() >= 0));
            assertTrue(events.stream().filter(e -> e.type() == EvaluationEvent.Type.EXECUTION_COMPLETED)
                    .allMatch(e -> e.durationNanos() > 0));
            assertTrue(events.stream().allMatch(e -> e.reasonCode() == null
                    || e.reasonCode().matches("[A-Z0-9_]{1,80}")));
            assertEquals(0, admission.snapshot().active());
        } finally {
            admission.close();
        }
    }

    @Test
    void observerRuntimeFailureCannotChangeEvaluationOrLeaseAccounting() {
        var admission = new EvaluationAdmissionController(
                new EvaluationAdmissionPolicy(1, 0, 1, 0, Duration.ZERO));
        EvaluationObserver broken = event -> { throw new IllegalStateException("telemetry-secret"); };
        try (var coordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 0, Duration.ofSeconds(1)), broken)) {
            var outcome = coordinator.execute(req -> new EvaluationExecutionOutcome.Completed("ok"), request());
            assertEquals("ok", assertInstanceOf(EvaluationExecutionOutcome.Completed.class, outcome).value());
            assertEquals(0, admission.snapshot().active());
        } finally {
            admission.close();
        }
    }

    @Test
    void callerTimeoutIsObservedSeparatelyFromProviderCompletion() throws Exception {
        var events = new CopyOnWriteArrayList<EvaluationEvent>();
        var admission = new EvaluationAdmissionController(
                new EvaluationAdmissionPolicy(1, 0, 1, 0, Duration.ZERO));
        var coordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 0, Duration.ofMillis(20)), events::add);
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            var outcome = coordinator.execute(req -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new EvaluationExecutionOutcome.Completed("late");
            }, request());
            assertInstanceOf(EvaluationExecutionOutcome.Unknown.class, outcome);
            assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(events.stream().anyMatch(e -> e.type() == EvaluationEvent.Type.CALLER_WAIT_TIMED_OUT));
            assertEquals(1, admission.snapshot().active(), "provider retains its lease after caller timeout");
            coordinator.close();
            release.countDown();
            assertTrue(coordinator.awaitTermination(Duration.ofSeconds(1)));
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
            while (admission.snapshot().active() != 0 && System.nanoTime() < until) Thread.yield();
            assertTrue(events.stream().anyMatch(e -> e.type() == EvaluationEvent.Type.EXECUTION_COMPLETED));
            assertEquals(0, admission.snapshot().active());
        } finally {
            release.countDown();
            coordinator.close();
            admission.close();
        }
    }

    @Test
    void admissionRejectionIsObservedWithStableReasonCode() {
        var events = new CopyOnWriteArrayList<EvaluationEvent>();
        var admission = new EvaluationAdmissionController(
                new EvaluationAdmissionPolicy(1, 0, 1, 0, Duration.ZERO));
        admission.close();
        try (var coordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 0, Duration.ofSeconds(1)), events::add)) {
            var outcome = coordinator.execute(req -> {
                fail("closed admission must not invoke provider");
                return new EvaluationExecutionOutcome.Completed(null);
            }, request());
            assertEquals("ADMISSION_CONTROLLER_CLOSED",
                    assertInstanceOf(EvaluationExecutionOutcome.Rejected.class, outcome).code());
            assertTrue(events.stream().anyMatch(e -> e.type() == EvaluationEvent.Type.ADMISSION_REJECTED
                    && "ADMISSION_CONTROLLER_CLOSED".equals(e.reasonCode())));
        } finally {
            admission.close();
        }
    }

    @Test
    void eventRejectsUnboundedOrSensitiveReasonLabels() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationEvent(EvaluationEvent.Type.EXECUTION_FAILED, 1, "tenant-secret"));
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationEvent(EvaluationEvent.Type.EXECUTION_FAILED, -1, null));
        assertThrows(NullPointerException.class,
                () -> new EvaluationEvent(null, 0, null));
    }
}
