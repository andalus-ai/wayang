package tech.kayys.wayang.deployment;

/**
 * Application-supplied authenticated encryption and serialization boundary for reconciliation
 * context. Production implementations must use authenticated encryption (for example AES-GCM or
 * an envelope-encryption KMS), bind the operation identity as associated data, and reject tampering.
 *
 * No plaintext codec is provided by the deployment module.
 */
public interface DeploymentLifecycleReconciliationContextCodec {
    byte[] encode(DeploymentLifecycleReconciliationContext context);

    DeploymentLifecycleReconciliationContext decode(byte[] ciphertext);
}
