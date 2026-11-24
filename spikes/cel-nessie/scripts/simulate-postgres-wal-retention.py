#!/usr/bin/env python3
"""Simulate PostgreSQL WAL retention against a disposable archive copy.

This utility never mutates the source archive and never performs a purge.
It applies only the candidate list from a previously generated dry-run plan,
then writes a JSON inventory proving the source archive stayed unchanged.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import shutil
import stat
import sys
from pathlib import Path
from typing import Any

WAL_NAME = re.compile(r"^[0-9A-F]{24}$")


class SimulationError(ValueError):
    """The plan or archive is unsafe to simulate."""


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inventory(directory: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for path in sorted(directory.iterdir()):
        if path.is_symlink() or not path.is_file():
            raise SimulationError(f"archive must contain regular files only: {path.name}")
        result[path.name] = file_sha256(path)
    return result


def inventory_fingerprint(files: dict[str, str]) -> str:
    encoded = json.dumps(files, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def catalog_snapshot_fingerprint(catalog_path: Path) -> str:
    """Read the current catalog snapshot using the planner's validation rules."""
    planner_path = Path(__file__).with_name("plan-postgres-wal-retention.py")
    spec = importlib.util.spec_from_file_location("wayang_wal_retention_planner", planner_path)
    if spec is None or spec.loader is None:
        raise SimulationError("cannot load WAL retention planner for catalog validation")
    planner = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = planner
    try:
        spec.loader.exec_module(planner)
        _retained, _segment_size, fingerprint = planner._load_catalog(catalog_path)
        return fingerprint
    except (OSError, ValueError, ImportError) as exc:
        raise SimulationError(f"cannot validate current backup catalog: {exc}") from exc


def load_plan(path: Path) -> dict[str, Any]:
    try:
        plan = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SimulationError(f"cannot read retention plan: {exc}") from exc
    if not isinstance(plan, dict) or plan.get("deletion_performed") is not False:
        raise SimulationError("input must be an explicit dry-run retention plan")
    for key in ("eligible_wal_files", "required_wal_files"):
        values = plan.get(key)
        if not isinstance(values, list) or any(
            not isinstance(name, str) or not WAL_NAME.fullmatch(name) for name in values
        ):
            raise SimulationError(f"plan {key} must contain valid WAL segment filenames")
        if len(values) != len(set(values)):
            raise SimulationError(f"plan {key} contains duplicate filenames")
    if set(plan["eligible_wal_files"]) & set(plan["required_wal_files"]):
        raise SimulationError("plan marks a required WAL segment as eligible")
    catalog_fingerprint = plan.get("catalog_snapshot_sha256")
    if not isinstance(catalog_fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", catalog_fingerprint):
        raise SimulationError("plan must contain a valid catalog_snapshot_sha256 fingerprint")
    fingerprint = plan.get("archive_snapshot_sha256")
    if not isinstance(fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
        raise SimulationError("plan must contain a valid archive_snapshot_sha256 fingerprint")
    if plan.get("missing_required_files") != []:
        raise SimulationError("plan reports missing required files; refusing simulation")
    return plan


def simulate(archive: Path, plan_path: Path, catalog_path: Path, destination: Path,
             remove_required_wal: bool = False) -> dict[str, Any]:
    if archive.is_symlink():
        raise SimulationError("source archive must not be a symbolic link")
    archive = archive.resolve(strict=True)
    destination = destination.resolve(strict=False)
    if not archive.is_dir():
        raise SimulationError("source archive must be a real directory")
    if destination.exists() or destination.is_symlink():
        raise SimulationError("simulation destination must not already exist")
    if destination == archive or archive in destination.parents or destination in archive.parents:
        raise SimulationError("simulation destination must be separate from source archive")

    plan = load_plan(plan_path)
    current_catalog_fingerprint = catalog_snapshot_fingerprint(catalog_path)
    if current_catalog_fingerprint != plan["catalog_snapshot_sha256"]:
        raise SimulationError("catalog snapshot changed since planning; re-plan and re-approve")
    before = inventory(archive)
    candidates = set(plan["eligible_wal_files"])
    required = set(plan["required_wal_files"])
    missing = (candidates | required) - set(before)
    if missing:
        raise SimulationError(f"plan references absent archive files: {', '.join(sorted(missing))}")
    if inventory_fingerprint(before) != plan["archive_snapshot_sha256"]:
        raise SimulationError("archive snapshot changed since planning; re-plan and re-approve")

    removed = candidates | (required if remove_required_wal else set())
    try:
        shutil.copytree(archive, destination, copy_function=shutil.copy2)
        # PostgreSQL recovery runs as the postgres OS user in the restore
        # container. Make the disposable copy readable by that user even when
        # the source archive was exported with owner-only permissions.
        for copied in destination.iterdir():
            if copied.is_file() and not copied.is_symlink():
                copied.chmod(stat.S_IMODE(copied.stat().st_mode) | stat.S_IRGRP | stat.S_IROTH)
        for name in sorted(removed):
            (destination / name).unlink()
        after = inventory(archive)
        if before != after:
            raise SimulationError("source archive changed during simulation")
        simulated = inventory(destination)
    except Exception:
        if destination.exists() and not destination.is_symlink():
            shutil.rmtree(destination)
        raise

    return {
        "source_archive_unchanged": True,
        "source_file_count": len(before),
        "source_inventory_sha256": hashlib.sha256(
            json.dumps(before, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest(),
        "simulation_directory": str(destination),
        "removed_candidate_files": sorted(candidates),
        "removed_required_files": sorted(required if remove_required_wal else set()),
        "remaining_file_count": len(simulated),
        "required_wal_present": sorted(required & set(simulated)),
        "deletion_performed_on_source": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive-dir", required=True, type=Path)
    parser.add_argument("--plan-json", required=True, type=Path)
    parser.add_argument("--catalog", required=True, type=Path,
                        help="current backup catalog used to verify plan freshness")
    parser.add_argument("--destination", required=True, type=Path)
    parser.add_argument("--remove-required-wal", action="store_true",
                        help="negative control: also remove required WAL from the copy")
    args = parser.parse_args()
    try:
        report = simulate(args.archive_dir, args.plan_json, args.catalog, args.destination,
                          args.remove_required_wal)
    except (OSError, SimulationError) as exc:
        print(f"WAL_RETENTION_SIMULATION_REFUSED: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
