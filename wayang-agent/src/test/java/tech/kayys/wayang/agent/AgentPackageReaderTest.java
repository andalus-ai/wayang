package tech.kayys.wayang.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AgentPackageReaderTest {

    private static final String MINIMAL = """
            {
              "apiVersion": "1.0",
              "kind": "AgentPackage",
              "metadata": {
                "id": "acme:support-triage",
                "version": "1.0.0",
                "publisher": "acme:publisher",
                "displayName": "Support Triage"
              },
              "spec": {
                "agentApiRange": "^1.0",
                "agent": {
                  "id": "support-triage",
                  "model": "openai/gpt-4o",
                  "tools": [],
                  "maxSteps": 8,
                  "timeoutSeconds": 180,
                  "requireApproval": [],
                  "outputKeys": ["category"]
                },
                "interface": {
                  "inputSchema": {
                    "type": "object",
                    "required": ["ticket"],
                    "properties": {"ticket": {"type": "string"}}
                  },
                  "outputSchema": {
                    "type": "object",
                    "required": ["category"],
                    "properties": {"category": {"type": "string"}}
                  }
                },
                "triggers": ["chat", "api"],
                "dependencies": [],
                "configuration": [],
                "requestedPermissions": []
              }
            }
            """;

    private final AgentPackageReader reader = new AgentPackageReader();

    @Test
    void readsJsonManifestAndMapsExistingAgentSpec() {
        var pkg = reader.read(MINIMAL);
        assertEquals("acme:support-triage", pkg.metadata().id());
        assertEquals("1.0.0", pkg.metadata().version());
        assertEquals("support-triage", pkg.agent().id());
        assertEquals("openai/gpt-4o", pkg.agent().model());
        assertEquals(8, pkg.agent().maxSteps());
        assertEquals(List.of("chat", "api"), pkg.triggers());
        assertEquals("object", pkg.agentInterface().inputSchema().get("type"));
    }

    @Test
    void readsYamlAndPreservesPersonaAndGuardrails() {
        String yaml = """
                apiVersion: "1.0"
                kind: AgentPackage
                metadata:
                  id: acme:assistant
                  version: 1.2.0
                  publisher: acme:publisher
                  displayName: Assistant
                spec:
                  agentApiRange: "^1.0"
                  agent:
                    id: assistant
                    model: local/llama
                    tools: []
                    options:
                      persona:
                        role: Researcher
                        tone: precise
                      guardrails:
                        maxOutputChars: 4000
                  interface:
                    inputSchema: {type: object}
                    outputSchema: {type: object}
                  dependencies: []
                  configuration: []
                  requestedPermissions: []
                """;
        var pkg = reader.read(yaml);
        assertEquals("Researcher", AgentPersona.of(pkg.agent()).role());
        assertEquals(4000, AgentGuardrails.of(pkg.agent()).maxOutputChars());
    }

    @Test
    void readsDependenciesAndSecretReferencesWithoutGrantingThem() {
        String source = MINIMAL.replace("\"dependencies\": []",
                "\"dependencies\": [{\"id\":\"acme:crm-connector\",\"versionRange\":\"^2.0\",\"kind\":\"extension\"}]")
                .replace("\"configuration\": []",
                "\"configuration\": [{\"id\":\"crm-token\",\"kind\":\"secret-reference\",\"required\":true}]")
                .replace("\"requestedPermissions\": []", "\"requestedPermissions\": [\"secret:crm-token\"]");
        var pkg = reader.read(source);
        assertEquals("acme:crm-connector", pkg.dependencies().getFirst().id());
        assertEquals("secret-reference", pkg.configuration().getFirst().kind());
        assertEquals(List.of("secret:crm-token"), pkg.requestedPermissions());
    }

    @Test
    void rejectsUnknownFieldsAndMissingInterface() {
        assertThrows(AgentPackageException.class, () -> reader.read(MINIMAL.replace(
                "\"displayName\": \"Support Triage\"", "\"displayName\": \"Support Triage\", \"unknown\": true")));
        assertThrows(AgentPackageException.class, () -> reader.read(MINIMAL.replace(
                "\"interface\": {", "\"ignoredInterface\": {")));
    }

    @Test
    void rejectsInvalidAgentDefinitionAndFractionalLimits() {
        assertThrows(AgentPackageException.class, () -> reader.read(MINIMAL.replace(
                "\"maxSteps\": 8", "\"maxSteps\": 2.5")));
        assertThrows(AgentPackageException.class, () -> reader.read(MINIMAL.replace(
                "\"id\": \"support-triage\"", "\"id\": \"invalid id\"")));
    }

    @Test
    void rejectsEmbeddedCredentialsAndUnsafeResourcePaths() {
        String withToolSecret = MINIMAL.replace("\"tools\": []",
                "\"tools\": [{\"name\":\"crm\",\"config\":{\"apiKey\":\"plain-text-secret\"}}]");
        assertThrows(AgentPackageException.class, () -> reader.read(withToolSecret));

        String withTraversal = MINIMAL.replace("\"requestedPermissions\": []",
                "\"requestedPermissions\": [], \"resources\": [{\"path\":\"../secret.txt\",\"mediaType\":\"text/plain\"}]");
        assertThrows(AgentPackageException.class, () -> reader.read(withTraversal));
    }

    @Test
    void rejectsDuplicateDependenciesAndUnsupportedTriggers() {
        String duplicate = MINIMAL.replace("\"dependencies\": []",
                "\"dependencies\": [{\"id\":\"acme:tool\",\"versionRange\":\"^1\",\"kind\":\"skill\"},"
                + "{\"id\":\"acme:tool\",\"versionRange\":\"^1\",\"kind\":\"skill\"}]");
        assertThrows(AgentPackageException.class, () -> reader.read(duplicate));
        assertThrows(AgentPackageException.class, () -> reader.read(MINIMAL.replace(
                "\"triggers\": [\"chat\", \"api\"]", "\"triggers\": [\"shell\"]")));
    }
    @Test
    @SuppressWarnings("unchecked")
    void deeplyFreezesNestedAgentOptionsAndToolConfiguration() {
        String source = MINIMAL.replace("\\"tools\\": []",
                "\\"tools\\": [{\\"name\\":\\"crm\\",\\"config\\":{\\"custom\\":{\\"items\\":[\\"safe\\"]}}}]")
                .replace("\\"tools\\": [{", "\\"tools\\": [{"); // Keep manifest JSON formatting deterministic.
        source = source.replace("\\"maxSteps\\": 8", "\\"maxSteps\\": 8, \\"options\\":{\\"custom\\":{\\"items\\":[\\"safe\\"]}}");
        var agent = reader.read(source).agent();
        Map<String, Object> options = (Map<String, Object>) agent.options().get("custom");
        List<Object> optionItems = (List<Object>) options.get("items");
        assertThrows(UnsupportedOperationException.class, () -> optionItems.add("mutated"));

        Map<String, Object> toolConfig = (Map<String, Object>) agent.tools().getFirst().config().get("custom");
        List<Object> configItems = (List<Object>) toolConfig.get("items");
        assertThrows(UnsupportedOperationException.class, () -> configItems.add("mutated"));
    }

}
