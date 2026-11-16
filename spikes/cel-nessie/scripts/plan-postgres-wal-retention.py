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


def _load_catalog(catalog_path: Path) -> tuple[list[tuple[str, Path, bytes]], int, str]:
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
    retained: list[tuple[str, Path, bytes]] = []
    seen: set[str] = set()
    digest = hashlib.sha256()
    digest.update(b"wayang-postgres-wal-catalog-snapshot-v1\\0")
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
            raise CatalogError(f"backup {backup_id}: manifest path is unavailable or outside catalog directory") from exc
        digest.update(backup_id.encode("utf-8"))
        digest.update(b"\\0")
        digest.update(len(manifest_bytes).to_bytes(8, "big"))
        digest.update(manifest_bytes)
        retained.append((backup_id, manifest_path, manifest_bytes))
    if not retained:
        raise CatalogError("catalog has no retained backups; refusing to plan deletion")
    return retained, segment_size, digest.hexdigest()
