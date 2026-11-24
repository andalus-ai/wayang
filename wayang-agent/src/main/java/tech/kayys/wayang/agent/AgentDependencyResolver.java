package tech.kayys.wayang.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves an Agent Package dependency closure against an explicit, already available catalog.
 * This class performs no network access, downloads, signature verification, or installation.
 */
public final class AgentDependencyResolver {

    private static final Pattern VERSION = Pattern.compile(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?");

    public Resolution resolve(PackageArtifact root, Collection<PackageArtifact> available) {
        Objects.requireNonNull(root, "root");
        Map<String, List<PackageArtifact>> catalog = new HashMap<>();
        if (available != null) {
            for (PackageArtifact artifact : available) {
                Objects.requireNonNull(artifact, "available artifact");
                catalog.computeIfAbsent(artifact.agentPackage().metadata().id(), ignored -> new ArrayList<>()).add(artifact);
            }
        }
        catalog.computeIfAbsent(root.agentPackage().metadata().id(), ignored -> new ArrayList<>()).add(root);
        catalog.forEach((packageId, list) -> {
            Map<String, String> digestByVersion = new HashMap<>();
            for (PackageArtifact artifact : list) {
                String version = artifact.agentPackage().metadata().version();
                String previousDigest = digestByVersion.putIfAbsent(version, artifact.sha256());
                if (previousDigest != null && !previousDigest.equals(artifact.sha256())) {
                    throw new ResolutionException("Package '" + packageId + "' version '" + version
                            + "' has multiple artifact digests");
                }
            }
            list.sort(Comparator
                    .comparing((PackageArtifact a) -> a.agentPackage().metadata().version(), AgentDependencyResolver::compareVersions)
                    .reversed()
                    .thenComparing(PackageArtifact::sha256));
        });

        Map<String, PackageArtifact> selected = new LinkedHashMap<>();
        Set<String> visiting = new LinkedHashSet<>();
        List<AgentPackageLock.LockedDependency> locked = new ArrayList<>();
        visit(root, catalog, selected, visiting, locked);
        return new Resolution(AgentPackageLock.create(root.agentPackage().metadata().id(),
                root.agentPackage().metadata().version(), root.sha256(), locked), List.copyOf(selected.values()));
    }

    private void visit(PackageArtifact current, Map<String, List<PackageArtifact>> catalog,
            Map<String, PackageArtifact> selected, Set<String> visiting,
            List<AgentPackageLock.LockedDependency> locked) {
        String currentId = current.agentPackage().metadata().id();
        if (!visiting.add(currentId)) {
            throw new ResolutionException("Agent dependency cycle detected: "
                    + String.join(" -> ", visiting) + " -> " + currentId);
        }
        for (AgentPackageReader.Dependency dependency : current.agentPackage().dependencies()) {
            if (!"agent".equals(dependency.kind())) {
                if (!dependency.optional()) {
                    throw new ResolutionException("Dependency kind '" + dependency.kind()
                            + "' is not resolvable by the Agent Package resolver: " + dependency.id());
                }
                continue;
            }
            List<PackageArtifact> candidates = catalog.getOrDefault(dependency.id(), List.of());
            PackageArtifact match = candidates.stream()
                    .filter(candidate -> matches(candidate.agentPackage().metadata().version(), dependency.versionRange()))
                    .findFirst().orElse(null);
            if (match == null) {
                if (dependency.optional()) continue;
                throw new ResolutionException("No available version of '" + dependency.id()
                        + "' satisfies '" + dependency.versionRange() + "'");
            }
            PackageArtifact previous = selected.get(dependency.id());
            if (previous != null) {
                if (!previous.sha256().equalsIgnoreCase(match.sha256())) {
                    if (!matches(previous.agentPackage().metadata().version(), dependency.versionRange())) {
                        throw new ResolutionException("Conflicting version constraints for '" + dependency.id() + "'");
                    }
                    match = previous;
                }
                continue;
            }
            if (visiting.contains(dependency.id())) {
                throw new ResolutionException("Agent dependency cycle detected: "
                        + String.join(" -> ", visiting) + " -> " + dependency.id());
            }
            selected.put(dependency.id(), match);
            visit(match, catalog, selected, visiting, locked);
            locked.add(new AgentPackageLock.LockedDependency("agent", dependency.id(),
                    match.agentPackage().metadata().version(), match.sha256()));
        }
        visiting.remove(currentId);
    }

    static boolean matches(String version, String range) {
        parts(version); // validate the candidate even when the range is a wildcard
        String expression = range == null ? "" : range.trim();
        if (expression.equals("*") || expression.equalsIgnoreCase("x")) return true;
        if (expression.startsWith("^") || expression.startsWith("~")) {
            String baseExpression = expression.substring(1);
            int[] base = parts(baseExpression);
            if (hasPrerelease(version) && !hasPrerelease(baseExpression)) return false;
            if (compareVersions(version, canonical(base)) < 0) return false;
            int[] upper = base.clone();
            if (expression.charAt(0) == '^') {
                if (base[0] > 0) { upper[0]++; upper[1] = 0; upper[2] = 0; }
                else if (base[1] > 0) { upper[1]++; upper[2] = 0; }
                else upper[2]++;
            } else {
                upper[1]++;
                upper[2] = 0;
            }
            return compareVersions(version, canonical(upper)) < 0;
        }
        if (VERSION.matcher(expression).matches()) return compareVersions(version, expression) == 0;
        for (String token : expression.split("[ ,]+")) {
            if (token.isBlank()) continue;
            String operator = token.startsWith(">=") || token.startsWith("<=") ? token.substring(0, 2)
                    : token.substring(0, 1);
            if (!Set.of(">=", "<=", ">", "<", "=").contains(operator)) {
                throw new ResolutionException("Unsupported version range '" + range + "'");
            }
            String boundary = token.substring(operator.length());
            if (hasPrerelease(version) && !hasPrerelease(boundary)) return false;
            int comparison = compareVersions(version, boundary);
            boolean accepted = switch (operator) {
                case ">=" -> comparison >= 0;
                case "<=" -> comparison <= 0;
                case ">" -> comparison > 0;
                case "<" -> comparison < 0;
                case "=" -> comparison == 0;
                default -> false;
            };
            if (!accepted) return false;
        }
        return true;
    }

    private static int[] parts(String value) {
        Matcher matcher = VERSION.matcher(value);
        if (matcher.matches()) {
            return new int[] { Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)) };
        }
        // Dependency ranges commonly use ^1.0 or ~2.3; omitted components are zero.
        String[] components = value.split("\\.", -1);
        if (components.length < 1 || components.length > 3) {
            throw new ResolutionException("Invalid semantic version '" + value + "'");
        }
        int[] result = new int[] { 0, 0, 0 };
        for (int i = 0; i < components.length; i++) {
            if (!components[i].matches("0|[1-9][0-9]*")) {
                throw new ResolutionException("Invalid semantic version '" + value + "'");
            }
            result[i] = Integer.parseInt(components[i]);
        }
        return result;
    }

    private static int compareVersions(String left, String right) {
        Matcher leftMatcher = VERSION.matcher(left);
        Matcher rightMatcher = VERSION.matcher(right);
        if (!leftMatcher.matches()) throw new ResolutionException("Invalid semantic version '" + left + "'");
        if (!rightMatcher.matches()) {
            int[] padded = parts(right);
            right = canonical(padded);
            rightMatcher = VERSION.matcher(right);
            rightMatcher.matches();
        }
        int[] leftParts = new int[] { Integer.parseInt(leftMatcher.group(1)), Integer.parseInt(leftMatcher.group(2)),
                Integer.parseInt(leftMatcher.group(3)) };
        int[] rightParts = new int[] { Integer.parseInt(rightMatcher.group(1)), Integer.parseInt(rightMatcher.group(2)),
                Integer.parseInt(rightMatcher.group(3)) };
        int numeric = compare(leftParts, rightParts);
        if (numeric != 0) return numeric;
        String leftPre = leftMatcher.group(4);
        String rightPre = rightMatcher.group(4);
        if (leftPre == null && rightPre == null) return 0;
        if (leftPre == null) return 1;
        if (rightPre == null) return -1;
        String[] a = leftPre.split("\\.");
        String[] b = rightPre.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            boolean an = a[i].matches("[0-9]+");
            boolean bn = b[i].matches("[0-9]+");
            int result;
            if (an && bn) result = new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i]));
            else if (an != bn) result = an ? -1 : 1;
            else result = a[i].compareTo(b[i]);
            if (result != 0) return result;
        }
        return Integer.compare(a.length, b.length);
    }

    private static boolean hasPrerelease(String value) {
        Matcher matcher = VERSION.matcher(value);
        return matcher.matches() && matcher.group(4) != null;
    }

    private static String canonical(int[] parts) {
        return parts[0] + "." + parts[1] + "." + parts[2];
    }

    private static int compare(int[] left, int[] right) {
        for (int i = 0; i < 3; i++) {
            int result = Integer.compare(left[i], right[i]);
            if (result != 0) return result;
        }
        return 0;
    }

    public record PackageArtifact(AgentPackageReader.AgentPackage agentPackage, String sha256) {
        public PackageArtifact {
            Objects.requireNonNull(agentPackage, "agentPackage");
            if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}"))
                throw new IllegalArgumentException("Artifact SHA-256 digest is required");
            sha256 = sha256.toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record Resolution(AgentPackageLock lock, List<PackageArtifact> dependencyClosure) {
        public Resolution {
            Objects.requireNonNull(lock, "lock");
            dependencyClosure = List.copyOf(dependencyClosure);
        }
    }

    public static final class ResolutionException extends IllegalArgumentException {
        public ResolutionException(String message) { super(message); }
    }
}
