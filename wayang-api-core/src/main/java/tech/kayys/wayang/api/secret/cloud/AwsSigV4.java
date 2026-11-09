package tech.kayys.wayang.api.secret.cloud;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** AWS Signature Version 4 (header form), for the services Wayang reads from. Pure: no I/O, no clock of its own. */
public final class AwsSigV4 {

    /** Static credentials; {@code sessionToken} is null for long-lived keys. toString never shows the key. */
    public record Credentials(String accessKeyId, String secretAccessKey, String sessionToken) {
        @Override
        public String toString() {
            return "Credentials[" + accessKeyId + "]";
        }
    }

    private static final DateTimeFormatter AMZ = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private AwsSigV4() {
    }

    /**
     * @param headers the request headers to sign besides host and x-amz-date (names in any case)
     * @return headers to add to the request: x-amz-date, authorization, and x-amz-security-token when there is one
     */
    public static Map<String, String> sign(String method, URI uri, Map<String, String> headers, byte[] body, String region, String service,
            Credentials c, Instant now) {
        String amzDate = AMZ.format(now);
        String day = amzDate.substring(0, 8);
        TreeMap<String, String> h = new TreeMap<>();
        headers.forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v.strip().replaceAll("\\s+", " ")));
        h.put("host", uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort()));
        h.put("x-amz-date", amzDate);
        if (c.sessionToken() != null && !c.sessionToken().isEmpty()) {
            h.put("x-amz-security-token", c.sessionToken());
        }
        StringBuilder canonHeaders = new StringBuilder();
        List<String> names = new ArrayList<>();
        h.forEach((k, v) -> {
            canonHeaders.append(k).append(':').append(v).append('\n');
            names.add(k);
        });
        String signed = String.join(";", names);
        String canonical = method + "\n" + canonicalPath(uri) + "\n" + canonicalQuery(uri) + "\n" + canonHeaders + "\n" + signed + "\n"
                + hex(sha256(body == null ? new byte[0] : body));
        String scope = day + "/" + region + "/" + service + "/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] k = hmac(("AWS4" + c.secretAccessKey()).getBytes(StandardCharsets.UTF_8), day);
        k = hmac(k, region);
        k = hmac(k, service);
        k = hmac(k, "aws4_request");
        String sig = hex(hmac(k, toSign));
        Map<String, String> out = new java.util.LinkedHashMap<>();
        out.put("x-amz-date", amzDate);
        if (h.containsKey("x-amz-security-token")) {
            out.put("x-amz-security-token", h.get("x-amz-security-token"));
        }
        out.put("authorization", "AWS4-HMAC-SHA256 Credential=" + c.accessKeyId() + "/" + scope + ", SignedHeaders=" + signed + ", Signature=" + sig);
        return out;
    }

    private static String canonicalPath(URI uri) {
        String p = uri.getRawPath();
        if (p == null || p.isEmpty()) {
            return "/";
        }
        // non-S3 services encode each (already raw) path segment once more
        StringBuilder sb = new StringBuilder();
        for (String seg : p.split("/", -1)) {
            if (!seg.isEmpty() || sb.length() > 0) {
                sb.append('/').append(enc(seg));
            }
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static String canonicalQuery(URI uri) {
        String q = uri.getRawQuery();
        if (q == null || q.isEmpty()) {
            return "";
        }
        TreeMap<String, String> m = new TreeMap<>();
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            String v = i < 0 ? "" : kv.substring(i + 1);
            m.put(enc(java.net.URLDecoder.decode(k, StandardCharsets.UTF_8)), enc(java.net.URLDecoder.decode(v, StandardCharsets.UTF_8)));
        }
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : "&").append(k).append('=').append(v));
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
