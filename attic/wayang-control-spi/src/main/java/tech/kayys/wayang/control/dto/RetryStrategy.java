package tech.kayys.wayang.control.dto;

public class RetryStrategy {
    public int maxAttempts;
    public long initialDelay;
    public double backoffMultiplier;
    public long maxDelay;
}