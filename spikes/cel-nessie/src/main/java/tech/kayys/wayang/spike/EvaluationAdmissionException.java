package tech.kayys.wayang.spike;

/** Stable, value-free failure code for host-side evaluation admission. */
public final class EvaluationAdmissionException extends RuntimeException {
    private final String code;

    public EvaluationAdmissionException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
