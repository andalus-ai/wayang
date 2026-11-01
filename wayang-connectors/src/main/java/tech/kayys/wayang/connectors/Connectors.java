package tech.kayys.wayang.connectors;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.runtime.ServiceHandler;

/** The connector set a deployment gets out of the box. */
public final class Connectors {

    private Connectors() {
    }

    /** {@code http} with the strict SSRF guard. */
    public static Map<String, ServiceHandler.Connector> defaults() {
        return defaults(UrlGuard.strict());
    }

    public static Map<String, ServiceHandler.Connector> defaults(UrlGuard guard) {
        Map<String, ServiceHandler.Connector> m = new LinkedHashMap<>();
        m.put("http", new HttpConnector(guard));
        m.put("telegram", new Telegram.Sender(guard));
        m.put("slack", new Slack.Sender(guard));
        m.put("whatsapp", new WhatsApp.Sender(guard));
        m.put("gmail", new Gmail.Sender(guard));
        m.put("smtp", new Smtp.Sender(guard));
        m.put("discord", new Discord.Sender(guard));
        m.put("teams", new Teams.Sender(guard));
        m.put("github", new GitHub.Sender(guard));
        m.put("notion", new Notion.Sender(guard));
        m.put("gcal", new GoogleApis.Calendar(guard));
        m.put("gsheets", new GoogleApis.Sheets(guard));
        m.put("twilio", new Twilio.Sender(guard));
        m.put("resend", new Resend.Sender(guard));
        m.put("airtable", new Airtable.Sender(guard));
        m.put("todoist", new Todoist.Sender(guard));
        m.put("linear", new Linear.Sender(guard));
        m.put("trello", new Trello.Sender(guard));
        m.put("soap", new Soap.Client(guard));
        m.put("mcp", new Mcp.Client(guard));
        return m;
    }

    /** Pollers for triggers, by name. */
    public static Map<String, tech.kayys.wayang.runtime.Poller> pollers(UrlGuard guard) {
        Map<String, tech.kayys.wayang.runtime.Poller> m = new LinkedHashMap<>();
        m.put("telegram", new Telegram.Updates(guard));
        m.put("gmail", new Gmail.Inbox(guard));
        m.put("imap", new Imap.Inbox(guard));
        m.put("rss", new Rss.Feed(guard));
        return m;
    }

    public static Map<String, ServiceHandler.Connector> defaults(boolean allowPrivate, Set<String> allowedHosts) {
        return defaults(new UrlGuard(allowPrivate, allowedHosts));
    }

    /** Webhook providers for triggers of kind WEBHOOK with {@code auth} set to the provider's name. */
    public static Map<String, tech.kayys.wayang.runtime.WebhookProvider> webhookProviders() {
        Map<String, tech.kayys.wayang.runtime.WebhookProvider> m = new LinkedHashMap<>();
        m.put("slack", new Slack.Webhook());
        m.put("whatsapp", new WhatsApp.Webhook());
        m.put("github", new GitHubWebhook());
        return m;
    }
}
