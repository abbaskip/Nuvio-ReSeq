"""Synthetic-only localhost fixtures. Ports 38791/2/3 are reversed through explicit adb serials."""
from __future__ import annotations
import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
from http.server import ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
from socketserver import ThreadingTCPServer
from threading import Thread, Lock
import time
import struct
from zlib import crc32
from urllib.parse import urlsplit, parse_qs

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("profile_fixtures", ROOT / "baselineprofile/fixtures/server.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
COUNTS = Counter()
LOCK = Lock()
STATE = {"tvdb": "available", "completed": False, "slowImport": False}
SHOWS = ["tt9900001", "tt9900002", "tt9900003"]
# A tracker-backed alternate order. Native E3 is display E5; native E7
# was watched earlier than E4, exercising latest-by-time with a later badge.
TRACKER_TITLES = ['The Arrival', 'Broken Compass', 'North Crossing', 'Open Water',
                  'Old Friends', 'The Return', 'Lost Weekend', 'Second Chance']
TRACKER_DVD = [1, 2, 5, 4, 3, 6, 7, 8]
TRACKER_HISTORY = {}


def tracker_time(seconds_ago):
    return (datetime.now(timezone.utc) - timedelta(seconds=seconds_ago)).strftime("%Y-%m-%dT%H:%M:%S.000Z")


def tracker_episode(number):
    return {'season': 1, 'number': number, 'title': TRACKER_TITLES[number-1],
            'first_aired': '2020-01-01T00:00:00Z',
            'ids': {'trakt': 100+number, 'tvdb': 990100+number}}


PART_BYTES = 262144
TOTAL_BYTES = PART_BYTES * 32


def nntp_part(number):
    # A real MP4 followed by a valid large free atom. Head/tail pass file validation;
    # middle articles stall only once the HTTP playback path starts reading ahead.
    data = (base.CLIP + struct.pack('>I', TOTAL_BYTES-len(base.CLIP)) + b'free').ljust(PART_BYTES, b'\0') if number == 1 else bytes(PART_BYTES)
    lines = [f'=ybegin part={number} total=32 line=128 size={TOTAL_BYTES} name=fixture.mp4'.encode(),
             f'=ypart begin={(number-1)*PART_BYTES+1} end={number*PART_BYTES}'.encode()]
    for offset in range(0, len(data), 64):
        encoded = bytearray()
        for byte in data[offset:offset+64]:
            value = (byte+42) & 255
            encoded.extend((61, (value+64) & 255) if value in (0, 10, 13, 61) else (value,))
        lines.append(b'.'+encoded if encoded.startswith(b'.') else encoded)
    lines.extend([f'=yend size={len(data)} part={number} pcrc32={crc32(data):08x}'.encode(), b'.', b''])
    return b'\r\n'.join(lines)


PARTS = {number: nntp_part(number) for number in (1, 32)}


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")


def meta(show):
    if STATE.get('trackerUnwatch') and show == SHOWS[1]:
        return {'id': show, 'imdb_id': show, 'type': 'series', 'name': 'Fixture ' + show,
                'videos': [dict(tracker_episode(i), id=f'{show}:1:{i}', episode=i,
                                released='2020-01-01T00:00:00.000Z') for i in range(1, 9)]}
    return {"id": show, "type": "series", "name": "Fixture " + show,
            "videos": [{"id": f"{show}:1:{i}", "title": f"Episode {i}", "season": 1,
                        "episode": i, "released": "2020-01-01T00:00:00.000Z"} for i in range(1, 9)]}


def sports():
    data = base.sports()
    # A schedule is older than the live scoreboard fetched by the TV. Otherwise
    # tiny host/device clock skew lets the schedule's PRE projection win the merge.
    data['generatedAt'] -= 60_000
    for entry in data['entries']:
        entry['fetchedAt'] = data['generatedAt']
    prototype = data["entries"][0]["payload"]["events"][0]
    data["entries"][0]["payload"]["events"] = [dict(prototype, id=str(80000+i),
        name=f"Fixture game {i}") for i in range(2000)]
    now = datetime.now(timezone.utc)
    cricket = {"id": "77702", "date": (now-timedelta(hours=3)).strftime('%Y-%m-%dT%H:%M:%SZ'),
        "name": "Australia vs England", "competitions": [{"competitors": [
            {"homeAway": side, "score": "325/6", "team": {"id": str(i), "name": name,
             "displayName": name, "shortDisplayName": name, "abbreviation": name[:3].upper()}}
            for i, (name, side) in enumerate([("Australia", "home"), ("England", "away")], 1)],
        "status": {"type": {"state": "in", "name": "STATUS_IN_PROGRESS", "shortDetail": "Day 2 - Session 2"}},
        "broadcasts": [{"names": ["Fixture Cricket 001"]}]}]}
    data["entries"].append({"provider": "espn", "leagueId": "cricket/19430",
                            "fetchedAt": data["generatedAt"], "payload": {"events": [cricket]}})
    return data


def guide():
    now = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
    yield b'<tv>'
    for channel in base.CHANNELS:
        i = channel['stream_id']
        yield f'<channel id="channel{i}"><display-name>{channel["name"]}</display-name></channel>'.encode()
    for channel in base.CHANNELS:
        i = channel['stream_id']
        for hour in range(-6, 42):
            start = (now+timedelta(hours=hour)).strftime('%Y%m%d%H%M%S +0000')
            end = (now+timedelta(hours=hour+1)).strftime('%Y%m%d%H%M%S +0000')
            yield (f'<programme channel="channel{i}" start="{start}" stop="{end}">'
                   '<title>Australia vs England - Live Test Cricket</title><category>Sports</category>'
                   '<desc>Local synthetic Test match guide entry.</desc></programme>').encode()
    yield b'</tv>'


class Web(base.Web):
    def send_json(self, value, status=200):
        body = json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        # Deliberately never log request bodies or authorization headers.
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        path = urlsplit(self.path).path
        if STATE.get('trackerUnwatch') and path == '/trakt/sync/history/remove':
            data = json.loads(body)
            removed = 0
            with LOCK:
                for show in data.get('shows', []):
                    if show['ids'].get('imdb') == SHOWS[1] or show['ids'].get('trakt') == 2:
                        for season in show['seasons']:
                            for episode in season['episodes']:
                                if season['number'] == 1 and TRACKER_HISTORY.pop(episode['number'], None):
                                    removed += 1
                COUNTS['tracker-removed-episodes'] += removed
            return self.send_json({'deleted': {'episodes': removed}})
        if path == '/tvdb/v4/login':
            return self.send_json({'status': 'success', 'data': {'token': 'fixture-token'}})
        if path.startswith('/trakt/scrobble/'):
            return self.send_json({'id': 1, 'action': 'pause', 'progress': 50})
        self.send_json({}, 404)

    def do_GET(self):
        path = urlsplit(self.path).path
        query = parse_qs(urlsplit(self.path).query)
        with LOCK:
            COUNTS[path] += 1
        if path == '/control':
            if 'trackerUnwatch' in query:
                with LOCK:
                    STATE['trackerUnwatch'] = query['trackerUnwatch'][0] == 'true'
                    STATE['trackerPausedAt'] = tracker_time(1800)
                    TRACKER_HISTORY.clear()
                    TRACKER_HISTORY.update({7: tracker_time(3600), 4: tracker_time(600), 3: tracker_time(60)})
                    COUNTS['tracker-removed-episodes'] = 0
            if 'tvdb' in query:
                STATE['tvdb'] = query['tvdb'][0]
            if 'completed' in query:
                STATE['completed'] = query['completed'][0] == 'true'
            if 'slowImport' in query:
                STATE['slowImport'] = query['slowImport'][0] == 'true'
            return self.send_json(STATE)
        if path == '/stats':
            return self.send_json({'requests': dict(COUNTS), 'state': STATE,
                                  'channels': len(base.CHANNELS), 'programmes': len(base.CHANNELS)*48,
                                  'movies': len(base.MOVIES), 'sportsEvents': 2001})
        if path == '/sports':
            return self.send_json(sports())
        if path.startswith('/scores/'):
            entries = sports()['entries']
            if path.endswith('/scoreboard'):
                return self.send_json(next((e['payload'] for e in entries if e['leagueId'] in path), {'events': []}))
            if path.endswith('/summary'):
                return self.send_json({'header': {'competitions': entries[-1]['payload']['events'][0]['competitions']}})
            return self.send_json({})
        if path == '/player_api.php' and query.get('action') == ['get_vod_streams'] and STATE['slowImport']:
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(b'[')
            for index, movie in enumerate(base.MOVIES):
                self.wfile.write(((',' if index else '')+json.dumps(movie)).encode())
                if index % 100 == 0:
                    self.wfile.flush()
                    time.sleep(.15)
            self.wfile.write(b']')
            self.close_connection = True
            return
        if path == '/xmltv.php':
            self.send_response(200)
            self.send_header('Content-Type', 'application/xml')
            self.send_header('Connection', 'close')
            self.end_headers()
            for chunk in guide():
                self.wfile.write(chunk)
            self.close_connection = True
            return
        if path == '/addon/manifest.json':
            return self.send_json({'id': 'stability.fixture', 'name': 'Stability fixtures', 'version': '1.0.0',
                'description': 'Synthetic local data only', 'types': ['series'], 'resources': ['meta', 'stream', 'catalog'],
                'catalogs': [{'type': 'series', 'id': 'fixture', 'name': 'Fixture series'}], 'idPrefixes': ['tt990']})
        if path.startswith('/addon/catalog/'):
            return self.send_json({'metas': [meta(s) for s in SHOWS]})
        if path.startswith('/addon/meta/'):
            return self.send_json({'meta': meta(path.rsplit('/', 1)[1].removesuffix('.json'))})
        if path.startswith('/addon/stream/'):
            return self.send_json({'streams': [{'name': 'Local fixture', 'url': base.BASE+'/movie/fixture/fixture/1.mp4'}]})
        if path.startswith('/tvdb/'):
            if STATE['tvdb'] == 'timeout':
                time.sleep(25)
            if STATE['tvdb'] == 'unavailable':
                return self.send_json({}, 503)
            if STATE['tvdb'] == 'invalid':
                return self.send_json({}, 401)
            if STATE.get('trackerUnwatch') and '/episodes/' in path:
                order = TRACKER_DVD if '/dvd' in path else range(1, 9)
                return self.send_json({'status': 'success', 'data': {'episodes': [
                    {'id': 990100+i, 'seasonNumber': 1, 'number': slot,
                     'name': TRACKER_TITLES[i-1], 'aired': '2020-01-01', 'runtime': 45}
                    for slot, i in enumerate(order, 1)]}, 'links': {'next': None}})
            if '/episodes/' in path:
                return self.send_json({'status': 'success', 'data': {'episodes': [
                    {'id': 990100+i, 'seasonNumber': 1, 'number': i, 'name': f'Episode {i}',
                     'aired': '2020-01-01', 'runtime': 45} for i in range(1, 9)]}, 'links': {'next': None}})
            return self.send_json({'status': 'success', 'data': {'id': 9901, 'name': 'Fixture ordered',
                'seasons': [{'id': 99011, 'number': 1, 'type': {'id': 1, 'type': 'official', 'name': 'Aired'}},
                            {'id': 99012, 'number': 1, 'type': {'id': 2, 'type': 'dvd', 'name': 'DVD'}}]}})
        if path.startswith('/trakt/'):
            return self.trakt(path)
        if path == '/stall.nzb':
            body = (f'<nzb><file subject="&quot;fixture.mp4&quot; yEnc"><segments>' + ''.join(
                f'<segment bytes="262144" number="{i}">stall{i}@fixture</segment>' for i in range(1, 33)) + '</segments></file></nzb>').encode()
            self.send_response(200)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        # Byte ranges ensure local VOD seeking exercises an actual HTTP data path.
        if path.startswith(('/movie/', '/live/')):
            start, end = 0, len(base.CLIP)-1
            if self.headers.get('Range', '').startswith('bytes='):
                parts = self.headers['Range'][6:].split('-', 1)
                start = int(parts[0] or 0)
                end = min(int(parts[1]) if parts[1] else end, end)
                self.send_response(206)
                self.send_header('Content-Range', f'bytes {start}-{end}/{len(base.CLIP)}')
            else:
                self.send_response(200)
            self.send_header('Content-Type', 'video/mp4')
            self.send_header('Content-Length', str(end-start+1))
            self.end_headers()
            self.wfile.write(base.CLIP[start:end+1])
            return
        super().do_GET()

    def trakt(self, path):
        shows = [{'title': 'Fixture '+s, 'year': 2020, 'ids': {'trakt': i+1, 'imdb': s, 'tvdb': 9901+i}}
                 for i, s in enumerate(SHOWS)]
        if STATE.get('trackerUnwatch'):
            show = shows[1]
            if path.endswith('/search/imdb/'+SHOWS[1]):
                return self.send_json([{'type': 'show', 'show': show}])
            if path.endswith('/shows/2/seasons'):
                return self.send_json([{'number': 1, 'episodes': [tracker_episode(i) for i in range(1, 9)]}])
            with LOCK:
                history = dict(TRACKER_HISTORY)
            if path.endswith('/watched/shows'):
                if parse_qs(urlsplit(self.path).query).get('page', ['1']) != ['1']:
                    return self.send_json([])
                return self.send_json([{'plays': len(history), 'show': show, 'last_watched_at': max(history.values()),
                    'seasons': [{'number': 1, 'episodes': [{'number': i, 'plays': 1, 'last_watched_at': t}
                                                           for i, t in history.items()]}]}])
            if '/progress/watched' in path:
                return self.send_json({'aired': 8, 'completed': len(history), 'seasons': [{'number': 1,
                    'episodes': [{'number': i, 'completed': i in history, 'last_watched_at': history.get(i)}
                                 for i in range(1, 9)]}]})
            if path.endswith('/sync/playback/episodes'):
                # A genuine retained pause with no match in this alternate catalogue.
                return self.send_json([{'id': 410, 'progress': 47, 'paused_at': STATE['trackerPausedAt'],
                    'type': 'episode', 'show': show, 'episode': {'season': 1, 'number': 10,
                    'title': 'Shareholder Meeting', 'ids': {'trakt': 410}}}])
            if '/sync/playback' in path or '/sync/history' in path:
                return self.send_json([])
        ep = {'season': 1, 'number': 1, 'title': 'Episode 1', 'ids': {'trakt': 101, 'tvdb': 990101}}
        if path.endswith('/last_activities'):
            return self.send_json({'all': now_iso(), 'episodes': {'watched_at': now_iso(), 'paused_at': now_iso()}})
        if '/sync/playback' in path:
            return self.send_json([] if STATE['completed'] or path.endswith('/movies') else [
                {'id': i+1, 'progress': 40, 'paused_at': now_iso(), 'type': 'episode', 'episode': ep, 'show': show}
                for i, show in enumerate(shows)])
        if path.endswith('/watched/shows'):
            return self.send_json([{'plays': 1, 'last_watched_at': now_iso(), 'show': show,
                                   'seasons': [{'number': 1, 'episodes': [{'number': 1, 'plays': 1, 'last_watched_at': now_iso()}]}]}
                                  for show in shows] if STATE['completed'] else [])
        if '/progress/watched' in path:
            return self.send_json({'aired': 8, 'completed': int(STATE['completed']), 'last_watched_at': now_iso(),
                'seasons': [{'number': 1, 'aired': 8, 'completed': int(STATE['completed']), 'episodes': [
                    {'number': i, 'completed': STATE['completed'] and i == 1, 'last_watched_at': now_iso() if i == 1 else None}
                    for i in range(1, 9)]}], 'next_episode': dict(ep, number=2 if STATE['completed'] else 1)})
        if path.endswith('/users/settings'):
            return self.send_json({'user': {'username': 'fixture', 'ids': {'slug': 'fixture'}}})
        return self.send_json([])


class News(base.News):
    def handle(self):
        self.wfile.write(b'200 synthetic NNTP ready\r\n')
        for line in self.rfile:
            if line.strip() in (b'BODY <stall1@fixture>', b'BODY <stall32@fixture>'):
                number = 1 if line.strip() == b'BODY <stall1@fixture>' else 32
                self.wfile.write(b'222 article follows\r\n'+PARTS[number])
                continue
            if line.startswith(b'BODY <stall'):
                with LOCK:
                    COUNTS['nntp-stalled'] += 1
                time.sleep(55)
                return
            if line.strip() == b'BODY <clip@fixture>':
                self.wfile.write(b'222 article follows\r\n' + base.ARTICLE)
            elif line.startswith(b'QUIT'):
                return
            else:
                self.wfile.write(b'500 unsupported command\r\n')


class DenyProxy(base.Web):
    def do_CONNECT(self):
        self.send_error(403, 'External network disabled in fixture build')
    def do_GET(self):
        self.send_error(403, 'External network disabled in fixture build')
    do_POST = do_GET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--channels', type=int, default=2000)
    parser.add_argument('--movies', type=int, default=20000)
    args = parser.parse_args()
    base.CHANNELS = [dict(stream_id=i, num=i, name=f'Fixture Cricket {i:03}', category_id='1', epg_channel_id=f'channel{i}')
                     for i in range(1, args.channels+1)]
    base.MOVIES = [dict(stream_id=i, name=f'Fixture Movie {i:05}', category_id='2', container_extension='mp4', added=str(1000+i))
                   for i in range(1, args.movies+1)]
    ThreadingTCPServer.allow_reuse_address = True
    ThreadingTCPServer.daemon_threads = True
    with ThreadingTCPServer(('127.0.0.1', 38792), News) as news, ThreadingHTTPServer(('127.0.0.1', 38793), DenyProxy) as proxy, ThreadingHTTPServer(('127.0.0.1', 38791), Web) as web:
        Thread(target=news.serve_forever, daemon=True).start()
        Thread(target=proxy.serve_forever, daemon=True).start()
        print(f'Fixtures ready: {args.channels} channels, {args.channels*48} programmes, {args.movies} movies', flush=True)
        web.serve_forever()


if __name__ == '__main__':
    main()
