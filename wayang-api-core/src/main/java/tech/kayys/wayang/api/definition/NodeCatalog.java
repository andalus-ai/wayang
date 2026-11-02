package tech.kayys.wayang.api.definition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.bpmn.ElementType;

/**
 * The Studio palette: one entry per BPMN element type with its category,
 * connection rules and a JSON-Schema for its {@code config} (plus the shared
 * execution-policy keys where they apply). The schemas describe what the
 * runtime handlers read; they are used to render forms and to give early,
 * precise validation errors.
 */
public final class NodeCatalog {

    public record Entry(String type, String label, String category, String description, boolean waits, int maxIncoming,
            int maxOutgoing, Map<String, Object> configSchema) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("label", label);
            m.put("category", category);
            m.put("description", description);
            m.put("waits", waits);
            m.put("maxIncoming", maxIncoming);
            m.put("maxOutgoing", maxOutgoing);
            m.put("configSchema", configSchema);
            return m;
        }
    }

    private static final Map<String, Object> DURATION = Map.of("type", "string", "pattern", "^P(?!$)(\\d+D)?(T(?=\\d)(\\d+H)?(\\d+M)?(\\d+(\\.\\d+)?S)?)?$",
            "description", "ISO-8601 duration, e.g. PT30S, PT5M, P1D");
    private static final Map<String, Object> EXPRESSION = Map.of("type", "string", "description", "Expression in the Wayang expression language");

    private static final Map<String, Object> POLICY = Map.of(
            "retries", Map.of("type", "integer", "minimum", 0, "maximum", 10, "description", "Extra attempts after the first failure"),
            "retryDelay", DURATION,
            "retryMaxDelay", DURATION,
            "retryBackoff", Map.of("type", "number", "minimum", 1, "maximum", 10),
            "timeout", DURATION,
            "onError", Map.of("enum", List.of("fail", "continue"), "description", "continue turns the failure into failed=true outputs"));

    private static final List<Entry> ENTRIES = build();

    private NodeCatalog() {
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Entry entry(ElementType t) {
        return ENTRIES.stream().filter(e -> e.type().equals(t.name())).findFirst().orElseThrow();
    }

    /** Problems with a node's config against its schema, empty when fine. Unknown keys are reported. */
    public static List<String> validateConfig(ElementType t, Map<String, Object> config) {
        return JsonSchemaLite.validate(entry(t).configSchema(), config == null ? Map.of() : config);
    }

    /** Insertion-ordered map from alternating keys and values (Map.of stops at 10 pairs). */
    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static Map<String, Object> obj(List<String> required, Map<String, Object> props, boolean withPolicy) {
        Map<String, Object> all = new LinkedHashMap<>(props);
        if (withPolicy) {
            all.putAll(POLICY);
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", all);
        s.put("additionalProperties", false);
        if (!required.isEmpty()) {
            s.put("required", required);
        }
        return s;
    }

    private static List<Entry> build() {
        List<Entry> l = new ArrayList<>();
        Map<String, Object> none = obj(List.of(), Map.of(), false);
        l.add(new Entry("START_EVENT", "Start", "Events", "Where a run begins", false, 0, 1, none));
        l.add(new Entry("END_EVENT", "End", "Events", "Where a path ends", false, Integer.MAX_VALUE, 0, none));
        l.add(new Entry("EXCLUSIVE_GATEWAY", "Exclusive gateway (XOR)", "Gateways", "First matching condition wins, else the default flow", false,
                Integer.MAX_VALUE, Integer.MAX_VALUE, none));
        l.add(new Entry("PARALLEL_GATEWAY", "Parallel gateway (AND)", "Gateways", "Splits into all flows and waits for all incoming on join", false,
                Integer.MAX_VALUE, Integer.MAX_VALUE, none));
        l.add(new Entry("INCLUSIVE_GATEWAY", "Inclusive gateway (OR)", "Gateways", "Takes every flow whose condition holds", false,
                Integer.MAX_VALUE, Integer.MAX_VALUE, none));
        l.add(new Entry("SERVICE_TASK", "Service call", "Tasks", "Calls a named connector", false, Integer.MAX_VALUE, 1,
                obj(List.of("connector"), m(
                        "connector", Map.of("type", "string", "minLength", 1, "description", "Name of a registered connector, e.g. http"),
                        "inputs", Map.of("type", "object", "description", "Input name to expression (http: body, query, headers; mcp: the tool arguments)"),
                        "url", Map.of("type", "string", "description", "http: target URL"),
                        "method", Map.of("type", "string", "enum", List.of("GET", "POST", "PUT", "PATCH", "DELETE", "get", "post", "put", "patch", "delete")),
                        "headers", Map.of("type", "object", "description", "http: request headers; may use ${secret:NAME}"),
                        "query", Map.of("type", "object", "description", "http: query parameters"),
                        "timeoutMs", Map.of("type", "integer", "minimum", 100, "maximum", 60000),
                        "maxResponseBytes", Map.of("type", "integer", "minimum", 1, "maximum", 10485760),
                        "operation", Map.of("type", "string", "description", "telegram/gmail: sendMessage|send; mcp: callTool|listTools; github: createIssue|comment|getIssue|listIssues; notion: createPage|appendText|queryDatabase; gcal: list|create|delete; gsheets: read|append; airtable: createRecord|updateRecord|listRecords; twilio, resend: send; todoist: createTask|listTasks|closeTask; linear: createIssue|comment|listIssues; trello: createCard|listCards; soap: the WSDL operation name (mode: describe lists them)"),
                        "baseUrl", Map.of("type", "string", "description", "telegram/slack/whatsapp/gmail: API base URL override"),
                        "botToken", Map.of("type", "string", "description", "telegram/slack: ${secret:NAME}"),
                        "accessToken", Map.of("type", "string", "description", "whatsapp/gmail/gcal/gsheets: ${secret:NAME} (Google: ${secret:oauth.CONNECTION})"),
                        "phoneNumberId", Map.of("type", "string", "description", "whatsapp: sender phone number id"),
                        "allowedRecipients", Map.of("type", "array", "items", Map.of("type", "string"), "description", "gmail: only these addresses may be emailed"),
                        "host", Map.of("type", "string", "description", "smtp: server host name"),
                        "port", Map.of("type", "integer", "minimum", 1, "maximum", 65535, "description", "smtp: server port (default 465 tls / 587 starttls)"),
                        "security", Map.of("type", "string", "enum", List.of("tls", "starttls", "none"), "description", "smtp: none is refused when credentials are set"),
                        "username", Map.of("type", "string", "description", "smtp: account name"),
                        "password", Map.of("type", "string", "description", "smtp: ${secret:NAME}"),
                        "from", Map.of("type", "string", "description", "smtp: sender address"),
                        "fromName", Map.of("type", "string", "description", "smtp: sender display name"),
                        "webhookUrl", Map.of("type", "string", "description", "discord/teams: ${secret:NAME} holding the webhook URL"),
                        "token", Map.of("type", "string", "description", "github/notion/airtable: ${secret:NAME}"),
                        "allowedRepos", Map.of("type", "array", "items", Map.of("type", "string"), "description", "github: required, owner/name or owner/*"),
                        "allowedDatabases", Map.of("type", "array", "items", Map.of("type", "string"), "description", "notion: database ids"),
                        "allowedPages", Map.of("type", "array", "items", Map.of("type", "string"), "description", "notion: page ids for appendText"),
                        "allowedCalendars", Map.of("type", "array", "items", Map.of("type", "string"), "description", "gcal: optional calendar ids"),
                        "accountSid", Map.of("type", "string", "description", "twilio: account SID (AC...)"),
                        "authToken", Map.of("type", "string", "description", "twilio: ${secret:NAME}"),
                        "apiKey", Map.of("type", "string", "description", "resend: ${secret:NAME}"),
                        "allowedTo", Map.of("type", "array", "items", Map.of("type", "string"), "description", "twilio: required, exact E.164 numbers"),
                        "allowedRecipients", Map.of("type", "array", "items", Map.of("type", "string"), "description", "resend: required, addresses or @domain"),
                        "allowedTables", Map.of("type", "array", "items", Map.of("type", "string"), "description", "airtable: required, appXXXX/Table or appXXXX/*"),
                        "allowedProjects", Map.of("type", "array", "items", Map.of("type", "string"), "description", "todoist: required project ids"),
                        "allowedTeams", Map.of("type", "array", "items", Map.of("type", "string"), "description", "linear: required team ids"),
                        "allowedLists", Map.of("type", "array", "items", Map.of("type", "string"), "description", "trello: required list ids"),
                        "wsdl", Map.of("type", "string", "description", "soap: WSDL URL, or the WSDL text itself"),
                        "allowedOperations", Map.of("type", "array", "items", Map.of("type", "string"), "description", "soap: required, exact operation names"),
                        "endpoint", Map.of("type", "string", "description", "soap: overrides the address in the WSDL"),
                        "wsSecurity", Map.of("type", "string", "enum", List.of("usernameToken"), "description", "soap: WS-Security UsernameToken using username/password"),
                        "passwordType", Map.of("type", "string", "enum", List.of("digest", "text"), "description", "soap wsSecurity: digest (default) or text"),
                        "allowedSpreadsheets", Map.of("type", "array", "items", Map.of("type", "string"), "description", "gsheets: required spreadsheet ids"),
                        "notifyAttendees", Map.of("type", "boolean", "description", "gcal: email attendees (default false)"),
                        "tool", Map.of("type", "string", "description", "mcp: tool name on the server"),
                        "outputMap", Map.of("type", "object", "description", "mcp: variable name to path in {result,text,isError,content}"),
                        "inputMapping", Map.of("type", "object", "description", "Mapper spec producing the connector inputs (overrides 'inputs')"),
                        "outputMapping", Map.of("type", "object", "description", "Mapper spec over {process variables, result} producing the node outputs"),
                        "outputMode", Map.of("type", "string", "enum", java.util.List.of("replace", "merge")),
                        "omitNull", Map.of("type", "boolean"),
                        "failOnToolError", Map.of("type", "boolean", "description", "mcp: fail the node when the tool reports isError (default true)")), true)));
        l.add(new Entry("BUSINESS_RULE_TASK", "Business rule (DMN)", "Tasks", "Evaluates a published decision", false, Integer.MAX_VALUE, 1,
                obj(List.of("model"), Map.of(
                        "model", Map.of("type", "string", "minLength", 1, "description", "Id of a published DECISION"),
                        "inputs", Map.of("type", "object", "description", "Input name to expression; default is all variables"),
                        "decisions", Map.of("type", "array", "items", Map.of("type", "string"))), false)));
        l.add(new Entry("AGENT_TASK", "AI agent", "Tasks", "Runs an agent by id or an inline spec", false, Integer.MAX_VALUE, 1,
                obj(List.of("agent"), Map.of("agent", Map.of("type", List.of("string", "object"),
                        "description", "Id of a published AGENT, or an inline agent spec"),
                        "forget", Map.of("type", "boolean", "description", "Instead of asking the model, forget this conversation (the agent's memory for the key of this message)")), true)));
        l.add(new Entry("EIP_TASK", "Integration pipeline (EIP)", "Tasks", "Runs an inline message pipeline", false, Integer.MAX_VALUE, 1,
                obj(List.of("pipeline"), Map.of("pipeline", Map.of("type", "object"), "message", EXPRESSION), true)));
        l.add(new Entry("USER_TASK", "Human task", "Tasks", "Creates a task for a person and waits", true, Integer.MAX_VALUE, 1,
                obj(List.of(), Map.of("title", Map.of("type", "string"), "assignee", Map.of("type", "string"),
                        "form", Map.of("type", "object", "description", "Opaque form definition for the UI")), false)));
        l.add(new Entry("TIMER_EVENT", "Timer", "Events", "Waits for a duration or until a moment", true, Integer.MAX_VALUE, 1,
                obj(List.of(), Map.of("duration", DURATION,
                        "until", Map.of("type", "string", "description", "ISO-8601 date-time, e.g. 2026-12-31T09:00:00Z")), false)));
        l.add(new Entry("MESSAGE_CATCH_EVENT", "Wait for message", "Events", "Waits for a correlated message", true, Integer.MAX_VALUE, 1,
                obj(List.of("message"), Map.of("message", Map.of("type", "string", "minLength", 1),
                        "correlation", EXPRESSION), false)));
        return List.copyOf(l);
    }
}
