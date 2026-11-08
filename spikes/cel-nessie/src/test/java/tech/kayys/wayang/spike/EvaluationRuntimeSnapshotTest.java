package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EvaluationRuntimeSnapshotTest {
    private static EvaluationRequestContext request(String tenant) {
        return new EvaluationRequestContext(tenant, "correlation", null, new EvaluationCancellationToken());
    }

    @Test
    void capturesCombinedAdmissionAndExecutorPressure() throws Exception {
        var admission = new EvaluationAdmissionController(
                new EvaluationAdmissionPolicy(3, 0, 3, 0, Duration.ZERO));
        var coordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 1, Duration.ofMillis(100)));
        var providerStarted = new CountDownLatch(1);
        var releaseProvider = new CountDownLatch(1);
        Thread running = new Thread(() -> coordinator.execute(req -> {
            providerStarted.countDown();
            try { releaseProvider.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new EvaluationExecutionOutcome.Completed("running");
        }, new EvaluationExecutionRequest(request("tenant-a"), null)));
        Thread queued = new Thread(() -> coordinator.execute(req ->
                new EvaluationExecutionOutcome.Completed("queued"),
                new EvaluationExecutionRequest(request("tenant-b"), null)));
        try {
            running.start();
            assertTrue(providerStarted.await(1, TimeUnit.SECONDS));
            queued.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (coordinator.queuedTasks() != 1 && System.nanoTime() < deadline) Thread.yield();
            assertEquals(1, coordinator.queuedTasks());

            EvaluationRuntimeSnapshot snapshot = EvaluationRuntimeSnapshot.capture(admission, coordinator);
            assertEquals(2, snapshot.admittedActive());
            assertEquals(0, snapshot.admissionQueued());
            assertEquals(1, snapshot.activeWorkers());
            assertEquals(1, snapshot.queuedProviderTasks());
            assertFalse(snapshot.admissionClosed());
            assertFalse(snapshot.executorShutdown());
            assertFalse(snapshot.executorTerminated());
        } finally {
            releaseProvider.countDown();
            running.join(2000);
            queued.join(2000);
            coordinator.close();
            admission.close();
        }
    }

    @Test
    void capturesClosedAndTerminatedLifecycle() throws Exception {
        var admission = new EvaluationAdmissionController(
                new EvaluationAdmissionPolicy(1, 0, 1, 0, Duration.ZERO));
        var coordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 0, Duration.ofSeconds(1)));
        coordinator.close();
        admission.close();
        assertTrue(coordinator.awaitTermination(Duration.ofSeconds(1)));

        var snapshot = EvaluationRuntimeSnapshot.capture(admission, coordinator);
        assertTrue(snapshot.admissionClosed());
        assertTrue(snapshot.executorShutdown());
        assertTrue(snapshot.executorTerminated());
        assertEquals(0, snapshot.admittedActive());
        assertEquals(0, snapshot.activeWorkers());
    }

    @Test
    void rejectsNegativePressureCounts() {
        assertThrows(IllegalArgumentException.class, () ->
                new EvaluationRuntimeSnapshot(-1, 0, false, 0, 0, false, false));
    }
}
