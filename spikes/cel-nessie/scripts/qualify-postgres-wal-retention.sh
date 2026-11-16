#!/usr/bin/env bash
set -euo pipefail

# Validate that retained base backups remain verifiable from archived WAL,
# and prove that deleting a required segment is detected before retention.
volume_prefix="wayang-wal-retention-${GITHUB_RUN_ID:-local}"
source_container="${volume_prefix}-source"
source_data_volume="${volume_prefix}-source-data"
wal_archive_volume="${volume_prefix}-wal-archive"
base_backup_volume="${volume_prefix}-base-backup"
base_backup_2_volume="${volume_prefix}-base-backup-2"
missing_wal_volume="${volume_prefix}-missing-wal"

cleanup() {
  docker rm -f "$source_container" >/dev/null 2>&1 || true
  docker volume rm "$source_data_volume" "$wal_archive_volume" \
    "$base_backup_volume" "$base_backup_2_volume" "$missing_wal_volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker volume create "$source_data_volume" >/dev/null
docker volume create "$wal_archive_volume" >/dev/null
docker volume create "$base_backup_volume" >/dev/null
docker volume create "$base_backup_2_volume" >/dev/null
docker volume create "$missing_wal_volume" >/dev/null

docker run --detach --name "$source_container" \
  --volume "$source_data_volume:/var/lib/postgresql/data" \
  --volume "$wal_archive_volume:/wal-archive" \
  --volume "$base_backup_volume:/base-backup" \
  --volume "$base_backup_2_volume:/base-backup-2" \
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
docker exec --user root "$source_container" chown postgres:postgres /wal-archive /base-backup /base-backup-2
export PGPASSWORD=postgres
run_sql() {
  docker exec -i -e PGPASSWORD=postgres "$source_container" \
    psql -U postgres -d wayang_wal_retention -v ON_ERROR_STOP=1 "$@"
}

run_sql -f /dev/stdin < spikes/cel-nessie/src/main/resources/db/migration/V1__pressure_event_store.sql
run_sql -c "CREATE TABLE wal_retention_marker (marker TEXT PRIMARY KEY)"
run_sql -c "INSERT INTO wal_retention_marker VALUES ('retained-backup-baseline')"

# -X none deliberately leaves backup-required WAL in the archive rather than
# embedding it in the backup's pg_wal directory.
docker exec -e PGPASSWORD=postgres "$source_container" pg_basebackup \
  -h 127.0.0.1 -U postgres -D /base-backup -Fp -X none -P

# Close the segment containing the backup stop point so archive_command can
# persist it. pg_verifybackup determines the complete WAL range from manifest.
run_sql -c "SELECT pg_switch_wal()"
run_sql -c "INSERT INTO wal_retention_marker VALUES ('second-retained-backup')"
docker exec -e PGPASSWORD=postgres "$source_container" pg_basebackup \
  -h 127.0.0.1 -U postgres -D /base-backup-2 -Fp -X none -P
run_sql -c "INSERT INTO wal_retention_marker VALUES ('post-backup-unreferenced-wal')"
run_sql -c "SELECT pg_switch_wal()"

verify_output=""
verified=0
for attempt in $(seq 1 60); do
  if docker run --rm \
      --volumes-from "$source_container":ro \
      postgres:17 pg_verifybackup --wal-directory=/wal-archive /base-backup \
      > /tmp/wayang-pg-verifybackup.log 2>&1 \
      && docker run --rm --volumes-from "$source_container":ro postgres:17 \
        pg_verifybackup --wal-directory=/wal-archive /base-backup-2 \
        > /tmp/wayang-pg-verifybackup-2.log 2>&1; then
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

# Exercise the new planner against the real backup_manifest and archive produced
# above, not only synthetic unit-test fixtures.
catalog_dir="/tmp/wayang-wal-dependency-catalog"
mkdir -p "$catalog_dir/archive"
docker cp "$source_container:/base-backup/backup_manifest" "$catalog_dir/backup_manifest-1"
docker cp "$source_container:/base-backup-2/backup_manifest" "$catalog_dir/backup_manifest-2"
docker cp "$source_container:/wal-archive/." "$catalog_dir/archive/"
segment_size="$(docker exec "$source_container" pg_controldata /var/lib/postgresql/data \
  | awk -F: '/Bytes per WAL segment/ { gsub(/[[:space:]]/, "", $2); print $2 }')"
if [[ ! "$segment_size" =~ ^[0-9]+$ ]] || [[ "$segment_size" -le 0 ]]; then
  echo "Could not determine PostgreSQL WAL segment size from pg_controldata" >&2
  exit 1
fi
python3 - "$catalog_dir" "$segment_size" <<'PY'
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
catalog = {
    "wal_segment_size_bytes": int(sys.argv[2]),
    "backups": [{
        "backup_id": "phase-4ao-retained-backup-1",
        "retained": True,
        "manifest": "backup_manifest-1",
        "backup_directory": "backup-1",
    }, {
        "backup_id": "phase-4ao-retained-backup-2",
        "retained": True,
        "manifest": "backup_manifest-2",
        "backup_directory": "backup-2",
    }],
}
(root / "catalog.json").write_text(json.dumps(catalog, indent=2), encoding="utf-8")
PY
python3 spikes/cel-nessie/scripts/plan-postgres-wal-retention.py \
  --archive-dir "$catalog_dir/archive" \
  --catalog "$catalog_dir/catalog.json" \
  --older-than-days 0 \
  > /tmp/wayang-wal-retention-plan.json
grep -q '"deletion_performed": false' /tmp/wayang-wal-retention-plan.json
grep -q '"required_wal_files": \[' /tmp/wayang-wal-retention-plan.json
# Simulate the planner's candidate removals in a disposable copy. The source
# archive is never modified; every retained backup must still verify afterward.
simulation_dir="/tmp/wayang-wal-retention-simulation"
python3 spikes/cel-nessie/scripts/simulate-postgres-wal-retention.py \
  --archive-dir "$catalog_dir/archive" \
  --plan-json /tmp/wayang-wal-retention-plan.json \
  --destination "$simulation_dir" \
  > /tmp/wayang-wal-retention-simulation.json
for backup in /base-backup /base-backup-2; do
  docker run --rm \
    --volume "$base_backup_volume:/base-backup:ro" \
    --volume "$base_backup_2_volume:/base-backup-2:ro" \
    --volume "$simulation_dir:/simulated-wal:ro" \
    postgres:17 pg_verifybackup --wal-directory=/simulated-wal "$backup"
done
# Negative control: removing all required WAL from another disposable copy
# must make every retained backup fail verification.
missing_simulation_dir="/tmp/wayang-wal-retention-missing-simulation"
python3 spikes/cel-nessie/scripts/simulate-postgres-wal-retention.py \
  --archive-dir "$catalog_dir/archive" \
  --plan-json /tmp/wayang-wal-retention-plan.json \
  --destination "$missing_simulation_dir" \
  --remove-required-wal
for backup in /base-backup /base-backup-2; do
  if docker run --rm \
      --volume "$base_backup_volume:/base-backup:ro" \
      --volume "$base_backup_2_volume:/base-backup-2:ro" \
      --volume "$missing_simulation_dir:/simulated-wal:ro" \
      postgres:17 pg_verifybackup --wal-directory=/simulated-wal "$backup"; then
    echo "Verifier unexpectedly accepted $backup after required WAL removal" >&2
    exit 1
  fi
done
expected_snapshot="$(python3 -c 'import json; print(json.load(open("/tmp/wayang-wal-retention-plan.json"))["catalog_snapshot_sha256"])')"
python3 spikes/cel-nessie/scripts/plan-postgres-wal-retention.py \
  --archive-dir "$catalog_dir/archive" \
  --catalog "$catalog_dir/catalog.json" \
  --older-than-days 0 \
  --expected-snapshot-sha256 "$expected_snapshot" \
  > /tmp/wayang-wal-retention-revalidation.json
cp "$catalog_dir/catalog.json" "$catalog_dir/catalog.original.json"
python3 - "$catalog_dir/catalog.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
catalog = json.loads(path.read_text(encoding="utf-8"))
catalog["backups"].append({
    "backup_id": "concurrent-backup-registration",
    "retained": False,
    "manifest": "not-created.json",
})
path.write_text(json.dumps(catalog, indent=2), encoding="utf-8")
PY
if python3 spikes/cel-nessie/scripts/plan-postgres-wal-retention.py \
  --archive-dir "$catalog_dir/archive" \
  --catalog "$catalog_dir/catalog.json" \
  --older-than-days 0 \
  --expected-snapshot-sha256 "$expected_snapshot" \
  > /tmp/wayang-wal-retention-stale-plan.json 2> /tmp/wayang-wal-retention-stale-plan.log; then
  echo "Stale catalog snapshot unexpectedly passed revalidation" >&2
  exit 1
fi
grep -q "catalog snapshot changed since planning" /tmp/wayang-wal-retention-stale-plan.log
mv "$catalog_dir/catalog.original.json" "$catalog_dir/catalog.json"

echo "WAL_ARCHIVE_QUALIFICATION_SUCCESS wal_files=$wal_count"
echo "$verify_output"
echo "Checksum inventory written to /tmp/wayang-wal-archive.sha256"
echo "Manifest-backed dry-run plan written to /tmp/wayang-wal-retention-plan.json"
echo "Post-retention simulation report written to /tmp/wayang-wal-retention-simulation.json"
