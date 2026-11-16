"""M1 emulator fixtures. Extends the existing local tracker; no real service requests."""
import json
import os
import struct
import sys
from urllib.parse import urlsplit, parse_qs
import stability_fixtures as f

OriginalWeb = f.Web
PLAYS = {}
PAUSE = None
CALLS = []


def multiview_clip(data):
    """Extend the video-only pattern to 16 seconds by retiming, without changing its H.264 data."""
    assert b'soun' not in data, 'This deterministic retiming fixture expects video only'
    result = bytearray(data)
    def integer(at, wide=False, factor=4):
        fmt = '>Q' if wide else '>I'
        value = struct.unpack_from(fmt, result, at)[0]
        struct.pack_into(fmt, result, at, value*factor)
    def walk(start, end):
        at = start
        while at+8 <= end:
            size, kind = struct.unpack_from('>I4s', result, at)
            size = size or end-at
            assert 8 <= size <= end-at
            payload = at+8
            version = result[payload] if payload < at+size else 0
            if kind in (b'moov', b'trak', b'mdia', b'edts'):
                walk(payload, at+size)
            elif kind == b'mvhd':
                integer(payload+(24 if version else 16), bool(version))
            elif kind == b'tkhd':
                integer(payload+(28 if version else 20), bool(version))
            elif kind == b'mdhd':
                scale_at = payload+(20 if version else 12)
                scale = struct.unpack_from('>I', result, scale_at)[0]
                assert scale % 4 == 0
                struct.pack_into('>I', result, scale_at, scale//4)
            elif kind == b'elst':
                count = struct.unpack_from('>I', result, payload+4)[0]
                for index in range(count):
                    integer(payload+8+index*(20 if version else 12), bool(version))
            at += size
    walk(0, len(result))
    return bytes(result)


MULTIVIEW_CLIP = multiview_clip(f.base.CLIP)


def episode(season, number):
    return next(ep for group in f.OFFICE_TRAKT if group['number'] == season
                for ep in group['episodes'] if ep['number'] == number)


def record(s, n, identifier, age):
    return {'id': identifier, 'watched_at': f.tracker_time(age), 'type': 'episode',
            'episode': episode(s, n), 'show': f.OFFICE_SHOW}


class Web(OriginalWeb):
    def do_GET(self):
        global PAUSE
        path = urlsplit(self.path).path
        query = parse_qs(urlsplit(self.path).query)
        if path == '/m1/control':
            mode = query.get('mode', ['rewatch'])[0]
            f.STATE['office'] = True
            f.OFFICE_CALLS.clear()
            CALLS.clear()
            PLAYS.clear()
            PAUSE = None
            if mode == 'rewatch':
                PLAYS.update({1: record(6, 16, 1, 3600), 2: record(6, 16, 2, 60),
                              3: record(6, 15, 3, 7200)})
                if os.environ.get('M1_REWATCH_PAUSE') == 'true':
                    del PLAYS[2]
                    PAUSE = {'id': 4321, 'type': 'episode', 'episode': episode(6, 16),
                        'show': f.OFFICE_SHOW, 'paused_at': f.tracker_time(10), 'progress': 47}
            if mode == 'pause':
                ep = episode(6, 16)
                PAUSE = {'id': 4321, 'type': 'episode', 'episode': ep, 'show': f.OFFICE_SHOW,
                         'paused_at': f.tracker_time(10), 'progress': 49.75}
            return self.send_json({'mode': mode})
        if path == '/m1/calls':
            return self.send_json({'writes': CALLS, 'plays': list(PLAYS.values()), 'pause': PAUSE})
        if path == '/m1/list.m3u':
            body = ('#EXTM3U\n' + ''.join(f'#EXTINF:-1 group-title="Fixtures",Fixture {i}\n'
                    f'http://127.0.0.1:38791/live/m1/{i}\n' for i in (1, 2))).encode()
            self.send_response(200)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path.startswith('/live/'):
            start, end = 0, len(MULTIVIEW_CLIP)-1
            requested = self.headers.get('Range', '')
            if requested.startswith('bytes='):
                left, right = requested[6:].split('-', 1)
                start, end = int(left or 0), min(int(right) if right else end, end)
                self.send_response(206)
                self.send_header('Content-Range', f'bytes {start}-{end}/{len(MULTIVIEW_CLIP)}')
            else:
                self.send_response(200)
            self.send_header('Content-Type', 'video/mp4')
            self.send_header('Content-Length', str(end-start+1))
            self.end_headers()
            self.wfile.write(MULTIVIEW_CLIP[start:end+1])
            return
        super().do_GET()

    def trakt(self, path):
        if f.STATE.get('office'):
            if path.endswith('/sync/playback/episodes') and PAUSE:
                return self.send_json([PAUSE])
            if path.endswith('/sync/watched/shows'):
                if parse_qs(urlsplit(self.path).query).get('page', ['1']) != ['1'] or not PLAYS:
                    return self.send_json([])
                seasons = {}
                for play in PLAYS.values():
                    ep = play['episode']
                    key = ep['season'], ep['number']
                    seasons.setdefault(key, []).append(play)
                grouped = {}
                for (s, n), plays in seasons.items():
                    grouped.setdefault(s, []).append({'number': n, 'plays': len(plays),
                        'last_watched_at': max(p['watched_at'] for p in plays)})
                return self.send_json([{'show': f.OFFICE_SHOW, 'plays': len(PLAYS),
                    'last_watched_at': max(p['watched_at'] for p in PLAYS.values()),
                    'seasons': [{'number': s, 'episodes': eps} for s, eps in grouped.items()]}])
            if '/progress/watched' in path:
                watched = {(p['episode']['season'], p['episode']['number']): p['watched_at'] for p in PLAYS.values()}
                return self.send_json({'aired': sum(len(s['episodes']) for s in f.OFFICE_TRAKT), 'completed': len(watched),
                    'seasons': [{'number': s['number'], 'episodes': [{'number': ep['number'],
                        'completed': (s['number'], ep['number']) in watched,
                        'last_watched_at': watched.get((s['number'], ep['number']))} for ep in s['episodes']]}
                        for s in f.OFFICE_TRAKT]})
            if '/sync/history/episodes' in path:
                suffix = path.split('/sync/history/episodes', 1)[1].strip('/')
                plays = [p for p in PLAYS.values() if not suffix or str(p['episode']['ids']['trakt']) == suffix]
                if parse_qs(urlsplit(self.path).query).get('page', ['1']) != ['1']:
                    plays = []
                return self.send_json(sorted(plays, key=lambda p: p['watched_at'], reverse=True))
        super().trakt(path)

    def do_POST(self):
        path = urlsplit(self.path).path
        if f.STATE.get('office') and path in ('/trakt/sync/history', '/trakt/sync/history/remove'):
            payload = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
            CALLS.append({'path': path, 'body': payload})
            if 'ids' in payload:
                removed = sum(PLAYS.pop(i, None) is not None for i in payload['ids'])
                return self.send_json({'deleted': {'episodes': removed}})
            coords = [(s['number'], ep['number']) for show in payload.get('shows', [])
                      for s in show.get('seasons', []) for ep in s.get('episodes', [])]
            if path.endswith('/remove'):
                for key, play in list(PLAYS.items()):
                    if (play['episode']['season'], play['episode']['number']) in coords:
                        del PLAYS[key]
                return self.send_json({'deleted': {'episodes': len(coords)}})
            for s, n in coords:
                identifier = max(PLAYS, default=10) + 1
                PLAYS[identifier] = record(s, n, identifier, 1)
            return self.send_json({'added': {'episodes': len(coords)}})
        if f.STATE.get('office') and path.startswith('/trakt/scrobble/'):
            payload = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
            ep = payload['episode']
            f.OFFICE_CALLS.append([path.rsplit('/', 1)[1], ep['season'], ep['number'], payload['progress']])
            CALLS.append({'path': path, 'body': payload})
            return self.send_json({'action': 'pause', 'progress': payload['progress']})
        super().do_POST()

    def do_DELETE(self):
        global PAUSE
        path = urlsplit(self.path).path
        if path == '/trakt/sync/playback/4321':
            CALLS.append({'path': path, 'body': {}})
            PAUSE = None
            self.send_response(204)
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        self.send_json({}, 404)


if __name__ == '__main__':
    # Separate host ports prevent collisions with another task's existing fixture servers.
    delta = int(os.environ.get('M1_FIXTURE_PORT', '48791')) - 38791
    class IsolatedHTTP(f.ThreadingHTTPServer):
        def __init__(self, address, handler):
            super().__init__((address[0], address[1]+delta), handler)
    class IsolatedTCP(f.ThreadingTCPServer):
        def __init__(self, address, handler):
            super().__init__((address[0], address[1]+delta), handler)
    f.ThreadingHTTPServer = IsolatedHTTP
    f.ThreadingTCPServer = IsolatedTCP
    f.Web = Web
    f.main()
