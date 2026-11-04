"""Safety properties of the unattended publication coordinator."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import upstream_sync as sync


class UpstreamSyncSafetyTest(unittest.TestCase):
    def test_published_functionality_wins_over_lagging_dev(self):
        with patch.object(sync, "ancestor", side_effect=[True]):
            self.assertEqual(sync.choose_base("old-dev", "released-tv-mix"), "released-tv-mix")

    def test_development_advances_released_baseline(self):
        with patch.object(sync, "ancestor", side_effect=[False, True]):
            self.assertEqual(sync.choose_base("new-dev", "released"), "new-dev")

    def test_diverged_branches_require_review(self):
        with patch.object(sync, "ancestor", return_value=False):
            with self.assertRaisesRegex(RuntimeError, "diverged"):
                sync.choose_base("dev", "release")

    def test_dry_run_never_publishes(self):
        with self.assertRaisesRegex(RuntimeError, "Dry runs"):
            sync.gate({"dry_run": True}, Path("unused"))

    def test_publication_disabled_without_successful_dry_run(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "not enabled"):
                sync.gate({"dry_run": False}, Path(directory))

    def test_shared_lock_rejects_other_run(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            sync.save(path / "lock.json", {"run": "20261104T000000Z-123456"})
            with self.assertRaisesRegex(RuntimeError, "own the shared lock"):
                sync.load_run(path, "20261104T000000Z-abcdef")

    def test_path_traversal_is_not_a_run_id(self):
        with self.assertRaisesRegex(RuntimeError, "Invalid run id"):
            sync.load_run(Path("unused"), "../../config")

    def test_review_change_invalidates_test_and_device_evidence(self):
        from argparse import Namespace
        with tempfile.TemporaryDirectory() as directory:
            run = {"worktree": directory, "commits": [{"sha": "abc"}], "id": "run", "base": "base",
                   "validation": {"passed": True}, "compatibility": {"preserved": True}, "device": {"status": "passed"}}
            sync.review(run, Namespace(commit="abc", outcome="held", reason="Episode identity overlap"))
            self.assertIsNone(run["validation"])
            self.assertIsNone(run["compatibility"])
            self.assertIsNone(run["device"])
            self.assertEqual(json.loads((Path(directory) / sync.LEDGER).read_text())["commits"]["abc"]["outcome"], "held")

    def test_failed_validation_retry_clears_persisted_pass(self):
        run = {"id": "20261104T000000Z-abcdef", "validation": {"passed": True},
               "compatibility": {"preserved": True}, "device": {"status": "passed"}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = root / ".upstream-sync"
            sync.save(state / "lock.json", {"run": run["id"]})
            sync.save(state / "runs" / f"{run['id']}.json", run)
            with patch.object(sync, "root_dir", return_value=root), patch.object(sync, "validation", side_effect=RuntimeError("failed")), \
                 patch("sys.argv", ["upstream_sync.py", "validate", "--run", run["id"]]):
                with self.assertRaisesRegex(RuntimeError, "failed"):
                    sync.main()
            saved = json.loads((state / "runs" / f"{run['id']}.json").read_text())
            self.assertIsNone(saved["validation"])
            self.assertIsNone(saved["compatibility"])
            self.assertIsNone(saved["device"])


if __name__ == "__main__":
    unittest.main()
