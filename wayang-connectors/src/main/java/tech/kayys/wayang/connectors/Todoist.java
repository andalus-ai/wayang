package tech.kayys.wayang.connectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code todoist}: tasks in allowed projects.
 *
 * <p>Config: {@code token} (as {@code ${secret:NAME}}), {@code operation}, <b>{@code allowedProjects} (required, project ids)</b>,
 * optional {@code baseUrl}. {@code createTask} (projectId, content, description?, dueString?, priority? 1..4),
 * {@code listTasks} (projectId, limit?), {@code closeTask} (taskId; the task is looked up first and must belong to an allowed project).
 */
public final class Todoist {

    private static final Pattern ID = Pattern.compile("[0-9A-Za-z]{1,30}");
    private static final String DEFAULT_BASE = "https://api.todoist.com/rest/v2";

    private Todoist() {
    }

    public static final class Sender implements ServiceHandler.Connector {
        private final HttpConnector http;

        public Sender(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("todoist needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            Map<String, String> h = Map.of("Authorization", "Bearer " + Rest.secret(config, "token", "todoist", 16));
            String base = Rest.base(config, DEFAULT_BASE);
            String op = config.get("operation") instanceof String o ? o : "";
            switch (op) {
                case "createTask" -> {
                    String project = Rest.segment(inputs.get("projectId"), ID, "projectId");
                    Rest.allowed(config.get("allowedProjects"), project, "project");
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("project_id", project);
                    body.put("content", Rest.text(inputs.get("content"), "content", 500, true));
                    String d = Rest.text(inputs.get("description"), "description", 16_000, false);
                    if (d != null) {
                        body.put("description", d);
                    }
                    String due = Rest.text(inputs.get("dueString"), "dueString", 100, false);
                    if (due != null) {
                        body.put("due_string", due);
                    }
                    if (inputs.get("priority") != null) {
                        if (!(inputs.get("priority") instanceof Number n) || n.intValue() < 1 || n.intValue() > 4) {
                            throw new ServiceHandler.ConnectorException("priority must be 1..4", false);
                        }
                        body.put("priority", n(inputs.get("priority")));
                    }
                    Map<String, Object> r = Rest.call(http, base + "/tasks", "POST", h, null, body);
                    return Map.of("id", String.valueOf(r.get("id")), "url", String.valueOf(r.get("url")));
                }
                case "listTasks" -> {
                    String project = Rest.segment(inputs.get("projectId"), ID, "projectId");
                    Rest.allowed(config.get("allowedProjects"), project, "project");
                    Map<String, Object> r = Rest.call(http, base + "/tasks", "GET", h, Map.of("project_id", project), null);
                    int limit = Rest.limit(inputs.get("limit"), 50, 200);
                    List<Map<String, Object>> out = new ArrayList<>();
                    if (r.get("items") instanceof List<?> l) {
                        for (Object o : l) {
                            if (o instanceof Map<?, ?> t && out.size() < limit) {
                                Map<String, Object> e = new LinkedHashMap<>();
                                e.put("id", t.get("id"));
                                e.put("content", t.get("content"));
                                e.put("priority", t.get("priority"));
                                e.put("due", t.get("due") instanceof Map<?, ?> due ? due.get("string") : null);
                                out.add(e);
                            }
                        }
                    }
                    return Map.of("tasks", out, "count", out.size());
                }
                case "closeTask" -> {
                    String id = Rest.segment(inputs.get("taskId"), ID, "taskId");
                    Map<String, Object> t = Rest.call(http, base + "/tasks/" + id, "GET", h, null, null);
                    Rest.allowed(config.get("allowedProjects"), String.valueOf(t.get("project_id")), "project");
                    Rest.call(http, base + "/tasks/" + id + "/close", "POST", h, null, null);
                    return Map.of("closed", true);
                }
                default -> throw new ServiceHandler.ConnectorException("todoist 'operation' must be createTask, listTasks or closeTask", false);
            }
        }

        private static int n(Object o) {
            return ((Number) o).intValue();
        }
    }
}
