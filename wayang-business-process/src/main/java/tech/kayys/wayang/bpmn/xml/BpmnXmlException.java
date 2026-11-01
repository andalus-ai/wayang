package tech.kayys.wayang.bpmn.xml;

import java.util.List;

/** The XML is malformed or uses BPMN features Wayang does not support (never silently ignored). */
public class BpmnXmlException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient List<String> problems;

    public BpmnXmlException(String message) {
        super(message);
        this.problems = List.of(message);
    }

    public BpmnXmlException(List<String> problems) {
        super("Cannot import BPMN: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public BpmnXmlException(String message, Throwable cause) {
        super(message, cause);
        this.problems = List.of(message);
    }

    public List<String> problems() {
        return problems;
    }
}
