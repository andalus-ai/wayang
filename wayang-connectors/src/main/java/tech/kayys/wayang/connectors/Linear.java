package tech.kayys.wayang.connectors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code linear}: issues through the GraphQL API. Values always travel as GraphQL variables, never spliced into the query.
 *
 * <p>Config: {@code token} (API key as {@code ${secret:NAME}}), {@code operation}, <b>{@code allowedTeams} (required, team ids)</b>,
 * optional {@code baseUrl}. {@code createIssue} (teamId, title, description?, priority? 0..4), {@code comment} (issueId, body; the
 * issue's team is looked up and must be allowed), {@code listIssues} (teamId, limit?).
 */
public final class Linear {

    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final String DEFAULT_BASE = "https://api.linear.app";

    private Linear() {
    }

    public static final class Sender implements ServiceHandler.Connector {
        private final HttpConnector http;

        public Sender(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("linear needs node configuration", false);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            Map<String, String> h = Map.of("Authorization", Rest.secret(config, "token", "linear", 16));
            String url = Rest.base(config, DEFAULT_BASE) + "/graphql";
            String op = config.get("operation") instanceof String o ? o : "";
            switch (op) {
                case "createIssue" -> {
                    String team = Rest.segment(inputs.get("teamId"), UUID, "teamId");
                    Rest.allowed(config.get("allowedTeams"), team, "team");
                    Map<String, Object> in = new LinkedHashMap<>();
                    in.put("teamId", team);
                    in.put("title", Rest.text(inputs.get("title"), "title", 500, true));
                    String d = Rest.text(inputs.get("description"), "description", 50_000, false);
                    if (d != null) {
                        in.put("description", d);
                    }
                    if (inputs.get("priority") != null) {
                        if (!(inputs.get("priority") instanceof Number n) || n.intValue() < 0 || n.intValue() > 4) {
                            throw new ServiceHandler.ConnectorException("priority must be 0..4", false);
                        }
                        in.put("priority", ((Number) inputs.get("priority")).intValue());
                    }
                    Map<String, Object> r = gql(url, h, "mutation($i: IssueCreateInput!){issueCreate(input:$i){success issue{id identifier url}}}", Map.of("i", in));
                    Map<String, Object> c = (Map<String, Object>) ((Map<String, Object>) r.get("issueCreate")).get("issue");
                    if (c == null) {
                        throw new ServiceHandler.ConnectorException("linear did not create the issue", false);
                    }
                    return Map.of("id", String.valueOf(c.get("id")), "identifier", String.valueOf(c.get("identifier")), "url", String.valueOf(c.get("url")));
                }
                case "comment" -> {
                    String issue = Rest.segment(inputs.get("issueId"), UUID, "issueId");
                    Map<String, Object> q = gql(url, h, "query($id: String!){issue(id:$id){team{id}}}", Map.of("id", issue));
                    Object team = q.get("issue") instanceof Map<?, ?> i && i.get("team") instanceof Map<?, ?> t ? t.get("id") : null;
                    Rest.allowed(config.get("allowedTeams"), String.valueOf(team), "team");
                    gql(url, h, "mutation($i: CommentCreateInput!){commentCreate(input:$i){success}}",
                            Map.of("i", Map.of("issueId", issue, "body", Rest.text(inputs.get("body"), "body", 50_000, true))));
                    return Map.of("commented", true);
                }
                case "listIssues" -> {
                    String team = Rest.segment(inputs.get("teamId"), UUID, "teamId");
                    Rest.allowed(config.get("allowedTeams"), team, "team");
                    Map<String, Object> r = gql(url, h, "query($id: ID!, $n: Int!){issues(filter:{team:{id:{eq:$id}}}, first:$n){nodes{id identifier title url}}}",
                            Map.of("id", team, "n", Rest.limit(inputs.get("limit"), 20, 50)));
                    List<Object> nodes = ((Map<String, Object>) r.get("issues")).get("nodes") instanceof List<?> l ? (List<Object>) l : List.of();
                    return Map.of("issues", nodes, "count", nodes.size());
                }
                default -> throw new ServiceHandler.ConnectorException("linear 'operation' must be createIssue, comment or listIssues", false);
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> gql(String url, Map<String, String> h, String query, Map<String, Object> vars) throws Exception {
            Map<String, Object> r = Rest.call(http, url, "POST", h, null, Map.of("query", query, "variables", vars));
            if (r.get("errors") != null) {
                throw new ServiceHandler.ConnectorException("linear returned an error", false);
            }
            return r.get("data") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
        }
    }
}
