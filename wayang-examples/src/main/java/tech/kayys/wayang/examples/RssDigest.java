package tech.kayys.wayang.examples;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.eip.IdempotencyStore;
import tech.kayys.wayang.runtime.ControlHandler;
import tech.kayys.wayang.runtime.EipHandler;
import tech.kayys.wayang.runtime.NodeRunner;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WaitRegistry;

/**
 * RSS watcher: an RSS trigger (poller) starts one run per new feed item ({@code event}: {@code id}, {@code title},
 * {@code link}, {@code summary}, {@code published}); a pipeline validates it, drops items already seen (idempotent on
 * {@code id}, so a feed that re-orders or re-publishes does not message you twice) and keeps only the ones whose title mentions
 * a keyword; those are sent to a Telegram chat.
 *
 * <pre>
 * item ──&gt; pick (EIP: validate, idempotent, choice) ──&gt; match? ──&gt; telegram sendMessage ──&gt; done
 *                                                      └──&gt; skipped
 * </pre>
 *
 * Wire it with a trigger {@code {source: rss, url: https://example.com/feed.xml, everySeconds: 900}} and a service node with
 * {@code botToken: ${secret:TELEGRAM_TOKEN}} (see docs/MAIL.md and docs/TRIGGERS.md). Change {@code KEYWORDS} to taste.
 */
public final class RssDigest {

    /** Lower-case keywords matched against the item title. */
    static final List<String> KEYWORDS = List.of("release", "security");

    private RssDigest() {
    }

    public static ProcessModel process() {
        String when = KEYWORDS.stream().map(k -> "contains(lower case(body.title), \"" + k + "\")")
                .reduce((a, b) -> a + " or " + b).orElseThrow();
        Map<String, Object> pipeline = Map.of("steps", List.of(
                Map.of("type", "validate", "required", List.of("id", "title", "link")),
                Map.of("type", "idempotent", "key", "body.id"),
                Map.of("type", "choice",
                        "branches", List.of(Map.of("when", when,
                                "steps", List.of(Map.of("type", "map", "headers", Map.of("lane", Map.of("$const", "send")))))),
                        "otherwise", List.of(Map.of("type", "map", "headers", Map.of("lane", Map.of("$const", "skip")))))));
        return ProcessModel.builder("rss-digest").name("RSS to Telegram").start("received")
                .task("pick", ElementType.EIP_TASK, Map.of("message", "event", "pipeline", pipeline))
                .exclusive("route")
                .task("notify", ElementType.SERVICE_TASK, Map.of("connector", "telegram", "operation", "sendMessage",
                        "inputs", Map.of("chatId", "'-1001'", "text", "event.title + \"\\n\" + event.link")))
                .end("done").end("skipped")
                .flow("received", "pick").flow("pick", "route")
                .flow("route", "notify", "pick.messages[1].headers.lane = \"send\"").defaultFlow("route", "skipped")
                .flow("notify", "done")
                .build();
    }

    /** A runner whose Telegram connector records what it was asked to send; [store] is shared so repeats can be seen. */
    public static NodeRunner runner(WaitRegistry waits, IdempotencyStore store, Consumer<Map<String, Object>> telegram) {
        return new NodeRunner(List.of(new ControlHandler(waits), new EipHandler(store),
                new ServiceHandler(Map.of("telegram", in -> {
                    telegram.accept(in);
                    return Map.of("messageId", 1);
                }))));
    }
}
