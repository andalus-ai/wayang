package tech.kayys.wayang.bpmn;

/** A validation finding. Errors block deployment; warnings do not. */
public record Issue(Severity severity, String elementId, String message) {
    public enum Severity {
        ERROR, WARNING
    }

    @Override
    public String toString() {
        return severity + (elementId == null ? "" : " [" + elementId + "]") + ": " + message;
    }
}
