#!/usr/bin/env bash
set -euo pipefail

# Create two independent live PostgreSQL promotions from the same timeline-1
# physical backup. The shared archive makes PostgreSQL allocate sibling
# timelines 2 and 3, while each branch has independent data and WAL.
prefix="wayang-sibling-forks-${GITHUB_RUN_ID:-local}"
source="${prefix}-source"
branch_a="${prefix}-branch-a"
branch_b="${prefix}-branch-b"
volumes=("${prefix}-source-data" "${prefix}-archive" "${prefix}-base-backup" "${prefix}-branch-a-data" "${prefix}-branch-b-data")
source_vol="${volumes[0]}"
archive_vol="${volumes[1]}"
backup_vol="${volumes[2]}"
a_vol="${volumes[3]}"
b_vol="${volumes[4]}"

cleanup() {
  docker rm -f "$branch_b" "$branch_a" "$source" >/dev/null 2>&1 || true
  docker volume rm "${volumes[@]}" >/dev/null 2>&1 || true
}
trap cleanup EXIT
for volume in "${volumes[@]}"; do docker volume create "$volume" >/dev/null; done

wait_ready() {
  local c="$1"
  for attempt in $(seq 1 90); do
    docker exec "$c" pg_isready -U postgres -d wayang_forks >/dev/null 2>&1 && return 0
    sleep 2
  done
  docker logs "$c" >&2 || true
  return 1
}
sql() {
  local c="$1"; shift
  docker exec -e PGPASSWORD=postgres "$c" psql -U postgres -d wayang_forks -At -v ON_ERROR_STOP=1 "$@"
}
wait_archive() {
  local c="$1" f="$2"
  for attempt in $(seq 1 90); do
    docker exec "$c" sh -c "test -s /wal-archive/$f" && return 0
    sleep 2
  done
  docker logs "$c" >&2 || true
  echo "archive file missing: $f" >&2
  return 1
}

docker run -d --name "$source" \
  -v "$source_vol:/var/lib/postgresql/data" -v "$archive_vol:/wal-archive" \
  -v "$backup_vol:/base-backup" \
  -e POSTGRES_DB=wayang_forks -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  postgres:17 -c wal_level=replica -c archive_mode=on -c archive_timeout=1s \
  -c "archive_command=test ! -f /wal-archive/%f && cp %p /wal-archive/%f"
wait_ready "$source"
docker exec --user root "$source" chown postgres:postgres /wal-archive /base-backup
sql "$source" -c "CREATE TABLE fork_marker (marker TEXT PRIMARY KEY)"
sql "$source" -c "INSERT INTO fork_marker VALUES ('common-ancestor')"
docker exec -e PGPASSWORD=postgres "$source" pg_basebackup -h 127.0.0.1 -U postgres -D /base-backup -Fp -X none -P
sql "$source" -c "INSERT INTO fork_marker VALUES ('parent-before-forks')"
parent_wal="$(sql "$source" -c "SELECT pg_walfile_name(pg_current_wal_lsn())")"
sql "$source" -c "SELECT pg_switch_wal()" >/dev/null
wait_archive "$source" "$parent_wal"
docker stop "$source" >/dev/null

clone_base() {
  local target_vol="$1"
  docker run --rm --user root -v "$backup_vol:/from:ro" -v "$target_vol:/to" --entrypoint sh postgres:17 \
    -c "cp -a /from/. /to/ && chown -R postgres:postgres /to && touch /to/recovery.signal && chown postgres:postgres /to/recovery.signal"
}
start_branch() {
  local c="$1" data_vol="$2" target_timeline="$3"
  docker run -d --name "$c" -v "$data_vol:/var/lib/postgresql/data" -v "$archive_vol:/wal-archive" postgres:17 \
    -c "restore_command=cp /wal-archive/%f %p" \
    -c "recovery_target_timeline=$target_timeline" \
    -c archive_mode=on -c archive_timeout=1s \
    -c "archive_command=test ! -f /wal-archive/%f && cp %p /wal-archive/%f"
  wait_ready "$c"
  if [[ "$(sql "$c" -c "SELECT pg_is_in_recovery()")" == "t" ]]; then
    docker exec --user postgres "$c" pg_ctl promote -D /var/lib/postgresql/data -w
  fi
  for attempt in $(seq 1 60); do
    [[ "$(sql "$c" -c "SELECT pg_is_in_recovery()")" == "f" ]] && return 0
    sleep 2
  done
  docker logs "$c" >&2 || true
  echo "branch did not promote: $c" >&2
  return 1
}

# Branch A follows the shared timeline-1 ancestor and creates timeline 2.
clone_base "$a_vol"
start_branch "$branch_a" "$a_vol" latest
a_tli="$(sql "$branch_a" -c "SELECT left(pg_walfile_name(pg_current_wal_lsn()),8)")"
test "$a_tli" = "00000002"
sql "$branch_a" -c "INSERT INTO fork_marker VALUES ('branch-a-only')"
a_wal="$(sql "$branch_a" -c "SELECT pg_walfile_name(pg_current_wal_lsn())")"
sql "$branch_a" -c "SELECT pg_switch_wal()" >/dev/null
wait_archive "$branch_a" "$a_wal"

# Branch B starts from the exact same physical backup but is pinned to TLI 1.
# The already archived 00000002.history makes PostgreSQL allocate TLI 3 when
# this independent branch is promoted.
clone_base "$b_vol"
start_branch "$branch_b" "$b_vol" 1
b_tli="$(sql "$branch_b" -c "SELECT left(pg_walfile_name(pg_current_wal_lsn()),8)")"
if [[ "$b_tli" != "00000003" ]]; then
  docker logs "$branch_b" >&2 || true
  echo "Expected sibling timeline 3, got $b_tli" >&2
  exit 1
fi
sql "$branch_b" -c "INSERT INTO fork_marker VALUES ('branch-b-only')"
b_wal="$(sql "$branch_b" -c "SELECT pg_walfile_name(pg_current_wal_lsn())")"
sql "$branch_b" -c "SELECT pg_switch_wal()" >/dev/null
wait_archive "$branch_b" "$b_wal"

mkdir -p /tmp/wayang-sibling-forks/archive
docker cp "$branch_b:/wal-archive/." /tmp/wayang-sibling-forks/archive/
for history in 00000002.history 00000003.history; do
  test -s "/tmp/wayang-sibling-forks/archive/$history" || { echo "missing $history" >&2; exit 1; }
done
fork_a="$(awk '$1 == 1 {print $2; exit}' /tmp/wayang-sibling-forks/archive/00000002.history)"
fork_b="$(awk '$1 == 1 {print $2; exit}' /tmp/wayang-sibling-forks/archive/00000003.history)"
test -n "$fork_a" && test -n "$fork_b"
test "$fork_a" = "$fork_b" || { echo "sibling branches do not share the same fork point" >&2; exit 1; }

docker run --rm -v "$backup_vol:/base-backup:ro" --entrypoint cat postgres:17 /base-backup/backup_manifest > /tmp/wayang-sibling-forks/backup_manifest
segment_size="$(docker exec "$branch_b" pg_controldata /var/lib/postgresql/data | awk -F: '/Bytes per WAL segment/ {gsub(/[[:space:]]/,"",$2);print $2}')"
python3 - "$segment_size" "$fork_a" <<'PY'
import json,sys
from pathlib import Path
root=Path("/tmp/wayang-sibling-forks")
manifest=json.loads((root/"backup_manifest").read_text())
ranges=manifest.get("WAL-Ranges")
if not ranges or {r["Timeline"] for r in ranges}!={1}:
    raise SystemExit("expected timeline-1 base backup manifest")
def n(s):
    a,b=s.split("/",1); return (int(a,16)<<32)|int(b,16)
def fmt(v): return f"{v>>32:X}/{v&0xffffffff:08X}"
fork=n(sys.argv[2])
if fork==0: raise SystemExit("invalid fork point")
start=min((r["Start-LSN"] for r in ranges),key=n)
# The two sibling fork points must be the same parent switchpoint. The
# retained recovery paths both include parent WAL up to fork-1, then diverge.
catalog={"wal_segment_size_bytes":int(sys.argv[1]),"backups":[
 {"backup_id":"branch-a-retained","retained":True,"manifest":"backup_manifest",
  "recovery_wal_ranges":[{"Timeline":1,"Start-LSN":start,"End-LSN":fmt(fork-1)},
                        {"Timeline":2,"Start-LSN":sys.argv[2],"End-LSN":fmt(fork+0x2000000)}]},
 {"backup_id":"branch-b-retained","retained":True,"manifest":"backup_manifest",
  "recovery_wal_ranges":[{"Timeline":1,"Start-LSN":start,"End-LSN":fmt(fork-1)},
                        {"Timeline":3,"Start-LSN":sys.argv[2],"End-LSN":fmt(fork+0x2000000)}]}
]}
(root/"catalog.json").write_text(json.dumps(catalog,indent=2))
PY

python3 spikes/cel-nessie/scripts/plan-postgres-wal-retention.py \
  --archive-dir /tmp/wayang-sibling-forks/archive --catalog /tmp/wayang-sibling-forks/catalog.json \
  --older-than-days 0 > /tmp/wayang-sibling-forks-plan.json
grep -q '"00000002.history"' /tmp/wayang-sibling-forks-plan.json
grep -q '"00000003.history"' /tmp/wayang-sibling-forks-plan.json
python3 spikes/cel-nessie/scripts/simulate-postgres-wal-retention.py \
  --archive-dir /tmp/wayang-sibling-forks/archive --plan-json /tmp/wayang-sibling-forks-plan.json \
  --destination /tmp/wayang-sibling-forks-simulated > /tmp/wayang-sibling-forks-simulation.json

{
 echo "timeline2=$a_tli"
 echo "timeline3=$b_tli"
 echo "branch_a_fork_lsn=$fork_a"
 echo "branch_b_fork_lsn=$fork_b"
 echo "same_timeline1_ancestor=yes"
 echo "independent_promotions=success"
 echo "planner_union_of_sibling_branches=success"
} | tee /tmp/wayang-sibling-forks-recovery.txt
echo "POSTGRES_SIBLING_FORK_QUALIFICATION_SUCCESS"
