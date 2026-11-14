#!/usr/bin/env bash
set -euo pipefail

# Validate that retained base backups remain verifiable from archived WAL,
# and prove that deleting a required segment is detected before retention.
volume_prefix="wayang-wal-retention-${GITHUB_RUN_ID:-local}"
source_container="${volume_prefix}-source"
source_data_volume="${volume_prefix}-source-data"
wal_archive_volume="${volume_prefix}-wal-archive"
base_backup_volume="${volume_prefix}-base-backup"
missing_wal_volume="${volume_prefix}-missing-wal"

cleanup() {
  docker rm -f "$source_container" >/dev/null 2>&1 || true
  docker volume rm "$source_data_volume" "$wal_archive_volume" \
    "$base_backup_volume" "$missing_wal_volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker volume create "$source_data_volume" >/dev/null
docker volume create "$wal_archive_volume" >/dev/null
docker volume create "$base_backup_volume" >/dev/null
docker volume create "$missing_wal_volume" >/dev/null

docker run --detach --name "$source_container" \
  --volume "$source_data_volume:/var/lib/postgresql/data" \
  --volume "$wal_archive_volume:/wal-archive" \
  --volume "$base_backup_volume:/base-backup" \
  --env POSTGRES_DB=wayang_wal_retention \
  --env POSTGRES_USER=postgres \
  --env POSTGRES_PASSWORD=postgres \
  postgres:17 \
  -c wal_level=replica \
  -c archive_mode=on \
  -c archive_timeout=1s \
  -c "archive_command=test ! -f /wal-archive/%f && cp %p /wal-archive/%f"

wait_for_postgres() {
  for attempt in $(seq 1 60); do
    if docker exec "$source_container" pg_isready -U postgres -d wayang_wal_retention >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  docker logs "$source_container" >&2 || true
  return 1
}

wait_for_postgres
docker exec --user root "$source_container" chown postgres:postgres /wal-archive /base-backup
export PGPASSWORD=postgres

psql -h 127.0.0.1 -p 5432 -U postgres -d wayang_wal_retention \
  -v ON_ERROR_STOP=1 -f spikes/cel-nessie/src/main/resources/db/migration/V1__pressure_event_store.sql
psql -h 127.0.0.1 -p 5432 -U postgres -d wayang_wal_retention \
  -v ON_ERROR_STOP=1 -c "CREATE TABLE wal_retention_marker (marker TEXT PRIMARY KEY)"
psql -h 127.0.0.1 -p 5432 -U postgres -d wayang_wal_retention \
  -v ON_ERROR_STOP=1 -c "INSERT INTO wal_retention_marker VALUES ('retained-backup-baseline')"

# -X none deliberately leaves backup-required WAL in the archive rather than
# embedding it in the backup's pg_wal directory.
docker exec -e PGPASSWORD=postgres "$source_container" pg_basebackup \
  -h 127.0.0.1 -U postgres -D /base-backup -Fp -X none -P

# Close the segment containing the backup stop point so archive_command can
# persist it. pg_verifybackup determines the complete WAL range from manifest.
psql -h 127.0.0.1 -p 5432 -U postgres -d wayang_wal_retention \
  -v ON_ERROR_STOP=1 -c "SELECT pg_switch_wal()"

verify_output=""
verified=0
for attempt in $(seq 1 60); do
  if docker run --rm \
      --volumes-from "$source_container":ro \
      postgres:17 pg_verifybackup --wal-directory=/wal-archive /base-backup \
      > /tmp/wayang-pg-verifybackup.log 2>&1; then
    verify_output="$(cat /tmp/wayang-pg-verifybackup.log)"
    verified=1
    break
  fi
  sleep 2
done
if [[ "$verified" != "1" ]]; then
  cat /tmp/wayang-pg-verifybackup.log >&2 || true
  docker logs "$source_container" >&2 || true
  echo "Retained base backup could not be verified against archived WAL" >&2
  exit 1
fi

wal_count="$(docker exec "$source_container" find /wal-archive -maxdepth 1 -type f | wc -l | tr -d ' ')"
if [[ "$wal_count" -lt 1 ]]; then
  echo "WAL archive is unexpectedly empty" >&2
  exit 1
fi

# Produce a stable checksum inventory for this qualification run.
docker run --rm --volumes-from "$source_container":ro postgres:17 \
  sh -c "find /wal-archive -maxdepth 1 -type f -print0 | sort -z | xargs -0 -r sha256sum" \
  > /tmp/wayang-wal-archive.sha256
test -s /tmp/wayang-wal-archive.sha256

# A simulated retention purge that removes every required WAL segment must be
# detected. This is a disposable empty volume; the real archive is untouched.
if docker run --rm \
    --volume "$base_backup_volume:/base-backup:ro" \
    --volume "$missing_wal_volume:/wal-archive:ro" \
    postgres:17 pg_verifybackup --wal-directory=/wal-archive /base-backup \
    > /tmp/wayang-pg-verifybackup-missing.log 2>&1; then
  echo "Verifier unexpectedly accepted a backup with its required WAL removed" >&2
  exit 1
fi

echo "WAL_ARCHIVE_QUALIFICATION_SUCCESS wal_files=$wal_count"
echo "$verify_output"
echo "Checksum inventory written to /tmp/wayang-wal-archive.sha256"
