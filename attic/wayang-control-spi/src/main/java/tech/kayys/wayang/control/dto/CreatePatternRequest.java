package tech.kayys.wayang.control.dto;

import tech.kayys.wayang.control.dto.error.ErrorHandlingConfig;

public record CreatePatternRequest(
        String patternName,
        String description,
        EIPPatternType patternType,
        EndpointConfig sourceConfig,
        EndpointConfig targetConfig,
        TransformationConfig transformation,
        ErrorHandlingConfig errorHandling) {
}
