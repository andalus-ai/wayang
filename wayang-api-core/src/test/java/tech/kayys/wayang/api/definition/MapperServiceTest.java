package tech.kayys.wayang.api.definition;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.Role;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MapperServiceTest {

    private final MapperService svc = new MapperService();
    private final Principal viewer = new Principal("acme", "v", Set.of(Role.VIEWER));
    private final Principal nobody = new Principal("acme", "n", Set.of());

    @Test
    void previewsAServiceNodeOutputMapping() {
        var r = svc.preview(viewer, Map.of("spec", Map.of("tier", "result.data.tier"),
                "vars", Map.of("result", Map.of("data", Map.of("tier", "gold")))));
        assertEquals(Map.of("tier", "gold"), r.get("result"));
    }

    @Test
    void previewsAPipelineAndNeverCallsOut() {
        var r = svc.preview(viewer, Map.of("body", "a,b\n1,2\n", "pipeline", Map.of("steps", List.of(Map.of("type", "parse", "format", "csv")))));
        assertEquals(1, ((List<?>) r.get("messages")).size());
        var e = assertThrows(ApiException.class, () -> svc.preview(viewer, Map.of("body", 1,
                "pipeline", Map.of("steps", List.of(Map.of("type", "call", "connector", "http"))))));
        assertEquals(ApiException.Code.BAD_REQUEST, e.code());
    }

    @Test
    void mistakesAreBadRequestsNotServerErrors() {
        assertEquals(ApiException.Code.BAD_REQUEST, assertThrows(ApiException.class,
                () -> svc.preview(viewer, Map.of("spec", Map.of("a", "body.")))).code());
        assertEquals(ApiException.Code.BAD_REQUEST, assertThrows(ApiException.class, () -> svc.preview(viewer, Map.of())).code());
        assertEquals(ApiException.Code.BAD_REQUEST, assertThrows(ApiException.class,
                () -> svc.preview(viewer, Map.of("spec", Map.of("$each", "x"), "vars", Map.of("x", 1)))).code());
    }

    @Test
    void needsAuthenticationAndReadPermission() {
        assertThrows(ApiException.class, () -> svc.preview(null, Map.of("spec", "1")));
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> svc.preview(nobody, Map.of("spec", "1"))).code());
    }
}
