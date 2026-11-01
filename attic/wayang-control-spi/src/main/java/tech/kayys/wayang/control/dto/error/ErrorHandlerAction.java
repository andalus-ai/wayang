package tech.kayys.wayang.control.dto.error;

public enum ErrorHandlerAction {
    RETRY,
    SKIP,
    COMPENSATE,
    ALERT,
    CUSTOM
}
