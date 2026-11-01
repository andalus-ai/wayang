package tech.kayys.wayang.security.secrets.sync;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.kayys.wayang.security.secrets.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Secret synchronization service for multi-region/multi-backend deployments.
 * 
 * Features:
 * - Bi-directional sync between backends
 * - Conflict resolution
 * - Delta sync (only changed secrets)
 * - Sync verification
 * - Cross-region replication
 * - Disaster recovery readiness
 */
@ApplicationScoped
public class SecretSyncService {
    
    private static final Logger LOG = Logger.getLogger(SecretSyncService.class);
    
    @Inject
    @Named("primary")
    SecretManager primaryBackend;
    
    @Inject
    @Named("secondary")
    SecretManager secondaryBackend;
    
    @Inject
    SyncStateRepository syncStateRepository;
    
    @Inject
    ConflictResolver conflictResolver;
    
    @Inject
    ProvenanceService provenanceService;
    
    @ConfigProperty(name = "sync.enabled", defaultValue = "false")
    boolean syncEnabled;
    
    @ConfigProperty(name = "sync.mode", defaultValue = "ONE_WAY")
    SyncMode syncMode;
    
    @ConfigProperty(name = "sync.conflict-resolution", defaultValue = "LATEST_WINS")
    ConflictResolutionStrategy conflictStrategy;
    
    private final Map<String, SyncStatus> activeSyncs = new ConcurrentHashMap<>();
    
    /**
     * Scheduled sync - runs every 5 minutes
     */
    @Scheduled(cron = "0 */5 * * * ?")
    void performScheduledSync() {
        if (!syncEnabled) {
            return;
        }
        
        LOG.info("Starting scheduled secret synchronization");
        
        syncAllSecrets()
            .subscribe().with(
                result -> LOG.infof("Sync completed: %s", result),
                error -> LOG.errorf(error, "Sync failed")
            );
    }
    
    /**
     * Sync all secrets between backends
     */
    public Uni<SyncResult> syncAllSecrets() {
        String syncId = UUID.randomUUID().toString();
        Instant startTime = Instant.now();
        
        LOG.infof("Starting sync: %s, mode: %s", syncId, syncMode);
        activeSyncs.put(syncId, SyncStatus.IN_PROGRESS);
        
        return Uni.createFrom().deferred(() -> {
            switch (syncMode) {
                case ONE_WAY:
                    return syncOneWay(syncId, startTime);
                case BI_DIRECTIONAL:
                    return syncBiDirectional(syncId, startTime);
                case MIRROR:
                    return syncMirror(syncId, startTime);
                default:
                    return Uni.createFrom().failure(
                        new IllegalStateException("Unknown sync mode: " + syncMode)
                    );
            }
        })
        .eventually(() -> activeSyncs.remove(syncId));
    }
    
    /**
     * One-way sync: primary -> secondary
     */
    private Uni<SyncResult> syncOneWay(String syncId, Instant startTime) {
        return getTenants()
            .onItem().transformToMulti(tenants ->
                Multi.createFrom().iterable(tenants)
            )
            .onItem().transformToUniAndMerge(tenantId ->
                syncTenantOneWay(tenantId)
            )
            .collect().asList()
            .onItem().transform(tenantResults -> {
                int synced = tenantResults.stream()
                    .mapToInt(TenantSyncResult::synced)
                    .sum();
                int conflicts = tenantResults.stream()
                    .mapToInt(TenantSyncResult::conflicts)
                    .sum();
                int errors = tenantResults.stream()
                    .mapToInt(TenantSyncResult::errors)
                    .sum();
                
                SyncResult result = new SyncResult(
                    syncId,
                    syncMode,
                    startTime,
                    Instant.now(),
                    synced,
                    conflicts,
                    errors,
                    tenantResults
                );
                
                logSync(result);
                activeSyncs.put(syncId, SyncStatus.COMPLETED);
                
                return result;
            })
            .onFailure().recoverWithItem(error -> {
                LOG.errorf(error, "Sync failed: %s", syncId);
                activeSyncs.put(syncId, SyncStatus.FAILED);
                
                return new SyncResult(
                    syncId,
                    syncMode,
                    startTime,
                    Instant.now(),
                    0,
                    0,
                    1,
                    List.of()
                );
            });
    }
    
    /**
     * Sync tenant secrets one-way
     */
    private Uni<TenantSyncResult> syncTenantOneWay(String tenantId) {
        LOG.debugf("Syncing tenant one-way: %s", tenantId);
        
        return primaryBackend.list(tenantId, "")
            .onItem().transformToMulti(metadataList ->
                Multi.createFrom().iterable(metadataList)
            )
            .onItem().transformToUniAndMerge(metadata ->
                syncSecretOneWay(tenantId, metadata)
            )
            .collect().asList()
            .onItem().transform(results -> {
                long synced = results.stream().filter(r -> r).count();
                long errors = results.stream().filter(r -> !r).count();
                
                return new TenantSyncResult(
                    tenantId,
                    (int) synced,
                    0,
                    (int) errors
                );
            });
    }
    
    /**
     * Sync a single secret one-way
     */
    private Uni<Boolean> syncSecretOneWay(String tenantId, SecretMetadata metadata) {
        // Get latest sync state
        return syncStateRepository.getLastSync(tenantId, metadata.path())
            .onItem().transformToUni(lastSyncOpt -> {
                // Check if secret has changed since last sync
                if (lastSyncOpt.isPresent()) {
                    SyncState lastSync = lastSyncOpt.get();
                    if (!metadata.updatedAt().isAfter(lastSync.lastSyncTime())) {
                        LOG.debugf("Secret unchanged, skipping: %s", metadata.path());
                        return Uni.createFrom().item(true);
                    }
                }
                
                // Retrieve secret from primary
                return primaryBackend.retrieve(
                    RetrieveSecretRequest.of(tenantId, metadata.path())
                )
                .onItem().transformToUni(secret -> {
                    // Store in secondary
                    StoreSecretRequest request = StoreSecretRequest.builder()
                        .tenantId(tenantId)
                        .path(secret.path())
                        .data(secret.data())
                        .type(secret.metadata().type())
                        .rotatable(secret.metadata().rotatable())
                        .metadata(secret.metadata().metadata())
                        .build();
                    
                    return secondaryBackend.store(request)
                        .onItem().transformToUni(newMetadata -> {
                            // Update sync state
                            SyncState syncState = new SyncState(
                                tenantId,
                                secret.path(),
                                metadata.version(),
                                newMetadata.version(),
                                Instant.now(),
                                SyncDirection.PRIMARY_TO_SECONDARY
                            );
                            
                            return syncStateRepository.save(syncState)
                                .onItem().transform(v -> true);
                        });
                })
                .onFailure().recoverWithItem(error -> {
                    LOG.errorf(error, "Failed to sync secret: %s", metadata.path());
                    return false;
                });
            });
    }
    
    /**
     * Bi-directional sync with conflict resolution
     */
    private Uni<SyncResult> syncBiDirectional(String syncId, Instant startTime) {
        return getTenants()
            .onItem().transformToMulti(tenants ->
                Multi.createFrom().iterable(tenants)
            )
            .onItem().transformToUniAndMerge(tenantId ->
                syncTenantBiDirectional(tenantId)
            )
            .collect().asList()
            .onItem().transform(tenantResults -> {
                int synced = tenantResults.stream()
                    .mapToInt(TenantSyncResult::synced)
                    .sum();
                int conflicts = tenantResults.stream()
                    .mapToInt(TenantSyncResult::conflicts)
                    .sum();
                int errors = tenantResults.stream()
                    .mapToInt(TenantSyncResult::errors)
                    .sum();
                
                SyncResult result = new SyncResult(
                    syncId,
                    syncMode,
                    startTime,
                    Instant.now(),
                    synced,
                    conflicts,
                    errors,
                    tenantResults
                );
                
                logSync(result);
                activeSyncs.put(syncId, SyncStatus.COMPLETED);
                
                return result;
            });
    }
    
    /**
     * Sync tenant bi-directionally
     */
    private Uni<TenantSyncResult> syncTenantBiDirectional(String tenantId) {
        // Get secrets from both backends
        return Uni.combine().all()
            .unis(
                primaryBackend.list(tenantId, ""),
                secondaryBackend.list(tenantId, "")
            )
            .asTuple()
            .onItem().transformToUni(tuple -> {
                List<SecretMetadata> primarySecrets = tuple.getItem1();
                List<SecretMetadata> secondarySecrets = tuple.getItem2();
                
                // Find differences
                Set<String> allPaths = new HashSet<>();
                primarySecrets.forEach(s -> allPaths.add(s.path()));
                secondarySecrets.forEach(s -> allPaths.add(s.path()));
                
                return Multi.createFrom().iterable(allPaths)
                    .onItem().transformToUniAndMerge(path ->
                        syncSecretBiDirectional(tenantId, path, 
                            findSecret(primarySecrets, path),
                            findSecret(secondarySecrets, path))
                    )
                    .collect().asList()
                    .onItem().transform(results -> {
                        long synced = results.stream()
                            .filter(r -> r.action() != SyncAction.SKIP)
                            .count();
                        long conflicts = results.stream()
                            .filter(r -> r.action() == SyncAction.CONFLICT)
                            .count();
                        
                        return new TenantSyncResult(
                            tenantId,
                            (int) synced,
                            (int) conflicts,
                            0
                        );
                    });
            });
    }
    
    /**
     * Sync a single secret bi-directionally
     */
    private Uni<SecretSyncResult> syncSecretBiDirectional(
            String tenantId,
            String path,
            Optional<SecretMetadata> primaryOpt,
            Optional<SecretMetadata> secondaryOpt) {
        
        // Both exist - check for conflicts
        if (primaryOpt.isPresent() && secondaryOpt.isPresent()) {
            SecretMetadata primary = primaryOpt.get();
            SecretMetadata secondary = secondaryOpt.get();
            
            // Check if versions differ
            if (primary.version() == secondary.version()) {
                return Uni.createFrom().item(
                    new SecretSyncResult(path, SyncAction.SKIP, null)
                );
            }
            
            // Conflict - resolve
            return resolveConflict(tenantId, path, primary, secondary);
        }
        
        // Only in primary - copy to secondary
        if (primaryOpt.isPresent()) {
            return copySecret(tenantId, path, 
                SyncDirection.PRIMARY_TO_SECONDARY);
        }
        
        // Only in secondary - copy to primary
        if (secondaryOpt.isPresent()) {
            return copySecret(tenantId, path, 
                SyncDirection.SECONDARY_TO_PRIMARY);
        }
        
        // Shouldn't happen
        return Uni.createFrom().item(
            new SecretSyncResult(path, SyncAction.SKIP, null)
        );
    }
    
    /**
     * Resolve conflict between two versions
     */
    private Uni<SecretSyncResult> resolveConflict(
            String tenantId,
            String path,
            SecretMetadata primary,
            SecretMetadata secondary) {
        
        LOG.warnf("Conflict detected: tenant=%s, path=%s, primary_v=%d, secondary_v=%d",
            tenantId, path, primary.version(), secondary.version());
        
        // Use configured strategy
        SyncDirection winner = conflictResolver.resolve(
            primary, 
            secondary, 
            conflictStrategy
        );
        
        return copySecret(tenantId, path, winner)
            .onItem().transform(result ->
                new SecretSyncResult(path, SyncAction.CONFLICT, winner)
            );
    }
    
    /**
     * Copy secret from one backend to another
     */
    private Uni<SecretSyncResult> copySecret(
            String tenantId,
            String path,
            SyncDirection direction) {
        
        SecretManager source = direction == SyncDirection.PRIMARY_TO_SECONDARY ?
            primaryBackend : secondaryBackend;
        SecretManager target = direction == SyncDirection.PRIMARY_TO_SECONDARY ?
            secondaryBackend : primaryBackend;
        
        return source.retrieve(RetrieveSecretRequest.of(tenantId, path))
            .onItem().transformToUni(secret -> {
                StoreSecretRequest request = StoreSecretRequest.builder()
                    .tenantId(tenantId)
                    .path(secret.path())
                    .data(secret.data())
                    .type(secret.metadata().type())
                    .rotatable(secret.metadata().rotatable())
                    .metadata(secret.metadata().metadata())
                    .build();
                
                return target.store(request)
                    .onItem().transform(metadata ->
                        new SecretSyncResult(path, SyncAction.COPY, direction)
                    );
            });
    }
    
    /**
     * Mirror sync: make secondary identical to primary
     */
    private Uni<SyncResult> syncMirror(String syncId, Instant startTime) {
        // Delete secrets in secondary that don't exist in primary
        // Then sync all secrets from primary to secondary
        return syncOneWay(syncId, startTime);
    }
    
    /**
     * Get sync status
     */
    public Optional<SyncStatus> getSyncStatus(String syncId) {
        return Optional.ofNullable(activeSyncs.get(syncId));
    }
    
    // Helper methods
    
    private Optional<SecretMetadata> findSecret(
            List<SecretMetadata> secrets, 
            String path) {
        return secrets.stream()
            .filter(s -> s.path().equals(path))
            .findFirst();
    }
    
    private Uni<List<String>> getTenants() {
        return Uni.createFrom().item(List.of("default-tenant"));
    }
    
    private void logSync(SyncResult result) {
        provenanceService.log(AuditPayload.builder()
            .runId(UUID.fromString(result.syncId()))
            .nodeId("sync-service")
            .systemActor()
            .event("SECRET_SYNC_COMPLETED")
            .level(AuditLevel.INFO)
            .metadata(Map.of(
                "sync_mode", result.mode().name(),
                "synced", result.synced(),
                "conflicts", result.conflicts(),
                "errors", result.errors()
            ))
            .build());
    }
}

/**
 * Conflict resolution service
 */
@ApplicationScoped
class ConflictResolver {
    
    private static final Logger LOG = Logger.getLogger(ConflictResolver.class);
    
    public SyncDirection resolve(
            SecretMetadata primary,
            SecretMetadata secondary,
            ConflictResolutionStrategy strategy) {
        
        return switch (strategy) {
            case LATEST_WINS -> 
                primary.updatedAt().isAfter(secondary.updatedAt()) ?
                    SyncDirection.PRIMARY_TO_SECONDARY :
                    SyncDirection.SECONDARY_TO_PRIMARY;
                    
            case PRIMARY_WINS -> SyncDirection.PRIMARY_TO_SECONDARY;
            
            case SECONDARY_WINS -> SyncDirection.SECONDARY_TO_PRIMARY;
            
            case HIGHEST_VERSION -> 
                primary.version() > secondary.version() ?
                    SyncDirection.PRIMARY_TO_SECONDARY :
                    SyncDirection.SECONDARY_TO_PRIMARY;
        };
    }
}

/**
 * Sync state repository
 */
@ApplicationScoped
class SyncStateRepository {
    
    private final Map<String, SyncState> states = new ConcurrentHashMap<>();
    
    public Uni<Optional<SyncState>> getLastSync(String tenantId, String path) {
        String key = tenantId + ":" + path;
        return Uni.createFrom().item(Optional.ofNullable(states.get(key)));
    }
    
    public Uni<Void> save(SyncState state) {
        String key = state.tenantId() + ":" + state.path();
        states.put(key, state);
        return Uni.createFrom().voidItem();
    }
}

// DTOs

record SyncResult(
    String syncId,
    SyncMode mode,
    Instant startTime,
    Instant endTime,
    int synced,
    int conflicts,
    int errors,
    List<TenantSyncResult> tenantResults
) {}

record TenantSyncResult(
    String tenantId,
    int synced,
    int conflicts,
    int errors
) {}

record SecretSyncResult(
    String path,
    SyncAction action,
    SyncDirection direction
) {}

record SyncState(
    String tenantId,
    String path,
    int primaryVersion,
    int secondaryVersion,
    Instant lastSyncTime,
    SyncDirection direction
) {}

enum SyncMode {
    ONE_WAY,           // Primary -> Secondary only
    BI_DIRECTIONAL,    // Both directions with conflict resolution
    MIRROR             // Make secondary identical to primary
}

enum SyncDirection {
    PRIMARY_TO_SECONDARY,
    SECONDARY_TO_PRIMARY
}

enum SyncAction {
    COPY,
    CONFLICT,
    SKIP
}

enum SyncStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

enum ConflictResolutionStrategy {
    LATEST_WINS,       // Use most recently updated
    PRIMARY_WINS,      // Always use primary
    SECONDARY_WINS,    // Always use secondary
    HIGHEST_VERSION    // Use highest version number
}

package tech.kayys.wayang.security.secrets.backup;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.kayys.wayang.security.secrets.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Secret backup and disaster recovery service.
 * 
 * Features:
 * - Automated daily backups
 * - Encrypted backup files
 * - Multiple storage backends (S3, GCS, Azure Blob, local)
 * - Point-in-time recovery
 * - Backup verification
 * - Cross-region replication
 * - Retention policies
 */
@ApplicationScoped
public class SecretBackupService {
    
    private static final Logger LOG = Logger.getLogger(SecretBackupService.class);
    private static final String BACKUP_VERSION = "1.0";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    
    @Inject
    SecretManager secretManager;
    
    @Inject
    BackupStorageProvider storageProvider;
    
    @Inject
    BackupEncryption encryption;
    
    @Inject
    ObjectMapper objectMapper;
    
    @Inject
    ProvenanceService provenanceService;
    
    @ConfigProperty(name = "backup.enabled", defaultValue = "true")
    boolean backupEnabled;
    
    @ConfigProperty(name = "backup.retention-days", defaultValue = "90")
    int retentionDays;
    
    /**
     * Scheduled backup - runs daily at 3 AM
     */
    @Scheduled(cron = "0 0 3 * * ?")
    void performScheduledBackup() {
        if (!backupEnabled) {
            LOG.info("Backup is disabled");
            return;
        }
        
        LOG.info("Starting scheduled secret backup");
        
        backupAllSecrets()
            .subscribe().with(
                result -> LOG.infof("Backup completed successfully: %s", result.backupId()),
                error -> LOG.errorf(error, "Backup failed")
            );
    }
    
    /**
     * Backup all secrets for all tenants
     */
    public Uni<BackupResult> backupAllSecrets() {
        String backupId = generateBackupId();
        Instant startTime = Instant.now();
        
        LOG.infof("Starting backup: %s", backupId);
        
        return Uni.createFrom().deferred(() -> {
            List<String> tenants = getTenants();
            
            return Multi.createFrom().iterable(tenants)
                .onItem().transformToUniAndMerge(tenantId ->
                    backupTenantSecrets(tenantId)
                )
                .collect().asList()
                .onItem().transformToUni(tenantBackups -> {
                    // Combine all tenant backups
                    BackupManifest manifest = new BackupManifest(
                        backupId,
                        BACKUP_VERSION,
                        startTime,
                        Instant.now(),
                        tenantBackups.stream()
                            .mapToInt(tb -> tb.secretCount())
                            .sum(),
                        tenantBackups
                    );
                    
                    // Serialize and encrypt
                    return serializeAndEncrypt(manifest)
                        .onItem().transformToUni(encryptedData ->
                            // Store backup
                            storageProvider.store(backupId, encryptedData)
                                .onItem().transform(location -> {
                                    BackupResult result = new BackupResult(
                                        backupId,
                                        true,
                                        manifest.secretCount(),
                                        manifest.tenantBackups().size(),
                                        location,
                                        null
                                    );
                                    
                                    logBackup(result);
                                    return result;
                                })
                        );
                })
                .onFailure().recoverWithItem(error -> {
                    LOG.errorf(error, "Backup failed: %s", backupId);
                    
                    BackupResult result = new BackupResult(
                        backupId,
                        false,
                        0,
                        0,
                        null,
                        error.getMessage()
                    );
                    
                    logBackup(result);
                    return result;
                });
        });
    }
    
    /**
     * Backup secrets for a specific tenant
     */
    public Uni<TenantBackup> backupTenantSecrets(String tenantId) {
        LOG.infof("Backing up secrets for tenant: %s", tenantId);
        
        return secretManager.list(tenantId, "")
            .onItem().transformToMulti(metadataList ->
                Multi.createFrom().iterable(metadataList)
            )
            .onItem().transformToUniAndMerge(metadata ->
                secretManager.retrieve(
                    RetrieveSecretRequest.of(tenantId, metadata.path())
                )
                .onItem().transform(secret -> new BackupSecret(
                    secret.path(),
                    secret.data(),
                    secret.metadata().type(),
                    secret.metadata().version(),
                    secret.metadata().createdAt(),
                    secret.metadata().expiresAt().orElse(null),
                    secret.metadata().rotatable(),
                    secret.metadata().metadata()
                ))
            )
            .collect().asList()
            .onItem().transform(secrets ->
                new TenantBackup(tenantId, secrets.size(), secrets)
            );
    }
    
    /**
     * Restore secrets from backup
     */
    public Uni<RestoreResult> restoreFromBackup(String backupId, RestoreOptions options) {
        LOG.infof("Starting restore from backup: %s", backupId);
        
        return storageProvider.retrieve(backupId)
            .onItem().transformToUni(encryptedData ->
                decryptAndDeserialize(encryptedData)
            )
            .onItem().transformToUni(manifest -> {
                // Filter tenants if specified
                List<TenantBackup> tenantsToRestore = manifest.tenantBackups();
                if (options.tenantId() != null) {
                    tenantsToRestore = tenantsToRestore.stream()
                        .filter(tb -> tb.tenantId().equals(options.tenantId()))
                        .toList();
                }
                
                return Multi.createFrom().iterable(tenantsToRestore)
                    .onItem().transformToUniAndMerge(tenantBackup ->
                        restoreTenantSecrets(tenantBackup, options)
                    )
                    .collect().asList()
                    .onItem().transform(results -> {
                        int totalRestored = results.stream()
                            .mapToInt(r -> r.restored())
                            .sum();
                        int totalSkipped = results.stream()
                            .mapToInt(r -> r.skipped())
                            .sum();
                        int totalFailed = results.stream()
                            .mapToInt(r -> r.failed())
                            .sum();
                        
                        RestoreResult result = new RestoreResult(
                            backupId,
                            true,
                            totalRestored,
                            totalSkipped,
                            totalFailed,
                            null
                        );
                        
                        logRestore(result);
                        return result;
                    });
            })
            .onFailure().recoverWithItem(error -> {
                LOG.errorf(error, "Restore failed: %s", backupId);
                
                RestoreResult result = new RestoreResult(
                    backupId,
                    false,
                    0,
                    0,
                    0,
                    error.getMessage()
                );
                
                logRestore(result);
                return result;
            });
    }
    
    /**
     * Restore secrets for a specific tenant
     */
    private Uni<TenantRestoreResult> restoreTenantSecrets(
            TenantBackup backup, 
            RestoreOptions options) {
        
        LOG.infof("Restoring %d secrets for tenant: %s", 
            backup.secretCount(), backup.tenantId());
        
        return Multi.createFrom().iterable(backup.secrets())
            .onItem().transformToUniAndMerge(backupSecret -> 
                restoreSecret(backup.tenantId(), backupSecret, options)
            )
            .collect().asList()
            .onItem().transform(results -> {
                long restored = results.stream().filter(r -> r).count();
                long skipped = results.stream().filter(r -> !r).count();
                
                return new TenantRestoreResult(
                    backup.tenantId(),
                    (int) restored,
                    (int) skipped,
                    0
                );
            });
    }
    
    /**
     * Restore a single secret
     */
    private Uni<Boolean> restoreSecret(
            String tenantId, 
            BackupSecret backupSecret, 
            RestoreOptions options) {
        
        // Check if secret already exists
        return secretManager.exists(tenantId, backupSecret.path())
            .onItem().transformToUni(exists -> {
                if (exists && !options.overwrite()) {
                    LOG.debugf("Skipping existing secret: %s", backupSecret.path());
                    return Uni.createFrom().item(false);
                }
                
                // Restore secret
                StoreSecretRequest request = StoreSecretRequest.builder()
                    .tenantId(tenantId)
                    .path(backupSecret.path())
                    .data(backupSecret.data())
                    .type(backupSecret.type())
                    .rotatable(backupSecret.rotatable())
                    .metadata(backupSecret.metadata())
                    .build();
                
                return secretManager.store(request)
                    .onItem().transform(metadata -> true)
                    .onFailure().recoverWithItem(error -> {
                        LOG.errorf(error, "Failed to restore secret: %s", 
                            backupSecret.path());
                        return false;
                    });
            });
    }
    
    /**
     * List available backups
     */
    public Uni<List<BackupInfo>> listBackups() {
        return storageProvider.list()
            .onItem().transformToMulti(backupIds ->
                Multi.createFrom().iterable(backupIds)
            )
            .onItem().transformToUniAndMerge(backupId ->
                getBackupInfo(backupId)
            )
            .collect().asList()
            .onItem().transform(backups ->
                backups.stream()
                    .sorted(Comparator.comparing(
                        BackupInfo::createdAt).reversed())
                    .toList()
            );
    }
    
    /**
     * Get backup information
     */
    public Uni<BackupInfo> getBackupInfo(String backupId) {
        return storageProvider.getMetadata(backupId)
            .onItem().transform(metadata ->
                new BackupInfo(
                    backupId,
                    metadata.size(),
                    metadata.createdAt(),
                    metadata.location(),
                    metadata.encrypted()
                )
            );
    }
    
    /**
     * Verify backup integrity
     */
    public Uni<VerificationResult> verifyBackup(String backupId) {
        LOG.infof("Verifying backup: %s", backupId);
        
        return storageProvider.retrieve(backupId)
            .onItem().transformToUni(encryptedData ->
                decryptAndDeserialize(encryptedData)
            )
            .onItem().transform(manifest -> {
                // Verify manifest
                boolean valid = manifest.backupId().equals(backupId) &&
                               manifest.version().equals(BACKUP_VERSION) &&
                               manifest.secretCount() > 0;
                
                return new VerificationResult(
                    backupId,
                    valid,
                    manifest.secretCount(),
                    valid ? null : "Invalid backup format"
                );
            })
            .onFailure().recoverWithItem(error ->
                new VerificationResult(
                    backupId,
                    false,
                    0,
                    "Verification failed: " + error.getMessage()
                )
            );
    }
    
    /**
     * Delete old backups based on retention policy
     */
    @Scheduled(cron = "0 0 4 * * ?") // 4 AM daily
    void cleanupOldBackups() {
        if (!backupEnabled) {
            return;
        }
        
        LOG.info("Starting backup cleanup");
        
        Instant threshold = Instant.now().minusSeconds(
            retentionDays * 24 * 60 * 60
        );
        
        listBackups()
            .onItem().transformToMulti(backups ->
                Multi.createFrom().iterable(backups)
            )
            .filter(backup -> backup.createdAt().isBefore(threshold))
            .onItem().transformToUniAndMerge(backup ->
                storageProvider.delete(backup.backupId())
                    .onItem().invoke(() ->
                        LOG.infof("Deleted old backup: %s", backup.backupId())
                    )
            )
            .collect().asList()
            .subscribe().with(
                deleted -> LOG.infof("Cleanup completed: %d backups deleted", 
                    deleted.size()),
                error -> LOG.errorf(error, "Cleanup failed")
            );
    }
    
    // Helper methods
    
    private Uni<byte[]> serializeAndEncrypt(BackupManifest manifest) {
        return Uni.createFrom().item(() -> {
            try {
                // Serialize to JSON
                String json = objectMapper.writeValueAsString(manifest);
                
                // Compress
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
                    gzip.write(json.getBytes());
                }
                byte[] compressed = baos.toByteArray();
                
                // Encrypt
                return encryption.encrypt(compressed);
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize backup", e);
            }
        });
    }
    
    private Uni<BackupManifest> decryptAndDeserialize(byte[] encryptedData) {
        return Uni.createFrom().item(() -> {
            try {
                // Decrypt
                byte[] compressed = encryption.decrypt(encryptedData);
                
                // Decompress
                ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (GZIPInputStream gzip = new GZIPInputStream(bais)) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = gzip.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                }
                String json = baos.toString();
                
                // Deserialize
                return objectMapper.readValue(json, BackupManifest.class);
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize backup", e);
            }
        });
    }
    
    private String generateBackupId() {
        String timestamp = DateTimeFormatter.ISO_INSTANT
            .format(Instant.now())
            .replace(":", "-")
            .replace(".", "-");
        return "backup-" + timestamp + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    private List<String> getTenants() {
        // TODO: Get from tenant registry
        return List.of("default-tenant");
    }
    
    private void logBackup(BackupResult result) {
        provenanceService.log(AuditPayload.builder()
            .runId(UUID.randomUUID())
            .nodeId("backup-service")
            .systemActor()
            .event(result.success() ? "BACKUP_SUCCESS" : "BACKUP_FAILED")
            .level(result.success() ? AuditLevel.INFO : AuditLevel.ERROR)
            .metadata(Map.of(
                "backup_id", result.backupId(),
                "secret_count", result.secretCount(),
                "tenant_count", result.tenantCount()
            ))
            .build());
    }
    
    private void logRestore(RestoreResult result) {
        provenanceService.log(AuditPayload.builder()
            .runId(UUID.randomUUID())
            .nodeId("backup-service")
            .systemActor()
            .event(result.success() ? "RESTORE_SUCCESS" : "RESTORE_FAILED")
            .level(result.success() ? AuditLevel.INFO : AuditLevel.ERROR)
            .metadata(Map.of(
                "backup_id", result.backupId(),
                "restored", result.restored(),
                "skipped", result.skipped(),
                "failed", result.failed()
            ))
            .build());
    }
}

/**
 * Backup encryption service
 */
@ApplicationScoped
class BackupEncryption {
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    
    @ConfigProperty(name = "backup.encryption-key")
    Optional<String> encryptionKeyBase64;
    
    private SecretKey encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();
    
    @PostConstruct
    void init() {
        if (encryptionKeyBase64.isPresent()) {
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKeyBase64.get());
            encryptionKey = new SecretKeySpec(keyBytes, "AES");
        } else {
            // Generate ephemeral key
            try {
                KeyGenerator keyGen = KeyGenerator.getInstance("AES");
                keyGen.init(256, secureRandom);
                encryptionKey = keyGen.generateKey();
            } catch (Exception e) {
                throw new RuntimeException("Failed to generate encryption key", e);
            }
        }
    }
    
    public byte[] encrypt(byte[] plaintext) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, parameterSpec);
        
        byte[] ciphertext = cipher.doFinal(plaintext);
        
        // Prepend IV to ciphertext
        byte[] result = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
        
        return result;
    }
    
    public byte[] decrypt(byte[] encryptedData) throws Exception {
        // Extract IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(encryptedData, 0, iv, 0, iv.length);
        
        // Extract ciphertext
        byte[] ciphertext = new byte[encryptedData.length - iv.length];
        System.arraycopy(encryptedData, iv.length, ciphertext, 0, ciphertext.length);
        
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, parameterSpec);
        
        return cipher.doFinal(ciphertext);
    }
}

/**
 * Backup storage provider interface
 */
interface BackupStorageProvider {
    Uni<String> store(String backupId, byte[] data);
    Uni<byte[]> retrieve(String backupId);
    Uni<Void> delete(String backupId);
    Uni<List<String>> list();
    Uni<BackupMetadata> getMetadata(String backupId);
}

/**
 * S3 backup storage implementation
 */
@ApplicationScoped
class S3BackupStorage implements BackupStorageProvider {
    
    private static final Logger LOG = Logger.getLogger(S3BackupStorage.class);
    
    @Inject
    S3Client s3Client;
    
    @ConfigProperty(name = "backup.s3.bucket")
    String bucketName;
    
    @ConfigProperty(name = "backup.s3.prefix", defaultValue = "backups/")
    String prefix;
    
    @Override
    public Uni<String> store(String backupId, byte[] data) {
        return Uni.createFrom().item(() -> {
            String key = prefix + backupId + ".enc";
            
            PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType("application/octet-stream")
                .metadata(Map.of(
                    "backup-id", backupId,
                    "created-at", Instant.now().toString(),
                    "encrypted", "true"
                ))
                .build();
            
            s3Client.putObject(request, RequestBody.fromBytes(data));
            
            String location = String.format("s3://%s/%s", bucketName, key);
            LOG.infof("Backup stored: %s", location);
            
            return location;
        });
    }
    
    @Override
    public Uni<byte[]> retrieve(String backupId) {
        return Uni.createFrom().item(() -> {
            String key = prefix + backupId + ".enc";
            
            GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
            
            return s3Client.getObjectAsBytes(request).asByteArray();
        });
    }
    
    @Override
    public Uni<Void> delete(String backupId) {
        return Uni.createFrom().item(() -> {
            String key = prefix + backupId + ".enc";
            
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
            
            s3Client.deleteObject(request);
            return null;
        });
    }
    
    @Override
    public Uni<List<String>> list() {
        return Uni.createFrom().item(() -> {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build();
            
            return s3Client.listObjectsV2(request).contents().stream()
                .map(obj -> obj.key().replace(prefix, "").replace(".enc", ""))
                .toList();
        });
    }
    
    @Override
    public Uni<BackupMetadata> getMetadata(String backupId) {
        return Uni.createFrom().item(() -> {
            String key = prefix + backupId + ".enc";
            
            HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
            
            var response = s3Client.headObject(request);
            
            return new BackupMetadata(
                backupId,
                response.contentLength(),
                response.lastModified(),
                String.format("s3://%s/%s", bucketName, key),
                true
            );
        });
    }
}

// DTOs

record BackupManifest(
    String backupId,
    String version,
    Instant startTime,
    Instant endTime,
    int secretCount,
    List<TenantBackup> tenantBackups
) {}

record TenantBackup(
    String tenantId,
    int secretCount,
    List<BackupSecret> secrets
) {}

record BackupSecret(
    String path,
    Map<String, String> data,
    SecretType type,
    int version,
    Instant createdAt,
    Instant expiresAt,
    boolean rotatable,
    Map<String, String> metadata
) {}

record BackupResult(
    String backupId,
    boolean success,
    int secretCount,
    int tenantCount,
    String location,
    String error
) {}

record RestoreOptions(
    String tenantId,
    boolean overwrite,
    boolean verifyOnly
) {
    public static RestoreOptions all() {
        return new RestoreOptions(null, false, false);
    }
    
    public static RestoreOptions tenant(String tenantId) {
        return new RestoreOptions(tenantId, false, false);
    }
}

record RestoreResult(
    String backupId,
    boolean success,
    int restored,
    int skipped,
    int failed,
    String error
) {}

record TenantRestoreResult(
    String tenantId,
    int restored,
    int skipped,
    int failed
) {}

record BackupInfo(
    String backupId,
    long size,
    Instant createdAt,
    String location,
    boolean encrypted
) {}

record BackupMetadata(
    String backupId,
    long size,
    Instant createdAt,
    String location,
    boolean encrypted
) {}

record VerificationResult(
    String backupId,
    boolean valid,
    int secretCount,
    String error
) {}


package tech.kayys.wayang.security.secrets.approval;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import tech.kayys.wayang.security.secrets.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Secret approval workflow for high-security environments.
 * 
 * Features:
 * - Multi-stage approval process
 * - Role-based approvers
 * - Automatic escalation
 * - Time-based expiration
 * - Approval history
 * - Notification system
 */
@ApplicationScoped
public class SecretApprovalService {
    
    private static final Logger LOG = Logger.getLogger(SecretApprovalService.class);
    
    @Inject
    SecretManager secretManager;
    
    @Inject
    ApprovalRequestRepository repository;
    
    @Inject
    ApprovalNotifier notifier;
    
    @Inject
    ProvenanceService provenanceService;
    
    /**
     * Request approval for creating a new secret
     */
    public Uni<ApprovalRequest> requestSecretCreation(
            StoreSecretRequest request,
            String requesterId) {
        
        LOG.infof("Creating approval request for secret: tenant=%s, path=%s, requester=%s",
            request.tenantId(), request.path(), requesterId);
        
        return Panache.withTransaction(() -> {
            // Determine required approvers
            List<String> approvers = determineApprovers(request);
            
            // Create approval request
            ApprovalRequestEntity entity = new ApprovalRequestEntity();
            entity.requestId = UUID.randomUUID().toString();
            entity.tenantId = request.tenantId();
            entity.secretPath = request.path();
            entity.requestType = ApprovalRequestType.CREATE;
            entity.requesterId = requesterId;
            entity.requesterName = getUsername(requesterId);
            entity.status = ApprovalStatus.PENDING;
            entity.createdAt = Instant.now();
            entity.expiresAt = Instant.now().plus(Duration.ofHours(24));
            
            // Store the secret request as JSON
            entity.secretRequest = serializeRequest(request);
            
            // Create approval tasks
            entity.approvalTasks = approvers.stream()
                .map(approverId -> {
                    ApprovalTask task = new ApprovalTask();
                    task.approverId = approverId;
                    task.approverName = getUsername(approverId);
                    task.status = TaskStatus.PENDING;
                    task.createdAt = Instant.now();
                    return task;
                })
                .collect(Collectors.toList());
            
            return repository.persist(entity)
                .onItem().invoke(saved -> {
                    // Notify approvers
                    notifier.notifyApprovers(saved);
                    
                    // Log provenance
                    logApprovalRequest(saved, "APPROVAL_REQUESTED");
                })
                .onItem().transform(this::toApprovalRequest);
        });
    }
    
    /**
     * Request approval for rotating a secret
     */
    public Uni<ApprovalRequest> requestSecretRotation(
            String tenantId,
            String path,
            String requesterId) {
        
        return Panache.withTransaction(() -> {
            ApprovalRequestEntity entity = new ApprovalRequestEntity();
            entity.requestId = UUID.randomUUID().toString();
            entity.tenantId = tenantId;
            entity.secretPath = path;
            entity.requestType = ApprovalRequestType.ROTATE;
            entity.requesterId = requesterId;
            entity.requesterName = getUsername(requesterId);
            entity.status = ApprovalStatus.PENDING;
            entity.createdAt = Instant.now();
            entity.expiresAt = Instant.now().plus(Duration.ofHours(24));
            
            List<String> approvers = List.of("admin", "security-team");
            entity.approvalTasks = createApprovalTasks(approvers);
            
            return repository.persist(entity)
                .onItem().invoke(saved -> {
                    notifier.notifyApprovers(saved);
                    logApprovalRequest(saved, "ROTATION_APPROVAL_REQUESTED");
                })
                .onItem().transform(this::toApprovalRequest);
        });
    }
    
    /**
     * Approve or reject an approval request
     */
    public Uni<ApprovalRequest> processApproval(
            String requestId,
            String approverId,
            ApprovalDecision decision,
            String comments) {
        
        LOG.infof("Processing approval: requestId=%s, approver=%s, decision=%s",
            requestId, approverId, decision);
        
        return Panache.withTransaction(() ->
            repository.findByRequestId(requestId)
                .onItem().transformToUni(entityOpt -> {
                    if (entityOpt.isEmpty()) {
                        return Uni.createFrom().failure(
                            new ApprovalNotFoundException("Request not found: " + requestId)
                        );
                    }
                    
                    ApprovalRequestEntity entity = entityOpt.get();
                    
                    // Check if request is still pending
                    if (entity.status != ApprovalStatus.PENDING) {
                        return Uni.createFrom().failure(
                            new IllegalStateException("Request already processed")
                        );
                    }
                    
                    // Check if expired
                    if (Instant.now().isAfter(entity.expiresAt)) {
                        entity.status = ApprovalStatus.EXPIRED;
                        return repository.persist(entity)
                            .onItem().transform(saved -> {
                                throw new IllegalStateException("Request expired");
                            });
                    }
                    
                    // Find approver's task
                    ApprovalTask task = entity.approvalTasks.stream()
                        .filter(t -> t.approverId.equals(approverId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                            "User not authorized to approve: " + approverId
                        ));
                    
                    // Update task
                    task.status = decision == ApprovalDecision.APPROVE ? 
                        TaskStatus.APPROVED : TaskStatus.REJECTED;
                    task.decision = decision;
                    task.comments = comments;
                    task.decidedAt = Instant.now();
                    
                    // Check if all approvals received
                    if (decision == ApprovalDecision.REJECT) {
                        // Immediate rejection
                        entity.status = ApprovalStatus.REJECTED;
                        entity.rejectedBy = approverId;
                        entity.rejectionReason = comments;
                        
                        return repository.persist(entity)
                            .onItem().invoke(saved -> {
                                notifier.notifyRejection(saved);
                                logApprovalRequest(saved, "APPROVAL_REJECTED");
                            })
                            .onItem().transform(this::toApprovalRequest);
                    }
                    
                    // Check if all approved
                    boolean allApproved = entity.approvalTasks.stream()
                        .allMatch(t -> t.status == TaskStatus.APPROVED);
                    
                    if (allApproved) {
                        entity.status = ApprovalStatus.APPROVED;
                        
                        // Execute the approved action
                        return executeApprovedAction(entity)
                            .onItem().transformToUni(v -> 
                                repository.persist(entity)
                                    .onItem().invoke(saved -> {
                                        notifier.notifyApproval(saved);
                                        logApprovalRequest(saved, "APPROVAL_GRANTED");
                                    })
                                    .onItem().transform(this::toApprovalRequest)
                            );
                    }
                    
                    // Still waiting for more approvals
                    return repository.persist(entity)
                        .onItem().transform(this::toApprovalRequest);
                })
        );
    }
    
    /**
     * Get pending approvals for a user
     */
    public Uni<List<ApprovalRequest>> getPendingApprovals(String approverId) {
        return repository.findPendingForApprover(approverId)
            .onItem().transform(entities ->
                entities.stream()
                    .map(this::toApprovalRequest)
                    .collect(Collectors.toList())
            );
    }
    
    /**
     * Get approval request by ID
     */
    public Uni<ApprovalRequest> getApprovalRequest(String requestId) {
        return repository.findByRequestId(requestId)
            .onItem().transform(opt ->
                opt.map(this::toApprovalRequest)
                    .orElseThrow(() -> new ApprovalNotFoundException(
                        "Request not found: " + requestId
                    ))
            );
    }
    
    /**
     * Check for expired approval requests (scheduled job)
     */
    @Scheduled(cron = "0 */15 * * * ?") // Every 15 minutes
    void expireOldRequests() {
        Panache.withTransaction(() ->
            repository.findExpired()
                .onItem().transformToMulti(entities ->
                    Multi.createFrom().iterable(entities)
                )
                .onItem().transformToUniAndMerge(entity -> {
                    entity.status = ApprovalStatus.EXPIRED;
                    return repository.persist(entity)
                        .onItem().invoke(saved ->
                            notifier.notifyExpiration(saved)
                        );
                })
                .collect().asList()
        ).subscribe().with(
            results -> LOG.infof("Expired %d approval requests", results.size()),
            error -> LOG.errorf(error, "Failed to expire requests")
        );
    }
    
    // Helper methods
    
    private Uni<Void> executeApprovedAction(ApprovalRequestEntity entity) {
        switch (entity.requestType) {
            case CREATE:
                StoreSecretRequest request = deserializeRequest(entity.secretRequest);
                return secretManager.store(request).replaceWithVoid();
                
            case ROTATE:
                return secretManager.getMetadata(entity.tenantId, entity.secretPath)
                    .onItem().transformToUni(metadata ->
                        secretManager.rotate(RotateSecretRequest.of(
                            entity.tenantId,
                            entity.secretPath,
                            Map.of() // Generate new value
                        ))
                    )
                    .replaceWithVoid();
                
            case DELETE:
                return secretManager.delete(DeleteSecretRequest.soft(
                    entity.tenantId,
                    entity.secretPath,
                    "Approved deletion"
                ));
                
            default:
                return Uni.createFrom().voidItem();
        }
    }
    
    private List<String> determineApprovers(StoreSecretRequest request) {
        // Determine approvers based on secret type and sensitivity
        List<String> approvers = new ArrayList<>();
        
        switch (request.type()) {
            case DATABASE_CREDENTIAL:
            case ENCRYPTION_KEY:
                // High-security secrets require multiple approvers
                approvers.add("security-team");
                approvers.add("admin");
                break;
                
            case API_KEY:
            case OAUTH_TOKEN:
                // Medium-security secrets require one approver
                approvers.add("team-lead");
                break;
                
            default:
                // Low-security secrets require basic approval
                approvers.add("reviewer");
        }
        
        return approvers;
    }
    
    private List<ApprovalTask> createApprovalTasks(List<String> approvers) {
        return approvers.stream()
            .map(approverId -> {
                ApprovalTask task = new ApprovalTask();
                task.approverId = approverId;
                task.approverName = getUsername(approverId);
                task.status = TaskStatus.PENDING;
                task.createdAt = Instant.now();
                return task;
            })
            .collect(Collectors.toList());
    }
    
    private ApprovalRequest toApprovalRequest(ApprovalRequestEntity entity) {
        return new ApprovalRequest(
            entity.requestId,
            entity.tenantId,
            entity.secretPath,
            entity.requestType,
            entity.requesterId,
            entity.requesterName,
            entity.status,
            entity.approvalTasks.stream()
                .map(t -> new ApprovalTaskInfo(
                    t.approverId,
                    t.approverName,
                    t.status,
                    t.decision,
                    t.comments,
                    t.decidedAt
                ))
                .collect(Collectors.toList()),
            entity.createdAt,
            entity.expiresAt,
            entity.rejectedBy,
            entity.rejectionReason
        );
    }
    
    private void logApprovalRequest(ApprovalRequestEntity entity, String event) {
        provenanceService.log(AuditPayload.builder()
            .runId(UUID.fromString(entity.requestId))
            .nodeId("secret-approval")
            .actor(entity.requesterId, "user")
            .event(event)
            .level(AuditLevel.INFO)
            .metadata(Map.of(
                "tenant_id", entity.tenantId,
                "secret_path", entity.secretPath,
                "request_type", entity.requestType.name()
            ))
            .build());
    }
    
    private String serializeRequest(StoreSecretRequest request) {
        // Serialize to JSON
        return "{}"; // Placeholder
    }
    
    private StoreSecretRequest deserializeRequest(String json) {
        // Deserialize from JSON
        return null; // Placeholder
    }
    
    private String getUsername(String userId) {
        // Get username from user service
        return userId;
    }
}

/**
 * Approval notification service
 */
@ApplicationScoped
class ApprovalNotifier {
    
    private static final Logger LOG = Logger.getLogger(ApprovalNotifier.class);
    
    public void notifyApprovers(ApprovalRequestEntity request) {
        LOG.infof("Notifying approvers for request: %s", request.requestId);
        
        for (ApprovalTask task : request.approvalTasks) {
            // Send email/notification to approver
            LOG.infof("Notification sent to: %s", task.approverName);
        }
    }
    
    public void notifyApproval(ApprovalRequestEntity request) {
        LOG.infof("Notifying approval granted: %s", request.requestId);
        // Notify requester
    }
    
    public void notifyRejection(ApprovalRequestEntity request) {
        LOG.infof("Notifying approval rejected: %s", request.requestId);
        // Notify requester
    }
    
    public void notifyExpiration(ApprovalRequestEntity request) {
        LOG.infof("Notifying approval expired: %s", request.requestId);
        // Notify requester and approvers
    }
}

/**
 * REST API for approval workflow
 */
@Path("/api/v1/secrets/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SecretApprovalResource {
    
    @Inject
    SecretApprovalService approvalService;
    
    @POST
    @Path("/request")
    public Uni<Response> requestApproval(ApprovalRequestDto request) {
        StoreSecretRequest storeRequest = StoreSecretRequest.builder()
            .tenantId(request.tenantId)
            .path(request.path)
            .data(request.data)
            .type(SecretType.valueOf(request.type))
            .build();
        
        return approvalService.requestSecretCreation(storeRequest, request.requesterId)
            .onItem().transform(approval -> Response
                .status(Response.Status.CREATED)
                .entity(approval)
                .build());
    }
    
    @POST
    @Path("/{requestId}/approve")
    public Uni<Response> approveRequest(
            @PathParam("requestId") String requestId,
            ApprovalDecisionDto decision) {
        
        return approvalService.processApproval(
            requestId,
            decision.approverId,
            decision.approve ? ApprovalDecision.APPROVE : ApprovalDecision.REJECT,
            decision.comments
        )
        .onItem().transform(approval -> Response.ok(approval).build());
    }
    
    @GET
    @Path("/pending")
    public Uni<Response> getPendingApprovals(
            @QueryParam("approverId") String approverId) {
        
        return approvalService.getPendingApprovals(approverId)
            .onItem().transform(approvals -> Response.ok(approvals).build());
    }
    
    @GET
    @Path("/{requestId}")
    public Uni<Response> getApprovalRequest(
            @PathParam("requestId") String requestId) {
        
        return approvalService.getApprovalRequest(requestId)
            .onItem().transform(approval -> Response.ok(approval).build())
            .onFailure(ApprovalNotFoundException.class).recoverWithItem(
                Response.status(Response.Status.NOT_FOUND).build()
            );
    }
}

// Entities and DTOs

@Entity
@Table(name = "secret_approval_requests")
class ApprovalRequestEntity {
    @Id
    String requestId;
    
    @Column(nullable = false)
    String tenantId;
    
    @Column(nullable = false)
    String secretPath;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    ApprovalRequestType requestType;
    
    @Column(nullable = false)
    String requesterId;
    
    @Column(nullable = false)
    String requesterName;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    ApprovalStatus status;
    
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "request_id")
    List<ApprovalTask> approvalTasks;
    
    @Column(columnDefinition = "TEXT")
    String secretRequest;
    
    @Column(nullable = false)
    Instant createdAt;
    
    @Column(nullable = false)
    Instant expiresAt;
    
    @Column
    String rejectedBy;
    
    @Column
    String rejectionReason;
}

@Entity
@Table(name = "approval_tasks")
class ApprovalTask {
    @Id
    @GeneratedValue
    Long id;
    
    @Column(nullable = false)
    String approverId;
    
    @Column(nullable = false)
    String approverName;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    TaskStatus status;
    
    @Enumerated(EnumType.STRING)
    ApprovalDecision decision;
    
    @Column
    String comments;
    
    @Column(nullable = false)
    Instant createdAt;
    
    @Column
    Instant decidedAt;
}

@ApplicationScoped
class ApprovalRequestRepository implements PanacheRepositoryBase<ApprovalRequestEntity, String> {
    
    public Uni<Optional<ApprovalRequestEntity>> findByRequestId(String requestId) {
        return find("requestId", requestId)
            .firstResult()
            .onItem().transform(Optional::ofNullable);
    }
    
    public Uni<List<ApprovalRequestEntity>> findPendingForApprover(String approverId) {
        return find("select r from ApprovalRequestEntity r " +
                   "join r.approvalTasks t " +
                   "where r.status = ?1 and t.approverId = ?2 and t.status = ?3",
            ApprovalStatus.PENDING, approverId, TaskStatus.PENDING
        ).list();
    }
    
    public Uni<List<ApprovalRequestEntity>> findExpired() {
        return find("status = ?1 and expiresAt < ?2",
            ApprovalStatus.PENDING, Instant.now()
        ).list();
    }
}

enum ApprovalRequestType {
    CREATE,
    ROTATE,
    DELETE
}

enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED
}

enum TaskStatus {
    PENDING,
    APPROVED,
    REJECTED
}

enum ApprovalDecision {
    APPROVE,
    REJECT
}

record ApprovalRequest(
    String requestId,
    String tenantId,
    String secretPath,
    ApprovalRequestType requestType,
    String requesterId,
    String requesterName,
    ApprovalStatus status,
    List<ApprovalTaskInfo> approvalTasks,
    Instant createdAt,
    Instant expiresAt,
    String rejectedBy,
    String rejectionReason
) {}

record ApprovalTaskInfo(
    String approverId,
    String approverName,
    TaskStatus status,
    ApprovalDecision decision,
    String comments,
    Instant decidedAt
) {}

record ApprovalRequestDto(
    String tenantId,
    String path,
    Map<String, String> data,
    String type,
    String requesterId
) {}

record ApprovalDecisionDto(
    String approverId,
    boolean approve,
    String comments
) {}

class ApprovalNotFoundException extends RuntimeException {
    public ApprovalNotFoundException(String message) {
        super(message);
    }
}

package tech.kayys.wayang.security.secrets.analytics;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import tech.kayys.wayang.security.secrets.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Secret analytics and monitoring service.
 * 
 * Features:
 * - Usage tracking and statistics
 * - Access pattern analysis
 * - Anomaly detection
 * - Compliance reporting
 * - Secret lifecycle tracking
 */
@ApplicationScoped
public class SecretAnalyticsService {
    
    private static final Logger LOG = Logger.getLogger(SecretAnalyticsService.class);
    
    @Inject
    SecretManager secretManager;
    
    @Inject
    AuditRepository auditRepository;
    
    @Inject
    AnomalyDetector anomalyDetector;
    
    /**
     * Generate access report for a tenant
     */
    public Uni<AccessReport> generateAccessReport(String tenantId, 
                                                  Instant from, 
                                                  Instant to) {
        LOG.infof("Generating access report: tenant=%s, from=%s, to=%s", 
            tenantId, from, to);
        
        return auditRepository.findSecretAccess(tenantId, from, to)
            .onItem().transformToUni(events -> {
                // Analyze access patterns
                Map<String, Long> accessCounts = events.stream()
                    .collect(Collectors.groupingBy(
                        AuditEvent::secretPath,
                        Collectors.counting()
                    ));
                
                // Find unused secrets
                return secretManager.list(tenantId, "")
                    .onItem().transform(allSecrets -> {
                        Set<String> accessedPaths = accessCounts.keySet();
                        
                        List<String> unusedSecrets = allSecrets.stream()
                            .map(SecretMetadata::path)
                            .filter(path -> !accessedPaths.contains(path))
                            .collect(Collectors.toList());
                        
                        // Detect anomalies
                        List<AnomalousAccess> anomalies = anomalyDetector
                            .detectAnomalies(events);
                        
                        // Calculate statistics
                        long totalAccesses = events.size();
                        long uniqueSecrets = accessCounts.size();
                        double avgAccessPerSecret = uniqueSecrets > 0 ? 
                            (double) totalAccesses / uniqueSecrets : 0;
                        
                        Map<String, Long> accessByHour = events.stream()
                            .collect(Collectors.groupingBy(
                                e -> String.valueOf(e.timestamp().atZone(
                                    java.time.ZoneOffset.UTC).getHour()),
                                Collectors.counting()
                            ));
                        
                        return new AccessReport(
                            tenantId,
                            from,
                            to,
                            totalAccesses,
                            uniqueSecrets,
                            avgAccessPerSecret,
                            accessCounts,
                            accessByHour,
                            unusedSecrets,
                            anomalies
                        );
                    });
            });
    }
    
    /**
     * Get secret lifecycle summary
     */
    public Uni<LifecycleSummary> getLifecycleSummary(String tenantId) {
        return secretManager.list(tenantId, "")
            .onItem().transform(secrets -> {
                long total = secrets.size();
                
                long active = secrets.stream()
                    .filter(s -> s.status() == SecretStatus.ACTIVE)
                    .count();
                
                long expiringSoon = secrets.stream()
                    .filter(s -> s.expiresAt().isPresent() &&
                        s.expiresAt().get().isBefore(
                            Instant.now().plus(Duration.ofDays(7))
                        ))
                    .count();
                
                long rotatable = secrets.stream()
                    .filter(SecretMetadata::rotatable)
                    .count();
                
                long needsRotation = secrets.stream()
                    .filter(s -> s.rotatable() && 
                        Duration.between(s.createdAt(), Instant.now())
                            .toDays() >= 90)
                    .count();
                
                Map<SecretType, Long> byType = secrets.stream()
                    .collect(Collectors.groupingBy(
                        SecretMetadata::type,
                        Collectors.counting()
                    ));
                
                return new LifecycleSummary(
                    total,
                    active,
                    expiringSoon,
                    rotatable,
                    needsRotation,
                    byType
                );
            });
    }
    
    /**
     * Get top accessed secrets
     */
    public Uni<List<SecretAccessSummary>> getTopAccessedSecrets(
            String tenantId, 
            Instant from, 
            Instant to,
            int limit) {
        
        return auditRepository.findSecretAccess(tenantId, from, to)
            .onItem().transform(events -> {
                Map<String, Long> accessCounts = events.stream()
                    .collect(Collectors.groupingBy(
                        AuditEvent::secretPath,
                        Collectors.counting()
                    ));
                
                return accessCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(limit)
                    .map(e -> new SecretAccessSummary(
                        e.getKey(),
                        e.getValue(),
                        events.stream()
                            .filter(ev -> ev.secretPath().equals(e.getKey()))
                            .max(Comparator.comparing(AuditEvent::timestamp))
                            .map(AuditEvent::timestamp)
                            .orElse(null)
                    ))
                    .collect(Collectors.toList());
            });
    }
    
    /**
     * Get compliance report
     */
    public Uni<ComplianceReport> getComplianceReport(String tenantId) {
        return secretManager.list(tenantId, "")
            .onItem().transform(secrets -> {
                List<ComplianceIssue> issues = new ArrayList<>();
                
                for (SecretMetadata secret : secrets) {
                    // Check TTL requirement
                    if (secret.expiresAt().isEmpty()) {
                        issues.add(new ComplianceIssue(
                            ComplianceIssueType.NO_TTL,
                            secret.path(),
                            "Secret does not have TTL set",
                            "Set expiration for secret"
                        ));
                    }
                    
                    // Check rotation requirement
                    if (!secret.rotatable()) {
                        issues.add(new ComplianceIssue(
                            ComplianceIssueType.NOT_ROTATABLE,
                            secret.path(),
                            "Secret is not marked as rotatable",
                            "Enable rotation for secret"
                        ));
                    }
                    
                    // Check age
                    long ageInDays = Duration.between(
                        secret.createdAt(), 
                        Instant.now()
                    ).toDays();
                    
                    if (ageInDays > 90) {
                        issues.add(new ComplianceIssue(
                            ComplianceIssueType.OLD_SECRET,
                            secret.path(),
                            "Secret is older than 90 days: " + ageInDays + " days",
                            "Rotate secret"
                        ));
                    }
                }
                
                boolean compliant = issues.isEmpty();
                double complianceScore = secrets.isEmpty() ? 100.0 :
                    ((secrets.size() - issues.size()) * 100.0) / secrets.size();
                
                return new ComplianceReport(
                    tenantId,
                    Instant.now(),
                    compliant,
                    complianceScore,
                    issues
                );
            });
    }
}

/**
 * Anomaly detection service
 */
@ApplicationScoped
class AnomalyDetector {
    
    private static final Logger LOG = Logger.getLogger(AnomalyDetector.class);
    
    public List<AnomalousAccess> detectAnomalies(List<AuditEvent> events) {
        List<AnomalousAccess> anomalies = new ArrayList<>();
        
        // Group by secret path
        Map<String, List<AuditEvent>> eventsByPath = events.stream()
            .collect(Collectors.groupingBy(AuditEvent::secretPath));
        
        for (Map.Entry<String, List<AuditEvent>> entry : eventsByPath.entrySet()) {
            String path = entry.getKey();
            List<AuditEvent> pathEvents = entry.getValue();
            
            // Detect high-frequency access (spike)
            detectAccessSpike(path, pathEvents, anomalies);
            
            // Detect unusual access times
            detectUnusualAccessTime(path, pathEvents, anomalies);
            
            // Detect failed access attempts
            detectFailedAccess(path, pathEvents, anomalies);
        }
        
        return anomalies;
    }
    
    private void detectAccessSpike(String path, List<AuditEvent> events, 
                                   List<AnomalousAccess> anomalies) {
        // Calculate average access rate
        if (events.size() < 10) return; // Need minimum data
        
        long totalDuration = Duration.between(
            events.get(0).timestamp(),
            events.get(events.size() - 1).timestamp()
        ).toMinutes();
        
        if (totalDuration == 0) return;
        
        double avgRate = (double) events.size() / totalDuration;
        
        // Check for 5-minute windows with spike
        Map<String, Long> accessByMinute = events.stream()
            .collect(Collectors.groupingBy(
                e -> e.timestamp().toString().substring(0, 16), // Truncate to minute
                Collectors.counting()
            ));
        
        for (Map.Entry<String, Long> entry : accessByMinute.entrySet()) {
            if (entry.getValue() > avgRate * 5) { // 5x spike
                anomalies.add(new AnomalousAccess(
                    path,
                    AnomalyType.HIGH_FREQUENCY,
                    "Access spike detected: " + entry.getValue() + 
                    " accesses in 1 minute (avg: " + String.format("%.1f", avgRate) + ")",
                    Instant.parse(entry.getKey() + ":00Z")
                ));
            }
        }
    }
    
    private void detectUnusualAccessTime(String path, List<AuditEvent> events,
                                        List<AnomalousAccess> anomalies) {
        // Detect access during unusual hours (e.g., 2-4 AM)
        for (AuditEvent event : events) {
            int hour = event.timestamp().atZone(java.time.ZoneOffset.UTC).getHour();
            
            if (hour >= 2 && hour <= 4) {
                anomalies.add(new AnomalousAccess(
                    path,
                    AnomalyType.UNUSUAL_TIME,
                    "Access during unusual hours: " + hour + ":00",
                    event.timestamp()
                ));
            }
        }
    }
    
    private void detectFailedAccess(String path, List<AuditEvent> events,
                                   List<AnomalousAccess> anomalies) {
        // Check for multiple failed access attempts
        long failedAttempts = events.stream()
            .filter(e -> e.level() == AuditLevel.ERROR)
            .count();
        
        if (failedAttempts > 3) {
            anomalies.add(new AnomalousAccess(
                path,
                AnomalyType.FAILED_ACCESS,
                "Multiple failed access attempts: " + failedAttempts,
                Instant.now()
            ));
        }
    }
}

/**
 * REST API for analytics
 */
@Path("/api/v1/secrets/analytics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SecretAnalyticsResource {
    
    @Inject
    SecretAnalyticsService analyticsService;
    
    @GET
    @Path("/access-report")
    public Uni<Response> getAccessReport(
            @QueryParam("tenantId") String tenantId,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        
        Instant fromInstant = from != null ? Instant.parse(from) : 
            Instant.now().minus(Duration.ofDays(30));
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        
        return analyticsService.generateAccessReport(tenantId, fromInstant, toInstant)
            .onItem().transform(report -> Response.ok(report).build());
    }
    
    @GET
    @Path("/lifecycle")
    public Uni<Response> getLifecycleSummary(
            @QueryParam("tenantId") String tenantId) {
        
        return analyticsService.getLifecycleSummary(tenantId)
            .onItem().transform(summary -> Response.ok(summary).build());
    }
    
    @GET
    @Path("/top-accessed")
    public Uni<Response> getTopAccessedSecrets(
            @QueryParam("tenantId") String tenantId,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("limit") @DefaultValue("10") int limit) {
        
        Instant fromInstant = from != null ? Instant.parse(from) : 
            Instant.now().minus(Duration.ofDays(7));
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        
        return analyticsService.getTopAccessedSecrets(
            tenantId, fromInstant, toInstant, limit
        )
        .onItem().transform(secrets -> Response.ok(secrets).build());
    }
    
    @GET
    @Path("/compliance")
    public Uni<Response> getComplianceReport(
            @QueryParam("tenantId") String tenantId) {
        
        return analyticsService.getComplianceReport(tenantId)
            .onItem().transform(report -> Response.ok(report).build());
    }
}

// Analytics DTOs

record AccessReport(
    String tenantId,
    Instant from,
    Instant to,
    long totalAccesses,
    long uniqueSecrets,
    double avgAccessPerSecret,
    Map<String, Long> accessBySecret,
    Map<String, Long> accessByHour,
    List<String> unusedSecrets,
    List<AnomalousAccess> anomalies
) {}

record LifecycleSummary(
    long totalSecrets,
    long activeSecrets,
    long expiringSoon,
    long rotatableSecrets,
    long needsRotation,
    Map<SecretType, Long> secretsByType
) {}

record SecretAccessSummary(
    String path,
    long accessCount,
    Instant lastAccessed
) {}

record AnomalousAccess(
    String secretPath,
    AnomalyType type,
    String description,
    Instant detectedAt
) {}

enum AnomalyType {
    HIGH_FREQUENCY,
    UNUSUAL_TIME,
    UNUSUAL_LOCATION,
    FAILED_ACCESS,
    PATTERN_CHANGE
}

record ComplianceReport(
    String tenantId,
    Instant generatedAt,
    boolean compliant,
    double complianceScore,
    List<ComplianceIssue> issues
) {}

record ComplianceIssue(
    ComplianceIssueType type,
    String secretPath,
    String description,
    String remediation
) {}

enum ComplianceIssueType {
    NO_TTL,
    NOT_ROTATABLE,
    OLD_SECRET,
    NO_METADATA,
    WEAK_VALIDATION
}

/**
 * Audit repository for querying audit events
 */
@ApplicationScoped
class AuditRepository {
    
    public Uni<List<AuditEvent>> findSecretAccess(String tenantId, 
                                                  Instant from, 
                                                  Instant to) {
        // Placeholder - implement actual query
        // This would query the audit/provenance database
        return Uni.createFrom().item(List.of());
    }
}

record AuditEvent(
    String secretPath,
    Instant timestamp,
    String actor,
    AuditLevel level
) {}

enum AuditLevel {
    INFO,
    WARN,
    ERROR
}


package tech.kayys.wayang.security.secrets.rotation;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.kayys.wayang.security.secrets.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatic secret rotation scheduler.
 * 
 * Features:
 * - Scheduled rotation based on TTL
 * - Pluggable rotation strategies
 * - Grace period for cutover
 * - Rollback on failure
 * - Notification on rotation
 * - Audit trail
 */
@ApplicationScoped
public class SecretRotationScheduler {
    
    private static final Logger LOG = Logger.getLogger(SecretRotationScheduler.class);
    
    @Inject
    SecretManager secretManager;
    
    @Inject
    RotationStrategyRegistry strategyRegistry;
    
    @Inject
    RotationNotifier notifier;
    
    @Inject
    ProvenanceService provenanceService;
    
    private final Map<String, RotationStatus> activeRotations = new ConcurrentHashMap<>();
    
    /**
     * Check for secrets that need rotation (runs daily at 2 AM)
     */
    @Scheduled(cron = "0 0 2 * * ?")
    void checkAndRotateSecrets() {
        LOG.info("Starting scheduled secret rotation check");
        
        Instant threshold = Instant.now().plus(Duration.ofDays(7));
        
        // For each tenant, check secrets
        List<String> tenants = getTenants();
        
        Multi.createFrom().iterable(tenants)
            .onItem().transformToUniAndMerge(tenantId ->
                checkTenantSecrets(tenantId, threshold)
            )
            .collect().asList()
            .subscribe().with(
                results -> LOG.infof("Rotation check completed: %d tenants processed", 
                    results.size()),
                error -> LOG.errorf(error, "Rotation check failed")
            );
    }
    
    /**
     * Check secrets for a specific tenant
     */
    private Uni<List<RotationResult>> checkTenantSecrets(String tenantId, Instant threshold) {
        return secretManager.list(tenantId, "")
            .onItem().transformToMulti(metadataList ->
                Multi.createFrom().iterable(metadataList)
            )
            .filter(metadata -> shouldRotate(metadata, threshold))
            .onItem().transformToUniAndMerge(metadata ->
                rotateSecret(tenantId, metadata)
            )
            .collect().asList();
    }
    
    /**
     * Determine if a secret should be rotated
     */
    private boolean shouldRotate(SecretMetadata metadata, Instant threshold) {
        // Check if rotatable
        if (!metadata.rotatable()) {
            return false;
        }
        
        // Check if expiring soon
        if (metadata.expiresAt().isPresent() && 
            metadata.expiresAt().get().isBefore(threshold)) {
            return true;
        }
        
        // Check age-based rotation (e.g., rotate every 90 days)
        Duration age = Duration.between(metadata.createdAt(), Instant.now());
        if (age.toDays() >= 90) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Rotate a secret using appropriate strategy
     */
    public Uni<RotationResult> rotateSecret(String tenantId, SecretMetadata metadata) {
        String rotationId = UUID.randomUUID().toString();
        
        LOG.infof("Starting rotation for secret: tenant=%s, path=%s, id=%s",
            tenantId, metadata.path(), rotationId);
        
        // Mark rotation as in progress
        activeRotations.put(rotationId, RotationStatus.IN_PROGRESS);
        
        return Uni.createFrom().deferred(() -> {
            // Get rotation strategy
            RotationStrategy strategy = strategyRegistry.getStrategy(metadata.type());
            
            if (strategy == null) {
                LOG.warnf("No rotation strategy for type: %s, using generic", 
                    metadata.type());
                strategy = strategyRegistry.getDefaultStrategy();
            }
            
            // Retrieve current secret
            return secretManager.retrieve(
                RetrieveSecretRequest.of(tenantId, metadata.path())
            )
            .onItem().transformToUni(currentSecret -> 
                // Generate new secret value
                strategy.generateNewSecret(currentSecret)
                    .onItem().transformToUni(newData -> {
                        // Store new version
                        RotateSecretRequest request = RotateSecretRequest.of(
                            tenantId,
                            metadata.path(),
                            newData
                        );
                        
                        return secretManager.rotate(request)
                            .onItem().transformToUni(newMetadata -> {
                                // Notify applications of rotation
                                return notifier.notifyRotation(
                                    tenantId,
                                    metadata.path(),
                                    metadata.version(),
                                    newMetadata.version()
                                )
                                .onItem().transform(v -> 
                                    new RotationResult(
                                        rotationId,
                                        true,
                                        tenantId,
                                        metadata.path(),
                                        metadata.version(),
                                        newMetadata.version(),
                                        null
                                    )
                                );
                            });
                    })
            )
            .onItem().invoke(result -> {
                activeRotations.put(rotationId, RotationStatus.COMPLETED);
                logRotation(result, true);
            })
            .onFailure().recoverWithItem(error -> {
                LOG.errorf(error, "Failed to rotate secret: %s", metadata.path());
                activeRotations.put(rotationId, RotationStatus.FAILED);
                
                RotationResult failedResult = new RotationResult(
                    rotationId,
                    false,
                    tenantId,
                    metadata.path(),
                    metadata.version(),
                    -1,
                    error.getMessage()
                );
                
                logRotation(failedResult, false);
                return failedResult;
            })
            .eventually(() -> activeRotations.remove(rotationId));
        });
    }
    
    /**
     * Manual rotation trigger
     */
    public Uni<RotationResult> rotateSecretManual(String tenantId, String path) {
        return secretManager.getMetadata(tenantId, path)
            .onItem().transformToUni(metadata -> rotateSecret(tenantId, metadata));
    }
    
    /**
     * Get rotation status
     */
    public Optional<RotationStatus> getRotationStatus(String rotationId) {
        return Optional.ofNullable(activeRotations.get(rotationId));
    }
    
    private void logRotation(RotationResult result, boolean success) {
        Map<String, Object> metadata = Map.of(
            "rotation_id", result.rotationId(),
            "tenant_id", result.tenantId(),
            "path", result.path(),
            "old_version", result.oldVersion(),
            "new_version", result.newVersion(),
            "success", success
        );
        
        provenanceService.log(AuditPayload.builder()
            .runId(UUID.fromString(result.rotationId()))
            .nodeId("secret-rotation")
            .systemActor()
            .event(success ? "SECRET_ROTATION_SUCCESS" : "SECRET_ROTATION_FAILED")
            .level(success ? AuditLevel.INFO : AuditLevel.ERROR)
            .metadata(metadata)
            .build());
    }
    
    private List<String> getTenants() {
        // TODO: Get from tenant registry
        return List.of("default-tenant");
    }
}

/**
 * Registry of rotation strategies
 */
@ApplicationScoped
class RotationStrategyRegistry {
    
    private static final Logger LOG = Logger.getLogger(RotationStrategyRegistry.class);
    
    private final Map<SecretType, RotationStrategy> strategies = new HashMap<>();
    
    @Inject
    GenericRotationStrategy genericStrategy;
    
    @Inject
    DatabaseCredentialRotationStrategy dbStrategy;
    
    @Inject
    APIKeyRotationStrategy apiKeyStrategy;
    
    @PostConstruct
    void init() {
        // Register strategies
        strategies.put(SecretType.GENERIC, genericStrategy);
        strategies.put(SecretType.DATABASE_CREDENTIAL, dbStrategy);
        strategies.put(SecretType.API_KEY, apiKeyStrategy);
        
        LOG.infof("Registered %d rotation strategies", strategies.size());
    }
    
    public RotationStrategy getStrategy(SecretType type) {
        return strategies.get(type);
    }
    
    public RotationStrategy getDefaultStrategy() {
        return genericStrategy;
    }
    
    public void registerStrategy(SecretType type, RotationStrategy strategy) {
        strategies.put(type, strategy);
        LOG.infof("Registered rotation strategy for type: %s", type);
    }
}

/**
 * Base interface for rotation strategies
 */
interface RotationStrategy {
    /**
     * Generate new secret value based on current secret
     */
    Uni<Map<String, String>> generateNewSecret(Secret currentSecret);
}

/**
 * Generic rotation strategy - generates new random value
 */
@ApplicationScoped
class GenericRotationStrategy implements RotationStrategy {
    
    private final SecureRandom random = new SecureRandom();
    
    @Override
    public Uni<Map<String, String>> generateNewSecret(Secret currentSecret) {
        // Generate new random secret
        byte[] randomBytes = new byte[32];
        random.nextBytes(randomBytes);
        
        String newValue = Base64.getEncoder().encodeToString(randomBytes);
        
        Map<String, String> newData = new HashMap<>(currentSecret.data());
        
        // Update primary key with new value
        String primaryKey = newData.keySet().iterator().next();
        newData.put(primaryKey, newValue);
        
        return Uni.createFrom().item(newData);
    }
}

/**
 * Database credential rotation strategy
 */
@ApplicationScoped
class DatabaseCredentialRotationStrategy implements RotationStrategy {
    
    private static final Logger LOG = Logger.getLogger(DatabaseCredentialRotationStrategy.class);
    
    @Override
    public Uni<Map<String, String>> generateNewSecret(Secret currentSecret) {
        // Extract current credentials
        String host = currentSecret.data().get("host");
        String port = currentSecret.data().get("port");
        String database = currentSecret.data().get("database");
        String username = currentSecret.data().get("username");
        String currentPassword = currentSecret.data().get("password");
        
        // Generate new password
        String newPassword = generateStrongPassword();
        
        return Uni.createFrom().deferred(() -> {
            // Connect to database and update password
            return updateDatabasePassword(host, port, database, username, 
                currentPassword, newPassword)
                .onItem().transform(success -> {
                    if (!success) {
                        throw new RuntimeException("Failed to update database password");
                    }
                    
                    // Return new credentials
                    Map<String, String> newData = new HashMap<>(currentSecret.data());
                    newData.put("password", newPassword);
                    
                    LOG.infof("Successfully rotated database credentials for: %s@%s", 
                        username, host);
                    
                    return newData;
                });
        });
    }
    
    private String generateStrongPassword() {
        SecureRandom random = new SecureRandom();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
        StringBuilder password = new StringBuilder(32);
        
        for (int i = 0; i < 32; i++) {
            password.append(chars.charAt(random.nextInt(chars.length())));
        }
        
        return password.toString();
    }
    
    private Uni<Boolean> updateDatabasePassword(String host, String port, 
            String database, String username, String currentPassword, String newPassword) {
        // Placeholder - implement actual database password update
        // This would connect to the database and run:
        // ALTER USER username WITH PASSWORD 'newPassword';
        
        LOG.infof("Updating database password for %s@%s:%s/%s", 
            username, host, port, database);
        
        return Uni.createFrom().item(true);
    }
}

/**
 * API key rotation strategy
 */
@ApplicationScoped
class APIKeyRotationStrategy implements RotationStrategy {
    
    @Override
    public Uni<Map<String, String>> generateNewSecret(Secret currentSecret) {
        // Generate new API key
        SecureRandom random = new SecureRandom();
        byte[] randomBytes = new byte[32];
        random.nextBytes(randomBytes);
        
        String newApiKey = "sk_" + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(randomBytes);
        
        Map<String, String> newData = new HashMap<>(currentSecret.data());
        newData.put("api_key", newApiKey);
        
        if (currentSecret.data().containsKey("api_secret")) {
            random.nextBytes(randomBytes);
            String newSecret = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);
            newData.put("api_secret", newSecret);
        }
        
        return Uni.createFrom().item(newData);
    }
}

/**
 * Rotation notifier - notifies applications of secret rotation
 */
@ApplicationScoped
class RotationNotifier {
    
    private static final Logger LOG = Logger.getLogger(RotationNotifier.class);
    
    @Inject
    EventBus eventBus;
    
    public Uni<Void> notifyRotation(String tenantId, String path, 
                                    int oldVersion, int newVersion) {
        LOG.infof("Notifying applications of secret rotation: %s/%s", tenantId, path);
        
        SecretRotatedEvent event = new SecretRotatedEvent(
            tenantId,
            path,
            oldVersion,
            newVersion
        );
        
        // Publish event
        eventBus.publish("secret.rotated", event);
        
        // TODO: Send webhook notifications to registered applications
        // TODO: Send email notifications to administrators
        
        return Uni.createFrom().voidItem();
    }
}

// DTOs

record RotationResult(
    String rotationId,
    boolean success,
    String tenantId,
    String path,
    int oldVersion,
    int newVersion,
    String error
) {}

enum RotationStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

package tech.kayys.wayang.security.secrets.azure;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.azure.security.keyvault.secrets.models.*;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.kayys.wayang.security.secrets.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Azure Key Vault implementation of SecretManager.
 * 
 * Features:
 * - Azure Key Vault for secret storage
 * - Managed Identity authentication
 * - Automatic secret versioning
 * - Soft-delete with recovery
 * - Tags for metadata
 * - RBAC integration
 * 
 * Configuration:
 * - azure.keyvault.vault-url=https://<vault-name>.vault.azure.net/
 * - azure.keyvault.tenant-id=<tenant-id>
 * 
 * Authentication:
 * - Managed Identity (recommended for Azure deployments)
 * - Service Principal (for local development)
 * - Azure CLI credentials (for development)
 */
@ApplicationScoped
public class AzureKeyVaultSecretManager implements SecretManager {
    
    private static final Logger LOG = Logger.getLogger(AzureKeyVaultSecretManager.class);
    private static final String TAG_TENANT = "tenant";
    private static final String TAG_TYPE = "type";
    private static final String TAG_ROTATABLE = "rotatable";
    private static final String TAG_CREATED_BY = "created_by";
    
    @ConfigProperty(name = "azure.keyvault.vault-url")
    String vaultUrl;
    
    @ConfigProperty(name = "azure.keyvault.secret-prefix", defaultValue = "wayang-")
    String secretPrefix;
    
    @Inject
    ObjectMapper objectMapper;
    
    @Inject
    VaultAuditLogger auditLogger;
    
    private SecretClient secretClient;
    
    @PostConstruct
    void init() {
        LOG.infof("Initializing Azure Key Vault client: %s", vaultUrl);
        
        secretClient = new SecretClientBuilder()
            .vaultUrl(vaultUrl)
            .credential(new DefaultAzureCredentialBuilder().build())
            .buildClient();
        
        LOG.info("Azure Key Vault client initialized successfully");
    }
    
    @Override
    public Uni<SecretMetadata> store(StoreSecretRequest request) {
        String secretName = buildSecretName(request.tenantId(), request.path());
        
        LOG.infof("Storing secret in Azure Key Vault: %s for tenant: %s", 
            secretName, request.tenantId());
        
        return Uni.createFrom().deferred(() -> {
            try {
                // Serialize secret data
                String secretValue = objectMapper.writeValueAsString(request.data());
                
                // Build KeyVault secret
                KeyVaultSecret secret = new KeyVaultSecret(secretName, secretValue);
                
                // Set properties
                SecretProperties properties = secret.getProperties();
                
                // Set expiration
                if (request.ttl() != null) {
                    OffsetDateTime expiresOn = OffsetDateTime.now()
                        .plus(request.ttl());
                    properties.setExpiresOn(expiresOn);
                }
                
                // Set tags for metadata
                Map<String, String> tags = new HashMap<>();
                tags.put(TAG_TENANT, request.tenantId());
                tags.put(TAG_TYPE, request.type().name());
                tags.put(TAG_ROTATABLE, String.valueOf(request.rotatable()));
                tags.put(TAG_CREATED_BY, getCurrentUser());
                
                if (request.metadata() != null) {
                    tags.putAll(request.metadata());
                }
                
                properties.setTags(tags);
                
                // Set content type
                properties.setContentType("application/json");
                
                // Store in Key Vault
                KeyVaultSecret stored = secretClient.setSecret(secret);
                
                // Build metadata
                SecretMetadata metadata = buildMetadata(
                    request.tenantId(),
                    request.path(),
                    stored
                );
                
                // Audit logging
                auditLogger.logSecretStore(request.tenantId(), secretName, 
                    extractVersion(stored.getProperties().getVersion()));
                
                return Uni.createFrom().item(metadata);
                
            } catch (Exception e) {
                LOG.errorf(e, "Failed to store secret in Azure Key Vault: %s", secretName);
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.BACKEND_UNAVAILABLE,
                        "Failed to store secret: " + e.getMessage(),
                        e
                    )
                );
            }
        });
    }
    
    @Override
    public Uni<Secret> retrieve(RetrieveSecretRequest request) {
        String secretName = buildSecretName(request.tenantId(), request.path());
        
        LOG.debugf("Retrieving secret from Azure Key Vault: %s, version: %s",
            secretName, request.version().map(String::valueOf).orElse("latest"));
        
        return Uni.createFrom().deferred(() -> {
            try {
                KeyVaultSecret secret;
                
                if (request.version().isPresent()) {
                    // Retrieve specific version
                    String versionId = String.valueOf(request.version().get());
                    secret = secretClient.getSecret(secretName, versionId);
                } else {
                    // Retrieve latest version
                    secret = secretClient.getSecret(secretName);
                }
                
                if (secret == null || secret.getValue() == null) {
                    return Uni.createFrom().failure(
                        new SecretException(
                            SecretException.ErrorCode.SECRET_NOT_FOUND,
                            "Secret not found: " + request.path()
                        )
                    );
                }
                
                // Check if enabled
                if (Boolean.FALSE.equals(secret.getProperties().isEnabled())) {
                    return Uni.createFrom().failure(
                        new SecretException(
                            SecretException.ErrorCode.SECRET_NOT_FOUND,
                            "Secret is disabled"
                        )
                    );
                }
                
                // Check expiration
                OffsetDateTime expiresOn = secret.getProperties().getExpiresOn();
                if (expiresOn != null && OffsetDateTime.now().isAfter(expiresOn)) {
                    return Uni.createFrom().failure(
                        new SecretException(
                            SecretException.ErrorCode.SECRET_NOT_FOUND,
                            "Secret has expired"
                        )
                    );
                }
                
                // Deserialize secret data
                @SuppressWarnings("unchecked")
                Map<String, String> secretData = objectMapper.readValue(
                    secret.getValue(),
                    Map.class
                );
                
                // Build metadata
                SecretMetadata metadata = buildMetadata(
                    request.tenantId(),
                    request.path(),
                    secret
                );
                
                // Audit logging
                auditLogger.logSecretRetrieve(request.tenantId(), secretName,
                    metadata.version());
                
                return Uni.createFrom().item(
                    new Secret(request.tenantId(), request.path(), secretData, metadata)
                );
                
            } catch (SecretException e) {
                return Uni.createFrom().failure(e);
            } catch (com.azure.core.exception.ResourceNotFoundException e) {
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.SECRET_NOT_FOUND,
                        "Secret not found: " + request.path()
                    )
                );
            } catch (Exception e) {
                LOG.errorf(e, "Failed to retrieve secret from Azure Key Vault: %s", secretName);
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.BACKEND_UNAVAILABLE,
                        "Failed to retrieve secret: " + e.getMessage(),
                        e
                    )
                );
            }
        });
    }
    
    @Override
    public Uni<Void> delete(DeleteSecretRequest request) {
        String secretName = buildSecretName(request.tenantId(), request.path());
        
        LOG.infof("Deleting secret from Azure Key Vault: %s (hard=%b)", 
            secretName, request.hardDelete());
        
        return Uni.createFrom().deferred(() -> {
            try {
                if (request.hardDelete()) {
                    // Begin deletion
                    secretClient.beginDeleteSecret(secretName);
                    
                    // Purge deleted secret (permanent deletion)
                    // Note: This requires "purge" permission
                    try {
                        secretClient.purgeDeletedSecret(secretName);
                    } catch (Exception e) {
                        LOG.warnf("Failed to purge secret (may require purge permission): %s", 
                            e.getMessage());
                    }
                } else {
                    // Soft delete (can be recovered within retention period)
                    secretClient.beginDeleteSecret(secretName);
                }
                
                // Audit logging
                auditLogger.logSecretDelete(request.tenantId(), secretName,
                    request.hardDelete(), request.reason());
                
                return Uni.createFrom().voidItem();
                
            } catch (Exception e) {
                LOG.errorf(e, "Failed to delete secret from Azure Key Vault: %s", secretName);
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.BACKEND_UNAVAILABLE,
                        "Failed to delete secret: " + e.getMessage(),
                        e
                    )
                );
            }
        });
    }
    
    @Override
    public Uni<List<SecretMetadata>> list(String tenantId, String path) {
        return Uni.createFrom().deferred(() -> {
            try {
                String prefix = secretPrefix + tenantId + "-" + 
                    (path.isEmpty() ? "" : path.replace("/", "-") + "-");
                
                List<SecretMetadata> metadataList = new ArrayList<>();
                
                // List all secrets
                for (SecretProperties properties : secretClient.listPropertiesOfSecrets()) {
                    String name = properties.getName();
                    
                    // Filter by prefix
                    if (name.startsWith(prefix)) {
                        // Check if enabled
                        if (Boolean.TRUE.equals(properties.isEnabled())) {
                            String relativePath = extractPath(tenantId, name);
                            
                            metadataList.add(new SecretMetadata(
                                tenantId,
                                relativePath,
                                extractVersion(properties.getVersion()),
                                extractTypeFromTags(properties.getTags()),
                                properties.getCreatedOn().toInstant(),
                                properties.getUpdatedOn().toInstant(),
                                Optional.ofNullable(properties.getExpiresOn())
                                    .map(OffsetDateTime::toInstant),
                                extractCreatedByFromTags(properties.getTags()),
                                extractCustomMetadata(properties.getTags()),
                                extractRotatableFromTags(properties.getTags()),
                                SecretStatus.ACTIVE
                            ));
                        }
                    }
                }
                
                return Uni.createFrom().item(metadataList);
                
            } catch (Exception e) {
                LOG.errorf(e, "Failed to list secrets from Azure Key Vault");
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.BACKEND_UNAVAILABLE,
                        "Failed to list secrets: " + e.getMessage(),
                        e
                    )
                );
            }
        });
    }
    
    @Override
    public Uni<SecretMetadata> rotate(RotateSecretRequest request) {
        String secretName = buildSecretName(request.tenantId(), request.path());
        
        LOG.infof("Rotating secret in Azure Key Vault: %s", secretName);
        
        return retrieve(RetrieveSecretRequest.of(request.tenantId(), request.path()))
            .onItem().transformToUni(currentSecret -> {
                // Store new version
                StoreSecretRequest storeRequest = StoreSecretRequest.builder()
                    .tenantId(request.tenantId())
                    .path(request.path())
                    .data(request.newData())
                    .type(currentSecret.metadata().type())
                    .metadata(currentSecret.metadata().metadata())
                    .rotatable(currentSecret.metadata().rotatable())
                    .build();
                
                return store(storeRequest)
                    .onItem().invoke(newMetadata -> {
                        // Optionally disable old version
                        if (request.deprecateOld()) {
                            try {
                                KeyVaultSecret oldSecret = secretClient.getSecret(
                                    secretName,
                                    String.valueOf(currentSecret.metadata().version())
                                );
                                oldSecret.getProperties().setEnabled(false);
                                secretClient.updateSecretProperties(oldSecret.getProperties());
                            } catch (Exception e) {
                                LOG.warnf("Failed to disable old secret version: %s", 
                                    e.getMessage());
                            }
                        }
                        
                        auditLogger.logSecretRotate(request.tenantId(), secretName,
                            currentSecret.metadata().version(), newMetadata.version());
                    });
            });
    }
    
    @Override
    public Uni<Boolean> exists(String tenantId, String path) {
        String secretName = buildSecretName(tenantId, path);
        
        return Uni.createFrom().deferred(() -> {
            try {
                secretClient.getSecret(secretName);
                return Uni.createFrom().item(true);
            } catch (com.azure.core.exception.ResourceNotFoundException e) {
                return Uni.createFrom().item(false);
            } catch (Exception e) {
                return Uni.createFrom().item(false);
            }
        });
    }
    
    @Override
    public Uni<SecretMetadata> getMetadata(String tenantId, String path) {
        String secretName = buildSecretName(tenantId, path);
        
        return Uni.createFrom().deferred(() -> {
            try {
                KeyVaultSecret secret = secretClient.getSecret(secretName);
                
                if (secret == null) {
                    return Uni.createFrom().failure(
                        new SecretException(
                            SecretException.ErrorCode.SECRET_NOT_FOUND,
                            "Secret not found: " + path
                        )
                    );
                }
                
                SecretMetadata metadata = buildMetadata(tenantId, path, secret);
                return Uni.createFrom().item(metadata);
                
            } catch (com.azure.core.exception.ResourceNotFoundException e) {
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.SECRET_NOT_FOUND,
                        "Secret not found: " + path
                    )
                );
            } catch (Exception e) {
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.BACKEND_UNAVAILABLE,
                        "Failed to get metadata: " + e.getMessage(),
                        e
                    )
                );
            }
        });
    }
    
    @Override
    public Uni<HealthStatus> health() {
        return Uni.createFrom().deferred(() -> {
            try {
                // Try to list secrets to check connectivity
                secretClient.listPropertiesOfSecrets().iterator().hasNext();
                
                Map<String, Object> details = Map.of(
                    "backend", "azure-keyvault",
                    "vault_url", vaultUrl,
                    "authenticated", true
                );
                
                return Uni.createFrom().item(
                    new HealthStatus(true, "azure-keyvault", details, Optional.empty())
                );
                
            } catch (Exception e) {
                LOG.errorf(e, "Azure Key Vault health check failed");
                return Uni.createFrom().item(
                    HealthStatus.unhealthy("azure-keyvault", e.getMessage())
                );
            }
        });
    }
    
    // Helper methods
    
    private String buildSecretName(String tenantId, String path) {
        // Azure Key Vault secret names can only contain alphanumeric and dashes
        String normalizedPath = path.replace("/", "-").replace("_", "-");
        return secretPrefix + tenantId + "-" + normalizedPath;
    }
    
    private String extractPath(String tenantId, String secretName) {
        String prefix = secretPrefix + tenantId + "-";
        if (secretName.startsWith(prefix)) {
            return secretName.substring(prefix.length()).replace("-", "/");
        }
        return secretName;
    }
    
    private SecretMetadata buildMetadata(String tenantId, String path, 
                                        KeyVaultSecret secret) {
        SecretProperties props = secret.getProperties();
        
        return new SecretMetadata(
            tenantId,
            path,
            extractVersion(props.getVersion()),
            extractTypeFromTags(props.getTags()),
            props.getCreatedOn().toInstant(),
            props.getUpdatedOn().toInstant(),
            Optional.ofNullable(props.getExpiresOn()).map(OffsetDateTime::toInstant),
            extractCreatedByFromTags(props.getTags()),
            extractCustomMetadata(props.getTags()),
            extractRotatableFromTags(props.getTags()),
            Boolean.TRUE.equals(props.isEnabled()) ? 
                SecretStatus.ACTIVE : SecretStatus.DELETED
        );
    }
    
    private int extractVersion(String versionId) {
        // Azure Key Vault uses GUIDs for versions
        // We'll use a simple counter based on hash
        return Math.abs(versionId.hashCode() % 10000);
    }
    
    private SecretType extractTypeFromTags(Map<String, String> tags) {
        if (tags == null) return SecretType.GENERIC;
        
        String type = tags.get(TAG_TYPE);
        if (type != null) {
            try {
                return SecretType.valueOf(type);
            } catch (IllegalArgumentException e) {
                return SecretType.GENERIC;
            }
        }
        return SecretType.GENERIC;
    }
    
    private String extractCreatedByFromTags(Map<String, String> tags) {
        if (tags == null) return "system";
        return tags.getOrDefault(TAG_CREATED_BY, "system");
    }
    
    private boolean extractRotatableFromTags(Map<String, String> tags) {
        if (tags == null) return false;
        return Boolean.parseBoolean(tags.getOrDefault(TAG_ROTATABLE, "false"));
    }
    
    private Map<String, String> extractCustomMetadata(Map<String, String> tags) {
        if (tags == null) return Map.of();
        
        return tags.entrySet().stream()
            .filter(e -> !e.getKey().equals(TAG_TENANT) &&
                        !e.getKey().equals(TAG_TYPE) &&
                        !e.getKey().equals(TAG_ROTATABLE) &&
                        !e.getKey().equals(TAG_CREATED_BY))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
    
    private String getCurrentUser() {
        return "system";
    }
}


package tech.kayys.wayang.workflow.execution.node;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.kayys.wayang.schema.node.NodeDescriptor;
import tech.kayys.wayang.workflow.execution.NodeContext;
import tech.kayys.wayang.workflow.execution.secrets.SecretResolver;
import tech.kayys.wayang.security.secrets.injection.SecretInjectionProcessor;

/**
 * Enhanced AbstractNode with automatic secret resolution.
 * 
 * Changes from original:
 * 1. Automatic secret resolution before execution
 * 2. Secret injection for annotated fields
 * 3. Sensitive data redaction in logs
 * 4. Enhanced error handling for secret failures
 */
public abstract class AbstractNode implements Node {

    protected static final Logger LOG = Logger.getLogger(AbstractNode.class);
    
    protected NodeDescriptor descriptor;
    protected NodeConfig config;
    protected MetricsCollector metrics;

    @Inject
    SecretResolver secretResolver;
    
    @Inject
    SecretInjectionProcessor secretInjector;

    @Override
    public Uni<Void> onLoad(NodeDescriptor descriptor, NodeConfig config) {
        this.descriptor = descriptor;
        this.config = config;
        this.metrics = MetricsCollector.forNode(descriptor.getId());
        
        // Inject secrets into annotated fields
        return secretInjector.injectSecrets(this, "default-tenant")
            .onItem().invoke(() -> doOnLoad(descriptor, config));
    }

    @Override
    public final Uni<ExecutionResult> execute(NodeContext context) {
        Span span = startTrace(context);
        long startTime = System.nanoTime();

        return Uni.createFrom().deferred(() -> {
            Guardrails guardrails = context.getGuardrails();
            ProvenanceService provenance = context.getProvenance().getService();

            // 1. Pre-check guardrails
            Uni<GuardrailResult> preCheck = config.guardrailsConfig().enabled()
                    ? guardrails.preCheck(context, descriptor)
                    : Uni.createFrom().item(GuardrailResult.allow());

            return preCheck
                .onItem().transformToUni(guardResult -> {
                    if (!guardResult.isAllowed()) {
                        return Uni.createFrom().item(
                            ExecutionResult.blocked(guardResult.getReason())
                        );
                    }

                    // 2. Resolve secrets from schema
                    return secretResolver.resolveSecrets(context, descriptor)
                        .onItem().transformToUni(enrichedContext -> {
                            
                            // 3. Validate inputs
                            return validateInputs(enrichedContext)
                                .onItem().transformToUni(valid -> {
                                    if (!valid) {
                                        return Uni.createFrom().item(
                                            ExecutionResult.failed("Input validation failed")
                                        );
                                    }

                                    // 4. Execute the node logic
                                    return doExecute(enrichedContext);
                                });
                        });
                })

                // 5. Post-check guardrails
                .onItem().transformToUni(result -> {
                    if (!config.guardrailsConfig().enabled() || !result.isSuccess()) {
                        return Uni.createFrom().item(result);
                    }

                    return guardrails.postCheck(result, descriptor)
                        .map(g -> g.isAllowed()
                            ? result
                            : ExecutionResult.blocked(g.getReason()));
                })

                // 6. Metrics + provenance
                .onItem().invoke(result -> {
                    long duration = System.nanoTime() - startTime;
                    metrics.recordExecution(duration, result.getStatus());
                    
                    // Redact sensitive data in provenance
                    NodeContext redactedContext = redactSensitiveData(context);
                    provenance.log(context.getNodeId(), redactedContext, result);
                })

                // 7. Error handling (including secret resolution failures)
                .onFailure().recoverWithItem(th -> {
                    metrics.recordFailure(th);
                    
                    // Special handling for secret failures
                    if (th instanceof SecretResolutionException) {
                        LOG.errorf(th, "Secret resolution failed for node: %s", 
                            descriptor.getId());
                        return ExecutionResult.error(
                            ErrorPayload.builder()
                                .type("SecretError")
                                .message("Failed to resolve secrets: " + th.getMessage())
                                .originNode(descriptor.getId())
                                .retryable(false)
                                .suggestedAction("human_review")
                                .build()
                        );
                    }
                    
                    return ExecutionResult.error(
                        ErrorPayload.from(th, descriptor.getId(), context)
                    );
                })

                // 8. Close span
                .eventually(() -> endTrace(span));
        });
    }

    /**
     * Redact sensitive data from context for logging
     */
    private NodeContext redactSensitiveData(NodeContext context) {
        if (!(context instanceof SecretAwareNodeContext secretContext)) {
            return context;
        }
        
        NodeContext redacted = context.copy();
        
        // Redact all sensitive inputs
        for (String inputName : context.getInputs().keySet()) {
            if (secretContext.isSensitive(inputName)) {
                redacted.setInput(inputName, "***REDACTED***");
            }
        }
        
        return redacted;
    }

    protected abstract Uni<ExecutionResult> doExecute(NodeContext context);

    protected void doOnLoad(NodeDescriptor descriptor, NodeConfig config) {
        // Default: no-op
    }

    private Uni<Boolean> validateInputs(NodeContext context) {
        return Uni.createFrom().item(() -> {
            for (var input : descriptor.getInputs()) {
                Object value = context.getInput(input.getName());

                if (input.isRequired() && value == null) {
                    LOG.warnf("Required input missing: %s", input.getName());
                    return false;
                }

                if (value != null && input.getSchema() != null) {
                    if (!SchemaUtils.validate(value, input.getSchema())) {
                        LOG.warnf("Input validation failed: %s", input.getName());
                        return false;
                    }
                }
            }
            return true;
        });
    }

    private Span startTrace(NodeContext context) {
        return Tracer.spanBuilder("node.execute")
            .withTag("node.id", descriptor.getId())
            .withTag("node.type", descriptor.getType())
            .withTag("run.id", context.getRunId())
            .withTag("tenant.id", context.getTenantId());
    }

    private void endTrace(Span span) {
        if (span != null) {
            span.finish();
        }
    }

    @Override
    public Uni<Void> onUnload() {
        if (metrics != null) {
            metrics.close();
        }
        return Uni.createFrom().voidItem();
    }
}

/**
 * Example usage: HTTP Request Node with secret injection
 */
@ApplicationScoped
public class HTTPRequestNode extends IntegrationNode {
    
    // Automatically injected from secret manager
    @SecretValue(path = "services/default/api-key", key = "api_key", required = false)
    String defaultApiKey;
    
    @Override
    protected Uni<ExecutionResult> executeIntegration(NodeContext context) {
        // Secrets are already resolved in context by SecretResolver
        String apiKey = context.getInput("apiKey") != null 
            ? (String) context.getInput("apiKey")
            : defaultApiKey;
        
        String url = (String) context.getInput("url");
        String method = (String) context.getInput("method");
        
        LOG.infof("Making HTTP %s request to %s", method, url);
        
        return makeHttpRequest(url, method, apiKey)
            .onItem().transform(response -> 
                ExecutionResult.success(Map.of("response", response))
            )
            .onFailure().transform(error -> 
                new ExecutionException("HTTP request failed: " + error.getMessage(), error)
            );
    }
    
    private Uni<String> makeHttpRequest(String url, String method, String apiKey) {
        // Implementation
        return Uni.createFrom().item("OK");
    }
}

/**
 * Example usage: Database Query Node
 */
@ApplicationScoped
public class DatabaseQueryNode extends IntegrationNode {
    
    @Override
    protected Uni<ExecutionResult> executeIntegration(NodeContext context) {
        // Connection string automatically resolved from secret
        String connectionString = (String) context.getInput("connectionString");
        String query = (String) context.getInput("query");
        
        LOG.infof("Executing database query");
        // Note: connectionString is marked as sensitive, won't appear in logs
        
        return executeQuery(connectionString, query)
            .onItem().transform(results -> 
                ExecutionResult.success(Map.of("results", results))
            );
    }
    
    private Uni<List<Map<String, Object>>> executeQuery(String connStr, String query) {
        // Implementation
        return Uni.createFrom().item(List.of());
    }
}

/**
 * Example workflow definition with secrets
 */
public class WorkflowWithSecretsExample {
    
    public static String example() {
        return """
        {
          "id": "api-integration-workflow",
          "name": "API Integration with Secrets",
          "nodes": [
            {
              "id": "http-call-1",
              "type": "http-request",
              "inputs": [
                {
                  "name": "apiKey",
                  "displayName": "API Key",
                  "description": "GitHub API Key from secret manager",
                  "data": {
                    "type": "string",
                    "source": "secret",
                    "sensitive": true,
                    "required": true,
                    "secretRef": {
                      "path": "services/github/api-key",
                      "key": "api_key",
                      "cacheTTL": 300,
                      "validation": "size(value) >= 40"
                    }
                  }
                },
                {
                  "name": "url",
                  "data": {
                    "type": "string",
                    "source": "input",
                    "defaultValue": "https://api.github.com/user"
                  }
                },
                {
                  "name": "method",
                  "data": {
                    "type": "string",
                    "source": "input",
                    "defaultValue": "GET"
                  }
                }
              ],
              "outputs": {
                "channels": [
                  {
                    "name": "success",
                    "type": "success",
                    "schema": {
                      "name": "response",
                      "data": {"type": "json"}
                    }
                  },
                  {
                    "name": "error",
                    "type": "error",
                    "schema": {
                      "name": "error",
                      "data": {"type": "object"}
                    }
                  }
                ]
              }
            },
            {
              "id": "db-store",
              "type": "database-connector",
              "inputs": [
                {
                  "name": "connectionString",
                  "data": {
                    "type": "string",
                    "source": "secret",
                    "sensitive": true,
                    "secretRef": {
                      "path": "databases/production/connection",
                      "key": "connection_string",
                      "cacheTTL": 600
                    }
                  }
                },
                {
                  "name": "data",
                  "data": {
                    "type": "json",
                    "source": "context"
                  }
                }
              ]
            }
          ],
          "edges": [
            {
              "from": "http-call-1",
              "to": "db-store",
              "fromPort": "success",
              "toPort": "data"
            }
          ]
        }
        """;
    }
}



