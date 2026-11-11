package tech.kayys.wayang.spike;

/** Consumer callback for one claimed durable pressure event. */
@FunctionalInterface
public interface PressureEventConsumer {
    /**
     * Complete the consumer side effect before returning. Implementations
     * should use envelope.eventId() as an idempotency key.
     *
     * @throws RuntimeException when delivery should be retried
     */
    void deliver(PressureEventEnvelope envelope);
}
