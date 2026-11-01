package tech.kayys.wayang.api;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The portable form of a project: its definitions, the triggers that start them, and its plain settings, plus what the target
 * environment must provide ({@code requires}). Never secret values: {@code ${secret:NAME}} references travel, values do not.
 *
 * <p>{@code integrity.sha256} is a digest of everything else in canonical JSON (it catches truncation and hand edits);
 * {@code integrity.hmac} is added when the server has a signing key, and then proves the bundle came from a server that holds
 * that key. A bare digest is not authenticity: anyone can recompute it.
 */
public final class WorkspaceBundle {

    public static final String FORMAT = "wayang.workspace";
    public static final int VERSION = 1;

    static final Pattern REF = Pattern.compile("\\$\\{(secret|config):([A-Za-z0-9_.-]{1,100})}");
    /** Superset of the trigger API's credential-key rule. */
    static final Pattern CREDENTIAL_KEY = Pattern.compile(
            "(?i).*(token|secret|password|passwd|apikey|api_key|api-key|private[-_]?key|credential|authorization|cookie|bearer).*");
    private static final Pattern SECRET_REF = Pattern.compile(".*\\$\\{secret:[A-Za-z0-9_.-]{1,100}}.*", Pattern.DOTALL);
    private static final Pattern URL_USERINFO = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://[^/\\s:@]+:[^/\\s@]+@");

    private WorkspaceBundle() {
    }

    // ------------------------------------------------------------------ integrity

    /** Deterministic JSON: object keys sorted, no whitespace. @throws IllegalArgumentException on NaN, infinity or unsupported types */
    public static String canonical(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("Bundle is nested too deeply");
        }
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Number n) {
            sb.append(number(n));
        } else if (v instanceof Map<?, ?> m) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            m.forEach((k, x) -> sorted.put(String.valueOf(k), x));
            sb.append('{');
            boolean first = true;
            for (var e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object x : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, x, depth + 1);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Bundle contains a value of type " + v.getClass().getSimpleName());
        }
    }

    /** One spelling per value whatever the type that carried it: 1, 1.0 (double) and 1.0 (BigDecimal) are all "1". */
    private static String number(Number n) {
        java.math.BigDecimal bd;
        try {
            if (n instanceof java.math.BigDecimal b) {
                bd = b;
            } else if (n instanceof java.math.BigInteger b) {
                bd = new java.math.BigDecimal(b);
            } else if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new IllegalArgumentException("Bundle contains a number JSON cannot hold");
                }
                bd = new java.math.BigDecimal(Double.toString(d));
            } else {
                bd = java.math.BigDecimal.valueOf(n.longValue());
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bundle contains a number JSON cannot hold");
        }
        if (bd.signum() == 0) {
            return "0";
        }
        if (Math.abs((long) bd.precision() - bd.scale()) > 400 || Math.abs((long) bd.scale()) > 400) {
            throw new IllegalArgumentException("Bundle contains a number that is too large or too small");
        }
        return bd.stripTrailingZeros().toPlainString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** The bundle without its {@code integrity} member, which is what the digest covers. */
    static Map<String, Object> body(Map<String, Object> bundle) {
        Map<String, Object> b = new java.util.LinkedHashMap<>(bundle);
        b.remove("integrity");
        return b;
    }

    public static String sha256(Map<String, Object> bundle) {
        return hex(digest().digest(canonical(body(bundle)).getBytes(StandardCharsets.UTF_8)));
    }

    public static String hmac(Map<String, Object> bundle, byte[] key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return hex(mac.doFinal(canonical(body(bundle)).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean constantTimeEquals(String a, String b) {
        return a != null && b != null
                && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ content checks

    /** JSON paths (never values) of anything that looks like a credential written out literally. */
    public static List<String> literalCredentials(String where, Object v) {
        List<String> out = new ArrayList<>();
        scan(where, v, out, 0);
        return out;
    }

    private static void scan(String path, Object v, List<String> out, int depth) {
        if (depth > 64 || out.size() >= 50) {
            return;
        }
        if (v instanceof Map<?, ?> m) {
            m.forEach((k, x) -> {
                String key = String.valueOf(k);
                String p = path + "." + key;
                if (x instanceof String s && CREDENTIAL_KEY.matcher(key).matches() && !s.isBlank() && !SECRET_REF.matcher(s).matches()) {
                    out.add(p);
                } else {
                    scan(p, x, out, depth + 1);
                }
            });
        } else if (v instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                scan(path + "[" + i + "]", l.get(i), out, depth + 1);
            }
        } else if (v instanceof String s && (s.contains("PRIVATE KEY-----") || URL_USERINFO.matcher(s).find())) {
            out.add(path);
        }
    }

    /** Every {@code ${secret:X}} (kind "secret") or {@code ${config:X}} (kind "config") named anywhere in the value. */
    public static Set<String> references(String kind, Object v) {
        Set<String> out = new LinkedHashSet<>();
        collect(kind, v, out, 0);
        return out;
    }

    private static void collect(String kind, Object v, Set<String> out, int depth) {
        if (depth > 64) {
            return;
        }
        if (v instanceof String s) {
            Matcher m = REF.matcher(s);
            while (m.find()) {
                if (m.group(1).equals(kind)) {
                    out.add(m.group(2));
                }
            }
        } else if (v instanceof Map<?, ?> m) {
            m.values().forEach(x -> collect(kind, x, out, depth + 1));
        } else if (v instanceof Iterable<?> it) {
            it.forEach(x -> collect(kind, x, out, depth + 1));
        }
    }
}
