package tech.kayys.wayang.agent;

public class AgentSpecException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AgentSpecException(String m) {
        super(m);
    }

    public AgentSpecException(String m, Throwable c) {
        super(m, c);
    }
}
