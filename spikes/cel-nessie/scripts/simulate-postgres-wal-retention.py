#!/usr/bin/env python3
"""Simulate PostgreSQL WAL retention against a disposable archive copy.

This utility never mutates the source archive and never performs a purge.
It applies only the candidate list from a previously generated dry-run plan,
then writes a JSON inventory proving the source archive stayed unchanged.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path
from typing import Any

WAL_NAME = re.compile(r"^[0-9A-F]{24}$")


class SimulationError(ValueError):
    """The plan or archive is unsafe to simulate."""


def inventory(directory: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for path in sorted(directory.iterdir()):
        if path.is_symlink() or not path.is_file():
            raise SimulationError(f"archive must contain regular files only: {path.name}")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        result[path.name] = digest
    return result


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
    if plan.get("missing_required_files") != []:
        raise SimulationError("plan reports missing required files; refusing simulation")
    return plan


def simulate(archive: Path, plan_path: Path, destination: Path,
             remove_required_wal: bool = False) -> dict[str, Any]:
    archive = archive.resolve(strict=True)
    destination = destination.resolve(strict=False)
    if not archive.is_dir() or archive.is_symlink():
        raise SimulationError("source archive must be a real directory")
    if destination.exists() or destination.is_symlink():
        raise SimulationError("simulation destination must not already exist")
    if destination == archive or archive in destination.parents or destination in archive.parents:
        raise SimulationError("simulation destination must be separate from source archive")

    plan = load_plan(plan_path)
    before = inventory(archive)
    candidates = set(plan["eligible_wal_files"])
    required = set(plan["required_wal_files"])
    missing = (candidates | required) - set(before)
    if missing:
        raise SimulationError(f"plan references absent archive files: {', '.join(sorted(missing))}")

    removed = candidates | (required if remove_required_wal else set())
    try:
        shutil.copytree(archive, destination, copy_function=shutil.copy2)
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
    parser.add_argument("--destination", required=True, type=Path)
    parser.add_argument("--remove-required-wal", action="store_true",
                        help="negative control: also remove required WAL from the copy")
    args = parser.parse_args()
    try:
        report = simulate(args.archive_dir, args.plan_json, args.destination,
                          args.remove_required_wal)
    except (OSError, SimulationError) as exc:
        print(f"WAL_RETENTION_SIMULATION_REFUSED: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
