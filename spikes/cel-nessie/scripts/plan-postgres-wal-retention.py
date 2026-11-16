#!/usr/bin/env python3
"""Build a fail-closed, dry-run PostgreSQL WAL archive retention plan.

The catalog lists retained base-backup manifests. No archive file is ever
removed by this program; it only reports eligible candidates for review.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from dataclasses import asdict, dataclass
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

    def to_dict(self) -> dict[str, Any]:
        return {
            "retained_backup_ids": list(self.retained_backup_ids),
            "archive_file_count": len(self.archive_files),
            "required_wal_files": list(self.required_wal_files),
            "protected_history_files": list(self.protected_history_files),
            "eligible_wal_files": list(self.eligible_wal_files),
            "protected_other_files": list(self.protected_other_files),
            "missing_required_files": list(self.missing_required_files),
            "deletion_performed": False,
        }


def parse_lsn(value: str) -> int:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9A-Fa-f]+/[0-9A-Fa-f]+", value):
        raise CatalogError(f"invalid WAL LSN: {value!r}")
    high, low = value.split("/", 1)
    return (int(high, 16) << 32) | int(low, 16)


def wal_filename(timeline: int, lsn: int, segment_size: int) -> str:
    segments_per_xlogid = UINT32 // segment_size
    segment_number = lsn // segment_size
    log = segment_number // segments_per_xlogid
    segment = segment_number % segments_per_xlogid
    if timeline < 1 or timeline > 0xFFFFFFFF or log > 0xFFFFFFFF:
        raise CatalogError("WAL timeline or segment number is outside PostgreSQL filename range")
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


def _load_catalog(catalog_path: Path) -> tuple[list[tuple[str, Path]], int]:
    try:
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CatalogError(f"cannot read backup catalog: {exc}") from exc
    if not isinstance(catalog, dict) or not isinstance(catalog.get("backups"), list):
        raise CatalogError("catalog must contain a backups array")
    segment_size = catalog.get("wal_segment_size_bytes")
    if not isinstance(segment_size, int) or isinstance(segment_size, bool):
        raise CatalogError("catalog wal_segment_size_bytes must be an integer")
    if segment_size <= 0 or segment_size & (segment_size - 1) or UINT32 % segment_size:
        raise CatalogError("WAL segment size must be a power of two dividing 2^32")

    retained: list[tuple[str, Path]] = []
    seen: set[str] = set()
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
        manifest = item.get("manifest")
        if not isinstance(manifest, str) or not manifest.strip():
            raise CatalogError(f"backup {backup_id}: manifest path is required")
        manifest_path = (catalog_path.parent / manifest).resolve()
        retained.append((backup_id, manifest_path))
    if not retained:
        raise CatalogError("catalog has no retained backups; refusing to plan deletion")
    return retained, segment_size


def build_plan(archive_dir: Path, catalog_path: Path, cutoff_epoch: float) -> RetentionPlan:
    retained, segment_size = _load_catalog(catalog_path)
    if not archive_dir.is_dir():
        raise CatalogError("WAL archive directory does not exist")

    required: set[str] = set()
    required_timelines: set[int] = set()
    for backup_id, manifest_path in retained:
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise CatalogError(f"backup {backup_id}: cannot read manifest: {exc}") from exc
        ranges = manifest.get("WAL-Ranges") if isinstance(manifest, dict) else None
        if not isinstance(ranges, list) or not ranges:
            raise CatalogError(f"backup {backup_id}: manifest has no WAL-Ranges")
        for wal_range in ranges:
            if not isinstance(wal_range, dict):
                raise CatalogError(f"backup {backup_id}: malformed WAL-Ranges entry")
            timeline = wal_range.get("Timeline")
            if not isinstance(timeline, int) or isinstance(timeline, bool) or timeline < 1:
                raise CatalogError(f"backup {backup_id}: invalid WAL timeline")
            required_timelines.add(timeline)
            required.update(expand_wal_range(
                timeline, wal_range.get("Start-LSN"), wal_range.get("End-LSN"), segment_size
            ))

    files: dict[str, Path] = {}
    for path in archive_dir.iterdir():
        if path.is_symlink():
            raise CatalogError(f"archive contains a symbolic link: {path.name}")
        if path.is_file():
            if path.name in files:
                raise CatalogError(f"duplicate archive filename: {path.name}")
            files[path.name] = path

    history = {name for name in files if HISTORY_NAME.fullmatch(name)}
    for timeline in required_timelines:
        if timeline > 1:
            history_name = f"{timeline:08X}.history"
            if history_name not in files:
                raise CatalogError(
                    f"missing timeline history file {history_name}; refusing to plan deletion"
                )

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
            # Unknown files and recent WAL are conservatively retained.
            other.append(name)

    return RetentionPlan(
        retained_backup_ids=tuple(sorted(backup_id for backup_id, _ in retained)),
        archive_files=tuple(sorted(files)),
        required_wal_files=tuple(sorted(required)),
        protected_history_files=tuple(sorted(history)),
        eligible_wal_files=tuple(sorted(eligible)),
        protected_other_files=tuple(sorted(other)),
        missing_required_files=tuple(missing),
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive-dir", required=True, type=Path)
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--older-than-days", required=True, type=float)
    args = parser.parse_args()
    if args.older_than_days < 0:
        parser.error("--older-than-days must not be negative")
    cutoff = time.time() - args.older_than_days * 24 * 60 * 60
    try:
        plan = build_plan(args.archive_dir, args.catalog, cutoff)
    except CatalogError as exc:
        print(f"RETENTION_PLAN_REFUSED: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(plan.to_dict(), indent=2))
    print("DRY_RUN_ONLY: no files were deleted", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
