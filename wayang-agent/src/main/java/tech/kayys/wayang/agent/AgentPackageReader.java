package tech.kayys.wayang.agent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads and validates the declarative manifest of a Wayang Agent Package.
 *
 * <p>This reader does not install packages, resolve dependencies, grant permissions,
 * bind secrets, verify signatures, or activate agents. JSON is accepted because it
 * is a YAML subset; YAML input is also supported for authoring convenience.</p>
 */
public final class AgentPackageReader {

    private static final Pattern NAMESPACED_ID = Pattern.compile(
            "[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*:[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final Pattern SEMVER = Pattern.compile(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?");
    private static final Pattern LOCAL_AGENT_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern CONFIG_ID = Pattern.compile("[a-z][a-z0-9_.-]{0,127}");
    private static final Pattern PERMISSION = Pattern.compile("[a-z][a-z0-9.-]*:[A-Za-z0-9._*:/-]+");
    private static final Set<String> ROOT_KEYS = Set.of("apiVersion", "kind", "metadata", "spec");
    private static final Set<String> METADATA_KEYS = Set.of(
            "id", "version", "publisher", "displayName", "description", "license", "tags", "icon");
    private static final Set<String> SPEC_KEYS = Set.of(
            "agentApiRange", "runtimeCompatibility", "agent", "interface", "triggers",
            "dependencies", "configuration", "requestedPermissions", "evaluation", "resources");
    private static final Set<String> AGENT_KEYS = Set.of(
            "id", "name", "model", "systemPrompt", "inputTemplate", "tools", "maxSteps",
            "timeoutSeconds", "requireApproval", "outputKeys", "options");
    private static final Set<String> TOOL_KEYS = Set.of("name", "type", "description", "config");
    private static final Set<String> DEPENDENCY_KEYS = Set.of("id", "versionRange", "kind", "optional");
    private static final Set<String> CONFIGURATION_KEYS = Set.of(
            "id", "displayName", "description", "kind", "required", "schema");
    private static final Set<String> RESOURCE_KEYS = Set.of("path", "mediaType", "sha256", "role");
    private static final Set<String> EVALUATION_KEYS = Set.of("requiredBeforePublish", "suites");
    private static final Set<String> SENSITIVE_KEY = Set.of(
            "password", "passwd", "token", "apikey", "secret", "authorization",
            "credential", "privatekey", "clientsecret");

    public AgentPackage read(String source) {
        if (source == null || source.isBlank()) {
            throw invalid("Manifest content is required");
        }
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(10);
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(1024 * 1024);
        Object document;
        try {
            document = new Yaml(new SafeConstructor(options)).load(source);
        } catch (RuntimeException e) {
            throw new AgentPackageException("Invalid agent package YAML/JSON: " + e.getMessage(), e);
        }
        Map<String, Object> root = object(document, "$");
        only(root, ROOT_KEYS, "$");
        if (!"1.0".equals(string(required(root, "apiVersion", "$"), "$.apiVersion"))
                || !"AgentPackage".equals(string(required(root, "kind", "$"), "$.kind"))) {
            throw invalid("Unsupported agent package apiVersion/kind");
        }

        Map<String, Object> metadata = object(required(root, "metadata", "$"), "$.metadata");
        only(metadata, METADATA_KEYS, "$.metadata");
        String id = namespaced(required(metadata, "id", "$.metadata"), "$.metadata.id");
        String version = semver(required(metadata, "version", "$.metadata"), "$.metadata.version");
        String publisher = namespaced(required(metadata, "publisher", "$.metadata"), "$.metadata.publisher");
        String displayName = nonBlank(required(metadata, "displayName", "$.metadata"), "$.metadata.displayName");
        String description = optionalString(metadata.get("description"), "$.metadata.description");
        String license = optionalString(metadata.get("license"), "$.metadata.license");
        List<String> tags = strings(metadata.get("tags"), "$.metadata.tags");
        String icon = optionalString(metadata.get("icon"), "$.metadata.icon");
        if (icon != null && !icon.startsWith("https://")) {
            safeResourcePath(icon, "$.metadata.icon");
        }

        Map<String, Object> spec = object(required(root, "spec", "$"), "$.spec");
        only(spec, SPEC_KEYS, "$.spec");
        String agentApiRange = nonBlank(required(spec, "agentApiRange", "$.spec"), "$.spec.agentApiRange");
        String runtimeCompatibility = optionalString(spec.get("runtimeCompatibility"), "$.spec.runtimeCompatibility");
        Map<String, Object> agentMap = object(required(spec, "agent", "$.spec"), "$.spec.agent");
        validateAgentMap(agentMap);
        AgentSpec agent;
        try {
            agent = immutableAgent(AgentSpecLoader.fromMap(agentMap));
        } catch (RuntimeException e) {
            throw new AgentPackageException("Invalid embedded AgentSpec: " + e.getMessage(), e);
        }

        Map<String, Object> api = object(required(spec, "interface", "$.spec"), "$.spec.interface");
        only(api, Set.of("inputSchema", "outputSchema"), "$.spec.interface");
        Map<String, Object> inputSchema = object(
                required(api, "inputSchema", "$.spec.interface"), "$.spec.interface.inputSchema");
        Map<String, Object> outputSchema = object(
                required(api, "outputSchema", "$.spec.interface"), "$.spec.interface.outputSchema");

        List<String> triggers = strings(spec.get("triggers"), "$.spec.triggers");
        Set<String> allowedTriggers = Set.of("chat", "api", "webhook", "schedule", "event");
        for (String trigger : triggers) {
            if (!allowedTriggers.contains(trigger)) {
                throw invalid("Unsupported trigger '" + trigger + "'");
            }
        }
        unique(triggers, "$.spec.triggers");

        List<Dependency> dependencies = dependencies(spec.get("dependencies"));
        List<ConfigurationRequirement> configuration = configuration(spec.get("configuration"));
        List<String> permissions = strings(spec.get("requestedPermissions"), "$.spec.requestedPermissions");
        for (String permission : permissions) {
            if (!PERMISSION.matcher(permission).matches()) {
                throw invalid("Invalid requested permission '" + permission + "'");
            }
        }
        unique(permissions, "$.spec.requestedPermissions");

        Map<String, Object> evaluation = spec.get("evaluation") == null
                ? Map.of() : object(spec.get("evaluation"), "$.spec.evaluation");
        only(evaluation, EVALUATION_KEYS, "$.spec.evaluation");
        boolean evaluationRequired = boolOr(evaluation.get("requiredBeforePublish"), false,
                "$.spec.evaluation.requiredBeforePublish");
        List<Resource> evaluationSuites = resources(evaluation.get("suites"), "$.spec.evaluation.suites");
        List<Resource> resources = resources(spec.get("resources"), "$.spec.resources");

        return new AgentPackage(
                new Metadata(id, version, publisher, displayName, description, license, tags, icon),
                agentApiRange, runtimeCompatibility, agent,
                new AgentInterface(inputSchema, outputSchema), triggers, dependencies, configuration,
                permissions, new Evaluation(evaluationRequired, evaluationSuites), resources);
    }


    private static AgentSpec immutableAgent(AgentSpec source) {
        List<AgentSpec.Tool> tools = source.tools().stream()
                .map(tool -> new AgentSpec.Tool(tool.name(), tool.type(), tool.description(), immutable(tool.config())))
                .toList();
        return new AgentSpec(source.id(), source.name(), source.model(), source.systemPrompt(), source.inputTemplate(),
                tools, source.maxSteps(), source.timeoutSeconds(), source.requireApproval(), source.outputKeys(),
                immutable(source.options()));
    }

    private static void validateAgentMap(Map<String, Object> agent) {
        only(agent, AGENT_KEYS, "$.spec.agent");
        String id = nonBlank(required(agent, "id", "$.spec.agent"), "$.spec.agent.id");
        if (!LOCAL_AGENT_ID.matcher(id).matches()) {
            throw invalid("Invalid embedded AgentSpec id '" + id + "'");
        }
        nonBlank(required(agent, "model", "$.spec.agent"), "$.spec.agent.model");
        optionalString(agent.get("name"), "$.spec.agent.name");
        optionalString(agent.get("systemPrompt"), "$.spec.agent.systemPrompt");
        optionalString(agent.get("inputTemplate"), "$.spec.agent.inputTemplate");
        integerOr(agent.get("maxSteps"), 10, 1, AgentSpec.MAX_STEPS_LIMIT, "$.spec.agent.maxSteps");
        integerOr(agent.get("timeoutSeconds"), 120, 1, AgentSpec.MAX_TIMEOUT_SECONDS,
                "$.spec.agent.timeoutSeconds");
        strings(agent.get("requireApproval"), "$.spec.agent.requireApproval");
        strings(agent.get("outputKeys"), "$.spec.agent.outputKeys");

        Object toolsValue = agent.get("tools");
        if (toolsValue != null) {
            for (Object item : list(toolsValue, "$.spec.agent.tools")) {
                Map<String, Object> tool = object(item, "$.spec.agent.tools[]");
                only(tool, TOOL_KEYS, "$.spec.agent.tools[]");
                nonBlank(required(tool, "name", "$.spec.agent.tools[]"), "$.spec.agent.tools[].name");
                optionalString(tool.get("type"), "$.spec.agent.tools[].type");
                optionalString(tool.get("description"), "$.spec.agent.tools[].description");
                if (tool.get("config") != null) {
                    Map<String, Object> config = object(tool.get("config"), "$.spec.agent.tools[].config");
                    rejectSecretMaterial(config, "$.spec.agent.tools[].config");
                }
            }
        }
        if (agent.get("options") != null) {
            Map<String, Object> opts = object(agent.get("options"), "$.spec.agent.options");
            rejectSecretMaterial(opts, "$.spec.agent.options");
        }
    }

    private static void rejectSecretMaterial(Map<String, Object> values, String path) {
        values.forEach((key, value) -> {
            String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
            boolean reference = normalized.endsWith("ref") || normalized.endsWith("reference");
            boolean secretLike = SENSITIVE_KEY.stream().anyMatch(normalized::endsWith)
                    || normalized.startsWith("secret") || normalized.startsWith("password")
                    || normalized.startsWith("passwd") || normalized.startsWith("credential")
                    || normalized.startsWith("privatekey") || normalized.startsWith("authorization");
            if (!reference && secretLike) {
                throw invalid("Secret-like field '" + path + "." + key
                        + "' is not allowed; use an approved secret reference");
            }
            if (value instanceof Map<?, ?> nested) {
                rejectSecretMaterial(object(nested, path + "." + key), path + "." + key);
            } else if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) instanceof Map<?, ?> nested) {
                        rejectSecretMaterial(object(nested, path + "." + key + "[" + i + "]"),
                                path + "." + key + "[" + i + "]");
                    }
                }
            }
        });
    }

    private static List<Dependency> dependencies(Object value) {
        List<Dependency> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : optionalList(value, "$.spec.dependencies")) {
            Map<String, Object> map = object(item, "$.spec.dependencies[]");
            only(map, DEPENDENCY_KEYS, "$.spec.dependencies[]");
            String id = namespaced(required(map, "id", "$.spec.dependencies[]"), "$.spec.dependencies[].id");
            String range = nonBlank(required(map, "versionRange", "$.spec.dependencies[]"),
                    "$.spec.dependencies[].versionRange");
            String kind = nonBlank(required(map, "kind", "$.spec.dependencies[]"), "$.spec.dependencies[].kind");
            if (!Set.of("agent", "skill", "extension", "workflow-template", "solution").contains(kind)) {
                throw invalid("Unsupported dependency kind '" + kind + "'");
            }
            boolean optional = boolOr(map.get("optional"), false, "$.spec.dependencies[].optional");
            if (!seen.add(kind + ":" + id)) {
                throw invalid("Duplicate dependency '" + kind + ":" + id + "'");
            }
            out.add(new Dependency(id, range, kind, optional));
        }
        return List.copyOf(out);
    }

    private static List<ConfigurationRequirement> configuration(Object value) {
        List<ConfigurationRequirement> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : optionalList(value, "$.spec.configuration")) {
            Map<String, Object> map = object(item, "$.spec.configuration[]");
            only(map, CONFIGURATION_KEYS, "$.spec.configuration[]");
            String id = nonBlank(required(map, "id", "$.spec.configuration[]"), "$.spec.configuration[].id");
            if (!CONFIG_ID.matcher(id).matches() || !seen.add(id)) {
                throw invalid("Invalid or duplicate configuration requirement id '" + id + "'");
            }
            String kind = nonBlank(required(map, "kind", "$.spec.configuration[]"), "$.spec.configuration[].kind");
            if (!Set.of("secret-reference", "connection-reference", "value").contains(kind)) {
                throw invalid("Unsupported configuration kind '" + kind + "'");
            }
            boolean required = boolOr(required(map, "required", "$.spec.configuration[]"), false,
                    "$.spec.configuration[].required");
            String displayName = optionalString(map.get("displayName"), "$.spec.configuration[].displayName");
            String description = optionalString(map.get("description"), "$.spec.configuration[].description");
            Map<String, Object> schema = map.get("schema") == null ? Map.of()
                    : object(map.get("schema"), "$.spec.configuration[].schema");
            out.add(new ConfigurationRequirement(id, displayName, description, kind, required, schema));
        }
        return List.copyOf(out);
    }

    private static List<Resource> resources(Object value, String path) {
        List<Resource> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : optionalList(value, path)) {
            Map<String, Object> map = object(item, path + "[]");
            only(map, RESOURCE_KEYS, path + "[]");
            String resourcePath = safeResourcePath(
                    required(map, "path", path + "[]"), path + "[].path");
            if (!seen.add(resourcePath)) {
                throw invalid("Duplicate package resource path '" + resourcePath + "'");
            }
            String mediaType = nonBlank(required(map, "mediaType", path + "[]"), path + "[].mediaType");
            String digest = optionalString(map.get("sha256"), path + "[].sha256");
            if (digest != null && !digest.matches("[a-fA-F0-9]{64}")) {
                throw invalid("Invalid SHA-256 digest at " + path + "[].sha256");
            }
            String role = optionalString(map.get("role"), path + "[].role");
            out.add(new Resource(resourcePath, mediaType, digest, role));
        }
        return List.copyOf(out);
    }

    private static String safeResourcePath(Object value, String path) {
        String text = nonBlank(value, path);
        if (text.startsWith("/") || text.startsWith("\\") || text.contains("\\")
                || text.matches("^[A-Za-z]:.*")) {
            throw invalid("Unsafe package resource path at " + path);
        }
        for (String segment : text.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw invalid("Unsafe package resource path at " + path);
            }
        }
        return text;
    }

    private static String namespaced(Object value, String path) {
        String text = nonBlank(value, path);
        if (!NAMESPACED_ID.matcher(text).matches()) {
            throw invalid("Invalid namespaced identifier at " + path + ": " + text);
        }
        return text;
    }

    private static String semver(Object value, String path) {
        String text = nonBlank(value, path);
        if (!SEMVER.matcher(text).matches()) {
            throw invalid("Invalid SemVer at " + path + ": " + text);
        }
        return text;
    }

    private static String nonBlank(Object value, String path) {
        String text = string(value, path);
        if (text.isBlank()) {
            throw invalid("Value must not be blank at " + path);
        }
        return text;
    }

    private static String optionalString(Object value, String path) {
        if (value == null) return null;
        return string(value, path);
    }

    private static String string(Object value, String path) {
        if (!(value instanceof String text)) {
            throw invalid("Expected a string at " + path);
        }
        return text;
    }

    private static int integerOr(Object value, int fallback, int min, int max, String path) {
        if (value == null) return fallback;
        try {
            int number = value instanceof Number n
                    ? new BigDecimal(n.toString()).intValueExact()
                    : new BigDecimal(string(value, path)).intValueExact();
            if (number < min || number > max) throw invalid("Integer out of range at " + path);
            return number;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new AgentPackageException("Expected an integer at " + path, e);
        }
    }

    private static boolean boolOr(Object value, boolean fallback, String path) {
        if (value == null) return fallback;
        if (!(value instanceof Boolean result)) throw invalid("Expected a boolean at " + path);
        return result;
    }

    private static List<String> strings(Object value, String path) {
        List<String> out = new ArrayList<>();
        for (Object item : optionalList(value, path)) out.add(string(item, path + "[]"));
        return List.copyOf(out);
    }

    private static List<?> optionalList(Object value, String path) {
        return value == null ? List.of() : list(value, path);
    }

    private static List<?> list(Object value, String path) {
        if (!(value instanceof List<?> list)) throw invalid("Expected a list at " + path);
        return list;
    }

    private static Map<String, Object> object(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) throw invalid("Expected an object at " + path);
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (!(key instanceof String)) throw invalid("Object keys must be strings at " + path);
            out.put((String) key, item);
        });
        return out;
    }

    private static Object required(Map<String, Object> map, String key, String path) {
        if (!map.containsKey(key) || map.get(key) == null) throw invalid("Missing required field " + path + "." + key);
        return map.get(key);
    }

    private static void only(Map<String, Object> map, Set<String> allowed, String path) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) throw invalid("Unknown field " + path + "." + key);
        }
    }

    private static void unique(List<String> values, String path) {
        if (new HashSet<>(values).size() != values.size()) throw invalid("Duplicate values at " + path);
    }

    private static AgentPackageException invalid(String message) {
        return new AgentPackageException(message);
    }

    private static Map<String, Object> immutable(Map<String, Object> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), immutableValue(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(list.stream().map(AgentPackageReader::immutableValue).toList());
        }
        return value;
    }

    public record AgentPackage(
            Metadata metadata,
            String agentApiRange,
            String runtimeCompatibility,
            AgentSpec agent,
            AgentInterface agentInterface,
            List<String> triggers,
            List<Dependency> dependencies,
            List<ConfigurationRequirement> configuration,
            List<String> requestedPermissions,
            Evaluation evaluation,
            List<Resource> resources) {
        public AgentPackage {
            triggers = List.copyOf(triggers);
            dependencies = List.copyOf(dependencies);
            configuration = List.copyOf(configuration);
            requestedPermissions = List.copyOf(requestedPermissions);
            resources = List.copyOf(resources);
        }
    }

    public record Metadata(String id, String version, String publisher, String displayName,
            String description, String license, List<String> tags, String icon) {
        public Metadata { tags = List.copyOf(tags); }
    }

    public record AgentInterface(Map<String, Object> inputSchema, Map<String, Object> outputSchema) {
        public AgentInterface {
            inputSchema = immutable(inputSchema);
            outputSchema = immutable(outputSchema);
        }
    }

    public record Dependency(String id, String versionRange, String kind, boolean optional) { }

    public record ConfigurationRequirement(String id, String displayName, String description,
            String kind, boolean required, Map<String, Object> schema) {
        public ConfigurationRequirement { schema = immutable(schema); }
    }

    public record Evaluation(boolean requiredBeforePublish, List<Resource> suites) {
        public Evaluation { suites = List.copyOf(suites); }
    }

    public record Resource(String path, String mediaType, String sha256, String role) { }
}
