import sys
from pathlib import Path
import unittest
from types import SimpleNamespace
from unittest.mock import Mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from stability_stage1 import completed_offer, membership, EXPECTED, CONTROL
from stability_run import device_session


def sample(episode=2, detail=False):
    intent = '#Intent;S.launchMode=detail;end' if detail else f'#Intent;i.episode={episode};S.launchMode=stream;end'
    return {'cwIds': list(EXPECTED), 'snapshot': [f'{id}:{episode}' for id in EXPECTED],
            'launcher': [CONTROL+'|'+intent], 'watchNext': ['wn_'+CONTROL+'|'+intent]}


class AssertionsTest(unittest.TestCase):
    def test_physical_devices_rejected_before_any_query_or_cleanup(self):
        session = Mock(args=SimpleNamespace(device='onn', serial='192.0.2.1:5555'))
        with self.assertRaises(ValueError):
            with device_session(session):
                self.fail('Physical-device session entered')
        session.adb.assert_not_called()
        session.stop_log.assert_not_called()

    def test_proven_next_and_series_choice_pass(self):
        completed_offer(sample())
        completed_offer(sample(0, detail=True))

    def test_finished_episode_fails(self):
        with self.assertRaises(AssertionError):
            completed_offer(sample(1))

    def test_missing_watch_next_fails(self):
        value = sample()
        value['watchNext'] = []
        with self.assertRaises(AssertionError):
            completed_offer(value)

    def test_identity_pending_disappearance_fails(self):
        value = sample()
        value['cwIds'].remove('tt9900002')
        with self.assertRaises(AssertionError):
            membership(value)


if __name__ == '__main__':
    unittest.main()
