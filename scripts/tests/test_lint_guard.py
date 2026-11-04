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


if __name__ == "__main__": unittest.main()
