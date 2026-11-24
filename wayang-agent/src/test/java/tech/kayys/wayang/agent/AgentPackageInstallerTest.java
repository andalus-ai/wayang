package tech.kayys.wayang.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AgentPackageInstallerTest {

    private static final String MANIFEST = """
            {
              "apiVersion":"1.0","kind":"AgentPackage",
              "metadata":{"id":"acme:support","version":"1.2.0","publisher":"acme:publisher","displayName":"Support"},
              "spec":{
                "agentApiRange":"^1.0",
                "agent":{"id":"support","model":"local/test","tools":[]},
                "interface":{"inputSchema":{"type":"object"},"outputSchema":{"type":"object"}},
                "dependencies":[],
                "configuration":[
                  {"id":"crm-secret","kind":"secret-reference","required":true},
                  {"id":"crm-connection","kind":"connection-reference","required":false}
                ],
                "requestedPermissions":["secret:crm"]
              }
            }
            """;

    @Test
    void createsProjectScopedInstallationWithOnlyReferenceBindings() {
        var pkg = new AgentPackageReader().read(MANIFEST);
        var artifact = new AgentDependencyResolver.PackageArtifact(pkg, "a".repeat(64));
        var installed = new AgentPackageInstaller().install("tenant-a", "project-1", "support",
                artifact, List.of(), Map.of(
                        "crm-secret", new InstalledAgent.ConfigurationBinding("secret-reference", "secret://crm/token"),
                        "crm-connection", new InstalledAgent.ConfigurationBinding("connection-reference", "connection://crm")),
                Instant.parse("2026-11-24T00:00:00Z"));

        assertEquals("tenant-a", installed.tenantId());
        assertEquals("project-1", installed.projectId());
        assertEquals("acme:support", installed.lock().packageId());
        assertEquals("a".repeat(64), installed.lock().artifactSha256());
        assertEquals("secret://crm/token", installed.configurationBindings().get("crm-secret").reference());
        assertEquals(List.of("secret:crm"), installed.requestedPermissions());
    }

    @Test
    void requiresRequiredBindingsAndRejectsUnknownOrMismatchedBindings() {
        var pkg = new AgentPackageReader().read(MANIFEST);
        var artifact = new AgentDependencyResolver.PackageArtifact(pkg, "a".repeat(64));
        var installer = new AgentPackageInstaller();
        Instant now = Instant.parse("2026-11-24T00:00:00Z");

        assertThrows(AgentPackageInstaller.InstallationException.class,
                () -> installer.install("t", "p", "i", artifact, List.of(), Map.of(), now));
        assertThrows(AgentPackageInstaller.InstallationException.class,
                () -> installer.install("t", "p", "i", artifact, List.of(), Map.of(
                        "unknown", new InstalledAgent.ConfigurationBinding("secret-reference", "secret://x")), now));
        assertThrows(AgentPackageInstaller.InstallationException.class,
                () -> installer.install("t", "p", "i", artifact, List.of(), Map.of(
                        "crm-secret", new InstalledAgent.ConfigurationBinding("connection-reference", "connection://x")), now));
    }

    @Test
    void rejectsLiteralSecretMaterialInBinding() {
        assertThrows(IllegalArgumentException.class,
                () -> new InstalledAgent.ConfigurationBinding("secret-reference", "plain-secret-value"));
    }
}
