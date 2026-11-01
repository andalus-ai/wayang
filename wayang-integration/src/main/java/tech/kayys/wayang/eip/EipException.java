package tech.kayys.wayang.eip;

public class EipException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final boolean permanent;

    public EipException(String m) {
        this(m, null, false);
    }

    public EipException(String m, Throwable c) {
        this(m, c, c instanceof Invoker.Permanent);
    }

    public EipException(String m, Throwable c, boolean permanent) {
        super(m, c);
        this.permanent = permanent;
    }

    /** True when trying again cannot help (bad credentials, validation, a 4xx). Retry steps give up at once. */
    public boolean permanent() {
        return permanent;
    }
}
