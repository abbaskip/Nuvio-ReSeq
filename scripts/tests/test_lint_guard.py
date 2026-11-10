import copy
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

import lint_guard as lint


def finding(severity=3, count=1, details=None):
    return {"id": "KnownError", "paths": ["app/src/main/Example.kt"], "severity": severity,
            "count": count, "levels": {str(severity): count}, "details": details or {"message": "same-error"}}


def baseline(current):
    return {"schema": 1, "origin": {"tag": "v0.1.0-beta.7.1"}, "seed": copy.deepcopy(current),
            "active": {key: {field:value[field] for field in ("count", "severity", "levels", "details")}
                       for key,value in copy.deepcopy(current).items()}}


class LintBudgetTest(unittest.TestCase):
    def test_existing_errors_are_reported_but_allowed(self):
        current = {"known": finding()}
        result = lint.compare(current, baseline(current))
        self.assertTrue(result["passed"])
        self.assertEqual(result["errors"], 1)

    def test_new_error_is_blocked(self):
        self.assertFalse(lint.compare({"new": finding()}, baseline({}))["passed"])

    def test_new_warning_does_not_become_a_future_error_allowance(self):
        original = baseline({})
        self.assertTrue(lint.compare({"new": finding(2)}, original)["passed"])
        self.assertNotIn("new", lint.ratchet({"new": finding(2)}, original)["active"])
        self.assertFalse(lint.compare({"new": finding(3)}, original)["passed"])

    def test_higher_count_severity_or_changed_details_is_blocked(self):
        original = baseline({"known": finding()})
        for current in (finding(count=2), finding(severity=4), finding(details={"message": "worse"})):
            with self.subTest(current=current):
                self.assertFalse(lint.compare({"known": current}, original)["passed"])

    def test_resolved_error_cannot_return(self):
        original = baseline({"known": finding()})
        shrunk = lint.ratchet({}, original)
        self.assertNotIn("known", shrunk["active"])
        verdict = lint.compare({"known": finding()}, shrunk)
        self.assertFalse(verdict["passed"])
        self.assertEqual(verdict["failures"][0]["kind"], "returned")

    def test_reduced_count_and_severity_are_permanent(self):
        original = baseline({"known": finding(4, 3)})
        shrunk = lint.ratchet({"known": finding(2, 1)}, original)
        for current in (finding(3, 1), finding(2, 2)):
            self.assertFalse(lint.compare({"known": current}, shrunk)["passed"])

    def test_existing_error_cannot_hide_a_warning_becoming_an_error(self):
        old = finding(3, 2)
        old["levels"] = {"2": 1, "3": 1}
        original = baseline({"same-location": old})
        self.assertFalse(lint.compare({"same-location": finding(3, 2)}, original)["passed"])

    def test_fixed_translation_language_cannot_regress(self):
        original = baseline({"translation": finding(details={"missing_locales": ["el", "pl"]})})
        reduced = {"translation": finding(details={"missing_locales": ["el"]})}
        shrunk = lint.ratchet(reduced, original)
        self.assertTrue(lint.compare(reduced, original)["passed"])
        self.assertFalse(lint.compare({"translation": finding(details={"missing_locales": ["el", "pl"]})}, shrunk)["passed"])
        self.assertFalse(lint.compare({"translation": finding(details={"missing_locales": ["el", "fr"]})}, original)["passed"])

    def test_failing_report_cannot_update_baseline(self):
        with self.assertRaises(ValueError): lint.ratchet({"new": finding()}, baseline({}))

    def test_baseline_cannot_expand_or_restore_retired_allowances(self):
        original = baseline({"known": finding()})
        for mutate in (lambda b: b["origin"].update(tag="other"),
                       lambda b: b["seed"].update(extra=finding()),
                       lambda b: b["active"]["known"].update(count=2),
                       lambda b: b["active"]["known"].update(severity=4)):
            changed = copy.deepcopy(original); mutate(changed)
            with self.assertRaises(ValueError): lint.monotonic(changed, original)
        retired = lint.ratchet({}, original)
        with self.assertRaises(ValueError): lint.monotonic(original, retired)

    def test_moved_lines_keep_identity_but_other_function_is_new(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", str(root)], capture_output=True, check=True)
            source = root / "Example.kt"
            text = "package test\n\n\nfun first() {\n    before()\n    risky()\n    after()\n}\n"
            source.write_text(text)
            subprocess.run(["git", "add", "Example.kt"], cwd=root, check=True)
            report = root / "lint.xml"
            def report_at(line):
                xml = ET.Element("issues")
                issue = ET.SubElement(xml, "issue", id="KnownError", severity="Error", message="danger", errorLine1="risky()")
                ET.SubElement(issue, "location", file=str(source), line=str(line))
                ET.ElementTree(xml).write(report)
                return lint.findings(report, root)[0]
            original = report_at(6)
            source.write_text("// moved down\n" + text)
            moved = report_at(7)
            self.assertEqual(original, moved)
            source.write_text(text.replace("first()", "differentFunction()"))
            other = report_at(6)
            self.assertNotEqual(set(original), set(other))


class VerifiedVersionAdviceTest(unittest.TestCase):
    old = "A newer version of example:library than 1.0.0 is available: 2.0.0"
    newer = "A newer version of example:library than 1.0.0 is available: 2.1.0"

    def scenario(self, message=None, severity=2, count=1, detector="GradleDependency", retired=False, bad_hash=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            released, current_report = root / "released.xml", root / "current.xml"
            def report(path, text, level):
                xml = ET.Element("issues")
                ET.SubElement(xml, "issue", id=detector, severity=level, message=text)
                ET.ElementTree(xml).write(path)
            report(released, self.old, "Warning")
            report(current_report, message or self.newer, "Error" if severity >= 3 else "Warning")
            old = finding(2, details={"message": lint.digest(self.old)})
            old["id"] = detector
            budget = baseline({"same-source": old})
            budget["origin"]["report_sha256"] = "incorrect" if bad_hash else lint.hashlib.sha256(released.read_bytes()).hexdigest()
            if retired: budget["active"].clear()
            current = finding(severity, count, {"message": lint.digest(message or self.newer)})
            current["id"] = detector
            source = {"same-source": current}
            frozen_budget, frozen_source = copy.deepcopy(budget), copy.deepcopy(source)
            adjusted, changes = lint.verified_version_advice(source, budget, current_report, released)
            self.assertEqual(frozen_budget, budget)
            self.assertEqual(frozen_source, source)
            return adjusted, changes, budget

    def test_verified_suggested_version_drift_keeps_complete_advice_and_exact_allowance(self):
        adjusted, changes, budget = self.scenario()
        self.assertTrue(lint.compare(adjusted, budget)["passed"])
        self.assertEqual("2.0.0", changes[0]["previous_available"])
        self.assertEqual("2.1.0", changes[0]["available"])
        self.assertEqual(budget, lint.ratchet(adjusted, budget))
        lint.monotonic(lint.ratchet(adjusted, budget), budget)

    def test_changed_declared_version_or_dependency_is_not_normalized(self):
        for message in (self.newer.replace("1.0.0", "1.1.0"), self.newer.replace("example:library", "other:library")):
            adjusted, changes, budget = self.scenario(message=message)
            self.assertFalse(lint.compare(adjusted, budget)["passed"])
            self.assertFalse(changes)

    def test_changed_warning_details_outside_version_are_not_normalized(self):
        adjusted, changes, budget = self.scenario(message=self.newer + " (security action required)")
        self.assertFalse(lint.compare(adjusted, budget)["passed"])
        self.assertFalse(changes)

    def test_severity_and_count_still_block(self):
        for severity, count in ((3, 1), (2, 2)):
            adjusted, _, budget = self.scenario(severity=severity, count=count)
            self.assertFalse(lint.compare(adjusted, budget)["passed"])

    def test_retired_finding_cannot_obtain_a_new_error_exemption(self):
        adjusted, changes, budget = self.scenario(severity=3, retired=True)
        self.assertFalse(lint.compare(adjusted, budget)["passed"])
        self.assertFalse(changes)

    def test_agp_advice_preserves_declared_version_and_count_protections(self):
        adjusted, changes, budget = self.scenario(detector="AndroidGradlePluginVersion")
        self.assertTrue(lint.compare(adjusted, budget)["passed"])
        self.assertEqual(len(changes), 1)
        for message, severity, count in ((self.newer.replace("1.0.0", "1.1.0"), 2, 1),
                                        (self.newer, 3, 1), (self.newer, 2, 2)):
            adjusted, _, budget = self.scenario(message, severity, count, detector="AndroidGradlePluginVersion")
            self.assertFalse(lint.compare(adjusted, budget)["passed"])

    def test_another_detector_is_never_normalized(self):
        adjusted, changes, budget = self.scenario(detector="ResourceType")
        self.assertFalse(lint.compare(adjusted, budget)["passed"])
        self.assertFalse(changes)

    def test_modified_reference_cannot_prove_advisory_drift(self):
        with self.assertRaisesRegex(ValueError, "immutable provenance"):
            self.scenario(bad_hash=True)


class ReadOnlyRatchetTests(unittest.TestCase):
    def invoke(self, current, budget, root):
        import contextlib
        import io
        from unittest.mock import patch
        path = root / "baseline.json"
        path.write_text(__import__('json').dumps(budget))
        argv = ['lint_guard.py', 'check', '--report', str(root / 'report.xml'),
                '--project', str(root), '--baseline', str(path), '--require-ratchet',
                '--summary', str(root / 'summary.json')]
        with patch('sys.argv', argv), patch.object(lint, 'findings', return_value=(current, 0)), \
                patch.object(lint, 'check_history'), contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(io.StringIO()):
            return lint.main()

    def test_resolved_allowance_requires_committed_retirement_without_mutating_file(self):
        import json
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            budget = baseline({'known': finding()})
            with self.assertRaisesRegex(ValueError, 'Retire resolved'):
                self.invoke({}, budget, root)
            self.assertEqual(budget, json.loads((root / 'baseline.json').read_text()))

    def test_new_error_still_writes_failed_verdict(self):
        import json
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(1, self.invoke({'new': finding()}, baseline({}), root))
            self.assertFalse(json.loads((root / 'summary.json').read_text())['passed'])


if __name__ == "__main__": unittest.main()
