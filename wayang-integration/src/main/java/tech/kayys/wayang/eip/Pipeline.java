package tech.kayys.wayang.eip;

import java.util.ArrayList;
import java.util.List;

import tech.kayys.wayang.expression.Scope;

/** An ordered chain of {@link Step}s. Each step is applied to every message the previous step produced. */
public final class Pipeline {

    /** Delivery guarantee the surrounding flow promises. Informational: enforced by the transport + idempotent receiver. */
    public enum Delivery {
        AT_MOST_ONCE, AT_LEAST_ONCE, EFFECTIVELY_ONCE
    }

    private final List<Step> steps;
    private final Delivery delivery;

    public Pipeline(List<Step> steps, Delivery delivery) {
        this.steps = List.copyOf(steps);
        this.delivery = delivery;
        if (delivery == Delivery.EFFECTIVELY_ONCE && steps.stream().noneMatch(s -> s instanceof Step.Idempotent)) {
            throw new EipException("EFFECTIVELY_ONCE requires an idempotent step in the pipeline");
        }
    }

    public Delivery delivery() {
        return delivery;
    }

    public List<Step> steps() {
        return steps;
    }

    public List<Message> run(Message in, Scope scope) {
        return runBatch(List.of(in), scope);
    }

    public List<Message> runBatch(List<Message> batch, Scope scope) {
        List<Message> current = new ArrayList<>(batch);
        for (Step s : steps) {
            current = s.applyAll(current, scope);
            if (current.size() > Step.MAX_FANOUT) {
                throw new EipException("Pipeline produced more than " + Step.MAX_FANOUT + " messages");
            }
        }
        return current;
    }
}
