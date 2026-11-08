package tech.kayys.wayang.spike;
import java.time.Instant; import java.util.List; import java.util.Objects;
/** Immutable event emitted only when the tracker commits a pressure-level transition. */
public record EvaluationPressureEvent(Instant occurredAt, EvaluationPressureAssessment.Level previousLevel, EvaluationPressureAssessment.Level currentLevel, EvaluationPressureAssessment.Level observedLevel, List<EvaluationPressureAssessment.Reason> observedReasons) {
 public EvaluationPressureEvent { Objects.requireNonNull(occurredAt); Objects.requireNonNull(previousLevel); Objects.requireNonNull(currentLevel); Objects.requireNonNull(observedLevel); observedReasons=List.copyOf(Objects.requireNonNull(observedReasons)); if(previousLevel==currentLevel) throw new IllegalArgumentException("event must represent a transition"); }
 public static EvaluationPressureEvent fromTransition(EvaluationPressureTransition t, Instant at) { Objects.requireNonNull(t); if(!t.changed()) throw new IllegalArgumentException("no level change"); return new EvaluationPressureEvent(at,t.previousLevel(),t.currentLevel(),t.observedLevel(),t.observedReasons()); }
}
