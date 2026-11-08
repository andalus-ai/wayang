package tech.kayys.wayang.spike;

import java.util.Objects;

/**
 * Stateful, process-local pressure hysteresis.
 *
 * A new level is committed only after the same different classification has
 * been observed for the configured number of consecutive samples. The tracker
 * does not schedule sampling or alter runtime admission/execution behavior.
 */
public final class EvaluationPressureTracker {
    private final int requiredConsecutiveSamples;
    private EvaluationPressureAssessment.Level currentLevel;
    private EvaluationPressureAssessment.Level candidateLevel;
    private int candidateSamples;

    public EvaluationPressureTracker(int requiredConsecutiveSamples) {
        this(requiredConsecutiveSamples, EvaluationPressureAssessment.Level.NORMAL);
    }

    public EvaluationPressureTracker(
            int requiredConsecutiveSamples, EvaluationPressureAssessment.Level initialLevel) {
        if (requiredConsecutiveSamples < 1) {
            throw new IllegalArgumentException("requiredConsecutiveSamples must be at least one");
        }
        this.requiredConsecutiveSamples = requiredConsecutiveSamples;
        this.currentLevel = Objects.requireNonNull(initialLevel, "initialLevel");
    }

    public synchronized EvaluationPressureTransition observe(EvaluationPressureAssessment observation) {
        Objects.requireNonNull(observation, "observation");
        var previous = currentLevel;
        var observed = observation.level();

        if (observed == currentLevel) {
            candidateLevel = null;
            candidateSamples = 0;
        } else if (observed != candidateLevel) {
            candidateLevel = observed;
            candidateSamples = 1;
        } else {
            candidateSamples++;
        }

        if (candidateLevel != null && candidateSamples >= requiredConsecutiveSamples) {
            currentLevel = candidateLevel;
            candidateLevel = null;
            candidateSamples = 0;
        }

        return new EvaluationPressureTransition(previous, currentLevel, observed,
                candidateSamples, previous != currentLevel, observation.reasons());
    }

    public synchronized EvaluationPressureAssessment.Level currentLevel() {
        return currentLevel;
    }

    /** Number of consecutive observations currently supporting a pending level. */
    public synchronized int candidateSamples() {
        return candidateSamples;
    }

    public int requiredConsecutiveSamples() {
        return requiredConsecutiveSamples;
    }

    /** Clears a pending transition without changing the committed level. */
    public synchronized void resetCandidate() {
        candidateLevel = null;
        candidateSamples = 0;
    }
}
