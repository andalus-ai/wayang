#!/usr/bin/env bash
set -euo pipefail

# Qualify two real PostgreSQL promotions and restore the original timeline-1
# base backup to a target on timeline 3 using only a simulated retained archive.
prefix="wayang-successive-timeline-${GITHUB_RUN_ID:-local}"
source_container="${prefix}-source"
timeline2_container="${prefix}-timeline2"
timeline3_container="${prefix}-timeline3"
restore_container="${prefix}-restore"
source_volume="${prefix}-source-data"
archive_volume="${prefix}-archive"
backup1_volume="${prefix}-backup1"
backup2_volume="${prefix}-backup2"
timeline2_volume="${prefix}-timeline2-data"
timeline3_volume="${prefix}-timeline3-data"
restore_volume="${prefix}-restore-data"
volumes=("$source_volume" "$archive_volume" "$backup1_volume" "$backup2_volume" "$timeline2_volume" "$timeline3_volume" "$restore_volume")

cleanup() {
  docker rm -f "$restore_container" "$timeline3_container" "$timeline2_container" "$source_container" >/dev/null 2>&1 || true
  docker volume rm "${volumes[@]}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for volume in "${volumes[@]}"; do docker volume create "$volume" >/dev/null; done

wait_ready() {
  local container="$1"
  for attempt in $(seq 1 90); do
    if docker exec "$container" pg_isready -U postgres -d wayang_timeline >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  docker logs "$container" >&2 || true
  return 1
}

sql() {
  local container="$1"; shift
  docker exec -e PGPASSWORD=postgres "$container" psql -U postgres -d wayang_timeline -At -v ON_ERROR_STOP=1 "$@"
}

wait_archive() {
  local container="$1" filename="$2"
  for attempt in $(seq 1 90); do
    if docker exec "$container" sh -c "test -s /wal-archive/$filename"; then return 0; fi
    sleep 2
  done
  docker logs "$container" >&2 || true
  echo "WAL archive file not produced: $filename" >&2
  return 1
}

start_archiver() {
  local container="$1" volume="$2"
  docker run --detach --name "$container" \
    --volume "$volume:/var/lib/postgresql/data" \
    --volume "$archive_volume:/wal-archive" \
    --volume "$backup2_volume:/backup2" \
    postgres:17 \
    -c "restore_command=cp /wal-archive/%f %p" \
    -c archive_mode=on \
    -c archive_timeout=1s \
    -c "archive_command=test ! -f /wal-archive/%f && cp %p /wal-archive/%f"
}

# Initialize timeline 1 and take the retained original base backup.
docker run --detach --name "$source_container" \
  --volume "$source_volume:/var/lib/postgresql/data" \
  --volume "$archive_volume:/wal-archive" \
  --volume "$backup1_volume:/base-backup" \
  --env POSTGRES_DB=wayang_timeline \
  --env POSTGRES_USER=postgres \
  --env POSTGRES_PASSWORD=postgres \
  postgres:17 \
  -c wal_level=replica -c archive_mode=on -c archive_timeout=1s \
  -c "archive_command=test ! -f /wal-archive/%f && cp %p /wal-archive/%f"
wait_ready "$source_container"
docker exec --user root "$source_container" chown postgres:postgres /wal-archive /base-backup
sql "$source_container" -c "CREATE TABLE timeline_marker (marker TEXT PRIMARY KEY)"
sql "$source_container" -c "INSERT INTO timeline_marker VALUES ('baseline-before-forks')"
docker exec -e PGPASSWORD=postgres "$source_container" pg_basebackup -h 127.0.0.1 -U postgres -D /base-backup -Fp -X none -P
sql "$source_container" -c "INSERT INTO timeline_marker VALUES ('timeline1-marker')"
timeline1_wal="$(sql "$source_container" -c "SELECT pg_walfile_name(pg_current_wal_lsn())")"
sql "$source_container" -c "SELECT pg_switch_wal()" >/dev/null
wait_archive "$source_container" "$timeline1_wal"
docker stop "$source_container" >/dev/null

# First fork: recover the original backup and promote to timeline 2.
docker run --rm --user root --volume "$backup1_volume:/from:ro" --volume "$timeline2_volume:/to" --entrypoint sh postgres:17 \
  -c "cp -a /from/. /to/ && chown -R postgres:postgres /to && touch /to/recovery.signal && chown postgres:postgres /to/recovery.signal"
start_archiver "$timeline2_container" "$timeline2_volume"
wait_ready "$timeline2_container"
if [[ "$(sql "$timeline2_container" -c "SELECT pg_is_in_recovery()")" == "t" ]]; then
  docker exec "$timeline2_container" pg_ctl promote -D /var/lib/postgresql/data -w
fi
for attempt in $(seq 1 60); do
  in_recovery="$(sql "$timeline2_container" -c "SELECT pg_is_in_recovery()")"
  [[ "$in_recovery" == "f" ]] && break
  sleep 2
done
timeline2_id="$(sql "$timeline2_container" -c "SELECT left(pg_walfile_name(pg_current_wal_lsn()), 8)")"
test "$timeline2_id" = "00000002"
sql "$timeline2_container" -c "INSERT INTO timeline_marker VALUES ('timeline2-marker')"
fork1_wal="$(sql "$timeline2_container" -c "SELECT pg_walfile_name(pg_current_wal_lsn())")"
sql "$timeline2_container" -c "SELECT pg_switch_wal()" >/dev/null
wait_archive "$timeline2_container" "$fork1_wal"

# Take a second physical backup from timeline 2. Streaming WAL into this
# backup makes it independently consistent; archive recovery still follows
# timeline 2 before the second promotion.
docker exec --user root "$timeline2_container" chown postgres:postgres /backup2 2>/dev/null || true
docker run --rm --user root --volume "$backup2_volume:/backup2" --entrypoint sh postgres:17 -c "chown postgres:postgres /backup2"
docker exec -e PGPASSWORD=postgres "$timeline2_container" pg_basebackup -h 127.0.0.1 -U postgres -D /backup2 -Fp -X stream -P
sql "$timeline2_container" -c "INSERT INTO timeline_marker VALUES ('timeline2-before-second-fork')"
timeline2_last_wal="$(sql "$timeline2_container" -c "SELECT pg_walfile_name(pg_current_wal_lsn())")"
sql "$timeline2_container" -c "SELECT pg_switch_wal()" >/dev/null
wait_archive "$timeline2_container" "$timeline2_last_wal"
docker stop "$timeline2_container" >/dev/null

# Second fork: start the timeline-2 backup as a standby so archive recovery
# reaches the end of available timeline-2 WAL and waits; promote to timeline 3.
docker run --rm --user root --volume "$backup2_volume:/from:ro" --volume "$timeline3_volume:/to" --entrypoint sh postgres:17 \
  -c "cp -a /from/. /to/ && chown -R postgres:postgres /to && touch /to/standby.signal && chown postgres:postgres /to/standby.signal"
start_archiver "$timeline3_container" "$timeline3_volume"
wait_ready "$timeline3_container"
if [[ "$(sql "$timeline3_container" -c "SELECT pg_is_in_recovery()")" != "t" ]]; then
  docker logs "$timeline3_container" >&2 || true
  echo "Timeline-2 clone did not enter standby recovery" >&2
  exit 1
fi
sql "$timeline3_container" -c "SELECT 1 FROM timeline_marker WHERE marker='timeline2-marker'" >/dev/null
docker exec "$timeline3_container" pg_ctl promote -D /var/lib/postgresql/data -w
for attempt in $(seq 1 60); do
  in_recovery="$(sql "$timeline3_container" -c "SELECT pg_is_in_recovery()")"
  [[ "$in_recovery" == "f" ]] && break
  sleep 2
done
timeline3_id="$(sql "$timeline3_container" -c "SELECT left(pg_walfile_name(pg_current_wal_lsn()), 8)")"
if [[ "$timeline3_id" != "00000003" ]]; then
  docker logs "$timeline3_container" >&2 || true
  echo "Expected timeline 3, got $timeline3_id" >&2
  exit 1
fi

sql "$timeline3_container" -c "INSERT INTO timeline_marker VALUES ('timeline3-marker')"
target_time="$(sql "$timeline3_container" -c "SELECT to_char(clock_timestamp(), 'YYYY-MM-DD HH24:MI:SS.USOF')")"
target_lsn="$(sql "$timeline3_container" -c "SELECT pg_current_wal_lsn()")"
sql "$timeline3_container" -c "INSERT INTO timeline_marker VALUES ('after-timeline3-target')"
sql "$timeline3_container" -c "SELECT pg_switch_wal()" >/dev/null
sql "$timeline3_container" -c "INSERT INTO timeline_marker VALUES ('after-target-segment')"
timeline3_last_wal="$(sql "$timeline3_container" -c "SELECT pg_walfile_name(pg_current_wal_lsn())")"
sql "$timeline3_container" -c "SELECT pg_switch_wal()" >/dev/null
wait_archive "$timeline3_container" "$timeline3_last_wal"

# Build recovery ranges from the actual PostgreSQL-generated history files.
catalog_dir="/tmp/wayang-successive-timeline-catalog"
mkdir -p "$catalog_dir/archive"
docker run --rm --volume "$backup1_volume:/base-backup:ro" --entrypoint cat postgres:17 /base-backup/backup_manifest > "$catalog_dir/backup_manifest"
docker cp "$timeline3_container:/wal-archive/." "$catalog_dir/archive/"
segment_size="$(docker exec "$timeline3_container" pg_controldata /var/lib/postgresql/data | awk -F: '/Bytes per WAL segment/ { gsub(/[[:space:]]/, "", $2); print $2 }')"
fork1_lsn="$(awk '$1 == 1 { print $2; exit }' "$catalog_dir/archive/00000002.history")"
fork2_lsn="$(awk '$1 == 2 { print $2; exit }' "$catalog_dir/archive/00000003.history")"
if [[ -z "$fork1_lsn" || -z "$fork2_lsn" ]]; then
  echo "Missing actual PostgreSQL fork points in timeline history files" >&2
  cat "$catalog_dir/archive/00000002.history" "$catalog_dir/archive/00000003.history" >&2
  exit 1
fi

python3 - "$catalog_dir" "$segment_size" "$fork1_lsn" "$fork2_lsn" "$target_lsn" <<'PY'
import json, sys
from pathlib import Path
root = Path(sys.argv[1])
segment_size = int(sys.argv[2])
fork1, fork2, target = sys.argv[3:]
manifest = json.loads((root / "backup_manifest").read_text(encoding="utf-8"))
ranges = manifest.get("WAL-Ranges")
if not isinstance(ranges, list) or not ranges or {r["Timeline"] for r in ranges} != {1}:
    raise SystemExit("original backup manifest must contain timeline-1 WAL ranges")
def number(lsn):
    high, low = lsn.split("/", 1)
    return (int(high, 16) << 32) | int(low, 16)
def lsn(value):
    return f"{value >> 32:X}/{value & 0xffffffff:08X}"
start = min((r["Start-LSN"] for r in ranges), key=number)
f1, f2 = number(fork1), number(fork2)
if f1 == 0 or f2 <= f1:
    raise SystemExit("successive fork points are not strictly increasing")
catalog = {
    "wal_segment_size_bytes": segment_size,
    "backups": [{
        "backup_id": "timeline-1-original-base-backup",
        "retained": True,
        "manifest": "backup_manifest",
        "recovery_wal_ranges": [
            {"Timeline": 1, "Start-LSN": start, "End-LSN": lsn(f1 - 1)},
            {"Timeline": 2, "Start-LSN": fork1, "End-LSN": lsn(f2 - 1)},
            {"Timeline": 3, "Start-LSN": fork2, "End-LSN": target},
        ],
    }],
}
(root / "catalog.json").write_text(json.dumps(catalog, indent=2), encoding="utf-8")
PY

python3 spikes/cel-nessie/scripts/plan-postgres-wal-retention.py \
  --archive-dir "$catalog_dir/archive" --catalog "$catalog_dir/catalog.json" --older-than-days 0 \
  > /tmp/wayang-successive-timeline-plan.json
grep -q '"00000002.history"' /tmp/wayang-successive-timeline-plan.json
grep -q '"00000003.history"' /tmp/wayang-successive-timeline-plan.json
python3 spikes/cel-nessie/scripts/simulate-postgres-wal-retention.py \
  --archive-dir "$catalog_dir/archive" --plan-json /tmp/wayang-successive-timeline-plan.json \
  --destination /tmp/wayang-successive-timeline-simulated-wal \
  > /tmp/wayang-successive-timeline-simulation.json

# Negative control: timeline-3 ancestry is mandatory even if all WAL segments
# remain present in the disposable archive copy.
mkdir -p /tmp/wayang-successive-timeline-no-history
cp -a "$catalog_dir/archive/." /tmp/wayang-successive-timeline-no-history/
rm /tmp/wayang-successive-timeline-no-history/00000003.history
if python3 spikes/cel-nessie/scripts/plan-postgres-wal-retention.py \
  --archive-dir /tmp/wayang-successive-timeline-no-history --catalog "$catalog_dir/catalog.json" --older-than-days 0 \
  > /tmp/wayang-successive-timeline-no-history.json 2> /tmp/wayang-successive-timeline-no-history.log; then
  echo "Planner unexpectedly accepted missing timeline-3 history" >&2
  exit 1
fi
grep -q "missing timeline history file 00000003.history" /tmp/wayang-successive-timeline-no-history.log

docker run --rm --volume "$backup1_volume:/base-backup:ro" \
  --volume /tmp/wayang-successive-timeline-simulated-wal:/simulated-wal:ro \
  postgres:17 pg_verifybackup --wal-directory=/simulated-wal /base-backup \
  > /tmp/wayang-successive-timeline-pg-verifybackup.log 2>&1

# Restore the original timeline-1 backup and follow PostgreSQL's full
# timeline-1 -> timeline-2 -> timeline-3 ancestry to the captured target.
docker run --rm --user root --entrypoint sh --volume "$backup1_volume:/from:ro" --volume "$restore_volume:/to" postgres:17 \
  -c "cp -a /from/. /to/ && touch /to/recovery.signal && chown -R postgres:postgres /to"
docker run --detach --name "$restore_container" \
  --volume "$restore_volume:/var/lib/postgresql/data" \
  --volume /tmp/wayang-successive-timeline-simulated-wal:/simulated-wal:ro \
  postgres:17 \
  -c "restore_command=cp /simulated-wal/%f %p" \
  -c "recovery_target_time=$target_time" \
  -c recovery_target_timeline=3 -c recovery_target_inclusive=off -c recovery_target_action=promote

for attempt in $(seq 1 120); do
  if docker exec "$restore_container" pg_isready -U postgres -d wayang_timeline >/dev/null 2>&1; then
    in_recovery="$(sql "$restore_container" -c "SELECT pg_is_in_recovery()" 2>/dev/null || echo t)"
    [[ "$in_recovery" == "f" ]] && break
  fi
  sleep 2
done
if [[ "${in_recovery:-t}" != "f" ]]; then
  docker logs "$restore_container" >&2 || true
  echo "PITR did not complete on timeline 3" >&2
  exit 1
fi
restored_timeline="$(sql "$restore_container" -c "SELECT left(pg_walfile_name(pg_current_wal_lsn()), 8)")"
test "$restored_timeline" = "00000003"

assert_marker() {
  local marker="$1" expected="$2"
  actual="$(sql "$restore_container" -c "SELECT count(*) FROM timeline_marker WHERE marker='$marker'")"
  if [[ "$actual" != "$expected" ]]; then
    echo "marker=$marker expected=$expected actual=$actual" >&2
    docker logs "$restore_container" >&2 || true
    exit 1
  fi
}
assert_marker baseline-before-forks 1
assert_marker timeline1-marker 1
assert_marker timeline2-marker 1
assert_marker timeline2-before-second-fork 1
assert_marker timeline3-marker 1
assert_marker after-timeline3-target 0
assert_marker after-target-segment 0

{
  echo "timeline2=$timeline2_id"
  echo "timeline3=$timeline3_id"
  echo "fork1_lsn=$fork1_lsn"
  echo "fork2_lsn=$fork2_lsn"
  echo "target_lsn=$target_lsn"
  echo "target_time=$target_time"
  echo "history_files=00000002.history,00000003.history protected"
  echo "two_successive_promotions=success"
  echo "timeline3_pitr_restore=success"
} | tee /tmp/wayang-successive-timeline-recovery.txt

echo "SUCCESSIVE_TIMELINE_WAL_QUALIFICATION_SUCCESS"
