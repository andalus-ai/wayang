package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class AggregateEvaluationMetricsTest {
 @Test void aggregatesLifecycleAndDurationWithoutRequestDimensions() {
  var metrics=new AggregateEvaluationMetrics();
  metrics.onEvent(new EvaluationEvent(EvaluationEvent.Type.ADMISSION_GRANTED,12,null));
  metrics.onEvent(new EvaluationEvent(EvaluationEvent.Type.ADMISSION_REJECTED,8,"QUEUE_FULL"));
  metrics.onEvent(new EvaluationEvent(EvaluationEvent.Type.EXECUTION_STARTED,0,null));
  metrics.onEvent(new EvaluationEvent(EvaluationEvent.Type.EXECUTION_COMPLETED,42,null));
  metrics.onEvent(new EvaluationEvent(EvaluationEvent.Type.EXECUTION_FAILED,30,"PROVIDER_FAILURE"));
  metrics.onEvent(new EvaluationEvent(EvaluationEvent.Type.CALLER_WAIT_TIMED_OUT,5,null));
  var s=metrics.snapshot();
  assertEquals(1,s.admissionGranted());
  assertEquals(1,s.admissionRejected());
  assertEquals(1,s.executionStarted());
  assertEquals(1,s.executionCompleted());
  assertEquals(1,s.executionFailed());
  assertEquals(1,s.callerWaitTimedOut());
  assertEquals(2,s.admissionDurationCount());
  assertEquals(20,s.admissionDurationTotalNanos());
  assertEquals(2,s.executionDurationCount());
  assertEquals(72,s.executionDurationTotalNanos());
  assertEquals(42,s.executionDurationMaxNanos());
 }

 @Test void supportsConcurrentEventUpdates() throws Exception {
  var metrics=new AggregateEvaluationMetrics();
  int threads=6, each=1000;
  var pool=Executors.newFixedThreadPool(threads);
  var start=new CountDownLatch(1);
  try {
   var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
   for(int t=0;t<threads;t++) tasks.add(pool.submit(()->{
    start.await();
    for(int i=0;i<each;i++) metrics.onEvent(new EvaluationEvent(EvaluationEvent.Type.EXECUTION_COMPLETED,3,null));
    return null;
   }));
   start.countDown();
   for(var task:tasks) task.get();
   var s=metrics.snapshot();
   assertEquals((long)threads*each,s.executionCompleted());
   assertEquals((long)threads*each,s.executionDurationCount());
   assertEquals((long)threads*each*3,s.executionDurationTotalNanos());
   assertEquals(3,s.executionDurationMaxNanos());
  } finally { pool.shutdownNow(); }
 }
}
