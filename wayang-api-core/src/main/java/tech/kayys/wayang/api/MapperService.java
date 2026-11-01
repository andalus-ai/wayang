package tech.kayys.wayang.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.eip.EipException;
import tech.kayys.wayang.eip.EipLoader;
import tech.kayys.wayang.eip.IdempotencyStore;
import tech.kayys.wayang.eip.Mapper;
import tech.kayys.wayang.eip.Message;
import tech.kayys.wayang.expression.ExpressionException;
import tech.kayys.wayang.expression.Scope;

/**
 * Side-effect-free dry runs for the Studio's mapper editor. Nothing is stored or called: {@code call} steps are
 * refused (no connectors are wired), idempotent steps use a throw-away store. Needs DEFINITION_READ.
 */
public final class MapperService {

    public static final int MAX_BODY_CHARS = 1_000_000;

    /**
     * {@code mapping}: evaluate {@code spec} with {@code vars} as variables (the service-node form: add a
     * {@code result} variable to try an outputMapping). {@code pipeline}: run {@code pipeline} on one message
     * ({@code body} may be text or data; {@code headers} optional).
     */
    public Map<String, Object> preview(Principal p, Map<String, Object> req) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.canAnywhere(Permission.DEFINITION_READ)) {
            throw ApiException.forbidden(Permission.DEFINITION_READ);
        }
        if (req == null) {
            throw ApiException.badRequest("Request body is required");
        }
        if (String.valueOf(req).length() > MAX_BODY_CHARS) {
            throw ApiException.badRequest("Request too large");
        }
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            if (req.get("pipeline") instanceof Map<?, ?> cfg) {
                Map<String, Object> c = new LinkedHashMap<>();
                cfg.forEach((k, v) -> c.put(String.valueOf(k), v));
                Map<String, Object> headers = new LinkedHashMap<>();
                if (req.get("headers") instanceof Map<?, ?> h) {
                    h.forEach((k, v) -> headers.put(String.valueOf(k), v));
                }
                var pipe = EipLoader.build(c, tech.kayys.wayang.eip.Services.scratch(null));
                List<Object> msgs = new ArrayList<>();
                pipe.run(new Message(headers, req.get("body")), Scope.empty()).forEach(m -> msgs.add(m.toMap()));
                out.put("messages", msgs);
                return out;
            }
            if (req.containsKey("spec")) {
                Map<String, Object> vars = new LinkedHashMap<>();
                if (req.get("vars") instanceof Map<?, ?> v) {
                    v.forEach((k, x) -> vars.put(String.valueOf(k), x));
                }
                boolean omit = Boolean.TRUE.equals(req.get("omitNull"));
                out.put("result", Mapper.compile(req.get("spec"), omit).apply(Scope.of(vars)));
                return out;
            }
            throw ApiException.badRequest("Give either 'spec' (with 'vars') or 'pipeline' (with 'body')");
        } catch (EipException | ExpressionException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }
}
