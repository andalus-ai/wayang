import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).parents[1] / "plan-postgres-wal-retention.py"
SPEC = importlib.util.spec_from_file_location("plan_postgres_wal_retention", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)
CatalogError = MODULE.CatalogError
build_plan = MODULE.build_plan
expand_wal_range = MODULE.expand_wal_range
require_snapshot_match = MODULE.require_snapshot_match


SEGMENT_SIZE = 16 * 1024 * 1024


class PostgresWalRetentionPlanTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.archive = self.root / "archive"
        self.archive.mkdir()
        self.catalog_path = self.root / "catalog.json"
        self.cutoff = 1_700_000_000

    def tearDown(self):
        self.temp.cleanup()

    def put_archive_file(self, name, mtime=1_600_000_000):
        path = self.archive / name
        if name.endswith(".history"):
            path.write_text("1 0/01000000 no recovery target specified\n", encoding="ascii")
        elif MODULE.WAL_NAME.fullmatch(name):
            # Sparse full-size segments keep the fixtures cheap while matching PostgreSQL archives.
            with path.open("wb") as stream:
                stream.truncate(SEGMENT_SIZE)
        else:
            path.write_bytes(b"wal-or-marker")
        os.utime(path, (mtime, mtime))
        return path

    def write_manifest(self, name, ranges):
        path = self.root / name
        path.write_text(json.dumps({"WAL-Ranges": ranges}), encoding="utf-8")
        return name

    def write_catalog(self, backups, segment_size=SEGMENT_SIZE):
        self.catalog_path.write_text(json.dumps({
            "wal_segment_size_bytes": segment_size,
            "backups": backups,
        }), encoding="utf-8")

    def range(self, timeline=1, start="0/00000000", end="0/02000000"):
        return {"Timeline": timeline, "Start-LSN": start, "End-LSN": end}

    def test_plan_has_unique_identity_expiry_and_integrity_digest(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        self.put_archive_file("000000010000000000000000")
        self.write_catalog([{"backup_id": "base", "retained": True, "manifest": manifest}])

        first = build_plan(self.archive, self.catalog_path, self.cutoff).to_dict()
        second = build_plan(self.archive, self.catalog_path, self.cutoff, approval_ttl_hours=48).to_dict()

        self.assertNotEqual(first["plan_id"], second["plan_id"])
        self.assertEqual(24 * 60 * 60, first["expires_at_epoch"] - first["created_at_epoch"])
        self.assertEqual(48 * 60 * 60, second["expires_at_epoch"] - second["created_at_epoch"])
        self.assertRegex(first["plan_sha256"], r"^[0-9a-f]{64}$")

    def test_approval_ttl_outside_one_to_168_hours_is_rejected(self):
        with self.assertRaisesRegex(CatalogError, "between 1 and 168 hours"):
            build_plan(self.archive, self.catalog_path, self.cutoff, approval_ttl_hours=169)

    def test_expands_inclusive_segment_range(self):
        self.assertEqual(
            {
                "000000010000000000000000",
                "000000010000000000000001",
                "000000010000000000000002",
            },
            expand_wal_range(1, "0/00000000", "0/02000000", SEGMENT_SIZE),
        )

    def test_union_of_multiple_retained_backups_protects_old_wal(self):
        first = self.write_manifest("first.json", [self.range(end="0/01000000")])
        second = self.write_manifest("second.json", [self.range(start="0/01000000", end="0/02000000")])
        required = {
            "000000010000000000000000",
            "000000010000000000000001",
            "000000010000000000000002",
        }
        for name in required:
            self.put_archive_file(name)
        unrelated = "000000010000000000000010"
        self.put_archive_file(unrelated)
        self.write_catalog([
            {"backup_id": "base-a", "retained": True, "manifest": first},
            {"backup_id": "base-b", "retained": True, "manifest": second},
        ])

        plan = build_plan(self.archive, self.catalog_path, self.cutoff)

        self.assertEqual(tuple(sorted(required)), plan.required_wal_files)
        self.assertEqual((unrelated,), plan.eligible_wal_files)
        self.assertFalse(plan.missing_required_files)

    def test_explicit_recovery_range_protects_wal_beyond_backup_manifest(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        required = {
            "000000010000000000000000",
            "000000010000000000000001",
            "000000010000000000000002",
        }
        for name in required:
            self.put_archive_file(name)
        unrelated = "000000010000000000000010"
        self.put_archive_file(unrelated)
        self.write_catalog([{
            "backup_id": "base",
            "retained": True,
            "manifest": manifest,
            "recovery_wal_ranges": [self.range(end="0/02000000")],
        }])

        plan = build_plan(self.archive, self.catalog_path, self.cutoff)

        self.assertEqual(tuple(sorted(required)), plan.required_wal_files)
        self.assertEqual((unrelated,), plan.eligible_wal_files)

    def test_missing_wal_inside_explicit_recovery_range_fails_closed(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        self.put_archive_file("000000010000000000000000")
        self.write_catalog([{
            "backup_id": "base",
            "retained": True,
            "manifest": manifest,
            "recovery_wal_ranges": [self.range(end="0/02000000")],
        }])

        with self.assertRaisesRegex(CatalogError, "missing required WAL"):
            build_plan(self.archive, self.catalog_path, self.cutoff)

    def test_recovery_range_change_invalidates_approved_snapshot(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        self.put_archive_file("000000010000000000000000")
        self.put_archive_file("000000010000000000000001")
        backup = {
            "backup_id": "base",
            "retained": True,
            "manifest": manifest,
            "recovery_wal_ranges": [self.range(end="0/00000000")],
        }
        self.write_catalog([backup])
        first = build_plan(self.archive, self.catalog_path, self.cutoff)

        backup["recovery_wal_ranges"] = [self.range(end="0/01000000")]
        self.write_catalog([backup])
        second = build_plan(self.archive, self.catalog_path, self.cutoff)

        with self.assertRaisesRegex(CatalogError, "snapshot changed"):
            require_snapshot_match(second, first.catalog_snapshot_sha256)

    def test_empty_explicit_recovery_range_is_rejected(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        self.put_archive_file("000000010000000000000000")
        self.write_catalog([{
            "backup_id": "base",
            "retained": True,
            "manifest": manifest,
            "recovery_wal_ranges": [],
        }])

        with self.assertRaisesRegex(CatalogError, "recovery_wal_ranges must be a non-empty array"):
            build_plan(self.archive, self.catalog_path, self.cutoff)

    def test_recent_wal_and_unknown_files_are_protected(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        required = "000000010000000000000000"
        self.put_archive_file(required)
        recent = "000000010000000000000010"
        self.put_archive_file(recent, mtime=self.cutoff + 100)
        unknown = "README"
        self.put_archive_file(unknown)
        self.write_catalog([{"backup_id": "base", "retained": True, "manifest": manifest}])

        plan = build_plan(self.archive, self.catalog_path, self.cutoff)

        self.assertEqual((), plan.eligible_wal_files)
        self.assertEqual((recent, unknown), plan.protected_other_files)

    def test_missing_required_wal_fails_closed(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        self.write_catalog([{"backup_id": "base", "retained": True, "manifest": manifest}])
        with self.assertRaisesRegex(CatalogError, "missing required WAL"):
            build_plan(self.archive, self.catalog_path, self.cutoff)

    def test_timeline_history_is_always_protected(self):
        manifest = self.write_manifest("backup.json", [self.range(timeline=2, end="0/00000000")])
        required = "000000020000000000000000"
        history = "00000002.history"
        unrelated = "000000010000000000000010"
        self.put_archive_file(required)
        self.put_archive_file(history)
        self.put_archive_file(unrelated)
        self.write_catalog([{"backup_id": "base", "retained": True, "manifest": manifest}])

        plan = build_plan(self.archive, self.catalog_path, self.cutoff)

        self.assertEqual((history,), plan.protected_history_files)
        self.assertEqual((unrelated,), plan.eligible_wal_files)

    def test_parent_and_child_timeline_ranges_are_unioned(self):
        manifest = self.write_manifest("backup.json", [self.range(timeline=1, end="0/00000000")])
        required = {
            "000000010000000000000000",
            "000000020000000000000001",
            "000000020000000000000002",
        }
        for name in required:
            self.put_archive_file(name)
        history = "00000002.history"
        self.put_archive_file(history)
        unrelated = "000000010000000000000010"
        self.put_archive_file(unrelated)
        self.write_catalog([{
            "backup_id": "timeline-fork",
            "retained": True,
            "manifest": manifest,
            "recovery_wal_ranges": [
                self.range(timeline=1, start="0/00000000", end="0/00FFFFFF"),
                self.range(timeline=2, start="0/01000000", end="0/02000000"),
            ],
        }])

        plan = build_plan(self.archive, self.catalog_path, self.cutoff)

        self.assertEqual(tuple(sorted(required)), plan.required_wal_files)
        self.assertEqual((history,), plan.protected_history_files)
        self.assertEqual((unrelated,), plan.eligible_wal_files)

    def test_successive_timeline_forks_validate_ancestry_and_boundaries(self):
        manifest = self.write_manifest("backup.json", [self.range(timeline=1, end="0/00000000")])
        required = {
            "000000010000000000000000",
            "000000020000000000000001",
            "000000030000000000000002",
            "000000030000000000000003",
        }
        for name in required:
            self.put_archive_file(name)
        self.put_archive_file("00000002.history").write_text(
            "1 0/01000000 first promotion\n", encoding="ascii"
        )
        self.put_archive_file("00000003.history").write_text(
            "1 0/01000000 first promotion\n2 0/02000000 second promotion\n",
            encoding="ascii"
        )
        self.write_catalog([
            {
                "backup_id": "successive-forks",
                "retained": True,
                "manifest": manifest,
                "recovery_wal_ranges": [
                    self.range(timeline=1, start="0/00000000", end="0/00FFFFFF"),
                    self.range(timeline=2, start="0/01000000", end="0/01FFFFFF"),
                    self.range(timeline=3, start="0/02000000", end="0/03000000"),
                ],
            }
        ])

        plan = build_plan(self.archive, self.catalog_path, self.cutoff)

        self.assertEqual(tuple(sorted(required)), plan.required_wal_files)
        self.assertEqual(("00000002.history", "00000003.history"), plan.protected_history_files)

    def test_disjoint_ranges_cannot_hide_a_fork_boundary_gap(self):
        manifest = self.write_manifest("backup.json", [self.range(timeline=1, end="0/00000000")])
        self.put_archive_file("000000010000000000000000")
        self.put_archive_file("000000020000000000000001")
        self.put_archive_file("00000002.history").write_text(
            "1 0/01000000 first promotion\n", encoding="ascii"
        )
        self.write_catalog([{
            "backup_id": "fork-gap",
            "retained": True,
            "manifest": manifest,
            "recovery_wal_ranges": [
                self.range(timeline=1, start="0/00000000", end="0/00FFFFFF"),
                self.range(timeline=2, start="0/01000001", end="0/01800000"),
                self.range(timeline=2, start="0/01F00000", end="0/02000000"),
            ],
        }])

        with self.assertRaisesRegex(CatalogError, "does not cover fork point 1000000"):
            build_plan(self.archive, self.catalog_path, self.cutoff)
    def test_successive_fork_with_missing_intermediate_timeline_range_fails(self):
        manifest = self.write_manifest("backup.json", [self.range(timeline=1, end="0/00000000")])
        for name in (
            "000000010000000000000000",
            "000000030000000000000002",
        ):
            self.put_archive_file(name)
        self.put_archive_file("00000002.history").write_text(
            "1 0/01000000 first promotion\n", encoding="ascii"
        )
        self.put_archive_file("00000003.history").write_text(
            "1 0/01000000 first promotion\n2 0/02000000 second promotion\n",
            encoding="ascii"
        )
        self.write_catalog([
            {
                "backup_id": "missing-intermediate",
                "retained": True,
                "manifest": manifest,
                "recovery_wal_ranges": [
                    self.range(timeline=1, start="0/00000000", end="0/00FFFFFF"),
                    self.range(timeline=3, start="0/02000000", end="0/03000000"),
                ],
            }
        ])

        with self.assertRaisesRegex(CatalogError, "recovery ranges omit timeline 2"):
            build_plan(self.archive, self.catalog_path, self.cutoff)

    def test_malformed_timeline_history_fails_closed(self):
        manifest = self.write_manifest("backup.json", [self.range(timeline=2, end="0/00000000")])
        self.put_archive_file("000000020000000000000000")
        self.put_archive_file("00000002.history").write_text("not a PostgreSQL history row\n", encoding="ascii")
        self.write_catalog([{"backup_id": "bad-history", "retained": True, "manifest": manifest}])

        with self.assertRaisesRegex(CatalogError, "malformed timeline history"):
            build_plan(self.archive, self.catalog_path, self.cutoff)
    def test_multi_timeline_recovery_requires_child_history_file(self):
        manifest = self.write_manifest("backup.json", [self.range(timeline=1, end="0/00000000")])
        self.put_archive_file("000000010000000000000000")
        self.put_archive_file("000000020000000000000001")
        self.write_catalog([{
            "backup_id": "timeline-fork",
            "retained": True,
            "manifest": manifest,
            "recovery_wal_ranges": [
                self.range(timeline=1, start="0/00000000", end="0/01000000"),
                self.range(timeline=2, start="0/01000000", end="0/02000000"),
            ],
        }])

        with self.assertRaisesRegex(CatalogError, "missing timeline history file 00000002.history"):
            build_plan(self.archive, self.catalog_path, self.cutoff)

    def test_missing_timeline_history_fails_closed(self):
        manifest = self.write_manifest("backup.json", [self.range(timeline=2, end="0/00000000")])
        self.put_archive_file("000000020000000000000000")
        self.write_catalog([{"backup_id": "base", "retained": True, "manifest": manifest}])
        with self.assertRaisesRegex(CatalogError, "missing timeline history"):
            build_plan(self.archive, self.catalog_path, self.cutoff)

    def test_empty_or_duplicate_catalog_fails_closed(self):
        self.write_catalog([])
        with self.assertRaisesRegex(CatalogError, "no retained backups"):
            build_plan(self.archive, self.catalog_path, self.cutoff)
        self.write_catalog([
            {"backup_id": "same", "retained": False},
            {"backup_id": "same", "retained": True, "manifest": "missing.json"},
        ])
        with self.assertRaisesRegex(CatalogError, "duplicate backup_id"):
            build_plan(self.archive, self.catalog_path, self.cutoff)

    def test_archive_symlink_fails_closed(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        self.put_archive_file("000000010000000000000000")
        target = self.root / "target"
        target.write_text("not an archive file", encoding="utf-8")
        (self.archive / "linked").symlink_to(target)
        self.write_catalog([{"backup_id": "base", "retained": True, "manifest": manifest}])
        with self.assertRaisesRegex(CatalogError, "symbolic link"):
            build_plan(self.archive, self.catalog_path, self.cutoff)

    def test_archive_snapshot_fingerprint_is_stable_for_unchanged_archive(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        self.put_archive_file("000000010000000000000000")
        self.write_catalog([{"backup_id": "base", "retained": True, "manifest": manifest}])

        first = build_plan(self.archive, self.catalog_path, self.cutoff)
        second = build_plan(self.archive, self.catalog_path, self.cutoff)

        self.assertEqual(first.archive_snapshot_sha256, second.archive_snapshot_sha256)
        self.assertRegex(first.archive_snapshot_sha256, r"^[0-9a-f]{64}$")

    def test_archive_content_change_invalidates_previous_archive_snapshot(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        self.put_archive_file("000000010000000000000000")
        candidate = self.put_archive_file("000000010000000000000010")
        self.write_catalog([{"backup_id": "base", "retained": True, "manifest": manifest}])
        first = build_plan(self.archive, self.catalog_path, self.cutoff)

        candidate.write_bytes(b"replacement with the same filename")
        second = build_plan(self.archive, self.catalog_path, self.cutoff)

        self.assertNotEqual(first.archive_snapshot_sha256, second.archive_snapshot_sha256)

    def test_archive_membership_change_invalidates_previous_archive_snapshot(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        self.put_archive_file("000000010000000000000000")
        self.write_catalog([{"backup_id": "base", "retained": True, "manifest": manifest}])
        first = build_plan(self.archive, self.catalog_path, self.cutoff)

        self.put_archive_file("000000010000000000000010")
        second = build_plan(self.archive, self.catalog_path, self.cutoff)

        self.assertNotEqual(first.archive_snapshot_sha256, second.archive_snapshot_sha256)

    def test_snapshot_fingerprint_is_stable_for_unchanged_catalog(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        self.put_archive_file("000000010000000000000000")
        self.write_catalog([{"backup_id": "base", "retained": True, "manifest": manifest}])

        first = build_plan(self.archive, self.catalog_path, self.cutoff)
        second = build_plan(self.archive, self.catalog_path, self.cutoff)

        self.assertEqual(first.catalog_snapshot_sha256, second.catalog_snapshot_sha256)
        require_snapshot_match(second, first.catalog_snapshot_sha256)

    def test_manifest_change_invalidates_previous_snapshot(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        self.put_archive_file("000000010000000000000000")
        self.write_catalog([{"backup_id": "base", "retained": True, "manifest": manifest}])
        first = build_plan(self.archive, self.catalog_path, self.cutoff)

        self.write_manifest("backup.json", [self.range(end="0/01000000")])
        with self.assertRaisesRegex(CatalogError, "missing required WAL"):
            build_plan(self.archive, self.catalog_path, self.cutoff)

        self.write_manifest("backup.json", [self.range(end="0/00000000", start="0/00000000")])
        manifest_path = self.root / "backup.json"
        manifest_path.write_text(manifest_path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
        second = build_plan(self.archive, self.catalog_path, self.cutoff)
        with self.assertRaisesRegex(CatalogError, "snapshot changed"):
            require_snapshot_match(second, first.catalog_snapshot_sha256)

    def test_catalog_change_invalidates_previous_snapshot(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        self.put_archive_file("000000010000000000000000")
        self.write_catalog([{"backup_id": "base", "retained": True, "manifest": manifest}])
        first = build_plan(self.archive, self.catalog_path, self.cutoff)

        self.catalog_path.write_text(self.catalog_path.read_text(encoding="utf-8").replace(
            '"backup_id": "base"', '"backup_id": "base-updated"'
        ), encoding="utf-8")
        second = build_plan(self.archive, self.catalog_path, self.cutoff)
        with self.assertRaisesRegex(CatalogError, "snapshot changed"):
            require_snapshot_match(second, first.catalog_snapshot_sha256)

    def test_manifest_path_traversal_and_symlink_fail_closed(self):
        outside = self.root.parent / (self.root.name + "-outside.json")
        outside.write_text(json.dumps({"WAL-Ranges": [self.range(end="0/00000000")]}), encoding="utf-8")
        self.write_catalog([{"backup_id": "escape", "retained": True, "manifest": "../" + outside.name}])
        with self.assertRaisesRegex(CatalogError, "escapes catalog directory"):
            build_plan(self.archive, self.catalog_path, self.cutoff)

        link = self.root / "linked-manifest.json"
        link.symlink_to(outside)
        self.write_catalog([{"backup_id": "link", "retained": True, "manifest": link.name}])
        with self.assertRaisesRegex(CatalogError, "symbolic link"):
            build_plan(self.archive, self.catalog_path, self.cutoff)
        outside.unlink()

    def test_invalid_expected_snapshot_fingerprint_fails_closed(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        self.put_archive_file("000000010000000000000000")
        self.write_catalog([{"backup_id": "base", "retained": True, "manifest": manifest}])
        plan = build_plan(self.archive, self.catalog_path, self.cutoff)
        with self.assertRaisesRegex(CatalogError, "lowercase SHA-256"):
            require_snapshot_match(plan, "not-a-digest")

    def test_truncated_required_wal_segment_fails_closed(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        path = self.put_archive_file("000000010000000000000000")
        path.write_bytes(b"truncated-wal")
        self.write_catalog([{"backup_id": "truncated", "retained": True, "manifest": manifest}])

        with self.assertRaisesRegex(CatalogError, "has size 13; expected 16777216 bytes"):
            build_plan(self.archive, self.catalog_path, self.cutoff)

    def test_oversized_required_wal_segment_fails_closed(self):
        manifest = self.write_manifest("backup.json", [self.range(end="0/00000000")])
        path = self.put_archive_file("000000010000000000000000")
        with path.open("ab") as stream:
            stream.write(b"x")
        self.write_catalog([{"backup_id": "oversized", "retained": True, "manifest": manifest}])

        with self.assertRaisesRegex(CatalogError, "expected 16777216 bytes"):
            build_plan(self.archive, self.catalog_path, self.cutoff)

    def test_invalid_segment_size_fails_closed(self):
        self.write_catalog([], segment_size=12345)
        with self.assertRaisesRegex(CatalogError, "power of two"):
            build_plan(self.archive, self.catalog_path, self.cutoff)


if __name__ == "__main__":
    unittest.main()
