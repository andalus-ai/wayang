package tech.kayys.wayang.adapter.gamelan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ExecutionPlan;
import tech.kayys.wayang.bpmn.PlanNode;

/**
 * Executors are shared by all tenants, so references to decisions and agents
 * are made tenant-qualified ({@code tenant/id}) before a plan is deployed.
 * Inline agent maps are left alone.
 */
public final class TenantQualifier {

    private TenantQualifier() {
    }

    public static String qualify(String tenant, String id) {
        return tenant + "/" + id;
    }

    /** @return {tenant, id} or null when the reference is not qualified */
    public static String[] split(String qualified) {
        int i = qualified == null ? -1 : qualified.indexOf('/');
        return i <= 0 ? null : new String[] { qualified.substring(0, i), qualified.substring(i + 1) };
    }

    public static ExecutionPlan apply(ExecutionPlan plan, String tenant) {
        List<PlanNode> out = new ArrayList<>();
        for (PlanNode n : plan.nodes()) {
            String key = n.type() == ElementType.BUSINESS_RULE_TASK ? "model"
                    : n.type() == ElementType.AGENT_TASK ? "agent" : null;
            if (key != null && n.config().get(key) instanceof String ref) {
                Map<String, Object> cfg = new LinkedHashMap<>(n.config());
                cfg.put(key, qualify(tenant, ref));
                n = new PlanNode(n.id(), n.name(), n.type(), n.executorType(), cfg, n.incoming(), n.outgoing());
            }
            out.add(n);
        }
        return new ExecutionPlan(plan.processId(), plan.processName(), out);
    }
}
