package tech.kayys.wayang.rest;

import java.time.Clock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.api.AuditService;
import tech.kayys.wayang.api.AuditStore;
import tech.kayys.wayang.store.jdbc.JdbcAuditStore;

/** The audit log lives in the same store kind as the rest ({@code wayang.store}): memory is bounded and lost on restart. */
@ApplicationScoped
public class AuditConfig {

    @ConfigProperty(name = "wayang.store", defaultValue = "memory")
    String storeKind;
    @ConfigProperty(name = "wayang.audit.memory-entries-per-tenant", defaultValue = "5000")
    int memoryEntries;

    @Inject
    WayangBeans beans;
    @Inject
    Clock clock;

    @ConfigProperty(name = "wayang.audit.secret-reads", defaultValue = "false")
    boolean secretReads;

    private volatile AuditService service;

    /** Null unless {@code wayang.audit.secret-reads=true}: one audit entry per secret a node resolves, so it grows with run volume. */
    public tech.kayys.wayang.runtime.SecretAccessListener secretReadListener() {
        return secretReads ? (t, p, r, n, s, f) -> service().recordSecretRead(t, p, r, n, s, f) : null;
    }

    public synchronized AuditService service() {
        if (service == null) {
            AuditStore store = "jdbc".equals(storeKind) ? new JdbcAuditStore(beans.jdbc()) : new AuditStore.InMemory(memoryEntries);
            service = new AuditService(store, clock);
        }
        return service;
    }
}
