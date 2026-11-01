package tech.kayys.wayang.control.dto;

import java.util.Map;

public class DeadLetterConfig {
    public boolean enabled;
    public String destination;
    public Map<String, Object> config;
}
