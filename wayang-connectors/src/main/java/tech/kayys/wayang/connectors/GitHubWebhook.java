package tech.kayys.wayang.connectors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tech.kayys.wayang.runtime.WebhookProvider;

/**
 * Webhook provider {@code github}: issues, issue comments, pull requests and pushes.
 *
 * <p>Config: {@code secret} (the webhook secret as {@code ${secret:NAME}}), <b>{@code allowedRepos} (required:
 * {@code owner/name} or {@code owner/*})</b>, optional {@code events} (subset of issues, issue_comment, pull_request, push;
 * default all). The signature {@code X-Hub-Signature-256} is checked over the raw body. Events from bot senders are dropped so a
 * workflow that comments on issues cannot trigger itself.
 */
public final class GitHubWebhook implements WebhookProvider {

    private static final int MAX_TEXT = 4000;

    @Override
    public void verify(Map<String, Object> config, Map<String, String> headers, byte[] body, Instant now) throws Rejected {
        if (!(config.get("secret") instanceof String secret) || secret.length() < 8) {
            throw new Rejected("github webhook needs a secret");
        }
        String sig = headers.get("x-hub-signature-256");
        if (sig == null || !Hmac.same("sha256=" + Hmac.sha256Hex(secret, body), sig.trim())) {
            throw new Rejected("bad signature");
        }
    }

    @Override
    public Optional<String> handshake(Map<String, Object> config, Map<String, String> query, Map<String, Object> json) {
        // GitHub's "ping" carries a zen line and the hook id
        return json != null && json.get("zen") instanceof String && json.get("hook_id") != null ? Optional.of("pong") : Optional.empty();
    }

    @Override
    public List<Event> events(Map<String, Object> config, Map<String, Object> json) {
        List<Event> out = new ArrayList<>();
        if (json == null || !(json.get("repository") instanceof Map<?, ?> repo) || !(repo.get("full_name") instanceof String full)) {
            return out;
        }
        try {
            Rest.allowed(config.get("allowedRepos"), full, "repository");
        } catch (Exception e) {
            return out;
        }
        if (json.get("sender") instanceof Map<?, ?> s && "Bot".equals(s.get("type"))) {
            return out;
        }
        String type;
        String ref;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("repo", full);
        d.put("sender", json.get("sender") instanceof Map<?, ?> s ? s.get("login") : null);
        if (json.get("comment") instanceof Map<?, ?> c && json.get("issue") instanceof Map<?, ?> i) {
            type = "issue_comment";
            ref = "c" + c.get("id");
            d.put("number", i.get("number"));
            d.put("title", i.get("title"));
            d.put("text", cut(c.get("body")));
            d.put("url", c.get("html_url"));
        } else if (json.get("pull_request") instanceof Map<?, ?> p) {
            type = "pull_request";
            ref = "p" + p.get("number") + ":" + p.get("updated_at");
            d.put("number", p.get("number"));
            d.put("title", p.get("title"));
            d.put("text", cut(p.get("body")));
            d.put("url", p.get("html_url"));
        } else if (json.get("issue") instanceof Map<?, ?> i) {
            type = "issues";
            ref = "i" + i.get("number") + ":" + i.get("updated_at");
            d.put("number", i.get("number"));
            d.put("title", i.get("title"));
            d.put("text", cut(i.get("body")));
            d.put("url", i.get("html_url"));
        } else if (json.get("commits") instanceof List<?> && json.get("after") instanceof String after) {
            type = "push";
            ref = "s" + after;
            d.put("ref", json.get("ref"));
            d.put("sha", after);
            d.put("title", json.get("head_commit") instanceof Map<?, ?> h ? cut(h.get("message")) : null);
            d.put("url", json.get("compare"));
        } else {
            return out;
        }
        if (config.get("events") instanceof List<?> allow && !allow.isEmpty() && !allow.contains(type)) {
            return out;
        }
        d.put("type", type);
        d.put("action", json.get("action"));
        out.add(new Event(full + "|" + type + "|" + json.get("action") + "|" + ref, d));
        return out;
    }

    private static String cut(Object o) {
        if (!(o instanceof String s)) {
            return null;
        }
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }
}
