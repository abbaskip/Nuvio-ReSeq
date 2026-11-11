"""Prevent fixture opt-in from dropping existing debug instrumentation hosts."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


class FixtureManifestTest(unittest.TestCase):
    def test_fixture_debug_preserves_existing_debug_components(self):
        root = Path(__file__).resolve().parents[2]
        original = ET.parse(root/'app/src/debug/AndroidManifest.xml').getroot().find('application')
        fixture = ET.parse(root/'app/src/stabilityFixtures/debug/AndroidManifest.xml').getroot().find('application')
        declarations = {(node.tag, tuple(sorted(node.attrib.items()))) for node in fixture}
        for node in original:
            self.assertIn((node.tag, tuple(sorted(node.attrib.items()))), declarations)
