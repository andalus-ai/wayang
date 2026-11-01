package tech.kayys.wayang.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tech.kayys.wayang.bpmn.ExecutionPlan;
import tech.kayys.wayang.bpmn.PlanNode;

/**
 * A small, single-threaded, in-memory engine that executes an
 * {@link ExecutionPlan} with the same {@link NodeRunner} the Gamelan adapter
 * uses. It exists to run examples and tests without infrastructure and to pin
 * down semantics; it is <strong>not durable</strong> and not a replacement for
 * Gamelan (no persistence, retries, timers or concurrency).
 */
public final class ReferenceEngine {

    public enum Status {
        COMPLETED, WAITING, FAILED
    }

    public static final class Run {
        private final String id = UUID.randomUUID().toString();
        private final ExecutionPlan plan;
        private final Map<String, Object> vars = new LinkedHashMap<>();
        private final List<String> executed = new ArrayList<>();
        private int cursor;
        private Status status = Status.COMPLETED;
        private WaitTicket waiting;
        private String error;

        private Run(ExecutionPlan plan, Map<String, Object> input) {
            this.plan = plan;
            this.vars.putAll(input);
        }

        public String id() {
            return id;
        }

        public Status status() {
            return status;
        }

        public Map<String, Object> variables() {
            return java.util.Collections.unmodifiableMap(vars);
        }

        /** Ids of nodes that did real work (were on a taken path), in order. */
        public List<String> executedNodes() {
            return java.util.Collections.unmodifiableList(executed);
        }

        public WaitTicket waitingOn() {
            return waiting;
        }

        public String error() {
            return error;
        }
    }

    private final NodeRunner runner;
    private final WaitRegistry waits;

    public ReferenceEngine(NodeRunner runner, WaitRegistry waits) {
        this.runner = runner;
        this.waits = waits;
    }

    public Run start(ExecutionPlan plan, Map<String, Object> input) {
        Run run = new Run(plan, input);
        advance(run);
        return run;
    }

    /** Completes the wait identified by {@code token} and keeps going. */
    public void complete(Run run, String token, Map<String, Object> outputs) {
        if (run.status != Status.WAITING || run.waiting == null || !run.waiting.token().equals(token)) {
            throw new IllegalStateException("Run " + run.id + " is not waiting on token " + token);
        }
        waits.take(token);
        PlanNode node = run.plan.nodes().get(run.cursor);
        NodeOutcome o = runner.resume(node, run.vars, outputs);
        run.waiting = null;
        apply(run, node, o);
        if (run.status != Status.FAILED) {
            run.cursor++;
            run.status = Status.COMPLETED;
            advance(run);
        }
    }

    /** Adapter so {@link TimerService} can complete waits of this run. */
    public WaitCompleter completerFor(Run run) {
        return (token, outputs) -> {
            if (run.status != Status.WAITING || run.waiting == null || !run.waiting.token().equals(token)) {
                return false;
            }
            complete(run, token, outputs);
            return true;
        };
    }

    private void advance(Run run) {
        while (run.cursor < run.plan.nodes().size()) {
            PlanNode node = run.plan.nodes().get(run.cursor);
            NodeOutcome o = runner.run(run.id, node, run.vars);
            if (o instanceof NodeOutcome.Waiting w) {
                run.status = Status.WAITING;
                run.waiting = w.ticket();
                run.executed.add(node.id());
                return;
            }
            apply(run, node, o);
            if (run.status == Status.FAILED) {
                return;
            }
            run.cursor++;
        }
        run.status = Status.COMPLETED;
    }

    private void apply(Run run, PlanNode node, NodeOutcome o) {
        if (o instanceof NodeOutcome.Completed c) {
            if (!Boolean.FALSE.equals(c.outputs().get("active")) && !run.executed.contains(node.id())) {
                run.executed.add(node.id());
            }
            c.outputs().forEach((k, v) -> run.vars.put(node.id() + "." + k, v));
        } else if (o instanceof NodeOutcome.Failed f) {
            run.status = Status.FAILED;
            run.error = f.error();
        }
    }
}
