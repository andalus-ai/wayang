package tech.kayys.wayang.api;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.SecretResolver;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.json.Json;

/**
 * "Test this node" for the Studio: runs a connector node against sample inputs with the real connector, but only for operations that read.
 * Anything that could send, create, change or delete (a message, an email, an issue, a SOAP call...) is refused: run the process for that.
 *
 * <p>Needs DEFINITION_WRITE and RUN_START, and an explicit {@code confirm: true} in the request, because even a read is a real call that
 * uses the tenant's secrets. {@code ${secret:NAME}} is resolved in {@code config} only (never in {@code inputs}), and every secret value is
 * scrubbed from errors and output. Output is capped.
 */
public final class ConnectorTester {

    public static final int MAX_OUTPUT_CHARS = 200_000;
    private static final Pattern SECRET_REF = Pattern.compile("\\$\\{secret:([A-Za-z0-9_.-]{1,100})}");

    private final Map<String, ServiceHandler.Connector> connectors;
    private final SecretResolver secrets;
    private final RateLimiter limiter;

    public ConnectorTester(Map<String, ServiceHandler.Connector> connectors, SecretResolver secrets) {
        this(connectors, secrets, null);
    }

    /** @param limiter shared across requests (the REST layer keeps one); null for no limit */
    public ConnectorTester(Map<String, ServiceHandler.Connector> connectors, SecretResolver secrets, RateLimiter limiter) {
        this.connectors = connectors;
        this.secrets = secrets;
        this.limiter = limiter;
    }

    /** The reason a node may not be tested, or null when it only reads. */
    static String refusal(String connector, Map<String, Object> config) {
        String op = config.get("operation") instanceof String s ? s : "";
        boolean readOnly = switch (connector) {
            case "http" -> "GET".equalsIgnoreCase(String.valueOf(config.getOrDefault("method", "GET")));
            case "github" -> Set.of("getIssue", "listIssues").contains(op);
            case "notion" -> op.equals("queryDatabase");
            case "gcal" -> op.equals("list");
            case "gsheets" -> op.equals("read");
            case "trello" -> op.equals("listCards");
            case "todoist" -> op.equals("listTasks");
            case "linear" -> op.equals("listIssues");
            case "airtable" -> op.equals("listRecords");
            case "mcp" -> op.equals("listTools");
            case "soap" -> "describe".equals(config.get("mode"));
            default -> false;
        };
        return readOnly ? null : "Only operations that read can be tested here (" + connector + (op.isEmpty() ? "" : " " + op)
                + " could send or change something): run the process instead";
    }

    /** @param req {@code {connector, config, inputs?, confirm: true}} */
    public Map<String, Object> test(Principal p, Map<String, Object> req) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.canAnywhere(Permission.DEFINITION_WRITE)) {
            throw ApiException.forbidden(Permission.DEFINITION_WRITE);
        }
        if (!p.canAnywhere(Permission.RUN_START)) {
            throw ApiException.forbidden(Permission.RUN_START);
        }
        if (req == null || !(req.get("connector") instanceof String name) || !(req.get("config") instanceof Map<?, ?> cfgRaw)) {
            throw ApiException.badRequest("'connector' and 'config' are required");
        }
        if (!Boolean.TRUE.equals(req.get("confirm"))) {
            throw ApiException.badRequest("This makes a real call with your secrets: send confirm: true to go ahead");
        }
        ServiceHandler.Connector connector = connectors.get(name);
        if (connector == null) {
            throw ApiException.badRequest("Unknown connector '" + name + "'");
        }
        Map<String, Object> probe = new LinkedHashMap<>();
        cfgRaw.forEach((k, v) -> probe.put(String.valueOf(k), v));
        String refused = refusal(name, probe);
        if (refused != null) {
            throw ApiException.badRequest(refused); // refused requests make no call, so they do not use up the limit
        }
        if (limiter != null) {
            limiter.check(p);
        }
        Map<String, Object> config = new LinkedHashMap<>();
        cfgRaw.forEach((k, v) -> config.put(String.valueOf(k), v));
        String why = refusal(name, config);
        if (why != null) {
            throw ApiException.badRequest(why);
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (req.get("inputs") instanceof Map<?, ?> in) {
            in.forEach((k, v) -> inputs.put(String.valueOf(k), v));
        } else if (req.get("inputs") != null) {
            throw ApiException.badRequest("'inputs' must be an object");
        }
        if (String.valueOf(inputs).length() + String.valueOf(config).length() > 1_000_000) {
            throw ApiException.badRequest("Request too large");
        }
        Set<String> used = new LinkedHashSet<>();
        Map<String, Object> resolved;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) resolve(p.tenantId(), config, used);
            resolved = r;
        } catch (IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        try {
            Map<String, Object> out = connector.call(resolved, inputs);
            Object clean = scrub(out, used);
            String json = Json.write(clean);
            if (json.length() > MAX_OUTPUT_CHARS) {
                throw ApiException.badRequest("The answer is larger than " + MAX_OUTPUT_CHARS + " characters; narrow the request");
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("output", clean);
            return res;
        } catch (ServiceHandler.ConnectorException e) {
            throw ApiException.badRequest(redact(String.valueOf(e.getMessage()), used));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("The call failed: " + e.getClass().getSimpleName());
        }
    }

    private Object resolve(String tenant, Object v, Set<String> used) {
        if (v instanceof String s) {
            Matcher m = SECRET_REF.matcher(s);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String value = secrets == null ? null : secrets.resolve(tenant, m.group(1));
                if (value == null) {
                    throw new IllegalStateException("Secret '" + m.group(1) + "' is not set");
                }
                used.add(value);
                m.appendReplacement(out, Matcher.quoteReplacement(value));
            }
            m.appendTail(out);
            return out.toString();
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, x) -> out.put(String.valueOf(k), resolve(tenant, x, used)));
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(x -> resolve(tenant, x, used)).toList();
        }
        return v;
    }

    private static Object scrub(Object v, Set<String> used) {
        if (v instanceof String s) {
            return redact(s, used);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), scrub(x, used)));
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(x -> scrub(x, used)).toList();
        }
        return v;
    }

    private static String redact(String s, Set<String> used) {
        String out = s;
        for (String secret : used) {
            if (secret.length() >= 4) {
                out = out.replace(secret, "***");
            }
        }
        return out;
    }
}
