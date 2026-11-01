package tech.kayys.wayang.connectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code github}: issues and comments through the REST API.
 *
 * <p>Config: {@code token} (fine-grained token as {@code ${secret:NAME}}), <b>{@code allowedRepos} (required: {@code owner/name}
 * or {@code owner/*})</b>, optional {@code baseUrl} (GitHub Enterprise), {@code operation}: {@code createIssue}
 * (repo, title, body?, labels?, assignees?), {@code comment} (repo, number, body), {@code getIssue} (repo, number),
 * {@code listIssues} (repo, state?, labels?, limit?). Output: {@code number, url, title, state} (+ {@code issues} for lists).
 * Pull requests are never returned by {@code listIssues}.
 */
public final class GitHub {

    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern STATE = Pattern.compile("open|closed|all");
    private static final String DEFAULT_BASE = "https://api.github.com";

    private GitHub() {
    }

    public static final class Sender implements ServiceHandler.Connector {
        private final HttpConnector http;

        public Sender(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("github needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String token = Rest.secret(config, "token", "github", 20);
            String op = config.get("operation") instanceof String o ? o : "";
            String repo = Rest.segment(inputs.get("repo"), REPO, "repo (owner/name)");
            for (String part : repo.split("/")) {
                if (part.equals(".") || part.equals("..")) {
                    throw new ServiceHandler.ConnectorException("Invalid repo (owner/name)", false);
                }
            }
            Rest.allowed(config.get("allowedRepos"), repo, "repo");
            Map<String, String> h = Map.of("Authorization", "Bearer " + token, "Accept", "application/vnd.github+json",
                    "X-GitHub-Api-Version", "2022-11-28", "User-Agent", "wayang");
            String api = Rest.base(config, DEFAULT_BASE) + "/repos/" + repo;
            switch (op) {
                case "createIssue" -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("title", Rest.text(inputs.get("title"), "title", 256, true));
                    String text = Rest.text(inputs.get("body"), "body", 60_000, false);
                    if (text != null) {
                        body.put("body", text);
                    }
                    for (String k : List.of("labels", "assignees")) {
                        List<String> l = names(inputs.get(k), k);
                        if (!l.isEmpty()) {
                            body.put(k, l);
                        }
                    }
                    return issue(Rest.call(http, api + "/issues", "POST", h, null, body));
                }
                case "comment" -> {
                    String n = number(inputs.get("number"));
                    Map<String, Object> r = Rest.call(http, api + "/issues/" + n + "/comments", "POST", h, null,
                            Map.of("body", Rest.text(inputs.get("body"), "body", 60_000, true)));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("commentId", r.get("id"));
                    out.put("url", r.get("html_url"));
                    return out;
                }
                case "getIssue" -> {
                    return issue(Rest.call(http, api + "/issues/" + number(inputs.get("number")), "GET", h, null, null));
                }
                case "listIssues" -> {
                    Map<String, Object> q = new LinkedHashMap<>();
                    String state = inputs.get("state") instanceof String s ? s : "open";
                    if (!STATE.matcher(state).matches()) {
                        throw new ServiceHandler.ConnectorException("state must be open, closed or all", false);
                    }
                    q.put("state", state);
                    List<String> labels = names(inputs.get("labels"), "labels");
                    if (!labels.isEmpty()) {
                        q.put("labels", String.join(",", labels));
                    }
                    int limit = Rest.limit(inputs.get("limit"), 20, 50);
                    q.put("per_page", limit);
                    Map<String, Object> r = Rest.call(http, api + "/issues", "GET", h, q, null);
                    List<Map<String, Object>> out = new ArrayList<>();
                    if (r.get("items") instanceof List<?> l) {
                        for (Object o : l) {
                            if (o instanceof Map<?, ?> m && !m.containsKey("pull_request")) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> mm = (Map<String, Object>) m;
                                out.add(issue(mm));
                            }
                        }
                    }
                    return Map.of("issues", out, "count", out.size());
                }
                default -> throw new ServiceHandler.ConnectorException("github 'operation' must be createIssue, comment, getIssue or listIssues", false);
            }
        }

        private static String number(Object v) throws ServiceHandler.ConnectorException {
            if (v instanceof Number n && n.longValue() > 0 && n.longValue() < 1_000_000_000L) {
                return Long.toString(n.longValue());
            }
            if (v instanceof String s && s.matches("[0-9]{1,9}") && !s.equals("0")) {
                return s;
            }
            throw new ServiceHandler.ConnectorException("Invalid issue number", false);
        }

        private static List<String> names(Object v, String what) throws ServiceHandler.ConnectorException {
            List<String> out = new ArrayList<>();
            if (v == null) {
                return out;
            }
            if (!(v instanceof List<?> l) || l.size() > 20) {
                throw new ServiceHandler.ConnectorException(what + " must be a list of at most 20 names", false);
            }
            for (Object o : l) {
                if (!(o instanceof String s) || s.isBlank() || s.length() > 100 || s.matches(".*[\\p{Cntrl},].*")) {
                    throw new ServiceHandler.ConnectorException("Invalid entry in " + what, false);
                }
                out.add(s);
            }
            return out;
        }

        private static Map<String, Object> issue(Map<String, Object> r) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("number", r.get("number"));
            out.put("url", r.get("html_url"));
            out.put("title", r.get("title"));
            out.put("state", r.get("state"));
            return out;
        }
    }
}
