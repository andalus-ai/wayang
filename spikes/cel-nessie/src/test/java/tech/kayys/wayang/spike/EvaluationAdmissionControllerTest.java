package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EvaluationAdmissionControllerTest {

    private static EvaluationRequestContext request(String tenant) {
        return new EvaluationRequestContext(tenant, "corr-" + tenant, null,
                new EvaluationCancellationToken());
    }

    private static EvaluationAdmissionPolicy policy(
            int concurrent, int queued, int perTenant, int queuedPerTenant, Duration wait) {
        return new EvaluationAdmissionPolicy(concurrent, queued, perTenant, queuedPerTenant, wait);
    }

    @Test
    void enforcesGlobalAndPerTenantActiveLimitsWhileAllowingOtherTenantsToProgress() throws Exception {
        var controller = new EvaluationAdmissionController(
                policy(2, 2, 1, 1, Duration.ofSeconds(2)));
        try (var first = controller.acquire(request("tenant-a"))) {
            FutureTask<EvaluationAdmissionController.Lease> waiting =
                    new FutureTask<>(() -> controller.acquire(request("tenant-a")));
            Thread waiter = new Thread(waiting, "tenant-a-waiter");
            waiter.start();

            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (controller.snapshot().queued() == 0 && System.nanoTime() < end) {
                Thread.yield();
            }
            assertEquals(1, controller.snapshot().queued());

            try (var otherTenant = controller.acquire(request("tenant-b"))) {
                assertEquals(2, controller.snapshot().active());
                assertEquals(1, controller.snapshot().queued());
            }

            first.close();
            try (var admitted = waiting.get(2, TimeUnit.SECONDS)) {
                assertEquals(1, controller.snapshot().active());
                assertTrue(admitted.isReleased() == false);
            }
        } finally {
            controller.close();
        }
    }

    @Test
    void rejectsWhenGlobalQueueIsDisabledOrFull() throws Exception {
        var controller = new EvaluationAdmissionController(
                policy(1, 0, 1, 0, Duration.ofSeconds(1)));
        try (var ignored = controller.acquire(request("tenant-a"))) {
            assertEquals("GLOBAL_QUEUE_FULL", assertThrows(EvaluationAdmissionException.class,
                    () -> controller.acquire(request("tenant-b"))).code());
        } finally {
            controller.close();
        }
    }

    @Test
    void queueWaitIsBounded() throws Exception {
        var controller = new EvaluationAdmissionController(
                policy(1, 1, 1, 1, Duration.ofMillis(40)));
        try (var ignored = controller.acquire(request("tenant-a"))) {
            assertEquals("QUEUE_WAIT_TIMEOUT", assertThrows(EvaluationAdmissionException.class,
                    () -> controller.acquire(request("tenant-b"))).code());
            assertEquals(0, controller.snapshot().queued());
        } finally {
            controller.close();
        }
    }

    @Test
    void requestDeadlineAndCancellationAreDistinct() throws Exception {
        var controller = new EvaluationAdmissionController(
                policy(1, 1, 1, 1, Duration.ofSeconds(1)));
        try {
            var expired = new EvaluationRequestContext("tenant-a", "expired",
                    Instant.now().minusSeconds(1), new EvaluationCancellationToken());
            assertEquals("REQUEST_DEADLINE_EXCEEDED", assertThrows(EvaluationAdmissionException.class,
                    () -> controller.acquire(expired)).code());

            var token = new EvaluationCancellationToken();
            token.cancel();
            var cancelled = new EvaluationRequestContext("tenant-a", "cancelled", null, token);
            assertEquals("EVALUATION_CANCELLED", assertThrows(EvaluationAdmissionException.class,
                    () -> controller.acquire(cancelled)).code());
        } finally {
            controller.close();
        }
    }

    @Test
    void controllerCloseWakesQueuedWaitersAndPreventsNewAdmissions() throws Exception {
        var controller = new EvaluationAdmissionController(
                policy(1, 1, 1, 1, Duration.ofSeconds(5)));
        var held = controller.acquire(request("tenant-a"));
        CountDownLatch started = new CountDownLatch(1);
        FutureTask<String> waiting = new FutureTask<>(() -> {
            started.countDown();
            try {
                controller.acquire(request("tenant-b"));
                return "unexpected";
            } catch (EvaluationAdmissionException expected) {
                return expected.code();
            }
        });
        Thread thread = new Thread(waiting, "closing-controller-waiter");
        thread.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (controller.snapshot().queued() == 0 && System.nanoTime() < end) {
            Thread.yield();
        }
        controller.close();
        assertEquals("ADMISSION_CONTROLLER_CLOSED", waiting.get(2, TimeUnit.SECONDS));
        held.close();
        assertEquals(0, controller.snapshot().active());
        assertTrue(controller.snapshot().closed());
        assertEquals("ADMISSION_CONTROLLER_CLOSED", assertThrows(EvaluationAdmissionException.class,
                () -> controller.acquire(request("tenant-c"))).code());
    }

    @Test
    void leaseReleaseIsIdempotentAndRestoresCapacity() throws Exception {
        var controller = new EvaluationAdmissionController(
                policy(1, 0, 1, 0, Duration.ZERO));
        var lease = controller.acquire(request("tenant-a"));
        lease.close();
        lease.close();
        assertTrue(lease.isReleased());
        assertEquals(0, controller.snapshot().active());
        try (var replacement = controller.acquire(request("tenant-a"))) {
            assertFalse(replacement.isReleased());
            assertEquals(1, controller.snapshot().active());
        } finally {
            controller.close();
        }
    }

    @Test
    void validatesPolicyAndTrustedTenantIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> policy(2, 1, 3, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationRequestContext(" ", "corr", null, new EvaluationCancellationToken()));
    }
}
