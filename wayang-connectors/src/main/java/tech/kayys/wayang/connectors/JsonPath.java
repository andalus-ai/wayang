package tech.kayys.wayang.connectors;

import java.util.List;
import java.util.Map;

/** Tiny path reader for output mappers: {@code a.b[0].c}. Missing steps give null; never throws on odd input. */
public final class JsonPath {

    private JsonPath() {
    }

    public static Object get(Object root, String path) {
        if (path == null || path.isBlank()) {
            return root;
        }
        String p = path.startsWith("$.") ? path.substring(2) : path.equals("$") ? "" : path;
        Object cur = root;
        int i = 0;
        int n = p.length();
        while (i < n && cur != null) {
            char c = p.charAt(i);
            if (c == '.') {
                i++;
            } else if (c == '[') {
                int j = p.indexOf(']', i);
                if (j < 0) {
                    return null;
                }
                String idx = p.substring(i + 1, j);
                if (idx.length() >= 2 && (idx.startsWith("\"") && idx.endsWith("\"") || idx.startsWith("'") && idx.endsWith("'"))) {
                    cur = cur instanceof Map<?, ?> m ? m.get(idx.substring(1, idx.length() - 1)) : null;
                } else {
                    try {
                        int k = Integer.parseInt(idx);
                        cur = cur instanceof List<?> l && k >= 0 && k < l.size() ? l.get(k) : null;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                i = j + 1;
            } else {
                int j = i;
                while (j < n && p.charAt(j) != '.' && p.charAt(j) != '[') {
                    j++;
                }
                cur = cur instanceof Map<?, ?> m ? m.get(p.substring(i, j)) : null;
                i = j;
            }
        }
        return cur;
    }
}
