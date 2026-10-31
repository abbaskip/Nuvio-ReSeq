# Shared sports catalogue

A periodically generated public JSON supplies schedules/catalogue metadata to the Sports repository. Devices retain local EPG matching and make bounded live-score requests. No user playlists, tracking history, Gemini output or personal keys are collected.

## Generate locally

Requires Python 3.10+ and no third-party packages. Run from the repository root:

```shell
python -m unittest discover -s tools/sports-feed -p "test_*.py"
python tools/sports-feed/generate.py
```

The existing Kotlin catalogue supplies ESPN league IDs and calendar slugs, avoiding another manually maintained list. TheSportsDB uses documented public V1 key `123` unless a deployment-owned `THESPORTSDB_API_KEY` environment variable is supplied. Keys are not written to the output. No donor keys, donor-hosted JSON or Gemini harvesting are used.

The default window is seven days back and fourteen ahead. ESPN calendar-month requests cover that window with usually one or two calls per league; unsupported or potentially truncated monthly responses fall back to cached individual days. UTC boundary padding prevents late US games from being lost. Completed response files persist under ignored `build/sports-feed/cache`; routine data expires after 6 hours, league metadata after 24 hours and calendars after 12 hours. Requests are paced per host. HTTP 429 stops remaining requests to that host for the run. A hard request budget and workflow timeout bound each run. Source failures retain valid cached data with its original timestamp; empty valid lists remain empty. A failed run does not replace the existing output. Coverage remains limited by each provider's free responses.

`--only football/nfl,basketball/nba,tsdb/4416 --days-back 1 --days-ahead 2 --max-requests 12` is a small integration smoke test. Running it again should reuse the cached responses. Avoid mixing a restricted smoke-test output with the full production output path.

## App configuration

Set repository-local `SPORTS_BASE_FEED_URL` or build environment `NUVIO_SPORTS_BASE_FEED_URL` to the chosen HTTPS `sports-base.json` endpoint. An explicitly empty development configuration can retain direct API mode; public builds use the repository-hosted default endpoint.

With a configured feed, the device checks at most once per 6 hours using ETag validators; failures back off 15 minutes and retain the last valid snapshot for up to 7 days. Invalid/oversized/future-dated/rolled-back JSON is rejected before replacing the saved copy. Failure never triggers a bulk per-league provider fallback. Expired catalogue data is not displayed indefinitely. EPG discovery remains available offline.

Routine multi-day/multi-league schedule and calendar fetches move to the generator. On-device ESPN scoreboard rounds are limited to two relevant league/date requests per 30 seconds, each league at most once per minute, prioritising the opened or watched event and followed competitions. Without Sports on screen, only a relevant watched/opened event qualifies. Known source timestamps and identities are retained. Snapshot LIVE values never masquerade as a fresh live score. Existing followed-team season, team-picker, event-details and broadcaster requests remain cached/on-demand.

For local testing, serve `build/sports-feed` with `python -m http.server 8765 --bind 127.0.0.1 --directory build/sports-feed`; a Debug build may use `http://127.0.0.1:8765/sports-base.json` with an ADB reverse mapping. HTTP is rejected for this feed in release builds. Do not replace someone's active TV test installation without coordinating.

## Scheduled publication

`.github/workflows/refresh-sports-feed.yml` runs every six hours and supports manual dispatch on this repository’s default branch. It tests and generates the feed, stages only `sports-base.json`, and deploys a GitHub Pages artifact. The application uses `https://abbaskip.github.io/Nuvio-ReSeq/sports-base.json` by default; a build-time override remains available.

GitHub Pages must use the Actions build type. The generator job has read-only repository permissions; only the deployment job receives Pages write and OIDC token permissions. No workflow writes application source, release assets or Git history. A failed generation does not deploy a replacement. A deployment-owned TheSportsDB key may be supplied through `THESPORTSDB_API_KEY`; otherwise the documented public free V1 key is used. No personal AI or account keys belong in the generator.
