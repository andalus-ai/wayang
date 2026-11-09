package tech.kayys.wayang.spike;

import java.time.Instant;
import java.util.Objects;

/** Process-local pressure hysteresis with optional committed-transition notifications. */
public final class EvaluationPressureTracker {
    private final int requiredConsecutiveSamples;
    private final EvaluationPressureEventListener listener;
    private EvaluationPressureAssessment.Level currentLevel;
    private EvaluationPressureAssessment.Level candidateLevel;
    private int candidateSamples;

    public EvaluationPressureTracker(int requiredConsecutiveSamples) {
        this(requiredConsecutiveSamples, EvaluationPressureAssessment.Level.NORMAL);
    }

    public EvaluationPressureTracker(int requiredConsecutiveSamples,
            EvaluationPressureAssessment.Level initialLevel) {
        this(requiredConsecutiveSamples, initialLevel, EvaluationPressureEventListener.NOOP);
    }

    public EvaluationPressureTracker(int requiredConsecutiveSamples,
            EvaluationPressureAssessment.Level initialLevel, EvaluationPressureEventListener listener) {
        if (requiredConsecutiveSamples < 1) {
            throw new IllegalArgumentException("requiredConsecutiveSamples must be at least one");
        }
        this.requiredConsecutiveSamples = requiredConsecutiveSamples;
        this.currentLevel = Objects.requireNonNull(initialLevel, "initialLevel");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public EvaluationPressureTransition observe(EvaluationPressureAssessment observation) {
        Objects.requireNonNull(observation, "observation");
        EvaluationPressureTransition transition;
        synchronized (this) {
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
            transition = new EvaluationPressureTransition(previous, currentLevel, observed,
                    candidateSamples, previous != currentLevel, observation.reasons());
        }
        if (transition.changed() && listener != EvaluationPressureEventListener.NOOP) {
            try {
                listener.onPressureTransition(EvaluationPressureEvent.fromTransition(transition, Instant.now()));
            } catch (RuntimeException ignored) {
                // A host callback cannot roll back an already committed transition.
            }
        }
        return transition;
    }

    public synchronized EvaluationPressureAssessment.Level currentLevel() { return currentLevel; }
    public synchronized int candidateSamples() { return candidateSamples; }
    public int requiredConsecutiveSamples() { return requiredConsecutiveSamples; }

    /** Clears pending evidence without changing the committed level. */
    public synchronized void resetCandidate() {
        candidateLevel = null;
        candidateSamples = 0;
    }
}
