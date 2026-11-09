package tech.kayys.wayang.api.secret.cloud;

import java.util.regex.Pattern;

/** How a (tenant, name) pair becomes a cloud secret id, without one tenant being able to name its way into another's. */
final class CloudNames {

    static final Pattern TENANT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}");
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)*");

    private CloudNames() {
    }

    static void check(String tenant, String name) {
        if (tenant == null || !TENANT.matcher(tenant).matches() || tenant.contains("..")) {
            throw new IllegalArgumentException("Tenant id cannot be used in a cloud secret id");
        }
        if (name == null || name.isEmpty() || name.length() > 100 || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Secret name cannot be used in a cloud secret id");
        }
    }

    /**
     * For services whose ids are one flat string (Azure Key Vault, GCP Secret Manager): {@code base--tenant--name}. It is
     * unambiguous because no part may contain {@code --} or start or end with {@code -}, so the split can only be where we put it.
     * {@code allowed} is what the service accepts in a part.
     */
    static String flat(String base, String tenant, String name, Pattern allowed) {
        check(tenant, name);
        StringBuilder sb = new StringBuilder();
        for (String part : base == null || base.isEmpty() ? new String[] { tenant, name } : new String[] { base, tenant, name }) {
            if (!allowed.matcher(part).matches() || part.contains("--") || part.startsWith("-") || part.endsWith("-")) {
                throw new IllegalArgumentException("A part of the secret id is not allowed by this cloud service");
            }
            if (sb.length() > 0) {
                sb.append("--");
            }
            sb.append(part);
        }
        return sb.toString();
    }
}
