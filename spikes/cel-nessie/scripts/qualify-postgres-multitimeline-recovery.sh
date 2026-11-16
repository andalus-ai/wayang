#!/usr/bin/env bash
set -euo pipefail

# Exercise a real PostgreSQL timeline fork and prove that the retention plan
# preserves the parent WAL, child WAL, and child timeline history needed by PITR.
prefix="wayang-multitimeline-${GITHUB_RUN_ID:-local}"
source_container="${prefix}-source"
branch_container="${prefix}-branch"
restore_container="${prefix}-restore"
source_volume="${prefix}-source-data"
archive_volume="${prefix}-archive"
backup_volume="${prefix}-backup"
branch_volume="${prefix}-branch-data"
restore_volume="${prefix}-restore-data"

cleanup() {
  docker rm -f "$restore_container" "$branch_container" "$source_container" >/dev/null 2>&1 || true
  docker volume rm "$source_volume" "$archive_volume" "$backup_volume" \
    "$branch_volume" "$restore_volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for volume in "$source_volume" "$archive_volume" "$backup_volume" "$branch_volume" "$restore_volume"; do
  docker volume create "$volume" >/dev/null
done

docker run --detach --name "$source_container" \
  --volume "$source_volume:/var/lib/postgresql/data" \
  --volume "$archive_volume:/wal-archive" \
  --volume "$backup_volume:/base-backup" \
  --env POSTGRES_DB=wayang_timeline \
  --env POSTGRES_USER=postgres \
  --env POSTGRES_PASSWORD=postgres \
  postgres:17 \
  -c wal_level=replica \
  -c archive_mode=on \
  -c archive_timeout=1s \
  -c "archive_command=test ! -f /wal-archive/%f && cp %p /wal-archive/%f"

wait_ready() {
  local container="$1"
  for attempt in $(seq 1 60); do
    if docker exec "$container" pg_isready -U postgres -d wayang_timeline >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  docker logs "$container" >&2 || true
  return 1
}

sql() {
  local container="$1"
  shift
  docker exec -e PGPASSWORD=postgres "$container" \
    psql -U postgres -d wayang_timeline -At -v ON_ERROR_STOP=1 "$@"
}

wait_ready "$source_container"
docker exec --user root "$source_container" chown postgres:postgres /wal-archive /base-backup
sql "$source_container" -c "CREATE TABLE timeline_marker (marker TEXT PRIMARY KEY)"
sql "$source_container" -c "INSERT INTO timeline_marker VALUES ('baseline-before-fork')"

# The base backup is deliberately taken before any promotion. Its manifest
# and recovery catalog therefore start on timeline 1.
docker exec -e PGPASSWORD=postgres "$source_container" pg_basebackup \
  -h 127.0.0.1 -U postgres -D /base-backup -Fp -X none -P

sql "$source_container" -c "INSERT INTO timeline_marker VALUES ('parent-timeline-marker')"
parent_wal="$(sql "$source_container" -c "SELECT pg_walfile_name(pg_current_wal_lsn())")"
sql "$source_container" -c "SELECT pg_switch_wal()" >/dev/null
for attempt in $(seq 1 60); do
  archived="$(docker exec "$source_container" sh -c "test -s /wal-archive/$parent_wal && echo t || echo f")"
  [[ "$archived" == "t" ]] && break
  sleep 2
done
if [[ "$archived" != "t" ]]; then
  docker logs "$source_container" >&2 || true
  echo "Parent timeline WAL was not archived" >&2
  exit 1
fi

# Stop the original timeline-1 writer so no concurrent source branch can
# obscure the fork point or race archive names.
docker stop "$source_container" >/dev/null

# Clone the retained base backup, replay all archived timeline-1 WAL, then
# promote. PostgreSQL creates 00000002.history at the fork.
docker run --rm --user root \
  --volume "$backup_volume:/from:ro" --volume "$branch_volume:/to" \
  --entrypoint sh postgres:17 \
  -c "cp -a /from/. /to/ && chown -R postgres:postgres /to && touch /to/recovery.signal && chown postgres:postgres /to/recovery.signal"

docker run --detach --name "$branch_container" \
  --volume "$branch_volume:/var/lib/postgresql/data" \
  --volume "$archive_volume:/wal-archive" \
  postgres:17 \
  -c "restore_command=cp /wal-archive/%f %p" \
  -c archive_mode=on \
  -c archive_timeout=1s \
  -c "archive_command=test ! -f /wal-archive/%f && cp %p /wal-archive/%f"

wait_ready "$branch_container"
in_recovery="$(sql "$branch_container" -c "SELECT pg_is_in_recovery()")"
sql "$branch_container" -c "SELECT 1 FROM timeline_marker WHERE marker='parent-timeline-marker'" >/dev/null

# With recovery.signal (not standby.signal), PostgreSQL can finish archive
# recovery automatically when it reaches the end of the available archive.
# If it remains in recovery, explicitly promote the clone; otherwise it has
# already completed recovery and created its new timeline.
if [[ "$in_recovery" == "t" ]]; then
  docker exec "$branch_container" pg_ctl promote -D /var/lib/postgresql/data -w
  for attempt in $(seq 1 60); do
    in_recovery="$(sql "$branch_container" -c "SELECT pg_is_in_recovery()")"
    [[ "$in_recovery" == "f" ]] && break
    sleep 2
  done
fi
if [[ "$in_recovery" != "f" ]]; then
  docker logs "$branch_container" >&2 || true
  echo "Timeline clone neither completed archive recovery nor promoted" >&2
  exit 1
fi

timeline_id="$(sql "$branch_container" -c "SELECT left(pg_walfile_name(pg_current_wal_lsn()), 8)")"
if [[ "$timeline_id" != "00000002" ]]; then
  echo "Expected promoted timeline 2, got $timeline_id" >&2
  docker logs "$branch_container" >&2 || true
  exit 1
fi

sql "$branch_container" -c "INSERT INTO timeline_marker VALUES ('child-timeline-marker')"
target_time="$(sql "$branch_container" -c "SELECT to_char(clock_timestamp(), 'YYYY-MM-DD HH24:MI:SS.USOF')")"
target_lsn="$(sql "$branch_container" -c "SELECT pg_current_wal_lsn()")"
sql "$branch_container" -c "INSERT INTO timeline_marker VALUES ('after-pitr-target')"
sql "$branch_container" -c "SELECT pg_switch_wal()" >/dev/null
sql "$branch_container" -c "INSERT INTO timeline_marker VALUES ('after-target-segment')"
child_wal="$(sql "$branch_container" -c "SELECT pg_walfile_name(pg_current_wal_lsn())")"
sql "$branch_container" -c "SELECT pg_switch_wal()" >/dev/null

for attempt in $(seq 1 90); do
  history_ready="$(docker exec "$branch_container" sh -c 'test -s /wal-archive/00000002.history && echo t || echo f')"
  child_archived="$(docker exec "$branch_container" sh -c "test -s /wal-archive/$child_wal && echo t || echo f")"
  [[ "$history_ready" == "t" && "$child_archived" == "t" ]] && break
  sleep 2
done
if [[ "$history_ready" != "t" || "$child_archived" != "t" ]]; then
  docker logs "$branch_container" >&2 || true
  echo "Timeline history or child WAL was not archived" >&2
  exit 1
fi

catalog_dir="/tmp/wayang-multitimeline-catalog"
mkdir -p "$catalog_dir/archive"
docker cp "$branch_container:/var/lib/postgresql/data/backup_manifest" "$catalog_dir/backup_manifest"
docker cp "$branch_container:/wal-archive/." "$catalog_dir/archive/"
segment_size="$(docker exec "$branch_container" pg_controldata /var/lib/postgresql/data \
  | awk -F: '/Bytes per WAL segment/ { gsub(/[[:space:]]/, "", $2); print $2 }')"
fork_lsn="$(awk -F '[[:space:]]+' '$1 == 1 { print $2; exit }' "$catalog_dir/archive/00000002.history")"
if [[ -z "$fork_lsn" ]]; then
  echo "Could not read timeline-1 fork LSN from 00000002.history" >&2
  cat "$catalog_dir/archive/00000002.history" >&2
  exit 1
fi

python3 - "$catalog_dir" "$segment_size" "$fork_lsn" "$target_lsn" <<'PY'
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
segment_size = int(sys.argv[2])
fork_lsn, target_lsn = sys.argv[3], sys.argv[4]
manifest = json.loads((root / "backup_manifest").read_text(encoding="utf-8"))
ranges = manifest.get("WAL-Ranges")
if not isinstance(ranges, list) or not ranges:
    raise SystemExit("base backup manifest has no WAL-Ranges")
if {entry["Timeline"] for entry in ranges} != {1}:
    raise SystemExit("expected the retained base backup to be on timeline 1")
def lsn_to_int(value):
    high, low = value.split("/", 1)
    return (int(high, 16) << 32) | int(low, 16)

def int_to_lsn(value):
    return f"{value >> 32:X}/{value & 0xFFFFFFFF:08X}"

start_lsn = min((entry["Start-LSN"] for entry in ranges), key=lsn_to_int)
fork_value = lsn_to_int(fork_lsn)
if fork_value == 0:
    raise SystemExit("timeline fork LSN cannot be zero")
# The parent timeline ends immediately before the switchpoint. Using the
# switchpoint itself would incorrectly require the parent timeline's segment
# at a segment boundary where only the child timeline has that segment.
parent_end_lsn = int_to_lsn(fork_value - 1)
catalog = {
    "wal_segment_size_bytes": segment_size,
    "backups": [{
        "backup_id": "timeline-1-base-backup",
        "retained": True,
        "manifest": "backup_manifest",
        "recovery_wal_ranges": [
            {"Timeline": 1, "Start-LSN": start_lsn, "End-LSN": parent_end_lsn},
            {"Timeline": 2, "Start-LSN": fork_lsn, "End-LSN": target_lsn},
        ],
    }],
}
(root / "catalog.json").write_text(json.dumps(catalog, indent=2), encoding="utf-8")
PY

python3 spikes/cel-nessie/scripts/plan-postgres-wal-retention.py \
  --archive-dir "$catalog_dir/archive" --catalog "$catalog_dir/catalog.json" \
  --older-than-days 0 > /tmp/wayang-multitimeline-plan.json
grep -q '"00000002.history"' /tmp/wayang-multitimeline-plan.json
grep -q '"00000002' /tmp/wayang-multitimeline-plan.json

# Missing timeline history is a hard failure even when the WAL segments remain.
mkdir -p /tmp/wayang-multitimeline-no-history
cp -a "$catalog_dir/archive/." /tmp/wayang-multitimeline-no-history/
rm /tmp/wayang-multitimeline-no-history/00000002.history
if python3 spikes/cel-nessie/scripts/plan-postgres-wal-retention.py \
  --archive-dir /tmp/wayang-multitimeline-no-history \
  --catalog "$catalog_dir/catalog.json" --older-than-days 0 \
  > /tmp/wayang-multitimeline-no-history.json \
  2> /tmp/wayang-multitimeline-no-history.log; then
  echo "Planner unexpectedly accepted missing timeline history" >&2
  exit 1
fi
grep -q "missing timeline history file 00000002.history" /tmp/wayang-multitimeline-no-history.log

python3 spikes/cel-nessie/scripts/simulate-postgres-wal-retention.py \
  --archive-dir "$catalog_dir/archive" \
  --plan-json /tmp/wayang-multitimeline-plan.json \
  --destination /tmp/wayang-multitimeline-simulated-wal \
  > /tmp/wayang-multitimeline-simulation.json

docker run --rm \
  --volume "$backup_volume:/base-backup:ro" \
  --volume /tmp/wayang-multitimeline-simulated-wal:/simulated-wal:ro \
  postgres:17 pg_verifybackup --wal-directory=/simulated-wal /base-backup \
  > /tmp/wayang-multitimeline-pg-verifybackup.log 2>&1

# A physical restore of the original timeline-1 backup must follow the
# timeline-2 history and replay child WAL to target_time.
docker run --rm --user root --entrypoint sh \
  --volume "$backup_volume:/from:ro" --volume "$restore_volume:/to" postgres:17 \
  -c "cp -a /from/. /to/ && touch /to/recovery.signal && chown -R postgres:postgres /to"
docker run --detach --name "$restore_container" \
  --volume "$restore_volume:/var/lib/postgresql/data" \
  --volume /tmp/wayang-multitimeline-simulated-wal:/simulated-wal:ro \
  postgres:17 \
  -c "restore_command=cp /simulated-wal/%f %p" \
  -c "recovery_target_time=$target_time" \
  -c recovery_target_timeline=2 \
  -c recovery_target_inclusive=off \
  -c recovery_target_action=promote

for attempt in $(seq 1 90); do
  if docker exec "$restore_container" pg_isready -U postgres -d wayang_timeline >/dev/null 2>&1; then
    in_recovery="$(sql "$restore_container" -c "SELECT pg_is_in_recovery()" 2>/dev/null || echo t)"
    [[ "$in_recovery" == "f" ]] && break
  fi
  sleep 2
done
if [[ "${in_recovery:-t}" != "f" ]]; then
  docker logs "$restore_container" >&2 || true
  echo "PITR did not promote onto timeline 2" >&2
  exit 1
fi

assert_marker() {
  local marker="$1"
  local expected="$2"
  actual="$(sql "$restore_container" -c "SELECT count(*) FROM timeline_marker WHERE marker='$marker'")"
  if [[ "$actual" != "$expected" ]]; then
    echo "marker=$marker expected=$expected actual=$actual" >&2
    docker logs "$restore_container" >&2 || true
    exit 1
  fi
}
assert_marker baseline-before-fork 1
assert_marker parent-timeline-marker 1
assert_marker child-timeline-marker 1
assert_marker after-pitr-target 0
assert_marker after-target-segment 0

{
  echo "promoted_timeline=$timeline_id"
  echo "fork_lsn=$fork_lsn"
  echo "target_lsn=$target_lsn"
  echo "target_time=$target_time"
  echo "timeline_history=00000002.history protected"
  echo "cross_timeline_restore=success"
} | tee /tmp/wayang-multitimeline-recovery.txt

echo "MULTITIMELINE_WAL_QUALIFICATION_SUCCESS"
