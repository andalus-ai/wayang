package tech.kayys.wayang.dmn;

/** DMN hit policies supported by {@link DecisionTable}. */
public enum HitPolicy {
    /** Exactly one rule may match; more than one is an error. No match yields null. */
    UNIQUE,
    /** First matching rule in table order wins. */
    FIRST,
    /** Several rules may match but all must produce the same output; otherwise an error. */
    ANY,
    /** Every matching rule contributes; result is a list (or an aggregate, see {@link Aggregator}). */
    COLLECT,
    /** Like COLLECT without aggregation, explicitly in rule order. */
    RULE_ORDER;

    /** Aggregation applied to COLLECT results (single numeric output column only, except COUNT). */
    public enum Aggregator {
        NONE, SUM, MIN, MAX, COUNT
    }
}
