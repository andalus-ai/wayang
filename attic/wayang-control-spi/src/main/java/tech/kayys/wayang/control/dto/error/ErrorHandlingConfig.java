package tech.kayys.wayang.control.dto.error;

import java.util.List;
import tech.kayys.wayang.control.dto.RetryStrategy;
import tech.kayys.wayang.control.dto.DeadLetterConfig;

/**
 * Error Handling Configuration
 */
public class ErrorHandlingConfig {
    public RetryStrategy retryStrategy;
    public DeadLetterConfig deadLetter;
    public List<ErrorHandler> handlers;
}
