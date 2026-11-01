package tech.kayys.wayang.control.dto.error;

import java.util.Map;

public class ErrorHandler {
    public String errorType;
    public ErrorHandlerAction action;
    public Map<String, Object> config;
}
