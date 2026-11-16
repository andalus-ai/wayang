#!/usr/bin/env python3
"""Build a fail-closed, dry-run PostgreSQL WAL archive retention plan.

The catalog lists retained base-backup manifests. No archive file is ever
removed by this program; it only reports eligible candidates for review.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

WAL_NAME = re.compile(r"^[0-9A-F]{24}$")
HISTORY_NAME = re.compile(r"^[0-9A-F]{8}\.history$")
UINT32 = 1 << 32
MAX_RANGE_SEGMENTS = 1_000_000


class CatalogError(ValueError):
    """The backup catalog or archive cannot be trusted for retention planning."""


@dataclass(frozen=True)
class RetentionPlan:
    retained_backup_ids: tuple[str, ...]
    archive_files: tuple[str, ...]
    required_wal_files: tuple[str, ...]
    protected_history_files: tuple[str, ...]
    eligible_wal_files: tuple[str, ...]
    protected_other_files: tuple[str, ...]
    missing_required_files: tuple[str, ...]
    catalog_snapshot_sha256: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "retained_backup_ids": list(self.retained_backup_ids),
            "archive_file_count": len(self.archive_files),
            "required_wal_files": list(self.required_wal_files),
            "protected_history_files": list(self.protected_history_files),
            "eligible_wal_files": list(self.eligible_wal_files),
            "protected_other_files": list(self.protected_other_files),
            "missing_required_files": list(self.missing_required_files),
            "catalog_snapshot_sha256": self.catalog_snapshot_sha256,
            "deletion_performed": False,
        }


def parse_lsn(value: str) -> int:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9A-Fa-f]+/[0-9A-Fa-f]+", value):
        raise CatalogError(f"invalid WAL LSN: {value!r}")
    high, low = value.split("/", 1)
    return (int(high, 16) << 32) | int(low, 16)


def wal_filename(timeline: int, lsn: int, segment_size: int) -> str:
    if timeline < 1 or timeline > 0xFFFFFFFF:
        raise CatalogError("WAL timeline is outside PostgreSQL filename range")
    segments_per_xlogid = UINT32 // segment_size
    segment_number = lsn // segment_size
    log = segment_number // segments_per_xlogid
    segment = segment_number % segments_per_xlogid
    if log > 0xFFFFFFFF:
        raise CatalogError("WAL segment number is outside PostgreSQL filename range")
    return f"{timeline:08X}{log:08X}{segment:08X}"


def expand_wal_range(timeline: int, start_lsn: str, end_lsn: str,
                     segment_size: int) -> set[str]:
    start = parse_lsn(start_lsn)
    end = parse_lsn(end_lsn)
    if end < start:
        raise CatalogError("WAL range end precedes start")
    first = start // segment_size
    last = end // segment_size
    if last - first + 1 > MAX_RANGE_SEGMENTS:
        raise CatalogError("WAL range exceeds safety expansion limit")
    return {
        wal_filename(timeline, segment * segment_size, segment_size)
        for segment in range(first, last + 1)
    }


def _load_catalog(catalog_path: Path) -> tuple[list[tuple[str, Path, bytes, list[dict[str, Any]] | None]], int, str]:
    try:
        catalog_bytes = catalog_path.read_bytes()
        catalog = json.loads(catalog_bytes.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise CatalogError(f"cannot read backup catalog: {exc}") from exc
    if not isinstance(catalog, dict) or not isinstance(catalog.get("backups"), list):
        raise CatalogError("catalog must contain a backups array")

    segment_size = catalog.get("wal_segment_size_bytes")
    if not isinstance(segment_size, int) or isinstance(segment_size, bool):
        raise CatalogError("catalog wal_segment_size_bytes must be an integer")
    if segment_size <= 0 or segment_size & (segment_size - 1) or UINT32 % segment_size:
        raise CatalogError("WAL segment size must be a power of two dividing 2^32")

    catalog_base = catalog_path.resolve().parent
    retained: list[tuple[str, Path, bytes, list[dict[str, Any]] | None]] = []
    seen: set[str] = set()
    digest = hashlib.sha256()
    digest.update(b"wayang-postgres-wal-catalog-snapshot-v1|")
    digest.update(len(catalog_bytes).to_bytes(8, "big"))
    digest.update(catalog_bytes)

    for item in catalog["backups"]:
        if not isinstance(item, dict):
            raise CatalogError("each backup catalog entry must be an object")
        backup_id = item.get("backup_id")
        if not isinstance(backup_id, str) or not backup_id.strip():
            raise CatalogError("backup_id must be a non-empty string")
        if backup_id in seen:
            raise CatalogError(f"duplicate backup_id: {backup_id}")
        seen.add(backup_id)

        keep = item.get("retained")
        if not isinstance(keep, bool):
            raise CatalogError(f"backup {backup_id}: retained must be boolean")
        if not keep:
            continue

        recovery_ranges = item.get("recovery_wal_ranges")
        if recovery_ranges is not None and (not isinstance(recovery_ranges, list) or not recovery_ranges):
            raise CatalogError(f"backup {backup_id}: recovery_wal_ranges must be a non-empty array")

        manifest = item.get("manifest")
        if not isinstance(manifest, str) or not manifest.strip():
            raise CatalogError(f"backup {backup_id}: manifest path is required")
        relative_manifest = Path(manifest)
        if relative_manifest.is_absolute():
            raise CatalogError(f"backup {backup_id}: manifest path must be relative")

        candidate = catalog_base / relative_manifest
        current = catalog_base
        for part in relative_manifest.parts:
            if part in ("", "."):
                continue
            if part == "..":
                raise CatalogError(f"backup {backup_id}: manifest path escapes catalog directory")
            current = current / part
            if current.is_symlink():
                raise CatalogError(f"backup {backup_id}: manifest path contains a symbolic link")

        try:
            manifest_path = candidate.resolve(strict=True)
            manifest_path.relative_to(catalog_base)
            manifest_bytes = manifest_path.read_bytes()
        except (OSError, ValueError) as exc:
            raise CatalogError(
                f"backup {backup_id}: manifest path is unavailable or outside catalog directory"
            ) from exc

        backup_id_bytes = backup_id.encode("utf-8")
        digest.update(len(backup_id_bytes).to_bytes(4, "big"))
        digest.update(backup_id_bytes)
        digest.update(len(manifest_bytes).to_bytes(8, "big"))
        digest.update(manifest_bytes)
        retained.append((backup_id, manifest_path, manifest_bytes, recovery_ranges))

    if not retained:
        raise CatalogError("catalog has no retained backups; refusing to plan deletion")
    return retained, segment_size, digest.hexdigest()


def parse_timeline_history(path: Path, timeline: int) -> tuple[tuple[int, int], ...]:
    """Parse PostgreSQL .history ancestry as (parent timeline, fork LSN)."""
    try:
        content = path.read_text(encoding="ascii")
    except (OSError, UnicodeDecodeError) as exc:
        raise CatalogError(f"cannot read timeline history {path.name}: {exc}") from exc
    ancestry: list[tuple[int, int]] = []
    previous_timeline = 0
    previous_lsn = -1
    for line_number, raw_line in enumerate(content.splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split(None, 2)
        if len(fields) != 3:
            raise CatalogError(f"malformed timeline history {path.name} at line {line_number}")
        try:
            parent = int(fields[0], 10)
            fork_lsn = parse_lsn(fields[1])
        except (ValueError, CatalogError) as exc:
            raise CatalogError(f"malformed timeline history {path.name} at line {line_number}") from exc
        if parent < 1 or parent >= timeline or parent <= previous_timeline:
            raise CatalogError(f"invalid ancestry order in timeline history {path.name} at line {line_number}")
        if fork_lsn < previous_lsn:
            raise CatalogError(f"fork LSNs move backwards in timeline history {path.name}")
        if not fields[2].strip():
            raise CatalogError(f"missing fork reason in timeline history {path.name} at line {line_number}")
        ancestry.append((parent, fork_lsn))
        previous_timeline = parent
        previous_lsn = fork_lsn
    if not ancestry:
        raise CatalogError(f"timeline history {path.name} has no valid parent ancestry")
    return tuple(ancestry)

def validate_recovery_timeline_chain(backup_id: str, ranges: list[dict[str, Any]], histories: dict[int, tuple[tuple[int, int], ...]]) -> None:
    """Require each ancestry fork to be covered on both sides by recovery ranges."""
    bounds: dict[int, tuple[int, int]] = {}
    for wal_range in ranges:
        timeline = wal_range["Timeline"]
        start = parse_lsn(wal_range["Start-LSN"])
        end = parse_lsn(wal_range["End-LSN"])
        previous = bounds.get(timeline)
        bounds[timeline] = (min(start, previous[0]) if previous else start, max(end, previous[1]) if previous else end)
    if len(bounds) < 2:
        return
    for child_timeline in sorted(bounds):
        if child_timeline <= 1:
            continue
        ancestry = histories.get(child_timeline)
        if ancestry is None:
            raise CatalogError(f"backup {backup_id}: missing parsed ancestry for timeline {child_timeline}")
        for index, (parent_timeline, fork_lsn) in enumerate(ancestry):
            child = ancestry[index + 1][0] if index + 1 < len(ancestry) else child_timeline
            parent_bounds = bounds.get(parent_timeline)
            child_bounds = bounds.get(child)
            if parent_bounds is None or child_bounds is None:
                missing = parent_timeline if parent_bounds is None else child
                raise CatalogError(f"backup {backup_id}: recovery ranges omit timeline {missing}")
            if parent_bounds[1] < fork_lsn - 1:
                raise CatalogError(f"backup {backup_id}: timeline {parent_timeline} recovery range does not reach byte before fork {fork_lsn:X}")
            if child_bounds[0] > fork_lsn or child_bounds[1] < fork_lsn:
                raise CatalogError(f"backup {backup_id}: timeline {child} recovery range does not cover fork point {fork_lsn:X}")

def build_plan(archive_dir: Path, catalog_path: Path, cutoff_epoch: float) -> RetentionPlan:
    retained, segment_size, snapshot_sha256 = _load_catalog(catalog_path)
    if not archive_dir.is_dir():
        raise CatalogError("WAL archive directory does not exist")

    required: set[str] = set()
    required_timelines: set[int] = set()
    for backup_id, _manifest_path, manifest_bytes, recovery_ranges in retained:
        try:
            manifest = json.loads(manifest_bytes.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise CatalogError(f"backup {backup_id}: cannot read manifest: {exc}") from exc

        def include_ranges(ranges: Any, label: str) -> None:
            if not isinstance(ranges, list) or not ranges:
                raise CatalogError(f"backup {backup_id}: {label} must be a non-empty array")
            for wal_range in ranges:
                if not isinstance(wal_range, dict):
                    raise CatalogError(f"backup {backup_id}: malformed {label} entry")
                timeline = wal_range.get("Timeline")
                if not isinstance(timeline, int) or isinstance(timeline, bool) or timeline < 1:
                    raise CatalogError(f"backup {backup_id}: invalid WAL timeline in {label}")
                required_timelines.add(timeline)
                required.update(expand_wal_range(
                    timeline, wal_range.get("Start-LSN"), wal_range.get("End-LSN"), segment_size
                ))

        manifest_ranges = manifest.get("WAL-Ranges") if isinstance(manifest, dict) else None
        include_ranges(manifest_ranges, "manifest WAL-Ranges")
        if recovery_ranges is not None:
            include_ranges(recovery_ranges, "recovery_wal_ranges")

    files: dict[str, Path] = {}
    for path in archive_dir.iterdir():
        if path.is_symlink():
            raise CatalogError(f"archive contains a symbolic link: {path.name}")
        if path.is_dir():
            raise CatalogError(f"archive must be flat; found directory: {path.name}")
        if path.is_file():
            files[path.name] = path

    history = {name for name in files if HISTORY_NAME.fullmatch(name)}
    parsed_histories: dict[int, tuple[tuple[int, int], ...]] = {}
    for timeline in required_timelines:
        if timeline > 1:
            history_name = f"{timeline:08X}.history"
            if history_name not in files:
                raise CatalogError(
                    f"missing timeline history file {history_name}; refusing to plan deletion"
                )
            parsed_histories[timeline] = parse_timeline_history(files[history_name], timeline)

    for backup_id, _manifest_path, _manifest_bytes, recovery_ranges in retained:
        if recovery_ranges is not None:
            distinct_timelines = {item.get("Timeline") for item in recovery_ranges if isinstance(item, dict)}
            if len(distinct_timelines) > 1:
                validate_recovery_timeline_chain(backup_id, recovery_ranges, parsed_histories)

    missing = sorted(required - files.keys())
    if missing:
        raise CatalogError("archive is missing required WAL files: " + ", ".join(missing))

    eligible: list[str] = []
    other: list[str] = []
    for name, path in files.items():
        if name in required or name in history:
            continue
        if WAL_NAME.fullmatch(name) and path.stat().st_mtime <= cutoff_epoch:
            eligible.append(name)
        else:
            other.append(name)

    return RetentionPlan(
        retained_backup_ids=tuple(sorted(backup_id for backup_id, _, _, _ in retained)),
        archive_files=tuple(sorted(files)),
        required_wal_files=tuple(sorted(required)),
        protected_history_files=tuple(sorted(history)),
        eligible_wal_files=tuple(sorted(eligible)),
        protected_other_files=tuple(sorted(other)),
        missing_required_files=tuple(missing),
        catalog_snapshot_sha256=snapshot_sha256,
    )


def require_snapshot_match(plan: RetentionPlan, expected_sha256: str) -> None:
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
        raise CatalogError("expected snapshot fingerprint must be a lowercase SHA-256 hex digest")
    if plan.catalog_snapshot_sha256 != expected_sha256:
        raise CatalogError("catalog snapshot changed since planning; re-plan and re-approve")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive-dir", required=True, type=Path)
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--older-than-days", required=True, type=float)
    parser.add_argument(
        "--expected-snapshot-sha256",
        help="refuse if the current catalog/manifests differ from this planned snapshot",
    )
    args = parser.parse_args()
    if args.older_than_days < 0:
        parser.error("--older-than-days must not be negative")
    cutoff = time.time() - args.older_than_days * 24 * 60 * 60
    try:
        plan = build_plan(args.archive_dir, args.catalog, cutoff)
        if args.expected_snapshot_sha256:
            require_snapshot_match(plan, args.expected_snapshot_sha256)
    except CatalogError as exc:
        print(f"RETENTION_PLAN_REFUSED: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(plan.to_dict(), indent=2))
    print("DRY_RUN_ONLY: no files were deleted", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
