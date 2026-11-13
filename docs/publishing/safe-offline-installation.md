# Safe offline installation

`ReleaseOfflinePackageInstaller.install(packageFile, trustedReleaseSigner, destination)` verifies and installs a package into a new directory.

## Installation guarantees

- Runs the complete offline package verifier before writing any installation files.
- Requires an existing, non-symlink destination parent and a destination that does not already exist.
- Creates a unique staging directory beside the destination, ensuring staging and publication use the same filesystem.
- Revalidates the package digest before and after extraction to detect package replacement during the operation.
- Revalidates archive entry paths, rejects directory entries and duplicate targets, and creates output files with create-new/no-follow-link semantics.
- Enforces entry-count, per-entry, and total uncompressed size limits while streaming.
- Re-hashes every extracted POM/JAR and compares it with the verified signed artifact inventory.
- Publishes the completed directory with an atomic move. If the filesystem cannot provide atomic publication, installation fails rather than exposing a partial tree.
- Removes the staging tree after any failed installation.

The installer never executes scripts, plugin code, Maven, or application binaries. It lays out the verified package, including the `release/` metadata and `repository/` Maven repository tree. Application activation, service registration, migration, health checks, and rollback are intentionally separate lifecycle operations.

## Result semantics

A returned `InstallationResult` means the verified directory was atomically published. It does not mean that the application was started or became healthy. The package's existing signed verification receipt remains an attestation of verification; a deployment controller should record activation and health outcomes in its own append-only lifecycle log.
