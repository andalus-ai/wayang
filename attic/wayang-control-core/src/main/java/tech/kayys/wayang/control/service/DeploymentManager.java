package tech.kayys.wayang.control.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for managing deployments to execution runtimes.
 */
@ApplicationScoped
public class DeploymentManager {

    private static final Logger LOG = LoggerFactory.getLogger(DeploymentManager.class);

    /*
     * @Inject
     * GamelanWorkflowEngine gamelanEngine;
     */

    /**
     * Deploy a route design to the default Gamelan runtime.
     * Stubbed to fix build errors.
     */
    public Uni<Object> deployToGamelan(Object design) {
        LOG.info("Deploying design to Gamelan runtime (Stubbed)");
        // return gamelanEngine.deploy(design);
        return Uni.createFrom().nullItem();
    }
}
