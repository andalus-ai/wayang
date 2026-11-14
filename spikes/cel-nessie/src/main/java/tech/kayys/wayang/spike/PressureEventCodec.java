package tech.kayys.wayang.spike;

/**
 * Host-supplied, versioned codec for the immutable event payload.
 *
 * <p>Implementations must be deterministic: encoding the same event must
 * produce identical bytes. Persist a codec/schema version in the implementation
 * or payload so deployments can decode events written by older versions.</p>
 */
public interface PressureEventCodec {
    byte[] encode(EvaluationPressureEvent event);

    EvaluationPressureEvent decode(byte[] payload);
}
