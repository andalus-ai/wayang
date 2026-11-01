package tech.kayys.wayang.eip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.expression.Expression;
import tech.kayys.wayang.expression.Expressions;
import tech.kayys.wayang.expression.Scope;

/**
 * Aggregator (batch form): groups messages by a correlation expression and
 * combines each group into one message whose body is the list of bodies.
 * Groups smaller than {@code completionSize} are reported as incomplete rather
 * than silently dropped.
 */
public final class Aggregator {

    public record Result(List<Message> complete, List<List<Message>> incomplete) {
    }

    private final Expression correlation;
    private final int completionSize;

    public Aggregator(String correlationExpression, int completionSize) {
        if (completionSize < 1) {
            throw new EipException("completionSize must be >= 1");
        }
        this.correlation = Expressions.parse(correlationExpression);
        this.completionSize = completionSize;
    }

    public Result aggregate(List<Message> batch, Scope scope) {
        Map<String, List<Message>> groups = new LinkedHashMap<>();
        for (Message m : batch) {
            Object k = correlation.evaluate(m.scope(scope));
            groups.computeIfAbsent(String.valueOf(k), x -> new ArrayList<>()).add(m);
        }
        List<Message> complete = new ArrayList<>();
        List<List<Message>> incomplete = new ArrayList<>();
        for (Map.Entry<String, List<Message>> g : groups.entrySet()) {
            if (g.getValue().size() >= completionSize) {
                List<Object> bodies = new ArrayList<>();
                g.getValue().forEach(m -> bodies.add(m.body()));
                complete.add(new Message(Map.of("correlationId", g.getKey(), "aggregateSize",
                        java.math.BigDecimal.valueOf(bodies.size())), bodies));
            } else {
                incomplete.add(g.getValue());
            }
        }
        return new Result(complete, incomplete);
    }
}
