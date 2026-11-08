package tech.kayys.wayang.runtime;

import java.util.HashMap;
import java.util.Map;

import tech.kayys.wayang.bpmn.FlowRuntime;
import tech.kayys.wayang.bpmn.NodePolicy;
import tech.kayys.wayang.bpmn.PlanNode;

/**
 * Applies the dead-path-elimination protocol around the domain handlers. This
 * is the single place that engine adapters call, so every engine (Gamelan,
 * the reference engine, tests) behaves identically.
 */
public final class NodeRunner {

    private final Map<String, DomainHandler> handlers = new HashMap<>();

    public NodeRunner(Iterable<? extends DomainHandler> handlers) {
        for (DomainHandler h : handlers) {
            if (this.handlers.putIfAbsent(h.executorType(), h) != null) {
                throw new IllegalArgumentException("Two handlers for executor type " + h.executorType());
            }
        }
    }

    private SecretResolver secrets;
    private ConfigResolver config;
    private SecretAccessListener accessListener;

    /** Reports every secret reference a node resolves (name only, never the value). */
    public NodeRunner withSecretAccessListener(SecretAccessListener listener) {
        this.accessListener = listener;
        return this;
    }

    /**
     * Enables {@code ${config:NAME}} in the same nodes as secrets. The value is inserted as it is: it is not scanned again (a value
     * of {@code ${secret:X}} stays literal text) and, like a secret, it is refused inside {@code inputs} expressions when it holds
     * a quote or backslash.
     */
    public NodeRunner withConfig(ConfigResolver resolver) {
        this.config = resolver;
        return this;
    }

    /**
     * Enables {@code ${secret:NAME}} in the configuration of service, agent and EIP nodes (only those: other
     * handlers copy configuration into tickets or outputs). Resolved values never reach run variables or error
     * messages: any occurrence is replaced by {@code ***}.
     */
    public NodeRunner withSecrets(SecretResolver resolver) {
        this.secrets = resolver;
        return this;
    }

    private static final java.util.Set<tech.kayys.wayang.bpmn.ElementType> SECRET_ELEMENTS = java.util.Set.of(
            tech.kayys.wayang.bpmn.ElementType.SERVICE_TASK, tech.kayys.wayang.bpmn.ElementType.AGENT_TASK,
            tech.kayys.wayang.bpmn.ElementType.EIP_TASK);
    private static final java.util.regex.Pattern SECRET_REF = java.util.regex.Pattern.compile("\\$\\{(secret|config):([A-Za-z0-9_.-]{1,100})}");

    /** Drops handlers the profile does not serve; nodes needing them then fail with a clear message. */
    public NodeRunner withProfile(Profile profile) {
        java.util.Objects.requireNonNull(profile, "profile");
        if (componentCatalog != null && !componentCatalog.profile().name().equals(profile.name())) {
            throw new IllegalArgumentException("Component catalog profile '" + componentCatalog.profile().name()
                    + "' does not match requested NodeRunner profile '" + profile.name() + "'");
        }
        this.profile = profile;
        handlers.keySet().removeIf(t -> !profile.allowsExecutor(t));
        return this;
    }

    /** Adds extension handlers (custom executor types); a type that is already served is an error. */
    public NodeRunner withExtensions(Extensions extensions) {
        for (DomainHandler h : extensions.handlers()) {
            if (handlers.putIfAbsent(h.executorType(), h) != null) {
                throw new IllegalArgumentException("Two handlers for executor type " + h.executorType());
            }
        }
        return this;
    }

    private Profile profile = Profile.FULL;
    private ComponentExecutorCatalog componentCatalog;

    /**
     * Enables explicit component-ID dispatch for nodes that declare
     * {@code wayang.component.id}. Nodes without that declaration continue
     * through the legacy executor-family handler path.
     *
     * <p>The catalog must be resolved for the same profile as this runner.
     * Component execution remains inside this runner's existing active-node,
     * retry, output-merging, and secret-redaction lifecycle.</p>
     */
    public NodeRunner withComponentCatalog(ComponentExecutorCatalog catalog) {
        if (catalog != null && !catalog.profile().name().equals(profile.name())) {
            throw new IllegalArgumentException("Component catalog profile '" + catalog.profile().name()
                    + "' does not match NodeRunner profile '" + profile.name() + "'");
        }
        this.componentCatalog = catalog;
        return this;
    }

    public boolean supports(String executorType) {
        return handlers.containsKey(executorType);
    }

    public NodeOutcome run(String runId, PlanNode node, Map<String, Object> vars) {
        return run(runId, node, vars, 1);
    }

    /**
     * @param attempt 1-based attempt number supplied by the engine; with {@code onError: continue} a failure is
     *                only converted into a completed node (outputs {@code failed=true}, {@code error}) on the
     *                last allowed attempt, so earlier attempts still go through the engine's retry policy
     */
    public NodeOutcome run(String runId, PlanNode node, Map<String, Object> vars, int attempt) {
        NodeOutcome o = runOnce(runId, node, vars);
        NodePolicy policy = NodePolicy.of(node);
        if (!policy.continueOnError()) {
            return o;
        }
        if (o instanceof NodeOutcome.Failed f && attempt >= policy.maxAttempts()) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("failed", true);
            out.put("error", f.error());
            return new NodeOutcome.Completed(FlowRuntime.liveOutputs(node, vars, out));
        }
        if (o instanceof NodeOutcome.Completed c && !Boolean.FALSE.equals(c.outputs().get("active"))) {
            Map<String, Object> out = new java.util.LinkedHashMap<>(c.outputs());
            out.put("failed", false); // lets conditions test "<node>.failed" on both paths
            return new NodeOutcome.Completed(out);
        }
        return o;
    }

    private NodeOutcome runOnce(String runId, PlanNode node, Map<String, Object> vars) {
        try {
            if (!FlowRuntime.isActive(node, vars)) {
                return new NodeOutcome.Completed(FlowRuntime.deadOutputs(node));
            }
            DomainHandler h = handlers.get(node.executorType());
            java.util.Set<String> used = new java.util.LinkedHashSet<>();
            PlanNode effective = node;
            if ((secrets != null || config != null) && SECRET_ELEMENTS.contains(node.type())) {
                String tenant = vars.get(SecretResolver.TENANT_VARIABLE) instanceof String t ? t : null;
                String process = vars.get(SecretResolver.PROCESS_VARIABLE) instanceof String pr ? pr : null;
                effective = withResolvedSecrets(runId, node, tenant, process, used);
            }
            NodeOutcome o;
            ComponentIdentity identity = ComponentIdentity.fromConfiguration(effective.config());
            if (identity != null) {
                if (componentCatalog == null) {
                    return new NodeOutcome.Failed("Node " + node.id()
                            + " declares component '" + identity.id() + "' but no component catalog is configured", false);
                }
                o = executeComponent(runId, effective, vars, identity);
            } else {
                if (h == null) {
                    if (!profile.allowsExecutor(node.executorType())) {
                        return new NodeOutcome.Failed("Node " + node.id() + " needs " + node.executorType()
                                + ", which the '" + profile.name() + "' profile does not enable", false);
                    }
                    return new NodeOutcome.Failed("No handler registered for executor type '" + node.executorType()
                            + "' (node " + node.id() + ")", false);
                }
                o = h.handle(runId, effective, vars);
            }
            if (!used.isEmpty()) {
                o = redact(o, used);
            }
            if (o instanceof NodeOutcome.Completed c) {
                return new NodeOutcome.Completed(FlowRuntime.liveOutputs(node, vars, c.outputs()));
            }
            return o;
        } catch (FlowRuntime.ProcessFlowException e) {
            return new NodeOutcome.Failed(e.getMessage(), false);
        } catch (tech.kayys.wayang.expression.LimitExceededException e) {
            return new NodeOutcome.Failed("Expression limit exceeded in node " + node.id() + ": " + e.getMessage(), false);
        } catch (RuntimeException e) {
            return new NodeOutcome.Failed(node.id() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), false);
        }
    }

    private NodeOutcome executeComponent(String runId, PlanNode node, Map<String, Object> vars,
            ComponentIdentity identity) {
        ComponentDescriptor descriptor = componentCatalog.components().stream()
                .filter(candidate -> candidate.id().equals(identity.id()))
                .findFirst().orElse(null);
        if (descriptor == null) {
            return new NodeOutcome.Failed("Component '" + identity.id()
                    + "' is not part of the resolved runtime catalog", false);
        }
        if (identity.version() != null && !descriptor.version().equals(identity.version())) {
            return new NodeOutcome.Failed("Component version mismatch for '" + identity.id()
                    + "': requested=" + identity.version() + ", resolved=" + descriptor.version(), false);
        }
        Map<String, Object> configuration = new java.util.LinkedHashMap<>();
        configuration.put("wayang.planNode", node.toConfiguration());
        configuration.put("componentConfig", node.config());
        ExecutionRequest request = new ExecutionRequest(runId, identity.id(), descriptor.version(), identity.operation(),
                vars, configuration, ExecutionMetadata.empty());
        ComponentExecutionResult result = componentCatalog.executors().execute(request);
        if (result instanceof ComponentExecutionResult.Completed completed) {
            return new NodeOutcome.Completed(completed.outputs());
        }
        if (result instanceof ComponentExecutionResult.Suspended suspended) {
            Map<String, Object> details = new java.util.LinkedHashMap<>(suspended.details());
            Object kind = details.remove("kind");
            return new NodeOutcome.Waiting(new WaitTicket(suspended.resumeToken(), node.id(),
                    kind == null ? "component" : String.valueOf(kind), details));
        }
        if (result instanceof ComponentExecutionResult.Rejected rejected) {
            return new NodeOutcome.Failed("Component dispatch rejected [" + rejected.code() + "]: "
                    + rejected.message(), false);
        }
        if (result instanceof ComponentExecutionResult.Failed failed) {
            return new NodeOutcome.Failed(failed.message(), failed.retryable());
        }
        return new NodeOutcome.Failed("Unsupported component execution result: " + result.getClass().getName(), false);
    }

    /** Completes a parked node once its ticket was fulfilled. */
    public NodeOutcome resume(PlanNode node, Map<String, Object> vars, Map<String, Object> resultOutputs) {
        try {
            return new NodeOutcome.Completed(FlowRuntime.liveOutputs(node, vars, resultOutputs));
        } catch (FlowRuntime.ProcessFlowException e) {
            return new NodeOutcome.Failed(e.getMessage(), false);
        }
    }

    // ------------------------------------------------------------------ secrets

    private PlanNode withResolvedSecrets(String runId, PlanNode node, String tenant, String process, java.util.Set<String> used) {
        Object resolved = resolveDeep(runId, node.config(), tenant, process, used, node.id(), 0, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) resolved;
        return new PlanNode(node.id(), node.name(), node.type(), node.executorType(), cfg, node.incoming(), node.outgoing());
    }

    private Object resolveDeep(String runId, Object v, String tenant, String process, java.util.Set<String> used, String nodeId, int depth,
            boolean inExpression) {
        if (depth > 32) {
            throw new IllegalStateException("Configuration of node " + nodeId + " is nested too deeply");
        }
        if (v instanceof String str) {
            var m = SECRET_REF.matcher(str);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                boolean isConfig = m.group(1).equals("config");
                String name = m.group(2);
                if (isConfig ? config == null : secrets == null) {
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group())); // that kind is not enabled: left as written
                    continue;
                }
                String value = tenant == null ? null : isConfig ? config.resolve(tenant, process, name) : secrets.resolve(tenant, name);
                if (!isConfig && accessListener != null) {
                    try {
                        accessListener.accessed(tenant, process, runId, nodeId, name, value != null);
                    } catch (RuntimeException ignored) {
                        // auditing must not fail a run
                    }
                }
                if (value == null) {
                    throw new IllegalStateException((isConfig ? "Config '" : "Secret '") + name + "' is not available to node " + nodeId);
                }
                if (inExpression && (value.indexOf('\'') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\\') >= 0)) {
                    // "inputs" values are parsed as expressions; a quote in a value could change the expression
                    throw new IllegalStateException((isConfig ? "Config '" : "Secret '") + name + "' contains characters not allowed in node "
                            + nodeId + " inputs");
                }
                if (!isConfig) {
                    used.add(value);
                }
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((k, x) -> out.put(String.valueOf(k), resolveDeep(runId, x, tenant, process, used, nodeId, depth + 1,
                    inExpression || (depth == 0 && "inputs".equals(String.valueOf(k))))));
            return out;
        }
        if (v instanceof java.util.List<?> l) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            l.forEach(x -> out.add(resolveDeep(runId, x, tenant, process, used, nodeId, depth + 1, inExpression)));
            return out;
        }
        return v;
    }

    private static NodeOutcome redact(NodeOutcome o, java.util.Set<String> secretValues) {
        if (o instanceof NodeOutcome.Failed f) {
            return new NodeOutcome.Failed((String) redactDeep(f.error(), secretValues, 0), f.retryable());
        }
        if (o instanceof NodeOutcome.Completed c) {
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) redactDeep(c.outputs(), secretValues, 0);
            return new NodeOutcome.Completed(out);
        }
        return o;
    }

    private static Object redactDeep(Object v, java.util.Set<String> secretValues, int depth) {
        if (depth > 32) {
            return "***";
        }
        if (v instanceof String s) {
            for (String sec : secretValues) {
                if (!sec.isEmpty()) {
                    s = s.replace(sec, "***");
                }
            }
            return s;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), redactDeep(x, secretValues, depth + 1)));
            return out;
        }
        if (v instanceof java.util.List<?> l) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            l.forEach(x -> out.add(redactDeep(x, secretValues, depth + 1)));
            return out;
        }
        return v;
    }
}
