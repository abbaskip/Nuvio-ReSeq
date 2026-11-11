"""Safety and false-pass regression checks; no adb connection is used."""
import argparse
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from scripts.stability_smoke import PACKAGE, apk_identity, crash_lines, smoke, validate_emulator


class SmokeSafetyTest(unittest.TestCase):
    def test_release_like_separate_package(self):
        self.assertEqual("com.nuvio.tv.MainActivity", apk_identity(
            f"package: name='{PACKAGE}' versionCode='10051'\n"
            "launchable-activity: name='com.nuvio.tv.MainActivity'\n"))

    def test_production_debug_and_missing_launcher_are_rejected(self):
        for text in ["package: name='io.github.abbaskip.nuvioreseq'\n",
                     f"package: name='{PACKAGE}'\napplication-debuggable\n",
                     f"package: name='{PACKAGE}'\n"]:
            with self.subTest(text=text), self.assertRaises(ValueError):
                apk_identity(text)

    def test_physical_device_cannot_be_selected(self):
        for serial, qemu in [("192.168.1.121:5555", "1"), ("emulator-5554", "0"),
                             ("USB123", "0")]:
            with self.subTest(serial=serial), self.assertRaises(ValueError):
                validate_emulator(serial, qemu, "feature:android.software.leanback", "")

    def test_existing_installation_and_phone_image_are_rejected(self):
        with self.assertRaises(ValueError):
            validate_emulator("emulator-5554", "1", "feature:android.software.leanback",
                              f"package:{PACKAGE}\n")
        with self.assertRaises(ValueError):
            validate_emulator("emulator-5554", "1", "feature:android.hardware.touchscreen", "")
        validate_emulator("emulator-5554", "1", "feature:android.software.leanback\n", "")

    def test_java_native_and_system_anr_events_fail(self):
        for event in ["AndroidRuntime: FATAL EXCEPTION: worker", "libc: Fatal signal 11 (SIGSEGV)",
                      "am_crash: [0,123,app]", "am_anr: [0,123,app]", "ActivityManager: ANR in app",
                      "ANR of ProcessRecord{123:app}"]:
            with self.subTest(event=event):
                self.assertEqual([event], crash_lines(event))
        self.assertEqual([], crash_lines("No ANR has occurred\nActivityManager: Start proc 123"))

    def test_background_crash_fails_result_and_uninstalls_only_test_package(self):
        commands = []

        def fake_run(*command, **kwargs):
            commands.append(command)
            if command[0] == "aapt":
                return (f"package: name='{PACKAGE}'\n"
                        "launchable-activity: name='com.nuvio.tv.MainActivity'\n")
            operation = command[3:]
            if operation == ("shell", "getprop", "ro.kernel.qemu"):
                return "1\n"
            if operation == ("shell", "pm", "list", "features"):
                return "feature:android.software.leanback\n"
            if operation[:1] in [("install",), ("uninstall",)]:
                return "Success\n"
            if operation[:3] == ("shell", "am", "start"):
                return "Status: ok\n"
            if operation[:2] == ("shell", "pidof"):
                return "123\n"
            return ""

        def logger(*args, **kwargs):
            kwargs["stdout"].write("AndroidRuntime: FATAL EXCEPTION: background\n")
            return Mock(poll=Mock(return_value=None))

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "result"
            args = argparse.Namespace(serial="emulator-5554", apk=Path("test.apk"),
                                      adb="adb", aapt="aapt", seconds=60, output=output)
            with patch("scripts.stability_smoke.run", side_effect=fake_run), \
                    patch("scripts.stability_smoke.subprocess.Popen", side_effect=logger), \
                    patch("scripts.stability_smoke.time.monotonic", side_effect=[0, 61, 61]):
                with self.assertRaisesRegex(RuntimeError, "Crash/ANR"):
                    smoke(args)
            result = json.loads((output / "result.json").read_text())
            self.assertEqual("failed", result["status"])
            self.assertEqual(1, len(result["crash_anr_lines"]))
            self.assertIn(("adb", "-s", "emulator-5554", "uninstall", PACKAGE), commands)
            self.assertNotIn(("adb", "-s", "emulator-5554", "uninstall", "io.github.abbaskip.nuvioreseq"), commands)


if __name__ == "__main__":
    unittest.main()
