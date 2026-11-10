"""Release-result and private-cache safety, independent of historical test counts."""
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import unittest

from ci_pipeline import ROOT, verify_test_results


class ResultTests(unittest.TestCase):
    def test_missing_skipped_and_failing_results_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                verify_test_results(root)
            for attrs in ('tests="1" skipped="1"', 'tests="2" failures="1"', 'tests="1" errors="1"'):
                (root / "TEST-suite.xml").write_text(f'<testsuite {attrs}/>')
                with self.assertRaises(ValueError):
                    verify_test_results(root)

    def test_valid_small_suite_passes_without_count_floor(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "TEST-suite.xml").write_text('<testsuite tests="2" skipped="1" failures="0" errors="0"/>')
            self.assertEqual(1, verify_test_results(root))


@unittest.skipIf(os.name == "nt" or not shutil.which("openssl"), "Linux CI cache transport")
class PrivateCacheTests(unittest.TestCase):
    def test_encrypted_round_trip_and_tamper_rejection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = root / "runner"
            runner.mkdir()
            home = root / "gradle"
            cache = home / "caches/build-cache-1"
            cache.mkdir(parents=True)
            item = cache / "fixture"
            marker = b"synthetic private build output, never plaintext in the shared cache"
            item.write_bytes(marker)
            env = dict(os.environ, GRADLE_USER_HOME=str(home), RUNNER_TEMP=str(runner),
                       NUVIO_CACHE_KEY=secrets.token_urlsafe(32))
            def run(mode):
                subprocess.run(["bash", str(ROOT / "scripts/ci-cache.sh"), mode], env=env,
                               check=True, capture_output=True)
            run("save")
            archive = runner / "nuvio-task-cache/outputs.enc"
            self.assertNotIn(marker, archive.read_bytes())
            item.unlink()
            run("restore")
            self.assertEqual(marker, item.read_bytes())
            item.unlink()
            data = bytearray(archive.read_bytes())
            data[-1] ^= 1
            archive.write_bytes(data)
            run("restore")
            self.assertFalse(item.exists(), "Unauthenticated task outputs must not be extracted")


if __name__ == "__main__":
    unittest.main()
