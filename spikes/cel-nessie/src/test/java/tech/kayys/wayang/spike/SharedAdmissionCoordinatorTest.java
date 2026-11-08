package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Cross-coordinator qualification for a shared host admission controller. */
class SharedAdmissionCoordinatorTest {
    private static EvaluationExecutionRequest request(String tenant, String correlation) {
        return new EvaluationExecutionRequest(
                new EvaluationRequestContext(tenant, correlation, null, new EvaluationCancellationToken()),
                null);
    }

    @Test
    void sharedAdmissionLimitAppliesAcrossIndependentCoordinators() throws Exception {
        var admission = new EvaluationAdmissionController(
                new EvaluationAdmissionPolicy(1, 2, 1, 2, Duration.ofSeconds(2)));
        var firstCoordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 0, Duration.ofSeconds(3)));
        var secondCoordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 0, Duration.ofSeconds(3)));
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondResult = new AtomicReference<EvaluationExecutionOutcome>();
        Thread first = new Thread(() -> firstCoordinator.execute(req -> {
            firstStarted.countDown();
            try { releaseFirst.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new EvaluationExecutionOutcome.Completed("first");
        }, request("tenant-a", "first")));
        Thread second = new Thread(() -> secondResult.set(secondCoordinator.execute(req -> {
            secondStarted.countDown();
            return new EvaluationExecutionOutcome.Completed("second");
        }, request("tenant-b", "second"))));
        try {
            first.start();
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            second.start();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (admission.snapshot().queued() != 1 && System.nanoTime() < until) Thread.yield();
            assertEquals(1, admission.snapshot().active());
            assertEquals(1, admission.snapshot().queued());
            assertEquals(1L, secondStarted.getCount(),
                    "second coordinator must not bypass shared global admission");
            releaseFirst.countDown();
            first.join(2000);
            second.join(2000);
            assertEquals(0L, secondStarted.getCount());
            assertInstanceOf(EvaluationExecutionOutcome.Completed.class, secondResult.get());
            assertEquals(0, admission.snapshot().active());
        } finally {
            releaseFirst.countDown();
            firstCoordinator.close();
            secondCoordinator.close();
            admission.close();
        }
    }

    @Test
    void closingOneCoordinatorDoesNotStopAnotherCoordinatorSharingAdmission() {
        var admission = new EvaluationAdmissionController(
                new EvaluationAdmissionPolicy(2, 0, 2, 0, Duration.ZERO));
        var firstCoordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 0, Duration.ofSeconds(1)));
        var secondCoordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 0, Duration.ofSeconds(1)));
        try {
            firstCoordinator.close();
            assertTrue(firstCoordinator.isShutdown());
            var result = secondCoordinator.execute(req ->
                    new EvaluationExecutionOutcome.Completed("still available"),
                    request("tenant", "after-first-close"));
            assertEquals("still available",
                    assertInstanceOf(EvaluationExecutionOutcome.Completed.class, result).value());
            assertFalse(secondCoordinator.isShutdown());
            assertEquals(0, admission.snapshot().active());
        } finally {
            firstCoordinator.close();
            secondCoordinator.close();
            admission.close();
        }
    }

    @Test
    void closingSharedAdmissionRejectsQueuedAcquirerButPreservesGrantedLease() throws Exception {
        var admission = new EvaluationAdmissionController(
                new EvaluationAdmissionPolicy(1, 1, 1, 1, Duration.ofSeconds(3)));
        var coordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 0, Duration.ofSeconds(2)));
        var providerStarted = new CountDownLatch(1);
        var releaseProvider = new CountDownLatch(1);
        var providerThread = new Thread(() -> coordinator.execute(req -> {
            providerStarted.countDown();
            try { releaseProvider.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new EvaluationExecutionOutcome.Completed("active-finished");
        }, request("active-tenant", "active")));
        var queuedResult = new AtomicReference<EvaluationExecutionOutcome>();
        var queuedThread = new Thread(() -> queuedResult.set(coordinator.execute(req ->
                new EvaluationExecutionOutcome.Completed("must-not-run"),
                request("queued-tenant", "queued"))));
        try {
            providerThread.start();
            assertTrue(providerStarted.await(1, TimeUnit.SECONDS));
            queuedThread.start();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (admission.snapshot().queued() != 1 && System.nanoTime() < until) Thread.yield();
            assertEquals(1, admission.snapshot().active());
            assertEquals(1, admission.snapshot().queued());
            admission.close();
            queuedThread.join(1000);
            assertFalse(queuedThread.isAlive());
            assertEquals("ADMISSION_CONTROLLER_CLOSED",
                    assertInstanceOf(EvaluationExecutionOutcome.Rejected.class, queuedResult.get()).code());
            assertEquals(1, admission.snapshot().active(),
                    "closing admission must not revoke an already granted lease");
            releaseProvider.countDown();
            providerThread.join(2000);
            assertFalse(providerThread.isAlive());
            assertEquals(0, admission.snapshot().active());
            assertTrue(admission.snapshot().closed());
        } finally {
            releaseProvider.countDown();
            coordinator.close();
            admission.close();
        }
    }
}
