package tech.kayys.wayang.publish;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import tech.kayys.wayang.connectors.Connectors;
import tech.kayys.wayang.embed.ReleaseBundle;
import tech.kayys.wayang.embed.ReleaseSecrets;
import tech.kayys.wayang.runtime.json.Json;
import tech.kayys.wayang.runtime.Extensions;

/**
 * Turns a published workspace bundle into the source of a release: a Maven project that depends only on what the workflows use
 * (the embedded runtime, plus the connector library only when a workflow calls a connector), carries the bundle as a resource,
 * and has a small generated {@code App} (command line, or HTTP service) plus a Dockerfile and a list of the secrets and settings
 * it needs. Pure and deterministic: the same bundle and options give byte-identical output, so a release is reproducible.
 *
 * <p>The bundle must come from an export of <em>published</em> versions (see {@link ReleaseBundle#parse}); this class does not
 * re-verify its integrity, the caller that produced it did.
 */
public final class Publisher {

    /** The generated project: path to bytes, and what was found while analysing the bundle. */
    public record Project(Map<String, byte[]> files, List<String> processes, List<String> components, List<String> connectors, boolean usesAgents, List<String> requiredSecrets,
            List<String> notes) {
    }

    private Publisher() {
    }

    /** @throws IllegalArgumentException when the bundle cannot be released (not published, unknown connector, no process, ...) */
    public static Project generate(String bundleJson, ReleaseOptions o) {
        ReleaseBundle bundle = ReleaseBundle.parse(bundleJson);
        List<String> processes = new ArrayList<>();
        Set<String> used = new TreeSet<>();
        boolean agents = false;
        for (ReleaseBundle.Definition d : bundle.definitions()) {
            if (d.kind().equals("PROCESS")) {
                processes.add(d.id());
                collectConnectors(d.document().get("elements"), used);
                if (d.document().get("elements") instanceof List<?> els) {
                    for (Object e : els) {
                        if (e instanceof Map<?, ?> m && "AGENT_TASK".equalsIgnoreCase(String.valueOf(m.get("type")))) {
                            agents = true;
                        }
                    }
                }
            }
        }
        if (processes.isEmpty()) {
            throw new IllegalArgumentException("The bundle has no process to release");
        }
        tech.kayys.wayang.runtime.Profile profile = tech.kayys.wayang.runtime.Profile.parse(o.profile());
        for (var plan : profile == tech.kayys.wayang.runtime.Profile.FULL ? List.<tech.kayys.wayang.bpmn.ExecutionPlan>of()
                : new tech.kayys.wayang.embed.ReleaseDefinitions(bundle).plans().values()) {
            List<String> bad = profile.violations(plan);
            if (!bad.isEmpty()) {
                throw new IllegalArgumentException("Process '" + plan.processId() + "' does not fit the '" + profile + "' profile: "
                        + String.join("; ", bad));
            }
        }
        Extensions extensions = Extensions.load(profile);
        Set<String> known = Connectors.defaults(new tech.kayys.wayang.connectors.UrlGuard(false, Set.of()), extensions).keySet();
        ComponentLockfile.Result componentLock = ComponentLockfile.create(bundle, profile, extensions);
        addAgentToolConnectors(bundle, used, known);
        used.remove(tech.kayys.wayang.embed.AgentActions.NAME); // built in: it only needs the other connectors and the secrets
        List<String> unknown = used.stream().filter(c -> !known.contains(c)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("These connectors are not available in a release: " + unknown);
        }
        Set<String> pollSources = new TreeSet<>();
        Set<String> providers = new TreeSet<>();
        for (ReleaseBundle.Trigger t : bundle.triggers()) {
            if (t.schedule()) {
                continue; // runs by the clock: no poller, no provider, no connector
            }
            if (t.webhook()) {
                if (t.provider() != null) {
                    providers.add(t.provider());
                }
            } else if (t.config().get("source") instanceof String src) {
                pollSources.add(src);
            } else {
                throw new IllegalArgumentException("Poll trigger " + t.id() + " has no source");
            }
        }
        List<String> unknownTriggers = new ArrayList<>();
        pollSources.stream().filter(x -> !Connectors.pollers(new tech.kayys.wayang.connectors.UrlGuard(false, Set.of())).containsKey(x))
                .forEach(x -> unknownTriggers.add("poll source " + x));
        providers.stream().filter(x -> !Connectors.webhookProviders().containsKey(x)).forEach(x -> unknownTriggers.add("webhook provider " + x));
        if (!unknownTriggers.isEmpty()) {
            throw new IllegalArgumentException("These trigger types are not available in a release: " + unknownTriggers);
        }
        boolean multimodal = used.stream().anyMatch(MULTIMODAL_CONNECTORS::contains);
        boolean needsConnectors = !used.isEmpty() || !pollSources.isEmpty() || !providers.isEmpty();
        List<String> notes = new ArrayList<>();
        List<String> badSecrets = bundle.requiredSecrets().stream().filter(s -> !ReleaseSecrets.supported(s)).toList();
        if (!badSecrets.isEmpty()) {
            throw new IllegalArgumentException("These secrets use a Vault/cloud/file form a release does not support (use plain names, provided "
                    + "as WAYANG_SECRET_<NAME> or a file): " + badSecrets);
        }
        if (agents) {
            notes.add("The workflows use agent tasks: they run through the built-in chat-model loop in Agents.java (model provider/model; key = secret llm.<provider>.api-key; endpoint "
                    + "WAYANG_LLM_<PROVIDER>_URL; tools = the connectors of this release; a tool listed in requireApproval is recorded and only run by the agent-actions step after a person approves). Replace Agents.invoker to use another runtime such as Andalus.");
        }
        if (!bundle.triggers().isEmpty()) {
            notes.add("Triggers run in `serve` only. Webhooks are served at /hooks/<id> (shared-secret ones read the secret trigger.<id>, i.e. WAYANG_SECRET_TRIGGER_<ID>; "
                    + "the old secret shown at creation cannot be reused). Poll cursors are kept in memory unless WAYANG_STATE_DIR is set, so a restart without it re-reads from the start.");
        }
        boolean gamelan = o.target() == ReleaseOptions.Target.GAMELAN;
        if (gamelan) {
            notes.add("Runs are Gamelan's: durable, they survive a restart of this service. Human tasks, timers and message waits are parked in this process, so run one replica "
                    + "when a workflow waits (a restart re-attaches them, but a second pod cannot complete them).");
            notes.add("Set WAYANG_GAMELAN_ENDPOINT (and WAYANG_GAMELAN_API_KEY, WAYANG_GAMELAN_GRPC_HOST/PORT, JWT) for your Gamelan engine, which must accept the tenant 'release'. "
                    + "The workflows are deployed at start as release~<process>; /readyz stays 503 until that worked. This target has no `check`/`run`/`info` commands.");
            notes.add("The generated project needs tech.kayys.wayang:wayang-gamelan-worker and wayang-embed" + (needsConnectors ? " and wayang-connectors" : "") + " version " + o.wayangVersion()
                    + ", and tech.kayys.gamelan:gamelan-sdk-executor-remote and gamelan-sdk-client-remote version " + o.gamelanVersion()
                    + ", from repositories you control. It was never built with Maven here, and never run against a Gamelan server.");
        } else {
            notes.add("Runs live in memory and are lost on restart. For durable, long-running workflows use the GAMELAN target.");
            notes.add("The generated project needs tech.kayys.wayang:wayang-embed" + (needsConnectors ? " and wayang-connectors" : "") + " version " + o.wayangVersion()
                    + " from a repository you control: run `mvn install` in the Wayang repository, or publish them to your registry.");
        }

        List<String> secrets = bundle.requiredSecrets().stream().distinct().sorted().toList();
        Map<String, byte[]> f = new TreeMap<>();
        String pkgPath = o.packageName().replace('.', '/');
        f.put("pom.xml", (gamelan ? GamelanTemplates.pom(o, bundle, needsConnectors, multimodal) : pom(o, bundle, needsConnectors, multimodal)).getBytes(StandardCharsets.UTF_8));
        f.put("src/main/resources/wayang/bundle.json", bundleJson.getBytes(StandardCharsets.UTF_8));
        f.put("src/main/resources/wayang/component-lock.json", componentLock.json().getBytes(StandardCharsets.UTF_8));
        f.put("src/main/java/" + pkgPath + "/Wiring.java", wiring(o, used, pollSources, providers).getBytes(StandardCharsets.UTF_8));
        if (gamelan) {
            f.put("src/main/java/" + pkgPath + "/ReleaseWiring.java", GamelanTemplates.wiringBean(o, agents).getBytes(StandardCharsets.UTF_8));
            f.put("src/main/java/" + pkgPath + "/ReleaseApp.java", GamelanTemplates.app(o).getBytes(StandardCharsets.UTF_8));
            f.put("src/main/resources/application.properties", GamelanTemplates.applicationProperties(o).getBytes(StandardCharsets.UTF_8));
        } else {
            f.put("src/main/java/" + pkgPath + "/App.java", app(o, agents).getBytes(StandardCharsets.UTF_8));
        }
        f.put("src/main/java/" + pkgPath + "/Processes.java", processes(o, bundle).getBytes(StandardCharsets.UTF_8));
        if (agents) {
            f.put("src/main/java/" + pkgPath + "/Agents.java", agentsStub(o).getBytes(StandardCharsets.UTF_8));
        }
        f.put("Dockerfile", (gamelan ? GamelanTemplates.dockerfile(o) : dockerfile(o)).getBytes(StandardCharsets.UTF_8));
        f.put(".dockerignore", "target\n.git\n.env\n".getBytes(StandardCharsets.UTF_8));
        f.put(".gitignore", "target/\n.env\n".getBytes(StandardCharsets.UTF_8));
        f.put(".env.example", envExample(o, bundle, secrets).getBytes(StandardCharsets.UTF_8));
        f.put("README.md", readme(o, bundle, processes, componentLock.componentIds(), used, agents, secrets, notes).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("format", "wayang.release");
        meta.put("target", o.target().name());
        meta.put("project", bundle.name());
        meta.put("bundleSha256", bundle.sha256());
        meta.put("wayangVersion", o.wayangVersion());
        if (gamelan) {
            meta.put("gamelanVersion", o.gamelanVersion());
            meta.put("quarkusVersion", o.quarkusVersion());
        }
        meta.put("processes", processes);
        meta.put("components", componentLock.componentIds());
        meta.put("componentLockSha256", componentLock.sha256());
        meta.put("connectors", new ArrayList<>(used));
        meta.put("triggers", bundle.triggers().stream().map(ReleaseBundle.Trigger::id).toList());
        f.put("src/main/resources/wayang/release.json", Json.write(meta).getBytes(StandardCharsets.UTF_8));
        if (o.target() == ReleaseOptions.Target.SERVICE) {
            f.put("deploy/kubernetes.yaml", kubernetes(o, secrets).getBytes(StandardCharsets.UTF_8));
        } else if (gamelan) {
            f.put("deploy/kubernetes.yaml", GamelanTemplates.kubernetes(o, secrets).getBytes(StandardCharsets.UTF_8));
        }
        return new Project(f, List.copyOf(processes), componentLock.componentIds(), List.copyOf(used), agents, secrets, List.copyOf(notes));
    }

    /** A deterministic zip (sorted, fixed timestamps) of the project. */
    public static byte[] zip(Project p) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : new TreeMap<>(p.files()).entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0)); // the zip epoch, not tied to any time zone: the same bundle gives the same bytes anywhere
                z.putNextEntry(ze);
                z.write(e.getValue());
                z.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ analysis

    private static void collectConnectors(Object o, Set<String> into) {
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if ("connector".equals(k) && v instanceof String s) {
                    into.add(s);
                } else {
                    collectConnectors(v, into);
                }
            });
        } else if (o instanceof List<?> l) {
            l.forEach(x -> collectConnectors(x, into));
        }
    }

    // ------------------------------------------------------------------ templates

    private static String pom(ReleaseOptions o, ReleaseBundle b, boolean connectors, boolean multimodal) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%1$s</groupId>
                    <artifactId>%2$s</artifactId>
                    <version>%3$s</version>
                    <name>%4$s</name>
                    <description>Generated by Wayang from a published workspace bundle. Do not edit the bundle by hand: change the workflow in Wayang and release again.</description>
                    <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                        <wayang.version>%5$s</wayang.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>tech.kayys.wayang</groupId>
                            <artifactId>wayang-embed</artifactId>
                            <version>${wayang.version}</version>
                        </dependency>
                %6$s    </dependencies>
                    <build>
                        <finalName>app</finalName>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.13.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-dependency-plugin</artifactId>
                                <version>3.6.1</version>
                                <executions>
                                    <execution>
                                        <id>copy-libs</id>
                                        <phase>package</phase>
                                        <goals><goal>copy-dependencies</goal></goals>
                                        <configuration>
                                            <outputDirectory>${project.build.directory}/lib</outputDirectory>
                                            <includeScope>runtime</includeScope>
                                        </configuration>
                                    </execution>
                                </executions>
                            </plugin>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-jar-plugin</artifactId>
                                <version>3.4.1</version>
                                <configuration>
                                    <archive>
                                        <manifest>
                                            <mainClass>%7$s.App</mainClass>
                                            <addClasspath>true</addClasspath>
                                            <classpathPrefix>lib/</classpathPrefix>
                                        </manifest>
                                    </archive>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(xml(o.groupId()), xml(o.artifactId()), xml(o.version()), xml(safe(b.name())), xml(o.wayangVersion()),
                (connectors ? CONNECTORS_DEP : "") + (multimodal ? MULTIMODAL_DEP : ""), o.packageName());
    }

    static final String CONNECTORS_DEP = """
                                <dependency>
                                    <groupId>tech.kayys.wayang</groupId>
                                    <artifactId>wayang-connectors</artifactId>
                                    <version>${wayang.version}</version>
                                </dependency>
                        """;
    /** Needed only when a connector of the multimodal extension is used; the runtime finds the extension through ServiceLoader. */
    static final String MULTIMODAL_DEP = """
                                <dependency>
                                    <groupId>tech.kayys.wayang</groupId>
                                    <artifactId>wayang-multimodal</artifactId>
                                    <version>${wayang.version}</version>
                                </dependency>
                        """;
    static final Set<String> MULTIMODAL_CONNECTORS = Set.of("image-generate", "comfyui", "transcribe", "speak");

    /** The connectors, poll sources and webhook providers this release uses, built from the environment. Shared by every target. */
    private static String wiring(ReleaseOptions o, Set<String> used, Set<String> pollSources, Set<String> providers) {
        boolean net = !used.isEmpty() || !pollSources.isEmpty();
        boolean conn = net || !providers.isEmpty();
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(o.packageName()).append(";\n\n");
        sb.append("import java.util.HashSet;\nimport java.util.LinkedHashMap;\nimport java.util.Map;\nimport java.util.Set;\nimport java.util.function.Function;\n\n");
        if (conn) {
            sb.append("import tech.kayys.wayang.connectors.Connectors;\n");
        }
        if (!pollSources.isEmpty()) {
            sb.append("import tech.kayys.wayang.connectors.UrlGuard;\n");
        }
        sb.append("import tech.kayys.wayang.runtime.Poller;\nimport tech.kayys.wayang.runtime.ServiceHandler;\nimport tech.kayys.wayang.runtime.WebhookProvider;\n\n");
        sb.append("/** Generated by Wayang. Only the connectors, poll sources and webhook providers this release uses are wired. */\n");
        sb.append("final class Wiring {\n\n    private Wiring() {\n    }\n\n");
        if (net) {
            sb.append("""
                        private static boolean allowPrivate(Function<String, String> env) {
                            return "true".equalsIgnoreCase(env.apply("WAYANG_CONNECTORS_ALLOW_PRIVATE"));
                        }

                        private static Set<String> hosts(Function<String, String> env) {
                            Set<String> hosts = new HashSet<>();
                            String allowed = env.apply("WAYANG_CONNECTORS_ALLOWED_HOSTS");
                            if (allowed != null) {
                                for (String h : allowed.split(",")) {
                                    if (!h.isBlank()) {
                                        hosts.add(h.strip());
                                    }
                                }
                            }
                            return hosts;
                        }

                    """);
        }
        if (used.isEmpty()) {
            sb.append("    static Map<String, ServiceHandler.Connector> connectors(Function<String, String> env) {\n        return Map.of();\n    }\n\n");
        } else {
            sb.append("""
                        static Map<String, ServiceHandler.Connector> connectors(Function<String, String> env) {
                            Map<String, ServiceHandler.Connector> connectors = new LinkedHashMap<>();
                            Connectors.defaults(allowPrivate(env), hosts(env)).forEach((name, c) -> {
                                if (Processes.CONNECTORS.contains(name)) {
                                    connectors.put(name, c); // only the connectors the workflows call
                                }
                            });
                            return connectors;
                        }

                    """);
        }
        if (pollSources.isEmpty()) {
            sb.append("    static Map<String, Poller> pollers(Function<String, String> env) {\n        return Map.of();\n    }\n\n");
        } else {
            sb.append("""
                        static Map<String, Poller> pollers(Function<String, String> env) {
                            Map<String, Poller> pollers = new LinkedHashMap<>();
                            Connectors.pollers(new UrlGuard(allowPrivate(env), hosts(env))).forEach((name, p) -> {
                                if (Processes.POLL_SOURCES.contains(name)) {
                                    pollers.put(name, p); // only the sources the triggers poll
                                }
                            });
                            return pollers;
                        }

                    """);
        }
        if (providers.isEmpty()) {
            sb.append("    static Map<String, WebhookProvider> providers() {\n        return Map.of();\n    }\n");
        } else {
            sb.append("""
                        static Map<String, WebhookProvider> providers() {
                            Map<String, WebhookProvider> providers = new LinkedHashMap<>();
                            Connectors.webhookProviders().forEach((name, p) -> {
                                if (Processes.WEBHOOK_PROVIDERS.contains(name)) {
                                    providers.put(name, p);
                                }
                            });
                            return providers;
                        }
                    """);
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String app(ReleaseOptions o, boolean agents) {
        String defaultCmd = o.target() == ReleaseOptions.Target.SERVICE ? "{ \"serve\" }" : "{ \"info\" }";
        String template = """
                package @PACKAGE@;

                import java.io.IOException;
                import java.io.InputStream;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.Path;
                import java.time.Clock;
                import java.util.Map;

                import tech.kayys.wayang.embed.Cli;
                import tech.kayys.wayang.embed.EmbeddedRuntime;
                import tech.kayys.wayang.embed.ReleaseBundle;
                import tech.kayys.wayang.embed.ReleaseSecrets;
                import tech.kayys.wayang.embed.ReleaseTriggers;
                import tech.kayys.wayang.runtime.ServiceHandler;

                /**
                 * Generated by Wayang. Starts the release: reads the bundle from the classpath, takes secrets from
                 * WAYANG_SECRET_&lt;NAME&gt; or files in WAYANG_SECRETS_DIR, settings from WAYANG_CONFIG_&lt;NAME&gt;, and hands the
                 * command line to the embedded CLI (@COMMANDS@).
                 */
                public final class App {

                    private App() {
                    }

                    public static void main(String[] args) throws IOException {
                        java.util.function.Function<String, String> env = System::getenv;
                        String json;
                        try (InputStream in = App.class.getResourceAsStream("/wayang/bundle.json")) {
                            if (in == null) {
                                throw new IllegalStateException("The release bundle is missing from the jar");
                            }
                            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        }
                        ReleaseBundle bundle = ReleaseBundle.parse(json);
                        Map<String, ServiceHandler.Connector> connectors = Wiring.connectors(env);
                        String dir = env.apply("WAYANG_SECRETS_DIR");
                        var secrets = ReleaseSecrets.secrets(dir == null || dir.isBlank() ? null : Path.of(dir), env);
                        var config = ReleaseSecrets.config(bundle.config(), env);
                        String state = env.apply("WAYANG_STATE_DIR"); // where poll cursors and conversation memory survive a restart
                        EmbeddedRuntime runtime = new EmbeddedRuntime(bundle, connectors, @INVOKER@, secrets, config, Clock.systemUTC(),
                                tech.kayys.wayang.embed.FileConversationMemory.forDir(state));
                        ReleaseTriggers triggers = new ReleaseTriggers(bundle, runtime, secrets, Wiring.pollers(env), Wiring.providers(), Clock.systemUTC(),
                                state == null || state.isBlank() ? null : Path.of(state));
                        String[] cmd = args.length == 0 ? new String[] @DEFAULT@ : args;
                        System.exit(Cli.run(cmd, runtime, triggers, env, secrets, System.in, System.out, System.err));
                    }
                }
                """;
        return template.replace("@PACKAGE@", o.packageName())
                .replace("@COMMANDS@", o.target() == ReleaseOptions.Target.SERVICE ? "serve by default" : "run, check, info, doctor")
                .replace("@INVOKER@", agents ? "Agents.invoker(env, secrets, config, connectors)" : "null").replace("@DEFAULT@", defaultCmd);
    }

    private static String processes(ReleaseOptions o, ReleaseBundle b) {
        StringBuilder consts = new StringBuilder();
        StringBuilder versions = new StringBuilder();
        Set<String> names = new TreeSet<>();
        for (ReleaseBundle.Definition d : b.definitions()) {
            if (!d.kind().equals("PROCESS")) {
                continue;
            }
            String base = d.id().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
            if (base.isEmpty() || Character.isDigit(base.charAt(0))) {
                base = "P_" + base;
            }
            String n = base;
            int i = 2;
            while (!names.add(n)) {
                n = base + "_" + i++;
            }
            consts.append("    public static final String ").append(n).append(" = \"").append(java(d.id())).append("\";\n");
            versions.append("        v.put(").append(n).append(", ").append(d.version()).append(");\n");
        }
        return """
                package %1$s;

                import java.util.Map;
                import java.util.Set;
                import java.util.TreeMap;

                /** Generated by Wayang: the process ids of this release and the version of each that was published. */
                public final class Processes {

                %2$s
                    /** Connectors the workflows call (only these are wired). */
                    public static final Set<String> CONNECTORS = Set.of(%3$s);

                    /** Poll sources the triggers use (only these are wired). */
                    public static final Set<String> POLL_SOURCES = Set.of(%5$s);

                    /** Webhook providers the triggers use (only these are wired). */
                    public static final Set<String> WEBHOOK_PROVIDERS = Set.of(%6$s);

                    /** Published version of each process. */
                    public static final Map<String, Integer> VERSIONS = versions();

                    private Processes() {
                    }

                    private static Map<String, Integer> versions() {
                        Map<String, Integer> v = new TreeMap<>();
                %4$s        return Map.copyOf(v);
                    }
                }
                """.formatted(o.packageName(), consts.toString().stripTrailing(), connectorList(b), versions,
                quoted(b.triggers().stream().filter(t -> !t.webhook() && !t.schedule()).map(t -> String.valueOf(t.config().get("source"))).collect(java.util.stream.Collectors.toCollection(TreeSet::new))),
                quoted(b.triggers().stream().filter(t -> t.webhook() && t.provider() != null).map(ReleaseBundle.Trigger::provider).collect(java.util.stream.Collectors.toCollection(TreeSet::new))));
    }

    private static String quoted(Set<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('"').append(java(n)).append('"');
        }
        return sb.toString();
    }

    /**
     * An agent's tools run through the connectors of the release too, so the ones it names must be wired in. A tool type with no
     * connector is not an error here: the agent is told, at call time, that it has no such tool.
     */
    private static void addAgentToolConnectors(ReleaseBundle b, Set<String> used, Set<String> known) {
        for (ReleaseBundle.Definition d : b.definitions()) {
            if (d.kind().equals("AGENT") && d.document().get("tools") instanceof List<?> tl) {
                for (Object t : tl) {
                    if (t instanceof Map<?, ?> tm && tm.get("type") instanceof String type && known.contains(type)) {
                        used.add(type);
                    }
                }
            }
        }
    }

    private static String connectorList(ReleaseBundle b) {
        Set<String> used = new TreeSet<>();
        for (ReleaseBundle.Definition d : b.definitions()) {
            if (d.kind().equals("PROCESS")) {
                collectConnectors(d.document().get("elements"), used);
            }
        }
        addAgentToolConnectors(b, used, Connectors.defaults(new tech.kayys.wayang.connectors.UrlGuard(false, Set.of()),
                tech.kayys.wayang.runtime.Extensions.load(tech.kayys.wayang.runtime.Profile.fromEnvironment())).keySet());
        used.remove(tech.kayys.wayang.embed.AgentActions.NAME);
        StringBuilder sb = new StringBuilder();
        for (String c : used) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('"').append(java(c)).append('"');
        }
        return sb.toString();
    }

    private static String agentsStub(ReleaseOptions o) {
        return """
                package %1$s;

                import java.util.Map;
                import java.util.function.Function;

                import tech.kayys.wayang.agent.AgentInvoker;
                import tech.kayys.wayang.embed.LlmAgentInvoker;
                import tech.kayys.wayang.runtime.ConfigResolver;
                import tech.kayys.wayang.runtime.SecretResolver;
                import tech.kayys.wayang.runtime.ServiceHandler;

                /**
                 * Generated by Wayang. Where the agent tasks of this release run. The default is the built-in tool-calling loop over a chat model
                 * (agent model "provider/model": openai, anthropic, ollama, openrouter, groq, together, mistral, deepseek, vllm; key = the secret
                 * llm.<provider>.api-key, endpoint = WAYANG_LLM_<PROVIDER>_URL, the agent's tools = connectors of this release). Replace the
                 * body to run agents elsewhere (for example over Andalus): anything that implements AgentInvoker works.
                 */
                public final class Agents {

                    private Agents() {
                    }

                    public static AgentInvoker invoker(Function<String, String> env, SecretResolver secrets, ConfigResolver config,
                            Map<String, ServiceHandler.Connector> connectors) {
                        return new LlmAgentInvoker(connectors, secrets, config, env);
                    }
                }
                """.formatted(o.packageName());
    }

    private static String dockerfile(ReleaseOptions o) {
        return """
                # Generated by Wayang. Build: docker build -t %1$s . ; run: docker run -p %2$d:%2$d -e WAYANG_API_KEY=... --env-file .env %1$s
                FROM maven:3.9-eclipse-temurin-21 AS build
                WORKDIR /src
                COPY pom.xml .
                COPY src src
                RUN mvn -q -B package -DskipTests

                FROM eclipse-temurin:21-jre
                RUN useradd --system --uid 10001 --no-create-home wayang
                WORKDIR /app
                COPY --from=build /src/target/app.jar app.jar
                COPY --from=build /src/target/lib lib
                USER 10001
                ENV WAYANG_BIND=0.0.0.0 WAYANG_PORT=%2$d WAYANG_PROFILE=%3$s
                EXPOSE %2$d
                ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
                """.formatted(o.artifactId(), o.port(), o.profile());
    }

    private static String envExample(ReleaseOptions o, ReleaseBundle b, List<String> secrets) {
        StringBuilder sb = new StringBuilder("# Copy to .env (never commit it). Values are examples.\n");
        if (o.target() == ReleaseOptions.Target.GAMELAN) {
            sb.append(GamelanTemplates.envExample(List.of()));
        }
        if (o.target() != ReleaseOptions.Target.STANDALONE) {
            sb.append("WAYANG_API_KEY=change-me-to-a-long-random-string\nWAYANG_PORT=").append(o.port()).append("\nWAYANG_BIND=0.0.0.0\n");
        }
        sb.append("# WAYANG_SECRETS_DIR=/run/secrets   # or one file per secret, named exactly as the secret\n");
        sb.append("# WAYANG_REQUIRE_SECRETS=true       # refuse to start while a secret is missing\n");
        sb.append("# WAYANG_CONNECTORS_ALLOWED_HOSTS=api.example.com,hooks.slack.com\n");
        sb.append("# WAYANG_CONNECTORS_ALLOW_PRIVATE=false\n");
        if (!b.triggers().isEmpty()) {
            sb.append("# WAYANG_STATE_DIR=/var/lib/wayang   # keeps poll cursors across restarts\n");
        }
        sb.append("\n# Secrets this release needs\n");
        for (String s : secrets) {
            sb.append(ReleaseSecrets.envName("WAYANG_SECRET_", s)).append("=\n");
        }
        sb.append("\n# Settings (the value in the bundle is the default; uncomment to override)\n");
        for (String k : b.requiredConfig()) {
            String v = b.config().get(k);
            sb.append("# ").append(ReleaseSecrets.envName("WAYANG_CONFIG_", k)).append('=').append(v == null ? "" : v.replace('\n', ' ')).append('\n');
        }
        return sb.toString();
    }

    private static String readme(ReleaseOptions o, ReleaseBundle b, List<String> processes, List<String> components, Set<String> connectors, boolean agents, List<String> secrets,
            List<String> notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(safe(b.name())).append(" (Wayang release)\n\n");
        sb.append("Generated from a published Wayang bundle (sha256 `").append(b.sha256().isEmpty() ? "n/a" : b.sha256()).append("`). ");
        sb.append("Change the workflows in Wayang and release again; do not edit `src/main/resources/wayang/bundle.json`.\n\n");
        sb.append("Target: **").append(o.target()).append("**. Processes: ").append(String.join(", ", processes.stream().map(p -> "`" + p + "`").toList())).append(".\n\n");
        sb.append("Resolved components: ").append(String.join(", ", components.stream().map(p -> "`" + p + "`").toList())).append(". See `src/main/resources/wayang/component-lock.json` for exact versions and dependency closure.\n\n");
        if (o.target() == ReleaseOptions.Target.GAMELAN) {
            sb.append("## Build and run\n\n```\nmvn -q package\njava -jar target/quarkus-app/quarkus-run.jar   # needs WAYANG_GAMELAN_ENDPOINT; see .env.example\n```\n\n");
            sb.append("Start runs with `POST /v1/processes/{id}/runs` (below). `GET /readyz` tells whether the workflows are deployed and the secrets present.\n\n");
        } else {
            sb.append("## Build and run\n\n```\nmvn -q package\njava -jar target/app.jar check        # what is missing?\n");
            sb.append("java -jar target/app.jar run ").append(processes.get(0)).append(" --input input.json\n");
            if (o.target() == ReleaseOptions.Target.SERVICE) {
                sb.append("java -jar target/app.jar serve        # HTTP on WAYANG_PORT (default ").append(o.port()).append(")\n");
            }
            sb.append("```\n\nExit codes of `run`: 0 completed, 1 failed, 2 still waiting (a task or timer), 64 bad usage.\n\n");
        }
        if (o.target() != ReleaseOptions.Target.STANDALONE) {
            sb.append("## HTTP\n\nSend `Authorization: Bearer $WAYANG_API_KEY` on everything under `/v1`. `/healthz` and `/readyz` need no key (use them as probes).\n\n");
            sb.append("```\nPOST /v1/processes/{id}/runs   {\"input\": {...}}\nGET  /v1/runs/{id}   GET /v1/runs\nPOST /v1/runs/{id}/cancel\nPOST /v1/tasks/{token}/complete   {\"outputs\": {...}}\nGET  /v1/release\n```\n\n");
        }
        sb.append("## Configuration\n\nSee `.env.example`. Secrets are read from `WAYANG_SECRET_<NAME>` or from files in `WAYANG_SECRETS_DIR`; settings from ");
        sb.append("`WAYANG_CONFIG_<NAME>` (the bundled value is the default). Secrets are never in the bundle.\n\n");
        sb.append("Needs ").append(secrets.size()).append(" secret(s)").append(connectors.isEmpty() ? "" : "; connectors used: " + String.join(", ", connectors))
                .append(agents ? "; agent tasks (model keys are secrets `llm.<provider>.api-key`; see `Agents.java`)" : "").append(".\n\n## Know before you deploy\n\n");
        for (String n : notes) {
            sb.append("- ").append(n).append('\n');
        }
        return sb.toString();
    }

    private static String kubernetes(ReleaseOptions o, List<String> secrets) {
        StringBuilder sec = new StringBuilder();
        for (String s : secrets) {
            sec.append("  ").append(ReleaseSecrets.envName("WAYANG_SECRET_", s)).append(": \"\"\n");
        }
        return """
                # Generated by Wayang. Fill the Secret (or use your secret manager), set the image, then kubectl apply -f.
                apiVersion: v1
                kind: Secret
                metadata:
                  name: %1$s
                type: Opaque
                stringData:
                  WAYANG_API_KEY: ""
                %2$s---
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: %1$s
                spec:
                  replicas: 1   # runs are in memory: more than one replica splits runs and tasks between pods
                  selector:
                    matchLabels: { app: %1$s }
                  template:
                    metadata:
                      labels: { app: %1$s }
                    spec:
                      securityContext:
                        runAsNonRoot: true
                        runAsUser: 10001
                      containers:
                        - name: app
                          image: %1$s:%3$s
                          ports:
                            - containerPort: %4$d
                          envFrom:
                            - secretRef: { name: %1$s }
                          readinessProbe:
                            httpGet: { path: /readyz, port: %4$d }
                          livenessProbe:
                            httpGet: { path: /healthz, port: %4$d }
                          resources:
                            requests: { cpu: 100m, memory: 256Mi }
                            limits: { memory: 512Mi }
                          securityContext:
                            allowPrivilegeEscalation: false
                            readOnlyRootFilesystem: true
                ---
                apiVersion: v1
                kind: Service
                metadata:
                  name: %1$s
                spec:
                  selector: { app: %1$s }
                  ports:
                    - port: %4$d
                      targetPort: %4$d
                """.formatted(o.artifactId(), sec, o.version(), o.port());
    }

    // ------------------------------------------------------------------ escaping

    static String xmlText(String s) {
        return xml(s);
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String java(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7e) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Text that ends up in a comment, a title or a markdown heading: no markup, no line breaks, bounded. */
    static String safe(String s) {
        String t = s == null ? "release" : s.replaceAll("[^A-Za-z0-9 ._()-]", "").strip();
        t = t.length() > 80 ? t.substring(0, 80) : t;
        return t.isEmpty() ? "release" : t;
    }
}
