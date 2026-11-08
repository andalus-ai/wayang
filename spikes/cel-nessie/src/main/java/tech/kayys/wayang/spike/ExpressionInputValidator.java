package tech.kayys.wayang.spike;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic host-side admission checks for source and activation values.
 *
 * <p>This bounds accepted input shape and estimated encoded size. It is not a
 * CEL instruction/CPU/memory budget and does not protect against allocations
 * performed before this validator receives an already-materialized object.
 */
public final class ExpressionInputValidator {

    private final Limits limits;

    public ExpressionInputValidator(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public void validateSource(String source) {
        Objects.requireNonNull(source, "source");
        int bytes = source.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limits.maxSourceBytes()) {
            throw new ExpressionInputLimitException("SOURCE_BYTES_LIMIT");
        }
    }

    public void validateVariables(Map<String, ?> variables) {
        Objects.requireNonNull(variables, "variables");
        if (variables.size() > limits.maxVariableCount()) {
            throw new ExpressionInputLimitException("VARIABLE_COUNT_LIMIT");
        }
        Budget budget = new Budget();
        IdentityHashMap<Object, Boolean> path = new IdentityHashMap<>();
        for (Map.Entry<String, ?> entry : variables.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "variable name");
            int keyBytes = utf8Length(key);
            if (keyBytes > limits.maxStringBytes()) {
                throw new ExpressionInputLimitException("VARIABLE_NAME_BYTES_LIMIT");
            }
            addBytes(budget, keyBytes);
            visit(entry.getValue(), 1, budget, path);
        }
        enforceTotal(budget);
    }

    public void validateResult(Object value) {
        Budget budget = new Budget();
        visit(value, 0, budget, new IdentityHashMap<>());
        enforceTotal(budget);
    }

    private void visit(Object value, int depth, Budget budget, IdentityHashMap<Object, Boolean> path) {
        budget.nodes++;
        if (budget.nodes > limits.maxNodes()) {
            throw new ExpressionInputLimitException("VALUE_NODE_LIMIT");
        }
        if (depth > limits.maxDepth()) {
            throw new ExpressionInputLimitException("VALUE_DEPTH_LIMIT");
        }
        if (value == null || value instanceof Boolean
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal) {
            addBytes(budget, 16);
            return;
        }
        if (value instanceof String text) {
            int bytes = utf8Length(text);
            if (bytes > limits.maxStringBytes()) {
                throw new ExpressionInputLimitException("STRING_BYTES_LIMIT");
            }
            addBytes(budget, bytes);
            return;
        }
        if (value instanceof byte[] bytes) {
            if (bytes.length > limits.maxStringBytes()) {
                throw new ExpressionInputLimitException("BYTE_ARRAY_LIMIT");
            }
            addBytes(budget, bytes.length);
            return;
        }
        if (path.put(value, Boolean.TRUE) != null) {
            throw new ExpressionInputLimitException("CYCLIC_VALUE_NOT_SUPPORTED");
        }
        try {
            if (value instanceof List<?> list) {
                requireCollectionSize(list.size());
                for (Object item : list) {
                    visit(item, depth + 1, budget, path);
                }
                return;
            }
            if (value instanceof Map<?, ?> map) {
                requireCollectionSize(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new ExpressionInputLimitException("MAP_KEYS_MUST_BE_STRINGS");
                    }
                    int keyBytes = utf8Length(key);
                    if (keyBytes > limits.maxStringBytes()) {
                        throw new ExpressionInputLimitException("MAP_KEY_BYTES_LIMIT");
                    }
                    addBytes(budget, keyBytes);
                    visit(entry.getValue(), depth + 1, budget, path);
                }
                return;
            }
            throw new ExpressionInputLimitException("UNSUPPORTED_HOST_VALUE_TYPE");
        } finally {
            path.remove(value);
        }
    }

    private void requireCollectionSize(int size) {
        if (size > limits.maxCollectionEntries()) {
            throw new ExpressionInputLimitException("COLLECTION_ENTRY_LIMIT");
        }
    }

    private void addBytes(Budget budget, int bytes) {
        budget.totalBytes += bytes;
        enforceTotal(budget);
    }

    private void enforceTotal(Budget budget) {
        if (budget.totalBytes > limits.maxTotalBytes()) {
            throw new ExpressionInputLimitException("TOTAL_INPUT_BYTES_LIMIT");
        }
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static final class Budget {
        private long totalBytes;
        private int nodes;
    }

    public record Limits(
            int maxSourceBytes,
            int maxVariableCount,
            int maxDepth,
            int maxCollectionEntries,
            int maxNodes,
            int maxStringBytes,
            long maxTotalBytes) {
        public Limits {
            if (maxSourceBytes < 1 || maxVariableCount < 0 || maxDepth < 0
                    || maxCollectionEntries < 0 || maxNodes < 1
                    || maxStringBytes < 0 || maxTotalBytes < 1) {
                throw new IllegalArgumentException("Expression limits must be non-negative and meaningful");
            }
        }

        public static Limits conservativeDefaults() {
            return new Limits(64 * 1024, 128, 16, 1024, 10_000, 256 * 1024, 1024 * 1024);
        }
    }
}
