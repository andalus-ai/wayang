package tech.kayys.wayang.api.secret.cloud;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import tech.kayys.wayang.runtime.json.Json;

/**
 * AWS credentials discovered at run time instead of configured: EC2 instance role (IMDSv2) and ECS/Fargate task role. Both are
 * plain-http link-local endpoints, so they are operator-chosen and fixed (never taken from a run), and credentials are cached
 * until five minutes before they expire. Web-identity (IRSA) and SSO are not implemented.
 */
public final class AwsCredentialSources {

    private AwsCredentialSources() {
    }

    private static final class Cache implements Supplier<AwsSigV4.Credentials> {
        private final Supplier<Map.Entry<AwsSigV4.Credentials, Instant>> fetch;
        private final Clock clock;
        private AwsSigV4.Credentials current;
        private Instant until = Instant.EPOCH;

        Cache(Supplier<Map.Entry<AwsSigV4.Credentials, Instant>> fetch, Clock clock) {
            this.fetch = fetch;
            this.clock = clock;
        }

        @Override
        public synchronized AwsSigV4.Credentials get() {
            Instant now = clock.instant();
            if (current == null || !now.isBefore(until)) {
                Map.Entry<AwsSigV4.Credentials, Instant> e = fetch.get();
                current = e.getKey();
                until = e.getValue().minusSeconds(300);
            }
            return current;
        }
    }

    /** EC2 instance profile via IMDSv2 (a session token first; v1 is never used). */
    public static Supplier<AwsSigV4.Credentials> imds(URI base, CloudHttp http, Clock clock) {
        return new Cache(() -> {
            CloudHttp.Response t = http.send("PUT", URI.create(base + "/latest/api/token"), Map.of("x-aws-ec2-metadata-token-ttl-seconds", "300"), null);
            if (t.status() != 200) {
                throw new CloudException("The EC2 metadata service refused a session token (" + t.status() + ")", t.status());
            }
            String token = new String(t.body(), StandardCharsets.UTF_8).strip();
            Map<String, String> h = Map.of("x-aws-ec2-metadata-token", token);
            CloudHttp.Response role = http.send("GET", URI.create(base + "/latest/meta-data/iam/security-credentials/"), h, null);
            if (role.status() != 200) {
                throw new CloudException("The EC2 instance has no role (" + role.status() + ")", role.status());
            }
            String name = new String(role.body(), StandardCharsets.UTF_8).strip().lines().findFirst().orElse("");
            if (!name.matches("[A-Za-z0-9+=,.@_-]{1,128}")) {
                throw new CloudException("The EC2 metadata service named an unusable role", 0);
            }
            return parse(http.send("GET", URI.create(base + "/latest/meta-data/iam/security-credentials/" + name), h, null), "The EC2 metadata service");
        }, clock);
    }

    /**
     * ECS/Fargate task role: GET the credentials URI (for the relative form, {@code http://169.254.170.2} plus the path), with the
     * task's authorization token when there is one.
     */
    public static Supplier<AwsSigV4.Credentials> container(URI credentialsUri, Supplier<String> authToken, CloudHttp http, Clock clock) {
        return new Cache(() -> {
            String tok = authToken == null ? null : authToken.get();
            Map<String, String> h = tok == null || tok.isBlank() ? Map.of() : Map.of("authorization", tok.strip());
            return parse(http.send("GET", credentialsUri, h, null), "The ECS credentials endpoint");
        }, clock);
    }

    private static Map.Entry<AwsSigV4.Credentials, Instant> parse(CloudHttp.Response r, String who) {
        if (r.status() != 200) {
            throw new CloudException(who + " answered " + r.status(), r.status());
        }
        try {
            if (Json.read(new String(r.body(), StandardCharsets.UTF_8)) instanceof Map<?, ?> m && m.get("AccessKeyId") instanceof String id
                    && m.get("SecretAccessKey") instanceof String sk && !id.isBlank() && !sk.isBlank()) {
                String tok = m.get("Token") instanceof String t ? t : null;
                Instant exp = m.get("Expiration") instanceof String e ? Instant.parse(e) : Instant.now().plusSeconds(600);
                return Map.entry(new AwsSigV4.Credentials(id, sk, tok), exp);
            }
        } catch (RuntimeException e) {
            // falls through
        }
        throw new CloudException(who + " answered with something that is not credentials", 0);
    }
}
