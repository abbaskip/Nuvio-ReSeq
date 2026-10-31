"""Produce one public sports catalogue. Standard library only; never consumes user/playlist data."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request

DAY = 86_400_000
SCHEMA = 1
SEEDS = ["4416", "4415", "4461", "5806", "5282"]
DISCOVERY = ["Rugby", "Cricket", "Soccer", "Basketball", "American Football", "Baseball", "Ice Hockey", "Swimming", "Athletics", "Snooker", "Gymnastics", "Cycling", "Fighting", "Tennis", "Golf"]


def catalogue(source):
    # Read the application's existing constructor declarations, rather than maintain a second league list.
    source = re.sub(r"//[^\n]*", "", source)
    pattern = r'League\("([^"\n]+)",\s*Sport\.[A-Z_]+,\s*"[^"\n]*",\s*"[^"\n]*"(?:,\s*"([^"\n]+)")?'
    result = []
    for match in re.finditer(pattern, source):
        start = match.start()
        depth = 0
        quote = False
        escaped = False
        end = start
        for end in range(source.index("(", start), len(source)):
            ch = source[end]
            if ch == '"' and not escaped:
                quote = not quote
            if not quote:
                if ch == "(": depth += 1
                elif ch == ")":
                    depth -= 1
                    if depth == 0: break
            escaped = ch == "\\" and not escaped
        block = source[start:end + 1]
        query = re.search(r'espnQuery\s*=\s*"([^"\n]*)"', block)
        slug = re.search(r'fixtureSlug\s*=\s*"([a-z0-9-]+)"', block)
        result.append({"id": match[1], "path": match[2], "query": query[1] if query else "", "calendar": slug[1] if slug else None})
    if not result or len({row["id"] for row in result}) != len(result):
        raise ValueError("Unable to read the application league catalogue")
    return result


class Fetcher:
    def __init__(self, cache, budget=600, clock=time.time, sleep=time.sleep):
        self.cache = Path(cache)
        self.cache.mkdir(parents=True, exist_ok=True)
        self.budget = budget
        self.requests = 0
        self.clock = clock
        self.sleep = sleep
        self.next_at = {}
        self.blocked = set()
        self.errors = 0

    def get(self, url, ttl):
        path = self.cache / (hashlib.sha256(url.encode()).hexdigest() + ".json")
        saved = None
        try:
            candidate = json.loads(path.read_text())
            if isinstance(candidate["payload"], dict) and 0 <= self.clock() * 1000 - candidate["fetchedAt"] <= 7 * DAY:
                saved = candidate
                if self.clock() * 1000 - saved["fetchedAt"] < ttl:
                    return saved
        except (OSError, ValueError, KeyError, TypeError):
            pass
        host = urllib.parse.urlsplit(url).hostname
        if self.requests >= self.budget or host in self.blocked:
            return saved
        self.sleep(max(0, self.next_at.get(host, 0) - self.clock()))
        self.next_at[host] = self.clock() + (2.2 if host == "www.thesportsdb.com" else 0.3)
        self.requests += 1
        try:
            request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "Nuvio-ReSeq-Sports/1"})
            with urllib.request.urlopen(request, timeout=25) as response:
                data = response.read(4 * 1024 * 1024 + 1)
            if len(data) > 4 * 1024 * 1024:
                raise ValueError("Oversized provider response")
            payload = json.loads(data)
            if not isinstance(payload, dict) or not any(k in payload for k in ("events", "event", "leagues", "countries", "tvevent")):
                raise ValueError("Invalid provider schema")
            saved = {"fetchedAt": int(self.clock() * 1000), "payload": payload}
            temp = path.with_suffix(".tmp")
            temp.write_text(json.dumps(saved, separators=(",", ":")), encoding="utf-8")
            temp.replace(path)
        except urllib.error.HTTPError as error:
            self.errors += 1
            if error.code == 429:
                self.blocked.add(host)  # No retries or alternate IPs/keys to work around limits.
        except (OSError, ValueError):
            self.errors += 1
        return saved


def trim_espn(event):
    # Publish fields read by SportsFeeds, including existing team logo URL references; no articles or media files.
    def keep(obj, keys):
        return {key: obj[key] for key in keys if key in obj}
    result = keep(event, ("id", "date", "endDate", "name", "shortName", "status", "week"))
    comps = []
    for comp in event.get("competitions", []):
        c = keep(comp, ("id", "date", "status", "broadcasts", "geoBroadcasts", "notes", "venue", "situation", "type"))
        competitors = []
        for side in comp.get("competitors", []):
            s = keep(side, ("id", "homeAway", "score", "winner", "records", "linescores", "order"))
            for key in ("team", "athlete"):
                if isinstance(side.get(key), dict):
                    s[key] = keep(side[key], ("id", "name", "displayName", "shortDisplayName", "shortName", "fullName", "abbreviation", "location", "color", "alternateColor", "logo", "logos"))
            competitors.append(s)
        c["competitors"] = competitors
        comps.append(c)
    result["competitions"] = comps
    return result


def tsdb_url(endpoint, **params):
    key = os.environ.get("THESPORTSDB_API_KEY", "123")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", key):
        raise ValueError("Invalid TheSportsDB key format")
    return "https://www.thesportsdb.com/api/v1/json/" + key + "/" + endpoint + "?" + urllib.parse.urlencode(params)


def rows(payload, *keys):
    for key in keys:
        if isinstance(payload.get(key), list):
            return [row for row in payload[key] if isinstance(row, dict)]
    return []


def build(leagues, fetcher, now, days_back=7, days_ahead=14, only=None, previous=None):
    today = dt.datetime.fromtimestamp(now / 1000, dt.timezone.utc).date()
    entries = []
    metadata = {str(row["idLeague"]): row for row in (previous or {}).get("metadata", {}).get("leagues", []) if row.get("idLeague")}
    selected = set(only or [])
    previous_entries = {(e["provider"], e["leagueId"]): e for e in (previous or {}).get("entries", [])}
    for league in leagues:
        if selected and league["id"] not in selected:
            continue
        old = previous_entries.get(("espn", league["id"]))
        if league["path"]:
            events, times, missing = {}, [], []
            wanted = [today + dt.timedelta(days=offset) for offset in range(-days_back, days_ahead + 1)]
            # ESPN accepts a calendar month even where arbitrary date ranges fail. Pad for US/UTC midnight.
            months = sorted({(day + dt.timedelta(days=padding)).strftime("%Y%m") for day in wanted for padding in (-1, 0, 1)})
            for month in months:
                prefix = "https://site.api.espn.com/apis/site/v2/sports/" + league["path"] + "/scoreboard?dates="
                suffix = "&limit=1000" + league["query"]
                monthly = fetcher.get(prefix + month + suffix, 6 * 3_600_000)
                available = monthly and isinstance(monthly["payload"].get("events"), list)
                # A response at the limit may be truncated: use cached individual days for that month.
                monthly_rows = rows(monthly["payload"], "events") if available else []
                if available and len(monthly_rows) < 1000:
                    times.append(monthly["fetchedAt"])
                    for event in monthly_rows:
                        if isinstance(event.get("id"), str) and wanted[0].isoformat() <= str(event.get("date", ""))[:10] <= wanted[-1].isoformat():
                            events[event["id"]] = trim_espn(event)
                    continue
                for day in wanted:
                    if day.strftime("%Y%m") != month:
                        continue
                    saved = fetcher.get(prefix + day.strftime("%Y%m%d") + suffix, 6 * 3_600_000)
                    if saved is None or not isinstance(saved["payload"].get("events"), list):
                        missing.append(day.isoformat())
                        continue
                    times.append(saved["fetchedAt"])
                    for event in rows(saved["payload"], "events"):
                        if isinstance(event.get("id"), str) and event.get("date"):
                            events[event["id"]] = trim_espn(event)
            if missing and old:
                for event in rows(old["payload"], "events"):
                    if str(event.get("date", ""))[:10] in missing:
                        events.setdefault(event["id"], event)
                times.append(old["fetchedAt"])
            if times:
                entries.append({"provider": "espn", "leagueId": league["id"], "fetchedAt": min(times), "complete": not missing,
                                "payload": {"events": list(events.values())}})
        if league["calendar"]:
            saved = fetcher.get("https://sporting-events.org/data/" + league["calendar"] + ".json", 12 * 3_600_000)
            if saved and isinstance(saved["payload"].get("events"), list):
                entries.append({"provider": "calendar", "leagueId": league["id"], **saved})
            elif ("calendar", league["id"]) in previous_entries:
                entries.append(previous_entries[("calendar", league["id"])])
    ids = set(SEEDS if not selected else [value.removeprefix("tsdb/") for value in selected if value.startswith("tsdb/")])
    if not selected:
        for sport in DISCOVERY:
            saved = fetcher.get(tsdb_url("search_all_leagues.php", s=sport), DAY)
            if saved:
                for row in rows(saved["payload"], "leagues", "countries"):
                    if str(row.get("idLeague", "")).isdigit():
                        metadata[row["idLeague"]] = row
                        ids.add(row["idLeague"])
    for ident in sorted(ids, key=lambda ident: (ident not in SEEDS, ident)):
        saved = fetcher.get(tsdb_url("lookupleague.php", id=ident), DAY)
        if saved:
            for row in rows(saved["payload"], "leagues", "countries"):
                if str(row.get("idLeague")) == ident:
                    metadata[ident] = row
        parts = []
        for endpoint, params in [("eventsnextleague.php", {"id": ident}), ("eventspastleague.php", {"id": ident}),
                                 ("eventsday.php", {"d": today.isoformat(), "l": ident})]:
            saved = fetcher.get(tsdb_url(endpoint, **params), 6 * 3_600_000)
            if saved:
                parts.append(saved)
        if parts:
            events = {str(row["idEvent"]): row for part in parts for row in rows(part["payload"], "events", "event")
                      if row.get("idEvent") and str(row.get("idLeague")) == ident}
            # Free metadata is partial. Retain verified older rows when a partial list drops them.
            old = previous_entries.get(("tsdb", "tsdb/" + ident))
            if old:
                for row in rows(old["payload"], "events"):
                    if str(row.get("dateEvent", "")) >= (today - dt.timedelta(days=days_back)).isoformat():
                        events.setdefault(str(row["idEvent"]), row)
            entries.append({"provider": "tsdb", "leagueId": "tsdb/" + ident, "fetchedAt": min(p["fetchedAt"] for p in parts),
                            "complete": False, "payload": {"events": [strip_artwork(row) for row in events.values()]}})
        elif ("tsdb", "tsdb/" + ident) in previous_entries:
            entries.append(previous_entries[("tsdb", "tsdb/" + ident)])
    published_at = max(now, int(fetcher.clock() * 1000))
    entries = [entry for entry in entries if published_at - 7 * DAY <= entry["fetchedAt"] <= published_at]
    if not entries:
        raise ValueError("No valid source responses; existing output must remain untouched")
    return {"schemaVersion": SCHEMA, "generatedAt": published_at, "windowStart": str(today - dt.timedelta(days=days_back)),
            "windowEnd": str(today + dt.timedelta(days=days_ahead)), "sources": ["ESPN", "TheSportsDB", "sporting-events.org"],
            "metadata": {"leagues": [strip_artwork(row) for row in metadata.values()]}, "entries": entries}


def strip_artwork(row):
    return {key: value for key, value in row.items() if not any(word in key.lower() for word in ("badge", "fanart", "poster", "thumb", "banner", "logo", "artwork", "cutout", "facebook", "twitter", "youtube", "instagram", "website"))}


def write_feed(feed, output):
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    data = json.dumps(feed, ensure_ascii=False, separators=(",", ":")).encode()
    if len(data) > 12 * 1024 * 1024:
        raise ValueError("Feed exceeds client size limit; split coverage before publishing")
    tmp = output.with_suffix(".tmp")
    tmp.write_bytes(data)
    tmp.replace(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalogue", type=Path, default=Path("app/src/main/java/com/nuvio/tv/livetv/sports/SportsCatalog.kt"))
    parser.add_argument("--output", type=Path, default=Path("build/sports-feed/sports-base.json"))
    parser.add_argument("--cache", type=Path, default=Path("build/sports-feed/cache"))
    parser.add_argument("--days-back", type=int, default=7)
    parser.add_argument("--days-ahead", type=int, default=14)
    parser.add_argument("--max-requests", type=int, default=1800)
    parser.add_argument("--only", help="Comma-separated league IDs for bounded local testing")
    args = parser.parse_args()
    if not 0 <= args.days_back <= 7 or not 0 <= args.days_ahead <= 35 or not 1 <= args.max_requests <= 2500:
        parser.error("Window/request budget outside supported bounds")
    previous = None
    try:
        previous = json.loads(args.output.read_text())
    except (OSError, ValueError):
        pass
    fetcher = Fetcher(args.cache, args.max_requests)
    result = build(catalogue(args.catalogue.read_text(encoding="utf-8")), fetcher, int(time.time() * 1000),
                   args.days_back, args.days_ahead, args.only.split(",") if args.only else None, previous)
    write_feed(result, args.output)
    print(json.dumps({"entries": len(result["entries"]), "provider_requests": fetcher.requests,
                      "request_budget": fetcher.budget, "failed_requests": fetcher.errors, "rate_limited_hosts": sorted(fetcher.blocked),
                      "coverage": {provider: sum(entry["provider"] == provider for entry in result["entries"]) for provider in ("espn", "tsdb", "calendar")},
                      "bytes": args.output.stat().st_size}))


if __name__ == "__main__":
    main()
