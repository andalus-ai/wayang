package tech.kayys.wayang.api.testkit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.mcp.McpServerDef;
import tech.kayys.wayang.api.mcp.McpStore;
import tech.kayys.wayang.mcp.McpTool;

/** Behavioural contract for {@link McpStore} implementations (pass a factory returning an empty store). */
public final class McpStoreContract {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private McpStoreContract() {
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("McpStore contract violated: " + what);
        }
    }

    private static McpServerDef def(String tenant, String id, String title) {
        McpTool tool = new McpTool("getPet", "Get a pet\n\nCalls GET /pets/{petId}",
                Map.of("type", "object", "properties", Map.of("petId", Map.of("type", "string")), "required", List.of("petId"), "additionalProperties", false),
                null, new McpTool.Hints(true, false, true),
                new McpTool.Binding("GET", "/pets/{petId}", List.of(new McpTool.Param("petId", "petId", "path", true)), null, null));
        return new McpServerDef(tenant, id, title, "1", "https://x.example/openapi.json", "https://x.example/v1",
                Map.of("Authorization", "Bearer ${secret:PETS}"), Map.of(), Map.of("methods", List.of("GET", "POST"), "maxTools", 10), List.of(tool),
                List.of("a warning"), List.of(Map.of("name", "bearer", "type", "http")), "ann", T, T);
    }

    public static void verify(Supplier<McpStore> factory) {
        McpStore s = factory.get();
        check(s.get("a", "pets").isEmpty(), "unknown server");
        s.create(def("a", "pets", "Pets"));
        s.create(def("a", "other", "Other"));
        s.create(def("b", "pets", "B Pets"));
        try {
            s.create(def("a", "pets", "dup"));
            check(false, "duplicate id must be CONFLICT");
        } catch (ApiException e) {
            check(e.code() == ApiException.Code.CONFLICT, "duplicate id must be CONFLICT");
        }
        McpServerDef got = s.get("a", "pets").orElseThrow();
        check(got.title().equals("Pets") && got.baseUrl().equals("https://x.example/v1"), "fields round-trip");
        check(got.headers().get("Authorization").equals("Bearer ${secret:PETS}"), "header templates round-trip unresolved");
        check(got.tools().size() == 1 && got.tools().get(0).binding().equals(def("a", "pets", "Pets").tools().get(0).binding()), "tool binding round-trips");
        check(got.tools().get(0).binding().params().get(0).required() && got.tools().get(0).hints().readOnly(), "params and hints round-trip");
        check(got.tools().get(0).description().contains("\n\n"), "descriptions keep their newlines");
        check(got.createdAt().equals(T) && got.options().get("methods").equals(List.of("GET", "POST")), "dates and options round-trip");
        check(s.list("a").size() == 2 && s.list("b").size() == 1 && s.list("c").isEmpty(), "list is tenant scoped");
        check(s.get("b", "other").isEmpty(), "tenant isolation on get");

        s.update(new McpServerDef("a", "pets", "Renamed", "2", got.sourceUrl(), got.baseUrl(), got.headers(), got.query(), got.options(), got.tools(),
                got.warnings(), got.security(), got.createdBy(), got.createdAt(), T.plusSeconds(5)));
        check(s.get("a", "pets").orElseThrow().title().equals("Renamed"), "update");
        check(s.get("b", "pets").orElseThrow().title().equals("B Pets"), "update does not leak across tenants");
        check(s.delete("a", "other") && !s.delete("a", "other"), "delete once");
        check(s.get("a", "pets").isPresent() && s.get("b", "pets").isPresent(), "delete is scoped");
    }
}
