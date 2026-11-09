package tech.kayys.wayang.api;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.json.Json;

/**
 * AWS Secrets Manager, read-only: {@code ${secret:aws.NAME}} reads the secret {@code <basePath>/<tenant>/NAME} (its
 * {@code SecretString}, the whole string). IAM can therefore scope Wayang to {@code arn:...:secret:<basePath>/*}. Credentials come
 * from a supplier (re-read per call, so rotated keys apply); instance-role/IRSA credential discovery is not implemented.
 */
public final class AwsSecretsManager implements CloudBackend {

    private static final Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-[0-9]{1,2}");
    private static final Pattern BASE = Pattern.compile("[A-Za-z0-9_+=@-]+(/[A-Za-z0-9_+=@-]+)*");
    private static final Pattern ERR = Pattern.compile("[A-Za-z]{1,60}");

    private final URI endpoint;
    private final String region;
    private final String base;
    private final Supplier<AwsSigV4.Credentials> credentials;
    private final CloudHttp http;
    private final Clock clock;

    /** @param endpoint null for the regional default; set for a VPC endpoint or a local test server */
    public AwsSecretsManager(String region, URI endpoint, String basePath, Supplier<AwsSigV4.Credentials> credentials, CloudHttp http, Clock clock) {
        if (region == null || !REGION.matcher(region).matches()) {
            throw new IllegalArgumentException("Not an AWS region: " + region);
        }
        if (basePath != null && !basePath.isEmpty() && !BASE.matcher(basePath).matches()) {
            throw new IllegalArgumentException("wayang.cloud.aws.base-path has characters Secrets Manager does not allow");
        }
        this.region = region;
        this.endpoint = endpoint != null ? endpoint : URI.create("https://secretsmanager." + region + ".amazonaws.com");
        this.base = basePath == null ? "" : basePath;
        this.credentials = credentials;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public String fetch(String tenant, String name) {
        CloudNames.check(tenant, name);
        AwsSigV4.Credentials c = credentials.get();
        if (c == null || c.accessKeyId() == null || c.accessKeyId().isBlank() || c.secretAccessKey() == null || c.secretAccessKey().isBlank()) {
            throw new CloudException("No AWS credentials", 0);
        }
        String id = (base.isEmpty() ? "" : base + "/") + tenant + "/" + name;
        byte[] body = ("{\"SecretId\":" + Json.write(id) + "}").getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/x-amz-json-1.1");
        headers.put("x-amz-target", "secretsmanager.GetSecretValue");
        Map<String, String> signed = AwsSigV4.sign("POST", endpoint, headers, body, region, "secretsmanager", c, clock.instant());
        headers.putAll(signed);
        URI uri = endpoint.getPath() == null || endpoint.getPath().isEmpty() ? URI.create(endpoint + "/") : endpoint;
        CloudHttp.Response r = http.send("POST", uri, headers, body);
        if (r.status() == 200) {
            try {
                if (Json.read(new String(r.body(), StandardCharsets.UTF_8)) instanceof Map<?, ?> m && m.get("SecretString") instanceof String s) {
                    return s;
                }
            } catch (RuntimeException e) {
                throw new CloudException("AWS Secrets Manager answered with invalid JSON", 0);
            }
            return null; // a binary secret: not something a ${secret:} can carry
        }
        String type = errorType(r.body());
        if (type != null && type.equals("ResourceNotFoundException")) {
            return null;
        }
        throw new CloudException("AWS Secrets Manager answered " + r.status() + (type == null ? "" : " " + type), r.status());
    }

    private static String errorType(byte[] body) {
        try {
            if (Json.read(new String(body, StandardCharsets.UTF_8)) instanceof Map<?, ?> m) {
                Object t = m.get("__type");
                if (t instanceof String s) {
                    String last = s.substring(s.lastIndexOf('#') + 1);
                    return ERR.matcher(last).matches() ? last : null;
                }
            }
        } catch (RuntimeException e) {
            // not JSON: no type
        }
        return null;
    }
}
