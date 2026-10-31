import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import generate as g

NOW = 1_793_318_400_000
LEAGUE = {"id": "football/nfl", "path": "football/nfl", "query": "", "calendar": None}
EVENT = {"id": "9", "date": "2026-10-30T20:00Z", "name": "A at B", "status": {"type": {"state": "pre"}}, "competitions": []}


class Responses:
    def __init__(self, payload=None):
        self.payload = payload
        self.clock = lambda: NOW / 1000
    def get(self, url, ttl):
        return self.payload


class ProducerTests(unittest.TestCase):
    def test_reads_existing_kotlin_catalogue_and_college_query(self):
        source = Path("app/src/main/java/com/nuvio/tv/livetv/sports/SportsCatalog.kt").read_text(encoding="utf-8")
        catalogue = g.catalogue(source)
        self.assertGreater(len([r for r in catalogue if r["path"]]), 30)
        college = next(r for r in catalogue if r["id"] == "football/college-football")
        self.assertEqual("&groups=80", college["query"])
        self.assertTrue(any(r["calendar"] for r in catalogue))

    def test_monthly_catalogue_replaces_daily_fanout_and_filters_outside_window(self):
        class Monthly(Responses):
            def __init__(self):
                super().__init__()
                self.urls = []
            def get(self, url, ttl):
                self.urls.append(url)
                return {"fetchedAt": NOW, "payload": {"events": [EVENT, {**EVENT, "id": "outside", "date": "2026-01-01T00:00Z"}]}}
        responses = Monthly()
        output = g.build([LEAGUE], responses, NOW, 1, 2, [LEAGUE["id"]])
        self.assertLessEqual(len(responses.urls), 2)
        self.assertTrue(all(len(url.split("dates=")[1].split("&")[0]) == 6 for url in responses.urls))
        self.assertEqual(["9"], [event["id"] for event in output["entries"][0]["payload"]["events"]])

    def test_valid_empty_days_remain_valid_not_provider_errors(self):
        output = g.build([LEAGUE], Responses({"fetchedAt": NOW, "payload": {"events": []}}), NOW, 0, 0, [LEAGUE["id"]])
        self.assertEqual([], output["entries"][0]["payload"]["events"])
        self.assertTrue(output["entries"][0]["complete"])

    def test_failed_source_preserves_old_data_and_its_actual_timestamp(self):
        previous = {"entries": [{"provider": "espn", "leagueId": LEAGUE["id"], "fetchedAt": NOW - 1000, "payload": {"events": [EVENT]}}]}
        output = g.build([LEAGUE], Responses(), NOW, 0, 0, [LEAGUE["id"]], previous)
        self.assertEqual(NOW - 1000, output["entries"][0]["fetchedAt"])
        self.assertEqual("9", output["entries"][0]["payload"]["events"][0]["id"])
        self.assertFalse(output["entries"][0]["complete"])
        with self.assertRaises(ValueError):
            g.build([LEAGUE], Responses(), NOW, 0, 0, [LEAGUE["id"]])

    def test_calendar_failure_preserves_last_good_source_and_team_logo_references_survive(self):
        league = {"id": "rugby/test", "path": None, "query": "", "calendar": "test-calendar"}
        previous = {"entries": [{"provider": "calendar", "leagueId": league["id"], "fetchedAt": NOW - 1000,
                                 "payload": {"events": [{"id": "fixture"}]}}]}
        output = g.build([league], Responses(), NOW, 0, 0, [league["id"]], previous)
        self.assertEqual(previous["entries"], output["entries"])
        event = {**EVENT, "competitions": [{"competitors": [{"team": {"id": "1", "logo": "https://example.org/team.png"}}]}]}
        self.assertEqual("https://example.org/team.png", g.trim_espn(event)["competitions"][0]["competitors"][0]["team"]["logo"])

    def test_generation_time_is_end_of_fetch_not_start(self):
        responses = Responses({"fetchedAt": NOW + 3000, "payload": {"events": [EVENT]}})
        responses.clock = lambda: NOW / 1000 + 4
        output = g.build([LEAGUE], responses, NOW, 0, 0, [LEAGUE["id"]])
        self.assertEqual(NOW + 4000, output["generatedAt"])
        self.assertEqual(NOW + 3000, output["entries"][0]["fetchedAt"])

    def test_cached_response_and_budget_avoid_network_and_keep_old_timestamp(self):
        with tempfile.TemporaryDirectory() as directory:
            fetcher = g.Fetcher(directory, budget=1, clock=lambda: NOW / 1000, sleep=lambda _: None)
            with patch("urllib.request.urlopen", return_value=io.BytesIO(b'{"events":[]}')) as network:
                first = fetcher.get("https://example.org/one", 60000)
                self.assertEqual(first, fetcher.get("https://example.org/one", 60000))
                self.assertIsNone(fetcher.get("https://example.org/two", 60000))
                self.assertEqual(1, network.call_count)
            restarted = g.Fetcher(directory, clock=lambda: NOW / 1000 + 120, sleep=lambda _: None)
            with patch("urllib.request.urlopen", side_effect=OSError()):
                self.assertEqual(first, restarted.get("https://example.org/one", 60000))

    def test_rate_limit_blocks_remaining_host_without_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            fetcher = g.Fetcher(directory, clock=lambda: NOW / 1000, sleep=lambda _: None)
            with patch("urllib.request.urlopen", side_effect=urllib.error.HTTPError("https://example.org/one", 429, "limited", {}, None)) as network:
                self.assertIsNone(fetcher.get("https://example.org/one", 1))
                self.assertIsNone(fetcher.get("https://example.org/two", 1))
                self.assertEqual(1, network.call_count)

    def test_public_output_excludes_artwork_and_other_league_fixture_records(self):
        responses = Responses({"fetchedAt": NOW, "payload": {"leagues": [{"idLeague": "4416", "strLeague": "NRL", "strBadge": "private-image"}],
            "events": [{"idEvent": "1", "idLeague": "4416", "strEvent": "A v B", "strThumb": "image"}, {"idEvent": "2", "idLeague": "4415"}]}})
        output = g.build([], responses, NOW, 0, 0, ["tsdb/4416"])
        self.assertNotIn("strBadge", output["metadata"]["leagues"][0])
        self.assertEqual(["1"], [row["idEvent"] for row in output["entries"][0]["payload"]["events"]])
        self.assertNotIn("strThumb", output["entries"][0]["payload"]["events"][0])
        self.assertNotIn("headlines", g.trim_espn({**EVENT, "headlines": "article"}))


if __name__ == "__main__":
    unittest.main()
