package tech.kayys.wayang.publish;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit allow-list of artifact repositories accepted by a release resolver.
 *
 * <p>This policy is intentionally independent of Maven Resolver so that the
 * same trust boundary can be applied by future downloaders and deployment
 * agents. It does not fetch artifacts or establish that a repository is
 * trustworthy; callers must still verify artifact digests and signatures.</p>
 */
public record RepositoryPolicy(List<Repository> repositories) {

    public record Repository(String id, URI baseUri) {
        public Repository {
            if (id == null || !id.matches("[a-zA-Z][a-zA-Z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("Repository id must be a short stable identifier");
            }
            baseUri = normalize(Objects.requireNonNull(baseUri, "baseUri"));
        }
    }

    public RepositoryPolicy {
        repositories = List.copyOf(Objects.requireNonNull(repositories, "repositories"));
        if (repositories.isEmpty()) {
            throw new IllegalArgumentException("At least one artifact repository must be configured");
        }
        Set<String> ids = new HashSet<>();
        Set<URI> urls = new HashSet<>();
        for (Repository repository : repositories) {
            Objects.requireNonNull(repository, "repository");
            if (!ids.add(repository.id())) {
                throw new IllegalArgumentException("Duplicate repository id: " + repository.id());
            }
            if (!urls.add(repository.baseUri())) {
                throw new IllegalArgumentException("Duplicate repository URL: " + repository.baseUri());
            }
        }
    }

    /** Returns true only when the candidate is beneath an explicitly configured repository base. */
    public boolean allows(URI candidate) {
        URI normalized = normalize(candidate);
        for (Repository repository : repositories) {
            URI base = repository.baseUri();
            if (!sameOrigin(base, normalized)) continue;
            String basePath = base.getPath();
            String candidatePath = normalized.getPath();
            if (candidatePath.equals(basePath.substring(0, basePath.length() - 1))
                    || candidatePath.startsWith(basePath)) {
                return true;
            }
        }
        return false;
    }

    /** Rejects a repository URL that is not covered by this policy. */
    public void requireAllowed(URI candidate) {
        if (!allows(candidate)) {
            throw new IllegalArgumentException("Artifact URL is outside the configured repository allow-list: " + candidate);
        }
    }

    private static URI normalize(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Artifact repositories must use an absolute HTTPS URL without credentials, query, or fragment");
        }
        String path = uri.normalize().getPath();
        if (path == null || path.isBlank()) path = "/";
        if (!path.startsWith("/")) path = "/" + path;
        if (!path.endsWith("/")) path += "/";
        try {
            return new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), path, null, null);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid artifact repository URL: " + uri, e);
        }
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }
}
