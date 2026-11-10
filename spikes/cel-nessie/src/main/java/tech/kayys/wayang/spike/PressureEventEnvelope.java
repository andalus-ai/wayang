package tech.kayys.wayang.spike;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity and ordering metadata around an immutable pressure transition.
 * Persist and retry this same envelope; do not regenerate its eventId on retry.
 */
public record PressureEventEnvelope(
        UUID eventId,
        String streamId,
        long sequence,
        Instant recordedAt,
        EvaluationPressureEvent event) {
    public PressureEventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        streamId = requireText(streamId, "streamId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(event, "event");
    }

    public static PressureEventEnvelope create(
            String streamId, long sequence, Instant recordedAt, EvaluationPressureEvent event) {
        return new PressureEventEnvelope(UUID.randomUUID(), streamId, sequence, recordedAt, event);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be non-blank and trimmed");
        }
        return value;
    }
}
