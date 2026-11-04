package tech.kayys.wayang.runtime;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ExecutionPlan;
import tech.kayys.wayang.bpmn.PlanNode;

/**
 * A deployment profile: which feature families a runtime (or a worker pool) serves. BPMN control flow is always
 * present; the rest is opt-in so Wayang can run as the full assistant platform, or only as an integration or
 * business-automation engine, and each feature can be split into its own scalable worker.
 */
public final class Profile {

    /** Feature families, each mapped to the executor type that serves it. */
    public enum Feature {
        SERVICE("wayang-service"), DMN("wayang-dmn"), EIP("wayang-eip"), AGENT("wayang-agent"), HUMAN("wayang-human"),
        /** Media generation (images today; audio and graph-style nodes later). Served by extensions, so it has no built-in handler. */
        MEDIA("wayang-media");

        private final String executorType;

        Feature(String executorType) {
            this.executorType = executorType;
        }

        public String executorType() {
            return executorType;
        }
    }

    public static final Profile FULL = new Profile("full", EnumSet.allOf(Feature.class));
    /** Business automation: rules, human tasks and service calls. */
    public static final Profile AUTOMATION = new Profile("automation",
            EnumSet.of(Feature.SERVICE, Feature.DMN, Feature.HUMAN));
    /** Integration platform: EIP pipelines and connectors. */
    public static final Profile INTEGRATION = new Profile("integration", EnumSet.of(Feature.SERVICE, Feature.EIP));
    /** Personal assistant / agent builder. */
    public static final Profile AGENTIC = new Profile("agentic",
            EnumSet.of(Feature.SERVICE, Feature.AGENT, Feature.HUMAN, Feature.EIP, Feature.MEDIA));

    private final String name;
    private final Set<Feature> features;

    private Profile(String name, Set<Feature> features) {
        this.name = name;
        this.features = features.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(features);
    }

    public static Profile of(String name, Set<Feature> features) {
        return new Profile(name, features);
    }

    /**
     * {@code full|automation|integration|agentic}, or a custom comma list such as {@code dmn,service}; blank means
     * {@code full}. Unknown names are rejected so a typo cannot silently enable everything.
     */
    public static Profile parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return FULL;
        }
        String s = spec.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "full":
                return FULL;
            case "automation":
                return AUTOMATION;
            case "integration":
                return INTEGRATION;
            case "agentic":
                return AGENTIC;
            default:
                break;
        }
        Set<Feature> f = EnumSet.noneOf(Feature.class);
        for (String part : s.split(",")) {
            String p = part.trim();
            if (p.isEmpty() || p.equals("bpmn")) {
                continue;
            }
            try {
                f.add(Feature.valueOf(p.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown profile or feature '" + p
                        + "'; use full, automation, integration, agentic or a list of: service, dmn, eip, agent, human, media");
            }
        }
        return new Profile(String.join(",", f.stream().map(x -> x.name().toLowerCase(Locale.ROOT)).toList()), f);
    }

    /** From {@code WAYANG_PROFILE}. */
    public static Profile fromEnvironment() {
        return parse(System.getenv("WAYANG_PROFILE"));
    }

    public String name() {
        return name;
    }

    public Set<Feature> features() {
        return java.util.Collections.unmodifiableSet(features);
    }

    public boolean has(Feature f) {
        return features.contains(f);
    }

    /** True for the built-in executor types this profile serves and for any non-built-in (extension) type. */
    public boolean allowsExecutor(String executorType) {
        for (Feature f : Feature.values()) {
            if (f.executorType.equals(executorType)) {
                return features.contains(f);
            }
        }
        return true;
    }

    public boolean allows(ElementType type) {
        return allowsExecutor(type.defaultExecutorType());
    }

    /** Human-readable problems for nodes this profile cannot run; empty when the plan fits. */
    public List<String> violations(ExecutionPlan plan) {
        Set<String> out = new LinkedHashSet<>();
        for (PlanNode n : plan.nodes()) {
            if (!allowsExecutor(n.executorType())) {
                out.add("Node '" + n.id() + "' (" + n.type() + ") needs " + n.executorType()
                        + ", which the '" + name + "' profile does not enable");
            }
        }
        return List.copyOf(out);
    }

    @Override
    public String toString() {
        return name;
    }
}
