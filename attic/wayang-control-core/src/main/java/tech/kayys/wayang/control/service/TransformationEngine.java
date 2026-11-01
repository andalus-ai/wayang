package tech.kayys.wayang.control.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.wayang.control.dto.TransformationConfig;
import tech.kayys.wayang.control.dto.TransformationStep;

import java.util.Map;

/**
 * Payload transformation engine
 */
@ApplicationScoped
public class TransformationEngine {

    private static final Logger LOG = LoggerFactory.getLogger(TransformationEngine.class);

    public Uni<Object> transform(
            Map<String, Object> payload,
            TransformationConfig config) {
        LOG.debug("Transform payload: {}", payload);
        LOG.debug("Transformation config: {}", config);
        if (config == null || config.steps() == null || config.steps().isEmpty()) {
            return Uni.createFrom().item(payload);
        }

        Object result = payload;

        for (TransformationStep step : config.steps()) {
            result = applyTransformation(result, step);
        }

        return Uni.createFrom().item(result);
    }

    private Object applyTransformation(Object data, TransformationStep step) {
        return switch (step.type) {
            case MAP -> applyMapping(data, step.expression);
            case FILTER -> applyFilter(data, step.expression);
            case ENRICH -> applyEnrichment(data, step.config);
            default -> data;
        };
    }

    private Object applyMapping(Object data, String expression) {
        // Simple mapping logic
        return data;
    }

    private Object applyFilter(Object data, String expression) {
        // Filter logic
        return data;
    }

    private Object applyEnrichment(Object data, Map<String, Object> config) {
        // Enrichment logic
        return data;
    }
}
