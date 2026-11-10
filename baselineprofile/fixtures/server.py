"""Loopback-only Xtream, Sports and NNTP fixtures for the Android TV profile journeys."""
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
from socketserver import StreamRequestHandler, ThreadingTCPServer
from threading import Thread
from urllib.parse import parse_qs, urlsplit
from zlib import crc32

ROOT = Path(__file__).resolve().parents[2]
CLIP = (ROOT / "app/src/test/resources/usenet/test-pattern.mp4").read_bytes()
CHANNELS = [{"stream_id": i, "num": i, "name": f"Channel {i:03}", "category_id": "1",
             "epg_channel_id": f"channel{i}"} for i in range(1, 81)]
MOVIES = [{"stream_id": i, "name": f"Fixture Movie {i:03}", "category_id": "2",
           "container_extension": "mp4", "added": str(1000 + i)} for i in range(1, 65)]


def sports():
    now = datetime.now(timezone.utc)
    teams = [{"homeAway": side, "team": {"id": str(i), "name": name, "displayName": name,
              "shortDisplayName": name, "abbreviation": name[:3].upper()}}
             for i, (name, side) in enumerate([("Fixture Falcons", "away"), ("Fixture Owls", "home")], 1)]
    comp = {"competitors": teams, "status": {"type": {"state": "pre", "description": "Scheduled"}},
            "broadcasts": [{"names": ["Channel 001"]}], "venue": {"fullName": "Fixture Arena"}}
    event = {"id": "77701", "date": (now + timedelta(minutes=45)).strftime("%Y-%m-%dT%H:%MZ"),
             "name": "Fixture Falcons at Fixture Owls", "competitions": [comp]}
    ms = int(now.timestamp() * 1000)
    return {"schemaVersion": 1, "generatedAt": ms, "entries": [{"provider": "espn",
            "leagueId": "basketball/nba", "fetchedAt": ms, "payload": {"events": [event]}}]}


def guide():
    now = datetime.now(timezone.utc).replace(second=0, microsecond=0)
    times = [(now + timedelta(minutes=m)).strftime("%Y%m%d%H%M%S +0000") for m in (-50, 45, 180)]
    channels = "".join(f'<channel id="channel{i}"><display-name>Channel {i:03}</display-name></channel>'
                       for i in range(1, len(CHANNELS) + 1))
    programs = []
    for i in range(1, len(CHANNELS) + 1):
        for start, end in zip(times, times[1:]):
            title = "Fixture Falcons at Fixture Owls" if i == 1 else f"Fixture programme {i}"
            programs.append(f'<programme channel="channel{i}" start="{start}" stop="{end}">'
                            f'<title>{title}</title><category>Sports</category></programme>')
    return ("<tv>" + channels + "".join(programs) + "</tv>").encode()


def article():
    lines = [f"=ybegin line=128 size={len(CLIP)} name=fixture.mp4".encode()]
    for offset in range(0, len(CLIP), 64):
        line = bytearray()
        for byte in CLIP[offset:offset + 64]:
            value = (byte + 42) & 255
            if value in (0, 10, 13, 61):
                line.extend([61, (value + 64) & 255])
            else:
                line.append(value)
        lines.append(b"." + line if line.startswith(b".") else line)
    lines.extend([f"=yend size={len(CLIP)} crc32={crc32(CLIP):08x}".encode(), b".", b""])
    return b"\r\n".join(lines)


ARTICLE = article()
BASE = "http://127.0.0.1:38791"


class Web(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlsplit(self.path)
        action = parse_qs(url.query).get("action", [""])[0]
        if url.path == "/player_api.php":
            data = {"": {"user_info": {"auth": 1, "allowed_output_formats": ["ts"]}, "server_info": {}},
                    "get_live_categories": [{"category_id": "1", "category_name": "Fixture channels"}],
                    "get_live_streams": CHANNELS,
                    "get_vod_categories": [{"category_id": "2", "category_name": "Fixture movies"}],
                    "get_vod_streams": MOVIES, "get_series_categories": [], "get_series": []}.get(action, [])
            body, mime = json.dumps(data).encode(), "application/json"
        elif url.path == "/get.php":
            body = ("#EXTM3U\n" + "\n".join(f'#EXTINF:-1 group-title="Fixture channels",{c["name"]}\n'
                    f'{BASE}/live/fixture/fixture/{c["stream_id"]}.ts' for c in CHANNELS)).encode()
            mime = "audio/x-mpegurl"
        elif url.path == "/xmltv.php":
            body, mime = guide(), "application/xml"
        elif url.path == "/sports":
            body, mime = json.dumps(sports()).encode(), "application/json"
        elif url.path == "/clip.nzb":
            body = (f'<nzb><file subject="&quot;fixture.mp4&quot; yEnc"><segments>'
                    f'<segment bytes="{len(ARTICLE)}" number="1">clip@fixture</segment>'
                    '</segments></file></nzb>').encode()
            mime = "application/x-nzb"
        elif url.path.startswith(("/movie/", "/live/")):
            body, mime = CLIP, "video/mp4"
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class News(StreamRequestHandler):
    def handle(self):
        self.wfile.write(b"200 local fixture ready\r\n")
        for line in self.rfile:
            if line.strip() == b"BODY <clip@fixture>":
                self.wfile.write(b"222 article follows\r\n" + ARTICLE)
            elif line.startswith(b"QUIT"):
                return
            else:
                self.wfile.write(b"500 unsupported command\r\n")


if __name__ == "__main__":
    ThreadingTCPServer.allow_reuse_address = True
    with ThreadingTCPServer(("127.0.0.1", 38792), News) as news, ThreadingHTTPServer(("127.0.0.1", 38791), Web) as web:
        Thread(target=news.serve_forever, daemon=True).start()
        web.serve_forever()
