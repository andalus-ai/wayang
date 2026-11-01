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
 * GitHub issue triage: a GitHub webhook trigger starts one run per new issue ({@code event}: {@code repo}, {@code number},
 * {@code title}, {@code text}, {@code url}); an integration pipeline validates and classifies it; bugs become a Todoist task and
 * get a comment on the issue, everything else is left alone.
 *
 * <pre>
 * issue ──&gt; classify (EIP: validate, choice) ──&gt; bug? ──&gt; todoist createTask ──&gt; github comment ──&gt; done
 *                                                  └──&gt; ignored
 * </pre>
 *
 * Wire it with a trigger {@code {auth: github, secret: ${secret:GH_HOOK}, allowedRepos: [...], events: [issues]}} and service
 * nodes configured with {@code operation}, {@code token} and the allow-lists (see docs/MAIL.md).
 */
public final class IssueTriage {

    private IssueTriage() {
    }

    public static ProcessModel process() {
        Map<String, Object> pipeline = Map.of("steps", List.of(
                Map.of("type", "validate", "required", List.of("title", "repo", "number")),
                Map.of("type", "choice",
                        "branches", List.of(Map.of("when", "contains(lower case(body.title), \"bug\") or contains(lower case(body.title), \"crash\")",
                                "steps", List.of(Map.of("type", "map", "headers", Map.of("lane", Map.of("$const", "bug")))))),
                        "otherwise", List.of(Map.of("type", "map", "headers", Map.of("lane", Map.of("$const", "other")))))));
        return ProcessModel.builder("issue-triage").name("GitHub issue triage").start("received")
                .task("classify", ElementType.EIP_TASK, Map.of("message", "event", "pipeline", pipeline))
                .exclusive("route")
                .task("task", ElementType.SERVICE_TASK, Map.of("connector", "todoist", "operation", "createTask",
                        "inputs", Map.of("projectId", "'P1'", "content", "event.title", "description", "event.url")))
                .task("comment", ElementType.SERVICE_TASK, Map.of("connector", "github", "operation", "comment",
                        "inputs", Map.of("repo", "event.repo", "number", "event.number", "body", "'Thanks. Filed as a task.'")))
                .end("done").end("ignored")
                .flow("received", "classify").flow("classify", "route")
                .flow("route", "task", "classify.messages[1].headers.lane = \"bug\"").defaultFlow("route", "ignored")
                .flow("task", "comment").flow("comment", "done")
                .build();
    }

    /** A runner whose Todoist and GitHub connectors record what they were asked to do (for the example and tests). */
    public static NodeRunner runner(WaitRegistry waits, Consumer<Map<String, Object>> todoist, Consumer<Map<String, Object>> github) {
        return new NodeRunner(List.of(new ControlHandler(waits), new EipHandler(new IdempotencyStore.InMemory(100)),
                new ServiceHandler(Map.of(
                        "todoist", in -> {
                            todoist.accept(in);
                            return Map.of("id", "t1");
                        },
                        "github", in -> {
                            github.accept(in);
                            return Map.of("commented", true);
                        }))));
    }
}
