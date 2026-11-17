"""Stage 2: frozen public Sports feed, varied 50k-channel guide, and pathological mode.

No private account is used. Download the public feed separately, then pass its local
path. Guide/feed bytes stay fixed between reference and candidate measurements.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timedelta, timezone
import gzip
import hashlib
from http.server import ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
from threading import Thread
from urllib.parse import parse_qs, urlsplit
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('stability', ROOT / 'scripts/stability_fixtures.py')
stability = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stability)
base = stability.base

SHOWS = [
    ('News', ['World Report', 'Morning Bulletin', 'The Daily Briefing', 'Business Today', 'Local News', 'Weather Outlook']),
    ('Entertainment', ['The Office', 'Friends', 'Ghosts', 'Modern Family', 'Community', 'Brooklyn Nine-Nine']),
    ('Movies', ['Arrival', 'The Martian', 'Paddington', 'Inside Out', 'The Grand Budapest Hotel', 'The Truman Show']),
    ('Kids', ['Bluey', 'Hey Duggee', 'Sesame Street', 'Octonauts', 'Peppa Pig', 'Shaun the Sheep']),
    ('Lifestyle', ['Grand Designs', 'Gardeners World', 'MasterChef', 'The Repair Shop', 'Escape to the Country', 'Antiques Roadshow']),
    ('Documentary', ['Planet Earth', 'The Blue Planet', 'History of the Railway', 'Science and Discovery', 'Wildlife Diaries', 'Ancient Worlds']),
]
SPORT_SHOWS = ['SportsCenter', 'Premier League Highlights', 'NBA Today', 'NFL Live', 'NRL Golden Point Thrillers',
    'Rugby League World Cup Highlights', 'Women’s Super League', 'ATP Tennis Highlights', 'PGA Tour Review',
    'Formula 1: Race Review', 'Cricket: Test Match Highlights', 'Snooker: Home Nations', 'Live Darts',
    'Cycling: Tour Highlights', 'NHL Tonight', 'Motorsport Magazine', 'WWE Raw', 'Live Rugby Union',
    'La Liga Highlights', 'NCAA Basketball Review', 'Live Bowls', 'Golf Central', 'AFL Classics', 'Live Netball']

def date(value):
    try:
        return datetime.fromisoformat(value.replace('Z', '+00:00')).astimezone(timezone.utc)
    except (ValueError, AttributeError, TypeError):
        return None

class Dataset:
    def __init__(self, feed: Path, folder: Path, channels=50001):
        self.bytes = feed.read_bytes()
        self.feed = json.loads(self.bytes)
        self.hash = hashlib.sha256(self.bytes).hexdigest()
        self.count = channels
        generated = datetime.fromtimestamp(self.feed['generatedAt']/1000, timezone.utc)
        self.now = generated.replace(minute=7, second=0, microsecond=0)
        if self.now < generated:
            self.now += timedelta(hours=1)
        self.hour = self.now.replace(minute=0)
        self.folder = folder
        folder.mkdir(parents=True, exist_ok=True)
        self.events = [(entry['leagueId'], event) for entry in self.feed['entries'] if entry['provider']=='espn'
                       for event in entry['payload']['events']]
        self.live = [(league, event) for league, event in self.events
                     if event.get('status', {}).get('type', {}).get('state') == 'in']
        self.airings = {}
        # Real events share a few regional/quality variants, rather than every channel.
        for i, (league, event) in enumerate(sorted(self.events, key=lambda pair: pair[1].get('date',''))):
            start = date(event.get('date'))
            if start is None: continue
            live = event.get('status', {}).get('type', {}).get('state') == 'in'
            if live and start < self.hour: start = self.hour
            if not self.hour-timedelta(hours=6) <= start < self.hour+timedelta(hours=42): continue
            slot = round((start-self.hour).total_seconds()/3600)
            for variant in range(2):
                channel = 1 + (i*2+variant) % 1000
                sport = league.split('/')[0].replace('-', ' ')
                title = f"Live: {event.get('name', event.get('shortName','Game'))} — {sport}"
                self.airings[(channel, slot)] = (title, f"Live {league} coverage", 'Sports')
        # Guarantee a current live game's variants are available even after unrelated assignments.
        for i, (league, event) in enumerate(self.live):
            for variant in range(2):
                self.airings[(1+i*2+variant, 0)] = (f"Live: {event['name']} — {league.split('/')[0]}",
                    f"Live {league} coverage", 'Sports')

    def channels(self, pathological=False):
        out=[]
        for i in range(1,self.count+1):
            if pathological:
                label = self.live[0][1]['name'] if self.live else 'Australia vs England'
                name, group = f'{label} Sports {i:05}', 'Sports'
            elif i<=1000:
                name, group = f"Sports Network {(i-1)//2+1:03} {'HD' if i%2 else 'SD'}", 'Sports'
            else:
                group, _ = SHOWS[((i-1001)//1000)%len(SHOWS)]
                name=f'{group} Channel {i:05}'
            out.append(dict(stream_id=i,num=i,name=name,category_id='1',epg_channel_id=f'channel{i}',group=group))
        return out

    def programme(self, channel, slot, pathological=False, revision=0):
        if pathological:
            live = self.live[0] if self.live else ('cricket/19430', {'name':'Australia vs England'})
            return f"Live: {live[1]['name']} — {live[0].split('/')[0]}", 'Live sports coverage', 'Sports'
        if (channel,slot) in self.airings: return self.airings[(channel,slot)]
        if channel<=1000:
            title=SPORT_SHOWS[(channel//2+slot)%len(SPORT_SHOWS)]
            return title, 'Sports studio, highlights and scheduled events', 'Sports'
        group, titles=SHOWS[((channel-1001)//1000)%len(SHOWS)]
        title=titles[(channel+slot+revision)%len(titles)]
        return title, f'{group} programme. Episode {(channel+slot)%20+1}.', group

    def guide(self, pathological=False, revision=0):
        suffix='pathological' if pathological else f'realistic-{revision}'
        path=self.folder/f'guide-{suffix}.xml.gz'
        if path.exists(): return path
        times=[(self.hour+timedelta(hours=h)).strftime('%Y%m%d%H%M%S +0000') for h in range(-6,43)]
        with gzip.open(path,'wb',compresslevel=1) as stream:
            stream.write(b'<tv>')
            for ch in self.channels(pathological):
                stream.write(f'<channel id="channel{ch["stream_id"]}"><display-name>{escape(ch["name"])}</display-name></channel>'.encode())
            for channel in range(1,self.count+1):
                for n,slot in enumerate(range(-6,42)):
                    title,description,category=self.programme(channel,slot,pathological,revision)
                    stream.write((f'<programme channel="channel{channel}" start="{times[n]}" stop="{times[n+1]}">'
                        f'<title>{escape(title)}</title><desc>{escape(description)}</desc><category>{category}</category></programme>').encode())
            stream.write(b'</tv>')
        return path

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--feed',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--port',type=int,default=39891)
    p.add_argument('--generate-only',action='store_true')
    args=p.parse_args()
    data=Dataset(args.feed,args.output)
    for mode,revision in [(False,0),(False,1),(True,0)]: data.guide(mode,revision)
    info={'feedBytes':len(data.bytes),'feedSha256':data.hash,'feedEvents':sum(len(e['payload']['events']) for e in data.feed['entries']),
        'channels':data.count,'listings':data.count*48,'sportsChannels':1000,'fixedNowMs':int(data.now.timestamp()*1000),
        'liveEvents':[(league,e['id'],e['name']) for league,e in data.live]}
    (args.output/'fixture-info.json').write_text(json.dumps(info,indent=2),encoding='utf-8')
    print(json.dumps(info),flush=True)
    if args.generate_only: return
    state={'pathological':False,'revision':0,'sports':'ok'}
    def configure(): base.CHANNELS=data.channels(state['pathological'])
    configure()
    base.MOVIES=[dict(stream_id=i,name=f'Fixture Movie {i:05}',category_id='2',container_extension='mp4',added=str(i+1000)) for i in range(1,20001)]
    class Web(stability.Web):
        def do_GET(self):
            request=urlsplit(self.path); query=parse_qs(request.query)
            if request.path=='/stage2-control':
                if 'pathological' in query: state['pathological']=query['pathological'][0]=='1'; configure()
                if 'guide' in query: state['revision']=int(query['guide'][0])
                if 'sports' in query: state['sports']=query['sports'][0]
                return self.send_json(state)
            if request.path=='/sports':
                if state['sports']=='fail': return self.send_json({'error':'Unavailable'},503)
                if state['sports']=='invalid': return self.send_json({'schemaVersion':99})
                if self.headers.get('If-None-Match')==data.hash:
                    self.send_response(304); self.end_headers(); return
                self.send_response(200); self.send_header('Content-Type','application/json')
                self.send_header('Content-Length',str(len(data.bytes))); self.send_header('ETag',data.hash); self.end_headers()
                self.wfile.write(data.bytes); return
            if request.path.startswith('/scores/'):
                if request.path.endswith('/scoreboard'):
                    return self.send_json(next((entry['payload'] for entry in data.feed['entries']
                        if entry['provider']=='espn' and f'/{entry["leagueId"]}/' in request.path),{'events':[]}))
                return self.send_json({})
            if request.path=='/xmltv.php':
                path=data.guide(state['pathological'],state['revision'])
                self.send_response(200); self.send_header('Content-Type','application/gzip')
                self.send_header('Content-Length',str(path.stat().st_size)); self.end_headers()
                with path.open('rb') as stream:
                    while block:=stream.read(1024*1024): self.wfile.write(block)
                return
            return super().do_GET()
    stability.ThreadingTCPServer.allow_reuse_address=True
    with stability.ThreadingTCPServer(('127.0.0.1',args.port+1),stability.News) as news, \
         ThreadingHTTPServer(('127.0.0.1',args.port+2),stability.DenyProxy) as proxy, \
         ThreadingHTTPServer(('127.0.0.1',args.port),Web) as web:
        Thread(target=news.serve_forever,daemon=True).start()
        Thread(target=proxy.serve_forever,daemon=True).start()
        print('Stage 2 fixtures ready',flush=True); web.serve_forever()

if __name__=='__main__': main()
