package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationPressureTrackerTest {
    private static EvaluationPressureAssessment assessment(EvaluationPressureAssessment.Level level) {
        return new EvaluationPressureAssessment(level, List.of());
    }

    @Test
    void requiresConsecutiveSamplesBeforeEnteringElevated() {
        var tracker = new EvaluationPressureTracker(3);
        assertFalse(tracker.observe(assessment(EvaluationPressureAssessment.Level.ELEVATED)).changed());
        assertFalse(tracker.observe(assessment(EvaluationPressureAssessment.Level.ELEVATED)).changed());
        var transition = tracker.observe(assessment(EvaluationPressureAssessment.Level.ELEVATED));
        assertTrue(transition.changed());
        assertEquals(EvaluationPressureAssessment.Level.NORMAL, transition.previousLevel());
        assertEquals(EvaluationPressureAssessment.Level.ELEVATED, transition.currentLevel());
        assertEquals(0, transition.consecutiveCandidateSamples());
    }

    @Test
    void transientSpikeDoesNotChangeCommittedLevel() {
        var tracker = new EvaluationPressureTracker(2);
        tracker.observe(assessment(EvaluationPressureAssessment.Level.ELEVATED));
        var reset = tracker.observe(assessment(EvaluationPressureAssessment.Level.NORMAL));
        assertEquals(EvaluationPressureAssessment.Level.NORMAL, reset.currentLevel());
        assertEquals(0, tracker.candidateSamples());
    }

    @Test
    void changingCandidateRestartsConsecutiveSampleCount() {
        var tracker = new EvaluationPressureTracker(2);
        tracker.observe(assessment(EvaluationPressureAssessment.Level.ELEVATED));
        var changedCandidate = tracker.observe(assessment(EvaluationPressureAssessment.Level.SATURATED));
        assertEquals(EvaluationPressureAssessment.Level.SATURATED, changedCandidate.observedLevel());
        assertEquals(1, changedCandidate.consecutiveCandidateSamples());
        assertFalse(changedCandidate.changed());
        assertEquals(EvaluationPressureAssessment.Level.SATURATED,
                tracker.observe(assessment(EvaluationPressureAssessment.Level.SATURATED)).currentLevel());
    }

    @Test
    void recoveryAlsoRequiresConsecutiveSamples() {
        var tracker = new EvaluationPressureTracker(2, EvaluationPressureAssessment.Level.SATURATED);
        assertFalse(tracker.observe(assessment(EvaluationPressureAssessment.Level.ELEVATED)).changed());
        var recovered = tracker.observe(assessment(EvaluationPressureAssessment.Level.ELEVATED));
        assertTrue(recovered.changed());
        assertEquals(EvaluationPressureAssessment.Level.ELEVATED, recovered.currentLevel());
    }

    @Test
    void supportsDirectTransitionsWhenConfiguredForOneSample() {
        var tracker = new EvaluationPressureTracker(1);
        var transition = tracker.observe(assessment(EvaluationPressureAssessment.Level.SATURATED));
        assertTrue(transition.changed());
        assertEquals(EvaluationPressureAssessment.Level.SATURATED, tracker.currentLevel());
    }

    @Test
    void resetCandidateKeepsCommittedLevel() {
        var tracker = new EvaluationPressureTracker(3, EvaluationPressureAssessment.Level.ELEVATED);
        tracker.observe(assessment(EvaluationPressureAssessment.Level.SATURATED));
        tracker.resetCandidate();
        assertEquals(EvaluationPressureAssessment.Level.ELEVATED, tracker.currentLevel());
        assertEquals(0, tracker.candidateSamples());
    }

    @Test
    void validatesConfigurationAndInputs() {
        assertThrows(IllegalArgumentException.class, () -> new EvaluationPressureTracker(0));
        assertThrows(NullPointerException.class,
                () -> new EvaluationPressureTracker(2).observe(null));
        assertThrows(NullPointerException.class,
                () -> new EvaluationPressureTracker(2, null));
    }
}
