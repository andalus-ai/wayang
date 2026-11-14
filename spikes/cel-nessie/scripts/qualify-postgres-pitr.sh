#!/usr/bin/env bash
set -euo pipefail

# Real PostgreSQL 17 physical base-backup + WAL point-in-time recovery exercise.
# Runs on a Docker-capable Linux runner; all state is isolated under mktemp.
work_dir="$(mktemp -d)"
source_container="wayang-pitr-source"
restore_container="wayang-pitr-restore"
source_port="55432"
restore_port="55433"
cleanup() {
  docker rm -f "$restore_container" "$source_container" >/dev/null 2>&1 || true
  rm -rf "$work_dir"
}
trap cleanup EXIT

mkdir -p "$work_dir/source-data" "$work_dir/wal-archive" "$work_dir/base-backup"
chmod 0777 "$work_dir/source-data" "$work_dir/wal-archive" "$work_dir/base-backup"

docker run --detach --name "$source_container" \
  --publish "$source_port:5432" \
  --volume "$work_dir/source-data:/var/lib/postgresql/data" \
  --volume "$work_dir/wal-archive:/wal-archive" \
  --volume "$work_dir/base-backup:/base-backup" \
  --env POSTGRES_DB=wayang_pitr \
  --env POSTGRES_USER=postgres \
  --env POSTGRES_PASSWORD=postgres \
  postgres:17 \
  -c wal_level=replica \
  -c archive_mode=on \
  -c archive_timeout=1s \
  -c "archive_command=test ! -f /wal-archive/%f && cp %p /wal-archive/%f"

wait_for_postgres() {
  local container="$1"
  local port="$2"
  for attempt in $(seq 1 60); do
    if docker exec "$container" pg_isready -U postgres -d wayang_pitr >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "PostgreSQL did not become ready on port $port" >&2
  docker logs "$container" >&2 || true
  return 1
}

wait_for_postgres "$source_container" "$source_port"
export PGPASSWORD=postgres

# Install the real adapter schema and establish data that must survive PITR.
psql -h 127.0.0.1 -p "$source_port" -U postgres -d wayang_pitr \
  -v ON_ERROR_STOP=1 -f spikes/cel-nessie/src/main/resources/db/migration/V1__pressure_event_store.sql
psql -h 127.0.0.1 -p "$source_port" -U postgres -d wayang_pitr \
  -v ON_ERROR_STOP=1 -c "CREATE TABLE pitr_qualification_marker (marker TEXT PRIMARY KEY)"
psql -h 127.0.0.1 -p "$source_port" -U postgres -d wayang_pitr \
  -v ON_ERROR_STOP=1 -c "INSERT INTO pitr_qualification_marker VALUES ('present-in-base-backup')"

# A physical base backup is the recovery starting point.
docker exec -e PGPASSWORD=postgres "$source_container" pg_basebackup \
  -h 127.0.0.1 -U postgres -D /base-backup -Fp -Xs -P
chown -R "$(docker exec "$source_container" id -u postgres):$(docker exec "$source_container" id -g postgres)" \
  "$work_dir/base-backup"

# Choose a target after the base backup but immediately before the later commit.
target_time="$(psql -h 127.0.0.1 -p "$source_port" -U postgres -d wayang_pitr \
  -At -v ON_ERROR_STOP=1 -c "SELECT to_char(clock_timestamp(), 'YYYY-MM-DD HH24:MI:SS.USOF')")"
psql -h 127.0.0.1 -p "$source_port" -U postgres -d wayang_pitr \
  -v ON_ERROR_STOP=1 -c "INSERT INTO pitr_qualification_marker VALUES ('must-not-exist-at-target')"
target_wal="$(psql -h 127.0.0.1 -p "$source_port" -U postgres -d wayang_pitr \
  -At -v ON_ERROR_STOP=1 -c "SELECT pg_walfile_name(pg_current_wal_lsn())")"
psql -h 127.0.0.1 -p "$source_port" -U postgres -d wayang_pitr \
  -v ON_ERROR_STOP=1 -c "SELECT pg_switch_wal()"

# Wait until WAL containing the post-target commit has been archived.
archived=0
for attempt in $(seq 1 60); do
  archived="$(psql -h 127.0.0.1 -p "$source_port" -U postgres -d wayang_pitr \
    -At -v ON_ERROR_STOP=1 -c "SELECT archived_count FROM pg_stat_archiver")"
  archived_target_wal="$(psql -h 127.0.0.1 -p "$source_port" -U postgres -d wayang_pitr \
    -At -v ON_ERROR_STOP=1 -c "SELECT COALESCE(last_archived_wal >= '$target_wal', FALSE) FROM pg_stat_archiver")"
  archived_file="$(find "$work_dir/wal-archive" -type f -print -quit)"
  if [[ "$archived" -gt 0 ]] && [[ "$archived_target_wal" = "t" ]] \
      && [[ -n "$archived_file" ]]; then
    break
  fi
  sleep 2
done
archived_file="$(find "$work_dir/wal-archive" -type f -print -quit)"
if [[ "$archived" -lt 1 ]] || [[ "${archived_target_wal:-f}" != "t" ]] \
    || [[ -z "$archived_file" ]]; then
  echo "No WAL segment was archived; refusing to claim PITR qualification" >&2
  docker logs "$source_container" >&2 || true
  exit 1
fi

docker stop "$source_container" >/dev/null

# Configure recovery from the physical backup and archived WAL to the target.
cat >> "$work_dir/base-backup/postgresql.auto.conf" <<EOF
restore_command = 'cp /wal-archive/%f %p'
recovery_target_time = '$target_time'
recovery_target_inclusive = 'false'
recovery_target_action = 'promote'
EOF
touch "$work_dir/base-backup/recovery.signal"

docker run --detach --name "$restore_container" \
  --publish "$restore_port:5432" \
  --volume "$work_dir/base-backup:/var/lib/postgresql/data" \
  --volume "$work_dir/wal-archive:/wal-archive:ro" \
  postgres:17

wait_for_postgres "$restore_container" "$restore_port"

# Recovery must have reached the target and promoted, preserve baseline data,
# and exclude the transaction committed after the requested recovery point.
in_recovery="t"
for attempt in $(seq 1 60); do
  in_recovery="$(psql -h 127.0.0.1 -p "$restore_port" -U postgres -d wayang_pitr \
    -At -v ON_ERROR_STOP=1 -c "SELECT pg_is_in_recovery()")"
  [[ "$in_recovery" = "f" ]] && break
  sleep 1
done
baseline_count="$(psql -h 127.0.0.1 -p "$restore_port" -U postgres -d wayang_pitr \
  -At -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM pitr_qualification_marker WHERE marker = 'present-in-base-backup'")"
post_target_count="$(psql -h 127.0.0.1 -p "$restore_port" -U postgres -d wayang_pitr \
  -At -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM pitr_qualification_marker WHERE marker = 'must-not-exist-at-target'")"

test "$in_recovery" = "f"
test "$baseline_count" = "1"
test "$post_target_count" = "0"

echo "PITR_QUALIFICATION_SUCCESS target=$target_time target_wal=$target_wal archived_wal_count=$archived baseline=$baseline_count post_target=$post_target_count"
