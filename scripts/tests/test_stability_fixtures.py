"""Fixture contracts that prevent workloads silently exercising empty data."""
from datetime import datetime, timezone
import unittest
from scripts.stability_fixtures import sports, meta, PARTS, TOTAL_BYTES, PART_BYTES


class FixtureContractTest(unittest.TestCase):
    def test_live_cricket_timestamp_matches_existing_android_parser(self):
        entries = sports()['entries']
        self.assertEqual(2000, len(entries[0]['payload']['events']))
        cricket = next(e for e in entries if e['leagueId'] == 'cricket/19430')['payload']['events'][0]
        datetime.strptime(cricket['date'], '%Y-%m-%dT%H:%M:%SZ')
        self.assertEqual('77702', cricket['id'])
        self.assertEqual('in', cricket['competitions'][0]['status']['type']['state'])
        self.assertLess(entries[-1]['fetchedAt'], datetime.now(timezone.utc).timestamp()*1000-50_000)

    def test_tracker_metadata_has_all_episode_identities(self):
        videos = meta('tt9900003')['videos']
        self.assertEqual(list(range(1, 9)), [video['episode'] for video in videos])
        self.assertEqual('tt9900003:1:2', videos[1]['id'])

    def test_nntp_head_and_tail_cover_declared_file_boundaries(self):
        self.assertIn(f'size={TOTAL_BYTES}'.encode(), PARTS[1].split(b'\r\n')[0])
        self.assertIn(f'begin=1 end={PART_BYTES}'.encode(), PARTS[1])
        self.assertIn(f'end={TOTAL_BYTES}'.encode(), PARTS[32])
        self.assertNotIn(2, PARTS)  # Intermediate reads take the explicit stall path.
