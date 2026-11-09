package tech.kayys.wayang.spike;

import java.util.ArrayList;
import java.util.Objects;

/** Classifies aggregate runtime pressure against host-supplied capacity policies. */
public final class EvaluationPressureClassifier {
    private EvaluationPressureClassifier() { }

    public static EvaluationPressureAssessment classify(
            EvaluationRuntimeSnapshot snapshot,
            EvaluationAdmissionPolicy admissionPolicy,
            EvaluationExecutionPolicy executionPolicy,
            EvaluationPressurePolicy pressurePolicy) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(admissionPolicy, "admissionPolicy");
        Objects.requireNonNull(executionPolicy, "executionPolicy");
        Objects.requireNonNull(pressurePolicy, "pressurePolicy");

        var reasons = new ArrayList<EvaluationPressureAssessment.Reason>();
        boolean[] severity = {false, false};
        assess(snapshot.admittedActive(), admissionPolicy.maxConcurrent(),
                pressurePolicy, EvaluationPressureAssessment.Reason.ADMISSION_ACTIVE_ELEVATED,
                EvaluationPressureAssessment.Reason.ADMISSION_ACTIVE_SATURATED, reasons, severity);
        assess(snapshot.admissionQueued(), admissionPolicy.maxQueued(),
                pressurePolicy, EvaluationPressureAssessment.Reason.ADMISSION_QUEUE_ELEVATED,
                EvaluationPressureAssessment.Reason.ADMISSION_QUEUE_SATURATED, reasons, severity);
        assess(snapshot.activeWorkers(), executionPolicy.maxWorkers(),
                pressurePolicy, EvaluationPressureAssessment.Reason.PROVIDER_WORKERS_ELEVATED,
                EvaluationPressureAssessment.Reason.PROVIDER_WORKERS_SATURATED, reasons, severity);
        assess(snapshot.queuedProviderTasks(), executionPolicy.maxQueuedTasks(),
                pressurePolicy, EvaluationPressureAssessment.Reason.PROVIDER_QUEUE_ELEVATED,
                EvaluationPressureAssessment.Reason.PROVIDER_QUEUE_SATURATED, reasons, severity);

        if (snapshot.admissionClosed()) reasons.add(EvaluationPressureAssessment.Reason.ADMISSION_CLOSED);
        if (snapshot.executorShutdown()) reasons.add(EvaluationPressureAssessment.Reason.EXECUTOR_SHUTDOWN);

        EvaluationPressureAssessment.Level level = severity[1]
                ? EvaluationPressureAssessment.Level.SATURATED
                : severity[0] ? EvaluationPressureAssessment.Level.ELEVATED
                : EvaluationPressureAssessment.Level.NORMAL;
        return new EvaluationPressureAssessment(level, reasons);
    }

    private static void assess(int current, int capacity, EvaluationPressurePolicy policy,
            EvaluationPressureAssessment.Reason elevatedReason,
            EvaluationPressureAssessment.Reason saturatedReason,
            ArrayList<EvaluationPressureAssessment.Reason> reasons, boolean[] severity) {
        if (current == 0) return;
        // A zero-capacity queue is healthy while empty and saturated if work is observed in it.
        int utilization = capacity == 0 ? 10_000
                : (int) Math.min(10_000L, ((long) current * 10_000L) / capacity);
        if (utilization >= policy.saturatedAtBasisPoints() || current > capacity) {
            reasons.add(saturatedReason);
            severity[1] = true;
        } else if (utilization >= policy.elevatedAtBasisPoints()) {
            reasons.add(elevatedReason);
            severity[0] = true;
        }
    }
}
