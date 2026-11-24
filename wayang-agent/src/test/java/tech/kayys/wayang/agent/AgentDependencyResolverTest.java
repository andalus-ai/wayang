package tech.kayys.wayang.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class AgentDependencyResolverTest {

    private static final String BASE = """
            {
              "apiVersion":"1.0","kind":"AgentPackage",
              "metadata":{"id":"%s","version":"%s","publisher":"acme:publisher","displayName":"%s"},
              "spec":{
                "agentApiRange":"^1.0",
                "agent":{"id":"agent","model":"local/test","tools":[]},
                "interface":{"inputSchema":{"type":"object"},"outputSchema":{"type":"object"}},
                "dependencies":[%s],"configuration":[],"requestedPermissions":[]
              }
            }
            """;

    private static AgentDependencyResolver.PackageArtifact artifact(String id, String version, String dependencies, char digest) {
        var pkg = new AgentPackageReader().read(BASE.formatted(id, version, id, dependencies));
        return new AgentDependencyResolver.PackageArtifact(pkg, String.valueOf(digest).repeat(64));
    }

    @Test
    void resolvesHighestCompatibleVersionAndTransitiveClosureDeterministically() {
        var root = artifact("acme:root", "1.0.0",
                """
                {"id":"acme:child","versionRange":"^1.0","kind":"agent"}
                """, 'a');
        var childOld = artifact("acme:child", "1.0.0", "", 'b');
        var childNew = artifact("acme:child", "1.4.0",
                """
                {"id":"acme:leaf","versionRange":"~2.1","kind":"agent"}
                """, 'c');
        var leaf = artifact("acme:leaf", "2.1.5", "", 'd');

        var resolver = new AgentDependencyResolver();
        var first = resolver.resolve(root, List.of(childOld, leaf, childNew));
        var second = resolver.resolve(root, List.of(childNew, childOld, leaf));

        assertEquals(first.lock(), second.lock());
        assertEquals(first.lock().sha256(), second.lock().sha256());
        assertEquals("1.4.0", first.lock().dependencies().stream()
                .filter(d -> d.packageId().equals("acme:child")).findFirst().orElseThrow().version());
        assertEquals(2, first.lock().dependencies().size());
    }

    @Test
    void failsMissingRequiredDependencyAndIgnoresMissingOptionalDependency() {
        var required = artifact("acme:root", "1.0.0",
                """
                {"id":"acme:missing","versionRange":"^1.0","kind":"agent"}
                """, 'a');
        assertThrows(AgentDependencyResolver.ResolutionException.class,
                () -> new AgentDependencyResolver().resolve(required, List.of()));

        var optional = artifact("acme:root", "1.0.0",
                """
                {"id":"acme:missing","versionRange":"^1.0","kind":"agent","optional":true}
                """, 'a');
        assertTrue(new AgentDependencyResolver().resolve(optional, List.of()).lock().dependencies().isEmpty());
    }

    @Test
    void rejectsCyclesAndUnsupportedRequiredDependencyKinds() {
        var root = artifact("acme:root", "1.0.0",
                """
                {"id":"acme:child","versionRange":"^1.0","kind":"agent"}
                """, 'a');
        var child = artifact("acme:child", "1.0.0",
                """
                {"id":"acme:root","versionRange":"^1.0","kind":"agent"}
                """, 'b');
        assertThrows(AgentDependencyResolver.ResolutionException.class,
                () -> new AgentDependencyResolver().resolve(root, List.of(child)));

        var extension = artifact("acme:root", "1.0.0",
                """
                {"id":"acme:connector","versionRange":"^1.0","kind":"extension"}
                """, 'a');
        assertThrows(AgentDependencyResolver.ResolutionException.class,
                () -> new AgentDependencyResolver().resolve(extension, List.of()));
    }

    @Test
    void lockRejectsInvalidDigestsAndTampering() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentPackageLock.create("acme:root", "1.0.0", "not-a-digest", List.of()));
        var valid = AgentPackageLock.create("acme:root", "1.0.0", "a".repeat(64), List.of());
        assertThrows(IllegalArgumentException.class, () -> new AgentPackageLock(
                valid.packageId(), valid.version(), valid.artifactSha256(), List.of(), "b".repeat(64)));
    }
    @Test
    void rejectsDifferentDigestsForTheSameImmutablePackageVersion() {
        var root = artifact("acme:root", "1.0.0",
                """
                {"id":"acme:child","versionRange":"^1.0","kind":"agent"}
                """, 'a');
        var child = artifact("acme:child", "1.0.0", "", 'b');
        var mutated = new AgentDependencyResolver.PackageArtifact(child.agentPackage(), "c".repeat(64));
        assertThrows(AgentDependencyResolver.ResolutionException.class,
                () -> new AgentDependencyResolver().resolve(root, List.of(child, mutated)));
    }

}
