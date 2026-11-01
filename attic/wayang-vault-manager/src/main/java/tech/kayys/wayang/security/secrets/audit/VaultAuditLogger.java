package tech.kayys.wayang.security.secrets.audit;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Audit logger for secret operations.
 * 
 * Logs all secret operations for compliance and security auditing.
 * In production, should integrate with AuditPayload system for structured logging.
 */
@ApplicationScoped
public class VaultAuditLogger {
    
    private static final Logger LOG = Logger.getLogger(VaultAuditLogger.class);

    /**
     * Log secret store operation
     */
    public void logSecretStore(String tenantId, String path, int version) {
        LOG.infof("AUDIT: Secret stored - tenant=%s, path=%s, version=%d", 
            tenantId, path, version);
    }

    /**
     * Log secret retrieval operation
     */
    public void logSecretRetrieve(String tenantId, String path, int version) {
        LOG.debugf("AUDIT: Secret retrieved - tenant=%s, path=%s, version=%d", 
            tenantId, path, version);
    }

    /**
     * Log secret deletion operation
     */
    public void logSecretDelete(String tenantId, String path, boolean hard, String reason) {
        LOG.infof("AUDIT: Secret deleted - tenant=%s, path=%s, hard=%b, reason=%s", 
            tenantId, path, hard, reason);
    }

    /**
     * Log secret rotation operation
     */
    public void logSecretRotate(String tenantId, String path, int oldVersion, int newVersion) {
        LOG.infof("AUDIT: Secret rotated - tenant=%s, path=%s, from=%d, to=%d", 
            tenantId, path, oldVersion, newVersion);
    }

    /**
     * Log access denied operation
     */
    public void logAccessDenied(String tenantId, String path, String reason) {
        LOG.warnf("AUDIT: Access denied - tenant=%s, path=%s, reason=%s", 
            tenantId, path, reason);
    }

    /**
     * Log secret expiration
     */
    public void logSecretExpired(String tenantId, String path) {
        LOG.infof("AUDIT: Secret expired - tenant=%s, path=%s", 
            tenantId, path);
    }
}
