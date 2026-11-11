"""Restoration and refusal checks with no device access."""
import argparse
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.stability_run import Session, device_session, PACKAGE, SETTINGS, ACTIVITY


class RestorationTest(unittest.TestCase):
    def exercise(self, failure=None, existing=False, reverse=''):
        commands = []
        settings = {f'{ns}/{key}': str(i+1) for i, (ns, key) in enumerate(SETTINGS)}
        volume = ['7']

        def adb(*cmd, **kwargs):
            commands.append(cmd)
            if cmd == ('shell', 'getprop', 'ro.kernel.qemu'): return '0'
            if cmd == ('shell', 'getprop', 'ro.product.model'): return 'onn4KPro'
            if cmd[:4] == ('shell', 'pm', 'list', 'packages'): return 'package:'+PACKAGE if existing else ''
            if cmd[:3] == ('shell', 'settings', 'put'):
                settings[cmd[3]+'/'+cmd[4]] = cmd[5]
            if cmd[:4] == ('shell', 'cmd', 'media_session', 'volume') and '--set' in cmd:
                volume[0] = cmd[-1]
            if cmd == ('reverse', '--list'): return reverse
            if cmd[0] == 'install' and failure == 'install': raise RuntimeError('install interrupted')
            if cmd[0] == 'uninstall' and failure == 'uninstall': raise RuntimeError('uninstall refused')
            if cmd[0] in ('install', 'uninstall'): return 'Success'
            return ''

        def aapt(*cmd):
            return ACTIVITY if 'xmltree' in cmd else f"package: name='{PACKAGE}'\nlaunchable-activity: name='com.nuvio.tv.MainActivity'"

        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(output=Path(directory), apk=Path('fixture.apk'), aapt='aapt',
                adb='adb', serial='192.168.1.121:5555', device='onn')
            session = Session(args)
            session.adb = adb
            session.state = lambda: {'settings': dict(settings), 'productionVersion': '10051', 'volume': 'volume is '+volume[0]}
            original = dict(settings)
            with patch('scripts.stability_run.run', side_effect=aapt):
                with self.assertRaises((RuntimeError, ValueError)):
                    with device_session(session):
                        raise RuntimeError('workload interrupted')
            cleanup = json.loads((Path(directory)/'cleanup.json').read_text())
            self.assertEqual(original, settings)
            self.assertEqual('7', volume[0])
            self.assertFalse(any('io.github.abbaskip.nuvioreseq' in cmd and PACKAGE not in cmd for cmd in commands))
            return commands, cleanup

    def test_workload_failure_restores_all_owned_state(self):
        commands, cleanup = self.exercise(reverse='UsbFfs tcp:38791 tcp:38791\n')
        self.assertEqual([], cleanup['errors'])
        self.assertNotIn(('reverse', '--remove', 'tcp:38791'), commands)
        self.assertIn(('reverse', '--remove', 'tcp:38792'), commands)
        self.assertIn(('uninstall', PACKAGE), commands)

    def test_install_timeout_still_attempts_uninstall(self):
        commands, _ = self.exercise(failure='install')
        self.assertIn(('uninstall', PACKAGE), commands)

    def test_uninstall_failure_does_not_skip_settings_or_reverse_restoration(self):
        commands, cleanup = self.exercise(failure='uninstall')
        self.assertTrue(cleanup['errors'])
        self.assertIn(('reverse', '--remove', 'tcp:38793'), commands)

    def test_preexisting_package_is_untouched(self):
        commands, _ = self.exercise(existing=True)
        self.assertFalse(any(cmd[0] in ('install', 'uninstall', 'reverse') for cmd in commands))

    def test_conflicting_reverse_is_untouched(self):
        commands, _ = self.exercise(reverse='UsbFfs tcp:38791 tcp:11111\n')
        self.assertNotIn(('reverse', 'tcp:38791', 'tcp:38791'), commands)
        self.assertFalse(any(cmd[:3] == ('shell', 'settings', 'put') for cmd in commands))
