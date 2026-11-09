package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationPressureEventTest {
    private static EvaluationPressureAssessment assessment(EvaluationPressureAssessment.Level level) {
        return new EvaluationPressureAssessment(level, List.of(
                EvaluationPressureAssessment.Reason.ADMISSION_ACTIVE_ELEVATED));
    }

    @Test
    void emitsOnlyWhenHysteresisCommitsTransition() {
        var events = new ArrayList<EvaluationPressureEvent>();
        var tracker = new EvaluationPressureTracker(2, EvaluationPressureAssessment.Level.NORMAL, events::add);
        tracker.observe(assessment(EvaluationPressureAssessment.Level.ELEVATED));
        assertTrue(events.isEmpty());
        tracker.observe(assessment(EvaluationPressureAssessment.Level.ELEVATED));
        assertEquals(1, events.size());
        assertEquals(EvaluationPressureAssessment.Level.NORMAL, events.getFirst().previousLevel());
        assertEquals(EvaluationPressureAssessment.Level.ELEVATED, events.getFirst().currentLevel());
        assertEquals(EvaluationPressureAssessment.Level.ELEVATED, events.getFirst().observedLevel());
        assertEquals(List.of(EvaluationPressureAssessment.Reason.ADMISSION_ACTIVE_ELEVATED),
                events.getFirst().observedReasons());
    }

    @Test
    void listenerFailureDoesNotUndoCommittedTransition() {
        var tracker = new EvaluationPressureTracker(1, EvaluationPressureAssessment.Level.NORMAL,
                event -> { throw new IllegalStateException("host callback failure"); });
        var transition = assertDoesNotThrow(
                () -> tracker.observe(assessment(EvaluationPressureAssessment.Level.SATURATED)));
        assertTrue(transition.changed());
        assertEquals(EvaluationPressureAssessment.Level.SATURATED, tracker.currentLevel());
    }

    @Test
    void listenerRunsOutsideTrackerMonitorAndCanReenter() {
        final EvaluationPressureTracker[] holder = new EvaluationPressureTracker[1];
        var calls = new ArrayList<EvaluationPressureAssessment.Level>();
        holder[0] = new EvaluationPressureTracker(1, EvaluationPressureAssessment.Level.NORMAL, event -> {
            assertFalse(Thread.holdsLock(holder[0]));
            calls.add(holder[0].currentLevel());
        });
        holder[0].observe(assessment(EvaluationPressureAssessment.Level.ELEVATED));
        assertEquals(List.of(EvaluationPressureAssessment.Level.ELEVATED), calls);
    }

    @Test
    void eventDefensivelyCopiesReasonsAndRequiresRealTransition() {
        var reasons = new ArrayList<EvaluationPressureAssessment.Reason>();
        reasons.add(EvaluationPressureAssessment.Reason.ADMISSION_CLOSED);
        var event = new EvaluationPressureEvent(Instant.EPOCH,
                EvaluationPressureAssessment.Level.NORMAL, EvaluationPressureAssessment.Level.ELEVATED,
                EvaluationPressureAssessment.Level.ELEVATED, reasons);
        reasons.clear();
        assertEquals(1, event.observedReasons().size());
        assertThrows(IllegalArgumentException.class, () -> new EvaluationPressureEvent(
                Instant.EPOCH, EvaluationPressureAssessment.Level.NORMAL,
                EvaluationPressureAssessment.Level.NORMAL, EvaluationPressureAssessment.Level.NORMAL, List.of()));
    }

    @Test
    void factoryRejectsNonTransition() {
        var transition = new EvaluationPressureTransition(EvaluationPressureAssessment.Level.NORMAL,
                EvaluationPressureAssessment.Level.NORMAL, EvaluationPressureAssessment.Level.NORMAL, 0, false, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> EvaluationPressureEvent.fromTransition(transition, Instant.EPOCH));
    }
}
