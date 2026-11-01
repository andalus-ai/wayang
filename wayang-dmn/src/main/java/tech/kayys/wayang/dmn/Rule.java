package tech.kayys.wayang.dmn;

import java.util.List;

/**
 * A decision-table rule: one unary-test source per input column and one
 * expression source per output column.
 */
public record Rule(List<String> inputEntries, List<String> outputEntries, String annotation) {
    public Rule {
        inputEntries = List.copyOf(inputEntries);
        outputEntries = List.copyOf(outputEntries);
    }

    public static Rule of(List<String> inputs, List<String> outputs) {
        return new Rule(inputs, outputs, null);
    }
}
