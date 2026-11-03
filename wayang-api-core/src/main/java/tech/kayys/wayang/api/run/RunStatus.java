package tech.kayys.wayang.api.run;

public enum RunStatus {
    RUNNING, WAITING, COMPLETED, FAILED, CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
