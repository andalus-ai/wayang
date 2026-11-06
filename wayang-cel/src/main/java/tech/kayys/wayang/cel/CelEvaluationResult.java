package tech.kayys.wayang.cel;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Successful null is represented by CelValue.NullValue, never Java null. */
public record CelEvaluationResult(CelEvaluationStatus status, CelValue value,
        List<String> diagnosticCodes, long durationNanos) {
    public CelEvaluationResult {
        Objects.requireNonNull(status, "status");
        diagnosticCodes = List.copyOf(diagnosticCodes);
        if (durationNanos < 0) throw new IllegalArgumentException("durationNanos must not be negative");
        if (status == CelEvaluationStatus.SUCCESS && value == null)
            throw new IllegalArgumentException("SUCCESS requires an explicit CelValue");
        if (status != CelEvaluationStatus.SUCCESS && value != null)
            throw new IllegalArgumentException("Failed evaluation cannot expose a value");
    }
    public Optional<CelValue> valueOptional() { return Optional.ofNullable(value); }
    public static CelEvaluationResult success(CelValue value, long durationNanos) {
        return new CelEvaluationResult(CelEvaluationStatus.SUCCESS, Objects.requireNonNull(value), List.of(), durationNanos);
    }
    public static CelEvaluationResult failure(CelEvaluationStatus status, List<String> diagnostics, long durationNanos) {
        if (status == CelEvaluationStatus.SUCCESS) throw new IllegalArgumentException("Use success() for successful results");
        return new CelEvaluationResult(status, null, diagnostics, durationNanos);
    }
}
