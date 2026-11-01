package tech.kayys.wayang.expression;

import java.util.Set;

/**
 * A parsed, immutable, thread-safe expression. Parse once (at deploy/validation
 * time), evaluate many times.
 */
public interface Expression {

    /** The original source text. */
    String source();

    /**
     * Evaluates the expression. Unless the scope is strict, type errors and
     * unknown variables yield {@code null} (FEEL semantics) instead of throwing.
     *
     * @throws LimitExceededException when the step budget is exhausted
     */
    Object evaluate(Scope scope);

    /** Convenience: {@code true} only when the expression evaluates to boolean {@code true}. */
    default boolean test(Scope scope) {
        return Boolean.TRUE.equals(evaluate(scope));
    }

    /**
     * Names of free variables referenced (first path segment). This is a
     * conservative over-approximation: names bound by quantifiers or filter
     * predicates are included. Used for dependency analysis and validation.
     */
    Set<String> referencedNames();
}
