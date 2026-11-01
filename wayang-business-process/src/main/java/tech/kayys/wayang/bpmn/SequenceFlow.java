package tech.kayys.wayang.bpmn;

/**
 * A directed connection. {@code condition} is an expression (null = unconditional);
 * {@code isDefault} marks the fallback of an exclusive/inclusive gateway.
 */
public record SequenceFlow(String id, String source, String target, String condition, boolean isDefault) {
}
