"""Checks hand-maintained rules against Kotlin/Java packages and the shipped Media3 jars."""
import unittest

from verify_release_profiles import RULES, packages, source_packages


class HandwrittenProfileTest(unittest.TestCase):
    def test_each_rule_has_a_shipped_owner(self):
        available = source_packages()
        for prefix in packages(RULES.read_text().splitlines()):
            with self.subTest(package=prefix):
                self.assertTrue(any(pkg.startswith(prefix) for pkg in available))
