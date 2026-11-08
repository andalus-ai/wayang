package tech.kayys.wayang.spike;

/**
 * Explicit utilization thresholds, expressed in basis points (10_000 = 100%).
 * Thresholds apply independently to each configured active/queue capacity.
 */
public record EvaluationPressurePolicy(int elevatedAtBasisPoints, int saturatedAtBasisPoints) {
    public EvaluationPressurePolicy {
        if (elevatedAtBasisPoints < 1 || saturatedAtBasisPoints > 10_000
                || saturatedAtBasisPoints < 1
                || elevatedAtBasisPoints >= saturatedAtBasisPoints) {
            throw new IllegalArgumentException("Pressure thresholds must satisfy 1 <= elevated < saturated <= 10000");
        }
    }

    public static EvaluationPressurePolicy conservativeDefaults() {
        return new EvaluationPressurePolicy(7_000, 9_000);
    }
}
