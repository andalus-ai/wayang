package tech.kayys.wayang.adapter.gamelan;

import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import tech.kayys.wayang.runtime.AgentHandler;
import tech.kayys.wayang.runtime.ControlHandler;
import tech.kayys.wayang.runtime.DmnHandler;
import tech.kayys.wayang.runtime.EipHandler;
import tech.kayys.wayang.runtime.HumanHandler;
import tech.kayys.wayang.runtime.NodeRunner;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.TimerService;
import tech.kayys.wayang.runtime.WaitRegistry;

/** Shared, lazily built runtime used by every Wayang executor in this JVM. */
@ApplicationScoped
public class WayangRuntime {

    @Inject
    Instance<WayangComponents> components;

    private volatile NodeRunner runner;
    private volatile WaitRegistry registry;
    private final PendingWaits waits = new PendingWaits();

    public NodeRunner runner() {
        NodeRunner r = runner;
        if (r == null) {
            synchronized (this) {
                if (runner == null) {
                    runner = build();
                }
                r = runner;
            }
        }
        return r;
    }

    /** The wait registry shared with the executors (tickets of parked nodes). */
    public WaitRegistry registry() {
        runner();
        return registry;
    }

    PendingWaits waits() {
        return waits;
    }

    /**
     * Completes a parked human task / timer / message-catch node.
     *
     * @return false when the token is unknown to this JVM
     */
    public boolean completeWait(String token, Map<String, Object> outputs) {
        runner(); // make sure the registry exists
        // Complete first, forget second: if this JVM lost the parked call (restart), the ticket
        // stays registered so the re-dispatched node re-attaches to it and can be completed again.
        if (!waits.complete(token, outputs == null ? Map.of() : outputs)) {
            return false;
        }
        registry.take(token);
        return true;
    }

    /** Fails a parked node (rejected task, cancelled timer). */
    public boolean cancelWait(String token, String reason) {
        runner();
        if (!waits.cancel(token, new IllegalStateException(reason))) {
            return false;
        }
        registry.take(token);
        return true;
    }

    /** Fires due timers; call from a scheduler (e.g. every few seconds). @return timers fired */
    public int fireDueTimers() {
        return timers().fireDue();
    }

    /** Delivers (or buffers) a message for a message-catch event. */
    public TimerService.Delivery deliverMessage(String name, String correlationKey, Map<String, Object> payload) {
        return timers().deliver(name, correlationKey, payload == null ? Map.of() : payload);
    }

    private TimerService timers() {
        runner();
        return new TimerService(registry, this::completeWait, java.time.Clock.systemUTC());
    }

    private NodeRunner build() {
        WayangComponents c = components.isResolvable() ? components.get() : new WayangComponents() {
        };
        WaitRegistry registry = c.waitRegistry();
        this.registry = registry;
        NodeRunner runner = new NodeRunner(List.of(new ControlHandler(registry), new HumanHandler(registry),
                new DmnHandler(c.decisionLookup()), new EipHandler(c.idempotencyStore(), c.connectors(), c.claimStore(), c.aggregationStore(), new tech.kayys.wayang.eip.EipState()),
                new AgentHandler(c.agentInvoker(), c.agentLookup()).withMemory(c.conversationMemory()), new ServiceHandler(c.connectors())));
        tech.kayys.wayang.runtime.Profile profile = tech.kayys.wayang.runtime.Profile.fromEnvironment();
        runner.withProfile(profile).withExtensions(tech.kayys.wayang.runtime.Extensions.load(profile));
        tech.kayys.wayang.runtime.SecretResolver secrets = c.secretResolver();
        tech.kayys.wayang.runtime.ConfigResolver config = c.configResolver();
        if (config != null) {
            runner.withConfig(config);
        }
        if (c.secretAccessListener() != null) {
            runner.withSecretAccessListener(c.secretAccessListener());
        }
        return secrets == null ? runner : runner.withSecrets(secrets);
    }
}
