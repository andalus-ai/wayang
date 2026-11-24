from __future__ import annotations

import importlib.util
import json
import stat
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "simulate-postgres-wal-retention.py"
SPEC = importlib.util.spec_from_file_location("wal_retention_simulation", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)

SimulationError = MODULE.SimulationError
simulate = MODULE.simulate

REQUIRED = "000000010000000000000001"
CANDIDATE = "000000010000000000000002"


class WalRetentionSimulationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.archive = self.root / "archive"
        self.archive.mkdir()
        (self.archive / REQUIRED).write_bytes(b"required-wal")
        (self.archive / CANDIDATE).write_bytes(b"unreferenced-wal")
        self.catalog = self.root / "catalog.json"
        self.manifest = self.root / "backup_manifest"
        self.manifest.write_text(json.dumps({"WAL-Ranges": []}), encoding="utf-8")
        self.catalog.write_text(json.dumps({
            "wal_segment_size_bytes": 16 * 1024 * 1024,
            "backups": [{"backup_id": "retained", "retained": True, "manifest": self.manifest.name}],
        }), encoding="utf-8")
        self.plan = self.root / "plan.json"
        self.write_plan()

    def tearDown(self):
        self.temp.cleanup()

    def write_plan(self, **overrides):
        plan = {
            "deletion_performed": False,
            "eligible_wal_files": [CANDIDATE],
            "required_wal_files": [REQUIRED],
            "missing_required_files": [],
            "archive_snapshot_sha256": MODULE.inventory_fingerprint(MODULE.inventory(self.archive)),
            "catalog_snapshot_sha256": MODULE.catalog_snapshot_fingerprint(self.catalog),
        }
        plan.update(overrides)
        self.plan.write_text(json.dumps(plan), encoding="utf-8")

    def test_candidates_removed_only_from_copy_and_required_wal_retained(self):
        destination = self.root / "simulation"
        before = {p.name: p.read_bytes() for p in self.archive.iterdir()}

        report = simulate(self.archive, self.plan, self.catalog, destination)

        self.assertFalse((destination / CANDIDATE).exists())
        self.assertTrue((destination / REQUIRED).is_file())
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.archive.iterdir()})
        self.assertTrue(report["source_archive_unchanged"])
        self.assertFalse(report["deletion_performed_on_source"])
        self.assertEqual(report["removed_candidate_files"], [CANDIDATE])

    def test_catalog_change_since_planning_fails_closed(self):
        changed = json.loads(self.catalog.read_text(encoding="utf-8"))
        changed["backups"][0]["backup_id"] = "retained-updated"
        self.catalog.write_text(json.dumps(changed), encoding="utf-8")

        with self.assertRaisesRegex(SimulationError, "catalog snapshot changed since planning"):
            simulate(self.archive, self.plan, self.catalog, self.root / "stale-catalog")
        self.assertFalse((self.root / "stale-catalog").exists())

    def test_manifest_change_since_planning_fails_closed(self):
        self.manifest.write_text('{"WAL-Ranges": [], "revision": 2}', encoding="utf-8")

        with self.assertRaisesRegex(SimulationError, "catalog snapshot changed since planning"):
            simulate(self.archive, self.plan, self.catalog, self.root / "stale-manifest")
        self.assertFalse((self.root / "stale-manifest").exists())

    def test_archive_content_change_since_planning_fails_closed(self):
        (self.archive / CANDIDATE).write_bytes(b"changed after planning")

        with self.assertRaisesRegex(SimulationError, "archive snapshot changed since planning"):
            simulate(self.archive, self.plan, self.catalog, self.root / "stale-plan")
        self.assertFalse((self.root / "stale-plan").exists())

    def test_archive_membership_change_since_planning_fails_closed(self):
        (self.archive / "000000010000000000000003").write_bytes(b"late arrival")

        with self.assertRaisesRegex(SimulationError, "archive snapshot changed since planning"):
            simulate(self.archive, self.plan, self.catalog, self.root / "stale-membership")
        self.assertFalse((self.root / "stale-membership").exists())

    def test_copy_drift_is_detected_before_candidates_are_removed(self):
        destination = self.root / "copy-drift"
        original_copytree = MODULE.shutil.copytree

        def copy_then_corrupt_source_snapshot(src, dst, **kwargs):
            copied = original_copytree(src, dst, **kwargs)
            (Path(dst) / REQUIRED).write_bytes(b"mixed-snapshot-copy")
            return copied

        with patch.object(MODULE.shutil, "copytree", side_effect=copy_then_corrupt_source_snapshot):
            with self.assertRaisesRegex(SimulationError, "changed while creating simulation copy"):
                simulate(self.archive, self.plan, self.catalog, destination)

        self.assertFalse(destination.exists())
        self.assertEqual((self.archive / REQUIRED).read_bytes(), b"required-wal")

    def test_copy_adds_read_access_for_restore_user_without_changing_source(self):
        source = self.archive / REQUIRED
        source.chmod(0o600)
        destination = self.root / "readable-copy"

        simulate(self.archive, self.plan, self.catalog, destination)

        source_mode = stat.S_IMODE(source.stat().st_mode)
        copy_mode = stat.S_IMODE((destination / REQUIRED).stat().st_mode)
        self.assertEqual(0o600, source_mode)
        self.assertTrue(copy_mode & stat.S_IRGRP)
        self.assertTrue(copy_mode & stat.S_IROTH)

    def test_negative_control_can_remove_required_wal_from_copy(self):
        destination = self.root / "negative"
        report = simulate(self.archive, self.plan, self.catalog, destination, remove_required_wal=True)

        self.assertFalse((destination / REQUIRED).exists())
        self.assertTrue((self.archive / REQUIRED).is_file())
        self.assertEqual(report["removed_required_files"], [REQUIRED])
        self.assertEqual(report["required_wal_present"], [])

    def test_existing_destination_is_never_overwritten(self):
        destination = self.root / "existing"
        destination.mkdir()
        marker = destination / "keep.txt"
        marker.write_text("keep", encoding="utf-8")

        with self.assertRaisesRegex(SimulationError, "must not already exist"):
            simulate(self.archive, self.plan, self.catalog, destination)
        self.assertEqual(marker.read_text(encoding="utf-8"), "keep")

    def test_missing_required_archive_file_fails_closed(self):
        (self.archive / REQUIRED).unlink()
        with self.assertRaisesRegex(SimulationError, "absent archive files"):
            simulate(self.archive, self.plan, self.catalog, self.root / "missing")

    def test_plan_that_reports_missing_dependencies_fails_closed(self):
        self.write_plan(missing_required_files=[REQUIRED])
        with self.assertRaisesRegex(SimulationError, "missing required"):
            simulate(self.archive, self.plan, self.catalog, self.root / "missing-plan")

    def test_plan_cannot_mark_required_segment_as_candidate(self):
        self.write_plan(eligible_wal_files=[REQUIRED])
        with self.assertRaisesRegex(SimulationError, "required WAL segment as eligible"):
            simulate(self.archive, self.plan, self.catalog, self.root / "overlap")

    def test_symlink_in_archive_fails_closed(self):
        link = self.archive / "000000010000000000000003"
        link.symlink_to(self.archive / REQUIRED)
        with self.assertRaisesRegex(SimulationError, "regular files only"):
            simulate(self.archive, self.plan, self.catalog, self.root / "symlink")

    def test_non_dry_run_plan_fails_closed(self):
        self.write_plan(deletion_performed=True)
        with self.assertRaisesRegex(SimulationError, "explicit dry-run"):
            simulate(self.archive, self.plan, self.catalog, self.root / "not-dry-run")


if __name__ == "__main__":
    unittest.main()
