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

SEGMENT_SIZE = 16 * 1024 * 1024


class DivergentTimelineRetentionTest(unittest.TestCase):
    """Sibling PostgreSQL timelines must be retained as independent recovery paths."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.archive = self.root / "archive"
        self.archive.mkdir()
        self.catalog = self.root / "catalog.json"
        self.cutoff = 1_700_000_000

    def tearDown(self):
        self.temp.cleanup()

    def archive_file(self, name, content=b"wal", mtime=1_600_000_000):
        path = self.archive / name
        if isinstance(content, str):
            content = content.encode("ascii")
        path.write_bytes(content)
        os.utime(path, (mtime, mtime))
        return path

    def manifest(self, name):
        path = self.root / name
        path.write_text(json.dumps({
            "WAL-Ranges": [{
                "Timeline": 1,
                "Start-LSN": "0/00000000",
                "End-LSN": "0/00000000",
            }]
        }), encoding="utf-8")
        return name

    def write_catalog(self, backups):
        self.catalog.write_text(json.dumps({
            "wal_segment_size_bytes": SEGMENT_SIZE,
            "backups": backups,
        }), encoding="utf-8")

    @staticmethod
    def wal_range(timeline, start, end):
        return {"Timeline": timeline, "Start-LSN": start, "End-LSN": end}

    def seed_sibling_branches(self):
        # Timeline 2 and timeline 3 are siblings: each forked directly from TLI 1.
        self.archive_file("00000002.history", "1 0/01000000 branch-a promotion\n")
        self.archive_file("00000003.history", "1 0/02000000 branch-b promotion\n")
        for name in (
            "000000010000000000000000",
            "000000010000000000000001",
            "000000020000000000000001",
            "000000020000000000000002",
            "000000030000000000000002",
            "000000030000000000000003",
        ):
            self.archive_file(name)
        self.archive_file("000000020000000000000010")
        self.archive_file("000000030000000000000010")

        first = self.manifest("branch-a-backup.json")
        second = self.manifest("branch-b-backup.json")
        self.write_catalog([
            {
                "backup_id": "retained-branch-a",
                "retained": True,
                "manifest": first,
                "recovery_wal_ranges": [
                    self.wal_range(1, "0/00000000", "0/00FFFFFF"),
                    self.wal_range(2, "0/01000000", "0/02000000"),
                ],
            },
            {
                "backup_id": "retained-branch-b",
                "retained": True,
                "manifest": second,
                "recovery_wal_ranges": [
                    self.wal_range(1, "0/00000000", "0/01FFFFFF"),
                    self.wal_range(3, "0/02000000", "0/03000000"),
                ],
            },
        ])

    def test_union_preserves_both_sibling_recovery_paths(self):
        self.seed_sibling_branches()

        plan = build_plan(self.archive, self.catalog, self.cutoff)

        self.assertEqual(
            ("retained-branch-a", "retained-branch-b"),
            plan.retained_backup_ids,
        )
        self.assertEqual(
            (
                "000000010000000000000000",
                "000000010000000000000001",
                "000000020000000000000001",
                "000000020000000000000002",
                "000000030000000000000002",
                "000000030000000000000003",
            ),
            plan.required_wal_files,
        )
        self.assertEqual(
            ("00000002.history", "00000003.history"),
            plan.protected_history_files,
        )
        self.assertEqual(
            ("000000020000000000000010", "000000030000000000000010"),
            plan.eligible_wal_files,
        )

    def test_missing_sibling_history_fails_closed(self):
        self.seed_sibling_branches()
        (self.archive / "00000003.history").unlink()

        with self.assertRaisesRegex(CatalogError, "missing timeline history file 00000003.history"):
            build_plan(self.archive, self.catalog, self.cutoff)

    def test_each_branch_must_cover_its_own_fork_boundary(self):
        self.seed_sibling_branches()
        catalog = json.loads(self.catalog.read_text(encoding="utf-8"))
        branch_b = catalog["backups"][1]
        branch_b["recovery_wal_ranges"][0]["End-LSN"] = "0/01FFFFFE"
        self.catalog.write_text(json.dumps(catalog), encoding="utf-8")

        with self.assertRaisesRegex(CatalogError, "timeline 1 recovery range does not reach byte before fork"):
            build_plan(self.archive, self.catalog, self.cutoff)


    def test_missing_wal_on_one_retained_sibling_fails_closed(self):
        self.seed_sibling_branches()
        (self.archive / "000000030000000000000003").unlink()

        with self.assertRaisesRegex(CatalogError, "archive is missing required WAL files"):
            build_plan(self.archive, self.catalog, self.cutoff)

    def test_corrupt_sibling_history_fails_closed(self):
        self.seed_sibling_branches()
        (self.archive / "00000003.history").write_text(
            "this is not a PostgreSQL timeline history record\\n",
            encoding="ascii",
        )

        with self.assertRaisesRegex(CatalogError, "malformed timeline history 00000003.history"):
            build_plan(self.archive, self.catalog, self.cutoff)

    def test_sibling_history_with_wrong_parent_fails_closed(self):
        self.seed_sibling_branches()
        (self.archive / "00000003.history").write_text(
            "2 0/02000000 incorrectly points to sibling timeline\\n",
            encoding="ascii",
        )

        with self.assertRaisesRegex(CatalogError, "timeline 1 recovery range does not reach byte before fork"):
            build_plan(self.archive, self.catalog, self.cutoff)

    def test_missing_wal_on_one_branch_does_not_get_masked_by_other_branch(self):
        self.seed_sibling_branches()
        (self.archive / "000000020000000000000002").unlink()

        with self.assertRaisesRegex(CatalogError, "archive is missing required WAL files"):
            build_plan(self.archive, self.catalog, self.cutoff)

    def test_unretained_sibling_branch_is_not_a_recovery_dependency(self):
        self.seed_sibling_branches()
        catalog = json.loads(self.catalog.read_text(encoding="utf-8"))
        self.catalog.write_text(json.dumps({
            "wal_segment_size_bytes": SEGMENT_SIZE,
            "backups": [catalog["backups"][1]],
        }), encoding="utf-8")
        (self.archive / "00000002.history").unlink()

        plan = build_plan(self.archive, self.catalog, self.cutoff)

        self.assertEqual(("retained-branch-b",), plan.retained_backup_ids)
        self.assertNotIn("000000020000000000000001", plan.required_wal_files)
        self.assertIn("000000020000000000000001", plan.eligible_wal_files)
        self.assertIn("00000003.history", plan.protected_history_files)


if __name__ == "__main__":
    unittest.main()
