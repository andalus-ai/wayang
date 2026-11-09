package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EvaluationPressureClassifierTest {
    private static final EvaluationAdmissionPolicy ADMISSION =
            new EvaluationAdmissionPolicy(10, 10, 5, 5, Duration.ofSeconds(1));
    private static final EvaluationExecutionPolicy EXECUTION =
            new EvaluationExecutionPolicy(4, 8, Duration.ofSeconds(1));
    private static final EvaluationPressurePolicy THRESHOLDS =
            new EvaluationPressurePolicy(7_000, 9_000);

    private static EvaluationRuntimeSnapshot snapshot(int admitted, int admissionQueue,
            int workers, int providerQueue) {
        return new EvaluationRuntimeSnapshot(admitted, admissionQueue, false,
                workers, providerQueue, false, false);
    }

    @Test
    void reportsNormalBelowElevatedThresholds() {
        var result = EvaluationPressureClassifier.classify(
                snapshot(6, 6, 2, 5), ADMISSION, EXECUTION, THRESHOLDS);
        assertEquals(EvaluationPressureAssessment.Level.NORMAL, result.level());
        assertTrue(result.reasons().isEmpty());
    }

    @Test
    void reportsElevatedAtConfiguredThresholdBoundary() {
        var result = EvaluationPressureClassifier.classify(
                snapshot(7, 0, 0, 0), ADMISSION, EXECUTION, THRESHOLDS);
        assertEquals(EvaluationPressureAssessment.Level.ELEVATED, result.level());
        assertEquals(java.util.List.of(
                EvaluationPressureAssessment.Reason.ADMISSION_ACTIVE_ELEVATED), result.reasons());
    }

    @Test
    void reportsSaturatedAtConfiguredThresholdBoundary() {
        var result = EvaluationPressureClassifier.classify(
                snapshot(9, 0, 0, 0), ADMISSION, EXECUTION, THRESHOLDS);
        assertEquals(EvaluationPressureAssessment.Level.SATURATED, result.level());
        assertTrue(result.reasons().contains(
                EvaluationPressureAssessment.Reason.ADMISSION_ACTIVE_SATURATED));
    }

    @Test
    void classifiesEachQueueAgainstItsOwnConfiguredCapacity() {
        var result = EvaluationPressureClassifier.classify(
                snapshot(0, 9, 0, 8), ADMISSION, EXECUTION, THRESHOLDS);
        assertEquals(EvaluationPressureAssessment.Level.SATURATED, result.level());
        assertTrue(result.reasons().contains(
                EvaluationPressureAssessment.Reason.ADMISSION_QUEUE_SATURATED));
        assertTrue(result.reasons().contains(
                EvaluationPressureAssessment.Reason.PROVIDER_QUEUE_SATURATED));
    }

    @Test
    void zeroCapacityQueueIsNormalWhenEmptyAndSaturatedWhenNonEmpty() {
        var noQueue = new EvaluationExecutionPolicy(2, 0, Duration.ofSeconds(1));
        assertEquals(EvaluationPressureAssessment.Level.NORMAL,
                EvaluationPressureClassifier.classify(snapshot(0, 0, 0, 0),
                        ADMISSION, noQueue, THRESHOLDS).level());
        var result = EvaluationPressureClassifier.classify(snapshot(0, 0, 0, 1),
                ADMISSION, noQueue, THRESHOLDS);
        assertEquals(EvaluationPressureAssessment.Level.SATURATED, result.level());
        assertTrue(result.reasons().contains(
                EvaluationPressureAssessment.Reason.PROVIDER_QUEUE_SATURATED));
    }

    @Test
    void lifecycleFlagsAreReportedWithoutMisclassifyingAsCapacityPressure() {
        var closed = new EvaluationRuntimeSnapshot(0, 0, true, 0, 0, true, false);
        var result = EvaluationPressureClassifier.classify(closed, ADMISSION, EXECUTION, THRESHOLDS);
        assertEquals(EvaluationPressureAssessment.Level.NORMAL, result.level());
        assertTrue(result.reasons().contains(EvaluationPressureAssessment.Reason.ADMISSION_CLOSED));
        assertTrue(result.reasons().contains(EvaluationPressureAssessment.Reason.EXECUTOR_SHUTDOWN));
    }

    @Test
    void validatesThresholdOrderingAndRange() {
        assertThrows(IllegalArgumentException.class, () -> new EvaluationPressurePolicy(9_000, 7_000));
        assertThrows(IllegalArgumentException.class, () -> new EvaluationPressurePolicy(0, 9_000));
        assertThrows(IllegalArgumentException.class, () -> new EvaluationPressurePolicy(7_000, 10_001));
    }
}
