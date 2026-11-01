package tech.kayys.wayang.rest;

import java.util.Map;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import tech.kayys.wayang.api.ApiException;

/** Uniform error body: {@code {"code","message","details"}}. */
@Provider
public class ApiExceptionMapper implements ExceptionMapper<ApiException> {

    @Override
    public Response toResponse(ApiException e) {
        return Response.status(e.code().httpStatus()).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("code", e.code().name(), "message", String.valueOf(e.getMessage()), "details", e.details()))
                .build();
    }
}
