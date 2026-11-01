# Secret Vault API Quick Reference

## Usage Examples

### 1. Store a Secret

```java
// Using builder pattern
StoreSecretRequest request = StoreSecretRequest.builder()
    .tenantId("tenant-123")
    .path("prod/database/credentials")
    .data(Map.of(
        "username", "dbuser",
        "password", "secret-password"
    ))
    .type(SecretType.DATABASE_CREDENTIAL)
    .ttl(Duration.ofDays(90))
    .rotatable(true)
    .metadata(Map.of("env", "production"))
    .build();

Uni<SecretMetadata> result = secretManager.store(request);
```

### 2. Retrieve a Secret

```java
// Get latest version
Uni<Secret> latest = secretManager.retrieve(
    RetrieveSecretRequest.latest("tenant-123", "prod/database/credentials")
);

// Get specific version
Uni<Secret> specific = secretManager.retrieve(
    RetrieveSecretRequest.version("tenant-123", "prod/database/credentials", 3)
);

// Extract data
Secret secret = latest.await().indefinitely();
String password = secret.data().get("password");
```

### 3. Delete a Secret

```java
// Soft delete (recoverable)
Uni<Void> soft = secretManager.delete(
    DeleteSecretRequest.soft("tenant-123", "prod/database/credentials", 
        "Rotation required - old credentials")
);

// Hard delete (permanent)
Uni<Void> hard = secretManager.delete(
    DeleteSecretRequest.hard("tenant-123", "prod/database/credentials", 
        "GDPR cleanup - customer requested")
);
```

### 4. List Secrets

```java
Uni<List<SecretMetadata>> secrets = secretManager.list(
    "tenant-123", 
    "prod/database/"
);

secrets.subscribe().with(
    list -> list.forEach(meta -> {
        System.out.println("Path: " + meta.path());
        System.out.println("Version: " + meta.version());
        System.out.println("Status: " + meta.status());
    })
);
```

### 5. Rotate a Secret

```java
// Replace with new credentials, mark old as deprecated
Uni<SecretMetadata> rotated = secretManager.rotate(
    RotateSecretRequest.deprecateOld(
        "tenant-123",
        "prod/database/credentials",
        Map.of(
            "username", "dbuser",
            "password", "new-secret-password"
        )
    )
);

rotated.subscribe().with(meta -> {
    System.out.println("New version: " + meta.version());
    System.out.println("Status: " + meta.status());
});
```

### 6. Check Secret Metadata

```java
Uni<SecretMetadata> metadata = secretManager.getMetadata(
    "tenant-123", 
    "prod/database/credentials"
);

metadata.subscribe().with(meta -> {
    System.out.println("Type: " + meta.type());
    System.out.println("Created: " + meta.createdAt());
    System.out.println("Expired: " + meta.isExpired());
    System.out.println("Status: " + meta.status());
});
```

### 7. Check Backend Health

```java
Uni<HealthStatus> health = secretManager.health();

health.subscribe().with(status -> {
    if (status.healthy()) {
        System.out.println("Backend is healthy: " + status.backend());
    } else {
        System.out.println("Error: " + status.error().orElse("Unknown"));
    }
});
```

## Configuration

### Application Properties

```properties
# Secret backend (vault, aws, local)
secret.backend=vault

# Vault specific
vault.addr=https://vault.example.com:8200
vault.token=${VAULT_TOKEN}
vault.token.renewal.enabled=true
vault.token.ttl.minutes=60
vault.token.renewal.interval.minutes=10

# AWS Secrets Manager specific
aws.region=us-east-1
aws.credentials.profile=default

# Local encrypted storage
local.storage.path=/var/secrets
local.encryption.key=${ENCRYPTION_KEY}
```

## Error Handling

```java
secretManager.retrieve(request)
    .onFailure().invoke(failure -> {
        if (failure instanceof SecretException ex) {
            switch (ex.getErrorCode()) {
                case SECRET_NOT_FOUND:
                    LOG.warn("Secret not found");
                    break;
                case PERMISSION_DENIED:
                    LOG.error("Access denied to secret");
                    break;
                case BACKEND_UNAVAILABLE:
                    LOG.error("Secret backend is down");
                    break;
                default:
                    LOG.error("Unknown error: " + ex.getErrorCode());
            }
        }
    })
    .subscribe()
    .with(secret -> handleSecret(secret));
```

## Supported Secret Types

- `GENERIC` - Generic untyped secret
- `API_KEY` - API key/token
- `DATABASE_CREDENTIAL` - Database credentials
- `OAUTH_TOKEN` - OAuth 2.0 token
- `SSH_KEY` - SSH private key
- `TLS_CERTIFICATE` - TLS/SSL certificate
- `ENCRYPTION_KEY` - Encryption key for data encryption
- `AWS_CREDENTIAL` - AWS access key/secret
- `AZURE_CREDENTIAL` - Azure credentials
- `GCP_CREDENTIAL` - Google Cloud Platform credentials

## Secret Lifecycle

```
ACTIVE -> (rotate) -> ROTATED -> (deprecate) -> DEPRECATED -> (delete) -> DELETED
                                                           -> (ttl) -> EXPIRED
```

## Audit Logging

All operations are logged for compliance:

- **Store** - Secret stored with version
- **Retrieve** - Secret retrieved (debug level)
- **Delete** - Soft/hard delete with reason
- **Rotate** - Version rotation with old/new numbers
- **Access Denied** - Failed access attempts
- **Expiration** - Secret TTL expiration

## Multi-Tenancy

All operations are tenant-isolated:

```java
// Tenant A cannot access Tenant B's secrets
secretManager.retrieve(
    RetrieveSecretRequest.latest("tenant-a", "path/to/secret")
)
// Will not return tenant-b's secrets even if path matches
```

## Best Practices

1. **Always use specific versions** when critical (e.g., deployments)
   ```java
   RetrieveSecretRequest.version(tenantId, path, 5)  // Good
   RetrieveSecretRequest.latest(tenantId, path)      // OK for non-critical
   ```

2. **Enable rotation** for frequently-used credentials
   ```java
   builder.rotatable(true).ttl(Duration.ofDays(30))
   ```

3. **Use soft delete** by default, hard delete only when necessary
   ```java
   DeleteSecretRequest.soft(...)     // Default
   DeleteSecretRequest.hard(...)     // Only for GDPR/compliance
   ```

4. **Monitor expiration** and rotate before TTL
   ```java
   if (metadata.isExpired()) {
       rotateSecret();
   }
   ```

5. **Check backend health** in startup/monitoring
   ```java
   secretManager.health().subscribe().with(status -> {
       if (!status.healthy()) {
           throw new IllegalStateException("Secret backend unavailable");
       }
   });
   ```

## Integration with Wayang

1. **Dependency Injection**
   ```java
   @Inject
   SecretManager secretManager;
   ```

2. **Authentication Integration**
   - SecretManager validates tenant context from security principal
   - Returns PERMISSION_DENIED for unauthorized access

3. **Audit Integration**
   - VaultAuditLogger integrates with Wayang's audit system
   - All operations are tracked per tenant

4. **Configuration Integration**
   - Properties via Eclipse MicroProfile Config
   - Supports environment variables and system properties

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `BACKEND_UNAVAILABLE` | Check backend connectivity and health endpoint |
| `PERMISSION_DENIED` | Verify tenant context and credentials |
| `SECRET_NOT_FOUND` | Check path format and secret existence |
| `ENCRYPTION_FAILED` | Verify encryption key availability |
| `QUOTA_EXCEEDED` | Check storage limits and cleanup old versions |

---

For detailed API documentation, see [IMPLEMENTATION.md](./IMPLEMENTATION.md)
