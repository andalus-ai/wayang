package tech.kayys.wayang.expression;

/**
 * An interval such as {@code [1..5)}. Either bound may be {@code null}
 * (open-ended). Bounds must be mutually comparable values.
 */
public record Range(Object start, boolean startIncluded, Object end, boolean endIncluded) {

    /** @return TRUE/FALSE, or {@code null} when {@code v} is not comparable with the bounds */
    public Boolean includes(Object v) {
        if (v == null) {
            return null;
        }
        if (start != null) {
            Integer c = Values.compare(v, start);
            if (c == null) {
                return null;
            }
            if (c < 0 || (c == 0 && !startIncluded)) {
                return false;
            }
        }
        if (end != null) {
            Integer c = Values.compare(v, end);
            if (c == null) {
                return null;
            }
            if (c > 0 || (c == 0 && !endIncluded)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return (startIncluded ? "[" : "(") + Values.asString(start) + ".." + Values.asString(end)
                + (endIncluded ? "]" : ")");
    }
}
