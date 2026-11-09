package tech.kayys.wayang.spike;

/** Raised when a durable expression artifact cannot be used by the requested runtime. */
public final class IncompatibleExpressionArtifactException extends IllegalStateException {

    private final String field;

    public IncompatibleExpressionArtifactException(String field, String artifactValue, String runtimeValue) {
        super("Expression artifact is incompatible at '" + field + "'");
        this.field = field;
    }

    public String field() {
        return field;
    }
}
