package tech.kayys.wayang.spike;
/** Optional host integration for committed pressure transitions. */
@FunctionalInterface public interface EvaluationPressureEventListener {
 EvaluationPressureEventListener NOOP=event->{};
 /** Listener failures are isolated by the tracker. */ void onPressureTransition(EvaluationPressureEvent event);
}
