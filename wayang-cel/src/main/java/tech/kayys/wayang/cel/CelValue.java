package tech.kayys.wayang.cel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Closed value model crossing the CEL adapter boundary. Missing is not a value. */
public sealed interface CelValue permits CelValue.BoolValue, CelValue.DecimalValue,
        CelValue.StringValue, CelValue.BytesValue, CelValue.NullValue,
        CelValue.ListValue, CelValue.MapValue {
    record BoolValue(boolean value) implements CelValue { }
    record DecimalValue(BigDecimal value) implements CelValue {
        public DecimalValue { Objects.requireNonNull(value, "value"); }
    }
    record StringValue(String value) implements CelValue {
        public StringValue { Objects.requireNonNull(value, "value"); }
    }
    record BytesValue(byte[] value) implements CelValue {
        public BytesValue { value = Objects.requireNonNull(value, "value").clone(); }
        @Override public byte[] value() { return value.clone(); }
    }
    record NullValue() implements CelValue { }
    record ListValue(List<CelValue> values) implements CelValue {
        public ListValue { values = List.copyOf(values); }
    }
    record MapValue(Map<String, CelValue> values) implements CelValue {
        public MapValue { values = Map.copyOf(values); }
    }
}
