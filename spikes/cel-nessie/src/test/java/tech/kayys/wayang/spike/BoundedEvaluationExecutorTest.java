package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BoundedEvaluationExecutorTest {
    private static EvaluationExecutionRequest request() {
        return new EvaluationExecutionRequest(
                new EvaluationRequestContext("tenant-test", "corr-test", null,
                        new EvaluationCancellationToken()),
                java.util.Map.of("x", 1));
    }

    @Test
    void returnsCompletedOutcomeFromProvider() {
        try (var executor = new BoundedEvaluationExecutor(
                new EvaluationExecutionPolicy(1, 2, Duration.ofSeconds(1)))) {
            var outcome = executor.execute(req -> new EvaluationExecutionOutcome.Completed("ok"), request());
            assertEquals("ok", assertInstanceOf(EvaluationExecutionOutcome.Completed.class, outcome).value());
        }
    }

    @Test
    void returnsUnknownWhenCallerWaitExpiresAndDoesNotPretendProviderStopped() {
        try (var executor = new BoundedEvaluationExecutor(
                new EvaluationExecutionPolicy(1, 1, Duration.ofMillis(30)))) {
            var outcome = executor.execute(req -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return new EvaluationExecutionOutcome.Completed("late");
            }, request());
            assertEquals("CALLER_WAIT_TIMEOUT_PROVIDER_STATE_UNKNOWN",
                    assertInstanceOf(EvaluationExecutionOutcome.Unknown.class, outcome).code());
        }
    }

    @Test
    void preCancelledRequestDoesNotInvokeProvider() {
        var token = new EvaluationCancellationToken();
        token.cancel();
        var req = new EvaluationExecutionRequest(
                new EvaluationRequestContext("tenant-test", "corr", null, token), null);
        try (var executor = new BoundedEvaluationExecutor(
                new EvaluationExecutionPolicy(1, 1, Duration.ofSeconds(1)))) {
            var outcome = executor.execute(request -> {
                throw new AssertionError("provider must not execute");
            }, req);
            assertInstanceOf(EvaluationExecutionOutcome.CancelledBeforeStart.class, outcome);
        }
    }

    @Test
    void rejectsAfterClose() {
        var executor = new BoundedEvaluationExecutor(
                new EvaluationExecutionPolicy(1, 1, Duration.ofSeconds(1)));
        executor.close();
        var outcome = executor.execute(req -> new EvaluationExecutionOutcome.Completed(null), request());
        assertEquals("EXECUTOR_CLOSED",
                assertInstanceOf(EvaluationExecutionOutcome.Rejected.class, outcome).code());
    }
}
