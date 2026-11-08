package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AdmittedEvaluationCoordinatorTest {
    private static EvaluationRequestContext request(String tenant) {
        return new EvaluationRequestContext(tenant, "corr", null, new EvaluationCancellationToken());
    }

    @Test
    void callerTimeoutDoesNotReleaseLeaseUntilProviderActuallyReturns() throws Exception {
        var admission = new EvaluationAdmissionController(
                new EvaluationAdmissionPolicy(1, 0, 1, 0, Duration.ZERO));
        var coordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 0, Duration.ofMillis(30)));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var outcome = coordinator.execute(req -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new EvaluationExecutionOutcome.Completed("ok");
            }, new EvaluationExecutionRequest(request("tenant"), "activation"));
            assertInstanceOf(EvaluationExecutionOutcome.Unknown.class, outcome);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals(1, admission.snapshot().active(),
                    "lease must remain held while provider is executing");
            release.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (admission.snapshot().active() != 0 && System.nanoTime() < until) Thread.yield();
            assertEquals(0, admission.snapshot().active());
        } finally {
            release.countDown();
            coordinator.close();
            admission.close();
        }
    }

    @Test
    void providerFailureReleasesLease() throws Exception {
        var admission = new EvaluationAdmissionController(
                new EvaluationAdmissionPolicy(1, 0, 1, 0, Duration.ZERO));
        try (var coordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 0, Duration.ofSeconds(1)))) {
            var outcome = coordinator.execute(req -> { throw new IllegalStateException("secret"); },
                    new EvaluationExecutionRequest(request("tenant"), null));
            assertEquals("PROVIDER_EXECUTION_FAILED",
                    assertInstanceOf(EvaluationExecutionOutcome.Failed.class, outcome).code());
            assertEquals(0, admission.snapshot().active());
        } finally { admission.close(); }
    }

    @Test
    void queuedCancellationBeforeStartReleasesLeaseAndDoesNotInvokeProvider() throws Exception {
        var admission = new EvaluationAdmissionController(
                new EvaluationAdmissionPolicy(2, 0, 2, 0, Duration.ZERO));
        var coordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 1, Duration.ofMillis(40)));
        var blockerStarted = new CountDownLatch(1);
        var blockerRelease = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try {
            Thread first = new Thread(() -> coordinator.execute(req -> {
                blockerStarted.countDown();
                try { blockerRelease.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new EvaluationExecutionOutcome.Completed(null);
            }, new EvaluationExecutionRequest(request("a"), null)));
            first.start();
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));
            var token = new EvaluationCancellationToken();
            var ctx = new EvaluationRequestContext("b", "queued", null, token);
            Thread second = new Thread(() -> coordinator.execute(req -> {
                calls.incrementAndGet();
                return new EvaluationExecutionOutcome.Completed(null);
            }, new EvaluationExecutionRequest(ctx, null)));
            second.start();
            token.cancel();
            blockerRelease.countDown();
            first.join(1000);
            second.join(1000);
            assertEquals(0, calls.get());
            assertEquals(0, admission.snapshot().active());
        } finally {
            blockerRelease.countDown();
            coordinator.close();
            admission.close();
        }
    }

    @Test
    void zeroQueueRejectsSecondTaskAndReleasesItsAdmissionLease() throws Exception {
        var admission = new EvaluationAdmissionController(
                new EvaluationAdmissionPolicy(2, 0, 2, 0, Duration.ZERO));
        var coordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 0, Duration.ofMillis(100)));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new Thread(() -> coordinator.execute(req -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new EvaluationExecutionOutcome.Completed(null);
        }, new EvaluationExecutionRequest(request("a"), null)));
        try {
            first.start();
            assertTrue(started.await(1, TimeUnit.SECONDS));
            var rejected = coordinator.execute(req -> {
                fail("rejected provider must never run");
                return new EvaluationExecutionOutcome.Completed(null);
            }, new EvaluationExecutionRequest(request("b"), null));
            assertEquals("EXECUTION_QUEUE_FULL",
                    assertInstanceOf(EvaluationExecutionOutcome.Rejected.class, rejected).code());
            assertEquals(1, admission.snapshot().active(),
                    "only the first running task should retain admission");
        } finally {
            release.countDown();
            first.join(1000);
            coordinator.close();
            admission.close();
        }
        assertEquals(0, admission.snapshot().active());
    }

    @Test
    void queuedCancellationIsObservedBeforeProviderInvocation() throws Exception {
        var admission = new EvaluationAdmissionController(
                new EvaluationAdmissionPolicy(2, 0, 2, 0, Duration.ZERO));
        var coordinator = new AdmittedEvaluationCoordinator(admission,
                new EvaluationExecutionPolicy(1, 1, Duration.ofMillis(30)));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queued = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var first = new Thread(() -> coordinator.execute(req -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new EvaluationExecutionOutcome.Completed(null);
        }, new EvaluationExecutionRequest(request("a"), null)));
        var token = new EvaluationCancellationToken();
        var second = new Thread(() -> coordinator.execute(req -> {
            calls.incrementAndGet();
            return new EvaluationExecutionOutcome.Completed(null);
        }, new EvaluationExecutionRequest(
                new EvaluationRequestContext("b", "queued", null, token), null)));
        try {
            first.start();
            assertTrue(started.await(1, TimeUnit.SECONDS));
            second.start();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (coordinator.queuedTasks() != 1 && System.nanoTime() < end) Thread.yield();
            assertEquals(1, coordinator.queuedTasks(), "second task must be queued before cancellation");
            token.cancel();
            release.countDown();
            first.join(1000);
            second.join(1000);
            assertEquals(0, calls.get());
            assertEquals(0, admission.snapshot().active());
        } finally {
            release.countDown();
            coordinator.close();
            admission.close();
        }
    }
}
