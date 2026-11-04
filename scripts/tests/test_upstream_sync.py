"""Safety properties of the unattended publication coordinator."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import upstream_sync as sync


class UpstreamSyncSafetyTest(unittest.TestCase):
    def test_new_publications_still_wait_for_all_active_release_jobs(self):
        jobs={"workflow_runs":[{"id":1,"status":"in_progress","head_sha":"ours"},
                               {"id":2,"status":"queued","head_sha":"other"},
                               {"id":3,"status":"completed","head_sha":"ours"}]}
        with patch.object(sync,"api",return_value=jobs):
            self.assertEqual(sync.active_releases(),[1,2])

    def test_cleanup_waits_only_for_its_own_release_commit(self):
        jobs={"workflow_runs":[{"id":1,"status":"completed","head_sha":"ours"},
                               {"id":2,"status":"in_progress","head_sha":"other"}]}
        with patch.object(sync,"api",return_value=jobs):
            self.assertEqual(sync.active_releases(head="ours"),[])
            self.assertEqual(sync.active_releases(head="other"),[2])

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

    def test_git_facade_cannot_push_or_override_the_worktree(self):
        for argv in (["push", "--force"], ["status", "-C", "elsewhere"], ["add", "-f", "local.properties"]):
            with self.subTest(argv=argv), self.assertRaises(RuntimeError):
                sync.worktree_git({"worktree": "unused"}, argv)

    def test_configuration_excludes_personal_tmdb_and_signing_material(self):
        from argparse import Namespace
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            source = root / "approved.properties"
            source.write_text("NUVIO_SUPABASE_URL=https://project.invalid\nTMDB_API_KEY=personal-test-only\n"
                              "TMDB_READ_ACCESS_TOKEN=personal-test-token\nNUVIO_RELEASE_KEY_PASSWORD=secret-test\n"
                              "sdk.dir=old-sdk\n", encoding="utf-8")
            target = root / "worktree"
            target.mkdir()
            sync.configure({"worktree": str(target)}, Namespace(properties=str(source), sdk=None), root)
            for name in ("local.properties", "local.dev.properties"):
                text = (target / name).read_text()
                self.assertIn("NUVIO_SUPABASE_URL=", text)
                self.assertNotIn("TMDB", text)
                self.assertNotIn("NUVIO_RELEASE_", text)
                self.assertNotIn("sdk.dir", text)

    def test_merge_only_resolution_is_not_lost_from_review_inventory(self):
        import subprocess
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args): return sync.git(*args, cwd=root)
            git("init", "--initial-branch=main")
            git("config", "user.name", "Sync safety test")
            git("config", "user.email", "sync-test@example.invalid")
            fixture = root / "identity.txt"
            fixture.write_text("base\n")
            git("add", "identity.txt"); git("commit", "-m", "base")
            base = git("rev-parse", "HEAD")
            git("switch", "-c", "left")
            fixture.write_text("left implementation\n")
            git("commit", "-am", "left")
            git("switch", "-c", "right", base)
            fixture.write_text("right implementation\n")
            git("commit", "-am", "right")
            conflict = subprocess.run(["git", "merge", "left", "--no-commit"], cwd=root, capture_output=True)
            self.assertEqual(conflict.returncode, 1)
            fixture.write_text("new identity decision introduced only by merge\n")
            git("add", "identity.txt"); git("commit", "-m", "resolve identity")
            merge = git("rev-parse", "HEAD")
            inventory = sync.upstream_inventory(base, merge, root, {"commits": {}})
            self.assertEqual(len(inventory), 3)
            resolution = next(c for c in inventory if c["sha"] == merge)
            self.assertTrue(resolution["is_merge"])
            self.assertEqual(resolution["files"], ["identity.txt"])
            self.assertIsNone(resolution["previous_review"])

    def test_lint_approval_cannot_promote_a_protected_functional_hold(self):
        from argparse import Namespace
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / sync.LEDGER
            sync.save(path, {"schema":1, "commits":{"abc":{"outcome":"held", "requires_decision":True}}})
            run = {"worktree":directory, "commits":[{"sha":"abc"}], "id":"run", "base":"base"}
            with self.assertRaisesRegex(RuntimeError, "maintainer's design decision"):
                sync.review(run, Namespace(commit="abc", outcome="integrated", reason="Lint passed"))
            self.assertEqual(json.loads(path.read_text())["commits"]["abc"]["outcome"], "held")

    def test_held_cherry_pick_is_blocked_even_without_upstream_ancestry(self):
        sha = "a" * 40
        run = {"worktree":"unused", "commits":[{"sha":sha}], "base":"base", "upstream":"upstream"}
        ledger = {sha:{"outcome":"held"}}
        with patch.object(sync,"ancestor",return_value=False), \
             patch.object(sync,"git",return_value=f"(cherry picked from commit {sha})"):
            with self.assertRaisesRegex(RuntimeError,"cherry-picked"):
                sync.enforce_held_changes(run,ledger,"candidate")

    def test_patch_equivalent_held_change_is_also_blocked(self):
        sha = "a" * 40
        run = {"worktree":"unused", "commits":[{"sha":sha}], "base":"base", "upstream":"upstream"}
        with patch.object(sync,"ancestor",return_value=False), patch.object(sync,"git",side_effect=["", "- "+sha]):
            with self.assertRaisesRegex(RuntimeError,"patch is present"):
                sync.enforce_held_changes(run,{sha:{"outcome":"held"}},"candidate")

    def test_cleanup_refuses_another_development_worktree(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve()
            build=root/".worktrees/beta6-release/app/build"
            build.mkdir(parents=True)
            sentinel=build/"active-output"; sentinel.write_text("keep")
            with self.assertRaisesRegex(RuntimeError,"known worktree names"):
                sync.retire_build_outputs({"worktree":str(build.parents[1])},root,root/".upstream-sync")
            self.assertTrue(sentinel.exists())

    def test_completed_run_cleanup_preserves_sources_evidence_and_held_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve()
            tree=root/".worktrees/upstream-20261104T000000Z-abcdef"
            build=tree/"app/build"
            (build/"reports").mkdir(parents=True)
            (build/"reports/result.txt").write_text("pass")
            apk=build/"outputs/apk/full/debug/app-full-armeabi-v7a-debug.apk"
            apk.parent.mkdir(parents=True); apk.write_bytes(b"fixture")
            source=tree/"app/unchanged-source.kt"; source.write_text("keep")
            run={"id":"20261104T000000Z-abcdef","worktree":str(tree),"publication":None}
            state=root/".upstream-sync"
            sync.retire_build_outputs(run,root,state)
            self.assertTrue(source.exists())
            self.assertFalse(build.exists())
            self.assertTrue((state/"evidence"/run["id"]/"reports/result.txt").exists())
            self.assertTrue((state/"artifacts"/run["id"]/apk.name).exists())

    def test_locked_generated_file_is_deferred_without_stranding_finished_run(self):
        import os
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve()
            tree=root/".worktrees/upstream-20261104T000000Z-abcdef"
            build=tree/"app/build"; build.mkdir(parents=True)
            run={"id":"20261104T000000Z-abcdef","worktree":str(tree),"publication":{"verified":True}}
            def locked(path,onerror):
                error=PermissionError("fixture locked by shared worker")
                onerror(os.unlink,str(Path(path)/"locked.jar"),(PermissionError,error,None))
            with patch.object(sync.shutil,"rmtree",side_effect=locked):
                sync.retire_build_outputs(run,root,root/".upstream-sync")
            self.assertEqual(run["storage"]["cleanup_pending"][0]["path"],"locked.jar")
            self.assertTrue(build.exists())

    def test_deferred_cleanup_never_touches_a_reused_worktree(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve(); state=root/".upstream-sync"
            tree=root/".worktrees/upstream-20261104T000000Z-abcdef"; tree.mkdir(parents=True)
            old={"id":"20261104T000000Z-abcdef","worktree":str(tree),"finished_utc":"done",
                 "finished_head":"original", "storage":{"cleanup_pending":[{"path":"locked.jar"}]}}
            sync.save(state/"runs"/(old["id"]+".json"),old)
            with patch.object(sync,"git",return_value="new-development-head"), \
                 patch.object(sync,"retire_build_outputs") as cleanup:
                sync.retry_pending_cleanup(root,state)
                cleanup.assert_not_called()


if __name__ == "__main__":
    unittest.main()
