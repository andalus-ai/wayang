package tech.kayys.wayang.dmn;

import java.util.List;

/**
 * Outcome of evaluating a decision.
 *
 * @param value        single output: the value; several outputs: a Map of output name to value; COLLECT: a List
 *                     of those (or an aggregate)
 * @param matchedRules 1-based indexes of the rules that matched, for audit/explainability
 */
public record DecisionResult(Object value, List<Integer> matchedRules) {
    public DecisionResult {
        matchedRules = List.copyOf(matchedRules);
    }
}
