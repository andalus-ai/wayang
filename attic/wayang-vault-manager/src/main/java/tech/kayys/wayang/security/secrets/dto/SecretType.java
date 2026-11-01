package tech.kayys.wayang.security.secrets.dto;

/**
 * Enumeration of supported secret types.
 * Each type may have different handling, rotation policies, and validation rules.
 */
public enum SecretType {
    /**
     * Generic untyped secret
     */
    GENERIC,

    /**
     * API key/token
     */
    API_KEY,

    /**
     * Database credentials
     */
    DATABASE_CREDENTIAL,

    /**
     * OAuth 2.0 token
     */
    OAUTH_TOKEN,

    /**
     * SSH private key
     */
    SSH_KEY,

    /**
     * TLS/SSL certificate
     */
    TLS_CERTIFICATE,

    /**
     * Encryption key for data encryption
     */
    ENCRYPTION_KEY,

    /**
     * AWS access key/secret
     */
    AWS_CREDENTIAL,

    /**
     * Azure credentials
     */
    AZURE_CREDENTIAL,

    /**
     * Google Cloud Platform credentials
     */
    GCP_CREDENTIAL
}
