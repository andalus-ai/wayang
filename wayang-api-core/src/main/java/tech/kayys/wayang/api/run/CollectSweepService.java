package tech.kayys.wayang.api.run;

import tech.kayys.wayang.api.definition.DefinitionKind;
import tech.kayys.wayang.api.definition.DefinitionStore;
import tech.kayys.wayang.api.Permission;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.definition.Studio;
import tech.kayys.wayang.api.TenantGate;
import tech.kayys.wayang.api.WayangApi;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;
import tech.kayys.wayang.eip.AggregationStore;
import tech.kayys.wayang.eip.CollectSweeper;

/**
 * Hard deadlines for {@code collect} (see {@link CollectSweeper}): finds, in every tenant that has groups waiting, the collect
 * steps with {@code sweepTo}, closes the groups that are past their timeout and starts the named process with the aggregate.
 * The target is read from the <b>latest published</b> version of the process that owns the collect node, so a group left by an
 * older version is swept with the current deadline. The started run belongs to that tenant and may only start runs
 * ({@code RUN_START}), like a trigger. Call {@link #sweep()} on a timer; several nodes may do so, a group is closed once.
 */
public final class CollectSweepService {

    private static final Set<Permission> GRANTS = EnumSet.of(Permission.RUN_START);

    private final CollectSweeper sweeper;
    private volatile TenantGate gate = TenantGate.ALLOW_ALL;

    public CollectSweepService(DefinitionStore store, WayangApi api, AggregationStore aggregation, Clock clock, Consumer<String> onError) {
        this.sweeper = new CollectSweeper(aggregation,
                (tenant, process, node) -> !gate.active(tenant) ? null : store.getLatest(tenant, DefinitionKind.PROCESS, process)
                        .map(d -> CollectSweeper.find(tenant, Studio.executable(d.document()), node)).orElse(null),
                (tenant, processId, input) -> api.startRun(new Principal(tenant, "collect-sweeper", Set.of(), GRANTS), processId, null, input),
                clock, onError);
    }

    /** A suspended tenant's groups are left waiting (not swept, not dropped) until it is active again. */
    public CollectSweepService withTenantGate(TenantGate g) {
        this.gate = g == null ? TenantGate.ALLOW_ALL : g;
        return this;
    }

    /** @return how many groups were closed and handed to their process */
    public int sweep() {
        return sweeper.sweep();
    }
}
