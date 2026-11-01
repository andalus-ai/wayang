package tech.kayys.wayang.control.dto;

import java.util.Map;

public class TransformationStep {
    public String name;
    public TransformationType type;
    public String expression;
    public Map<String, Object> config;
}
