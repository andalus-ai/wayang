package tech.kayys.wayang.connectors;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code a2a}: talks to another agent over the Agent2Agent protocol (JSON-RPC 2.0 over HTTP).
 *
 * <p>Config: {@code url} (the agent's JSON-RPC endpoint), {@code headers} (secrets as {@code ${secret:NAME}}), {@code operation}:
 * {@code send} (default; input {@code text}, optional {@code contextId} / {@code taskId} to continue a conversation or task),
 * {@code getTask} / {@code cancelTask} (input {@code taskId}), {@code agentCard} (GET of {@code cardUrl}, default
 * {@code <origin>/.well-known/agent-card.json}, then {@code agent.json}). {@code blocking} (default true) asks the agent to answer when done;
 * {@code failOnTaskFailure} (default true) fails the node when the task ends {@code failed}, {@code rejected} or {@code canceled}.
 *
 * <p>Output of send/getTask: {@code text} (the agent's words, at most 20,000 characters), {@code state}, {@code taskId}, {@code contextId},
 * {@code result} (raw). What another agent says is untrusted input, like any message. Streaming ({@code message/stream}) and push
 * notifications are not supported.
 */
public final class A2a {

    private static final int MAX_TEXT = 20_000;
    private static final java.util.Set<String> FAILED = java.util.Set.of("failed", "rejected", "canceled", "cancelled");

    private A2a() {
    }

    public static final class Client implements ServiceHandler.Connector {
        private final HttpConnector http;

        public Client(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("a2a needs node configuration (url)", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String url = Proto.url(config, "a2a");
            String op = String.valueOf(config.getOrDefault("operation", "send"));
            Map<String, Object> in = inputs == null ? Map.of() : inputs;
            switch (op) {
                case "agentCard" -> {
                    return card(config, url);
                }
                case "send" -> {
                    if (!(in.get("text") instanceof String text) || text.isBlank() || text.length() > 100_000) {
                        throw new ServiceHandler.ConnectorException("a2a send needs 'text' (1 to 100000 characters)", false);
                    }
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("kind", "message");
                    msg.put("role", "user");
                    msg.put("messageId", UUID.randomUUID().toString());
                    msg.put("parts", List.of(Map.of("kind", "text", "text", text)));
                    if (in.get("contextId") instanceof String c && !c.isBlank()) {
                        msg.put("contextId", id(c, "contextId"));
                    }
                    if (in.get("taskId") instanceof String t && !t.isBlank()) {
                        msg.put("taskId", id(t, "taskId"));
                    }
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("message", msg);
                    params.put("configuration", Map.of("blocking", !Boolean.FALSE.equals(config.get("blocking"))));
                    return output(config, rpc(config, url, "message/send", params));
                }
                case "getTask", "cancelTask" -> {
                    String tid = id(in.get("taskId"), "taskId");
                    return output(config, rpc(config, url, op.equals("getTask") ? "tasks/get" : "tasks/cancel", Map.of("id", tid)));
                }
                default -> throw new ServiceHandler.ConnectorException("Unsupported a2a operation '" + op + "'", false);
            }
        }

        private static String id(Object v, String what) throws ServiceHandler.ConnectorException {
            if (!(v instanceof String s) || !s.matches("[A-Za-z0-9._:~-]{1,200}")) {
                throw new ServiceHandler.ConnectorException("a2a " + what + " is missing or not valid", false);
            }
            return s;
        }

        private Object rpc(Map<String, Object> config, String url, String method, Map<String, Object> params) throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("id", UUID.randomUUID().toString());
            body.put("method", method);
            body.put("params", params);
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("url", url);
            cfg.put("method", "POST");
            cfg.put("timeoutMs", config.getOrDefault("timeoutMs", 60_000));
            Map<String, String> h = Proto.headers(config);
            h.put("Accept", "application/json");
            cfg.put("headers", h);
            Map<String, Object> res = http.call(cfg, Map.of("body", body));
            if (!(res.get("body") instanceof Map<?, ?> r)) {
                throw new ServiceHandler.ConnectorException("The agent did not answer with JSON-RPC", false);
            }
            if (r.get("error") instanceof Map<?, ?> e) {
                throw new ServiceHandler.ConnectorException("Agent error " + Proto.clip(e.get("code"), 20) + ": " + Proto.clip(e.get("message"), 300), false);
            }
            if (r.get("result") == null) {
                throw new ServiceHandler.ConnectorException("The agent answered without a result", false);
            }
            return r.get("result");
        }

        private Map<String, Object> output(Map<String, Object> config, Object result) throws ServiceHandler.ConnectorException {
            Map<String, Object> out = new LinkedHashMap<>();
            Map<?, ?> r = result instanceof Map<?, ?> m ? m : Map.of();
            String state = null;
            List<String> texts = new ArrayList<>();
            if ("message".equals(r.get("kind")) || (r.get("parts") != null && r.get("status") == null)) {
                collect(r.get("parts"), texts);
                state = "completed";
            } else {
                if (r.get("status") instanceof Map<?, ?> s) {
                    state = s.get("state") instanceof String st ? st : null;
                    if (s.get("message") instanceof Map<?, ?> sm) {
                        collect(sm.get("parts"), texts);
                    }
                }
                if (r.get("artifacts") instanceof List<?> arts) {
                    List<String> at = new ArrayList<>();
                    for (Object a : arts) {
                        if (a instanceof Map<?, ?> am) {
                            collect(am.get("parts"), at);
                        }
                    }
                    if (!at.isEmpty()) {
                        texts = at; // the artifacts are the answer; the status message is only a note
                    }
                }
            }
            String text = Proto.clip(String.join("\n", texts), MAX_TEXT);
            if (state != null && FAILED.contains(state) && !Boolean.FALSE.equals(config.get("failOnTaskFailure"))) {
                throw new ServiceHandler.ConnectorException("The agent's task ended " + state + (text.isEmpty() ? "" : ": " + Proto.clip(text, 300)), false);
            }
            out.put("text", text);
            out.put("state", state == null ? "" : state);
            out.put("taskId", r.get("kind") instanceof String k && k.equals("task") && r.get("id") != null ? String.valueOf(r.get("id"))
                    : r.get("taskId") != null ? String.valueOf(r.get("taskId")) : "");
            out.put("contextId", r.get("contextId") != null ? String.valueOf(r.get("contextId")) : "");
            out.put("result", result);
            Proto.outputMap(config, out, out);
            return out;
        }

        private static void collect(Object parts, List<String> into) {
            if (parts instanceof List<?> l) {
                for (Object p : l) {
                    if (p instanceof Map<?, ?> pm && ("text".equals(pm.get("kind")) || pm.get("kind") == null) && pm.get("text") instanceof String t && !t.isBlank()) {
                        into.add(t);
                    }
                }
            }
        }

        private Map<String, Object> card(Map<String, Object> config, String url) throws Exception {
            List<String> candidates = new ArrayList<>();
            if (config.get("cardUrl") instanceof String cu && !cu.isBlank()) {
                candidates.add(cu);
            } else {
                URI u = URI.create(url);
                String origin = u.getScheme() + "://" + u.getRawAuthority();
                candidates.add(origin + "/.well-known/agent-card.json");
                candidates.add(origin + "/.well-known/agent.json");
            }
            ServiceHandler.ConnectorException last = null;
            for (String c : candidates) {
                Map<String, Object> cfg = new LinkedHashMap<>();
                cfg.put("url", c);
                cfg.put("timeoutMs", config.getOrDefault("timeoutMs", 20_000));
                cfg.put("headers", Proto.headers(config));
                try {
                    Map<String, Object> res = http.call(cfg, Map.of());
                    if (res.get("body") instanceof Map<?, ?> card) {
                        List<Object> skills = new ArrayList<>();
                        if (card.get("skills") instanceof List<?> sl) {
                            for (Object s : sl) {
                                skills.add(s instanceof Map<?, ?> sm && sm.get("name") != null ? sm.get("name") : s);
                            }
                        }
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("name", card.get("name") == null ? "" : String.valueOf(card.get("name")));
                        out.put("description", card.get("description") == null ? "" : Proto.clip(card.get("description"), 2000));
                        out.put("skills", skills);
                        out.put("card", card);
                        return out;
                    }
                } catch (ServiceHandler.ConnectorException e) {
                    last = e;
                }
            }
            throw last != null ? last : new ServiceHandler.ConnectorException("No agent card found", false);
        }
    }
}
