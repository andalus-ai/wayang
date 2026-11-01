package tech.kayys.wayang.dmn;

import java.util.Set;

import tech.kayys.wayang.expression.Expression;
import tech.kayys.wayang.expression.Expressions;
import tech.kayys.wayang.expression.Scope;

/** A named decision inside a {@link DecisionModel}: either a decision table or a literal expression. */
public sealed interface Decision {

    String name();

    /** Names this decision reads (candidates for dependencies on other decisions or on input data). */
    Set<String> referencedNames();

    Object evaluate(Scope scope);

    record Table(DecisionTable table) implements Decision {
        @Override
        public String name() {
            return table.name();
        }

        @Override
        public Set<String> referencedNames() {
            return table.referencedNames();
        }

        @Override
        public Object evaluate(Scope scope) {
            return table.evaluate(scope).value();
        }
    }

    record Literal(String name, Expression expression) implements Decision {
        public static Literal of(String name, String source) {
            try {
                return new Literal(name, Expressions.parse(source));
            } catch (tech.kayys.wayang.expression.ExpressionException e) {
                throw new DmnException("Decision '" + name + "' expression '" + source + "': " + e.getMessage(), e);
            }
        }

        @Override
        public Set<String> referencedNames() {
            return expression.referencedNames();
        }

        @Override
        public Object evaluate(Scope scope) {
            return expression.evaluate(scope);
        }
    }
}
