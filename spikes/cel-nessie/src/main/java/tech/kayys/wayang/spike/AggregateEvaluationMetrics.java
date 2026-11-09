package tech.kayys.wayang.spike;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe in-process aggregate adapter; no vendor SDK or request dimensions. */
public final class AggregateEvaluationMetrics implements EvaluationObserver {
 private final LongAdder admissionGranted=new LongAdder(), admissionRejected=new LongAdder();
 private final LongAdder executionSubmitted=new LongAdder(), executionRejected=new LongAdder();
 private final LongAdder executionStarted=new LongAdder(), executionCompleted=new LongAdder(), executionFailed=new LongAdder();
 private final LongAdder callerWaitTimedOut=new LongAdder(), executionCancelled=new LongAdder(), executionDeadline=new LongAdder(), resultUnavailable=new LongAdder();
 private final LongAdder admissionDurationCount=new LongAdder(), admissionDurationTotal=new LongAdder();
 private final LongAdder executionDurationCount=new LongAdder(), executionDurationTotal=new LongAdder();
 private final AtomicLong executionDurationMax=new AtomicLong();
 @Override public void onEvent(EvaluationEvent event) {
  switch(event.type()) {
   case ADMISSION_GRANTED -> { admissionGranted.increment(); recordAdmission(event.durationNanos()); }
   case ADMISSION_REJECTED -> { admissionRejected.increment(); recordAdmission(event.durationNanos()); }
   case EXECUTION_SUBMITTED -> executionSubmitted.increment();
   case EXECUTION_REJECTED -> executionRejected.increment();
   case EXECUTION_STARTED -> executionStarted.increment();
   case EXECUTION_COMPLETED -> { executionCompleted.increment(); recordExecution(event.durationNanos()); }
   case EXECUTION_FAILED -> { executionFailed.increment(); recordExecution(event.durationNanos()); }
   case CALLER_WAIT_TIMED_OUT -> callerWaitTimedOut.increment();
   case EXECUTION_CANCELLED_BEFORE_START -> executionCancelled.increment();
   case EXECUTION_DEADLINE_BEFORE_START -> executionDeadline.increment();
   case EXECUTION_RESULT_UNAVAILABLE -> resultUnavailable.increment();
   case CALLER_INTERRUPTED -> { }
  }
 }
 public EvaluationMetricsSnapshot snapshot() {
  return new EvaluationMetricsSnapshot(admissionGranted.sum(), admissionRejected.sum(), executionSubmitted.sum(), executionRejected.sum(), executionStarted.sum(), executionCompleted.sum(), executionFailed.sum(), callerWaitTimedOut.sum(), executionCancelled.sum(), executionDeadline.sum(), resultUnavailable.sum(), admissionDurationCount.sum(), admissionDurationTotal.sum(), executionDurationCount.sum(), executionDurationTotal.sum(), executionDurationMax.get());
 }
 private void recordAdmission(long n) { admissionDurationCount.increment(); admissionDurationTotal.add(n); }
 private void recordExecution(long n) { executionDurationCount.increment(); executionDurationTotal.add(n); executionDurationMax.accumulateAndGet(n, Math::max); }
}
