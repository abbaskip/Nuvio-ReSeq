"""One-shot Stage 2 observers. These commands are restricted to owned fixture emulators."""
import argparse
from datetime import datetime,timezone,timedelta
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE='io.github.abbaskip.nuvioreseq.iptvdebug'
ACTIVITY=PACKAGE+'/com.nuvio.tv.profiling.StabilityJourneyActivity'

class Device:
    def __init__(self,serial):
        if not serial.startswith('emulator-'):raise ValueError('Fixture emulators only; no TVs or TCP devices')
        self.serial=serial
        self.command=[str(Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/platform-tools/adb.exe'),'-P','5044','-s',serial]
        self.env=dict(os.environ,ADB_MDNS_AUTO_CONNECT='0')
        name=self.adb('emu','avd','name').splitlines()[0].strip()
        if name not in ('stage2-realistic-3072','stage2-1536','stage2-3072'):raise ValueError('Not a task-owned fixture AVD')
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            found=subprocess.run(self.command+['shell','pidof',PACKAGE],capture_output=True,env=self.env,timeout=10)
            if found.returncode==0 and found.stdout.strip():
                self.pid=found.stdout.decode().split()[0];break
            time.sleep(0.2)
        else:raise RuntimeError('Fixture process did not start within 30 seconds; no relaunch attempted')
    def adb(self,*args,binary=False):
        result=subprocess.run(self.command+list(args),capture_output=True,env=self.env,timeout=30,check=True)
        return result.stdout if binary else result.stdout.decode('utf-8',errors='replace')
    def journey(self,name,*args):return self.adb('shell','am','start','-n',ACTIVITY,'--es','journey',name,*args)
    def log(self):return self.adb('logcat','-d','--pid='+self.pid,'-s','NuvioStability','Sports','AndroidRuntime','LiveTvRepository')
    def capture(self,path):
        path.with_suffix('.png').write_bytes(self.adb('exec-out','screencap','-p',binary=True))
        self.adb('shell','uiautomator','dump','/sdcard/stage2-observer.xml')
        xml=self.adb('shell','cat','/sdcard/stage2-observer.xml')
        path.with_suffix('.xml').write_text(xml,encoding='utf-8')
        return [n.get('text','') for n in ET.fromstring(xml).iter('node')]

def records(log):
    result=[]
    for line in log.splitlines():
        if 'NuvioStability: {' in line:
            try:result.append(json.loads(line.split('NuvioStability: ',1)[1]))
            except ValueError:pass
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=['load','sports','game','contract'])
    parser.add_argument('--serial',default='emulator-5590')
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--timeout',type=int,default=600)
    parser.add_argument('--keep-scene',action='store_true')
    parser.add_argument('--pathological',action='store_true',help='Adversarial OOM check: require a completed pass, without requiring the deliberately unrelated followed league to match')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    device=Device(args.serial);deadline=time.monotonic()+args.timeout
    initial=records(device.log());floor=max((r['elapsedMs'] for r in initial),default=0)
    result={'serial':args.serial,'pid':device.pid,'mode':args.mode};captured=set()
    if args.mode=='sports':device.journey('sports')
    elif args.mode=='game':device.journey('stage2-game','--es','eventId','espn:cricket/19430:1525662')
    elif args.mode=='contract':
        if not args.keep_scene:device.journey('home')
        device.journey('stage2-contract')
    start=time.monotonic()
    while time.monotonic()<deadline:
        log=device.log();(args.output/'logcat.txt').write_text(log,encoding='utf-8')
        if 'FATAL EXCEPTION' in log or 'OutOfMemoryError' in log:
            result['failure']='App crash/OOM';break
        all_records=records(log)
        current=all_records if args.mode=='load' else [r for r in all_records if r['elapsedMs']>=floor]
        if any(r['kind']=='journey-failed' for r in current):result['failure']='Journey assertion/operation failed';break
        if args.mode=='load':
            done=[r for r in current if r['kind']=='load-report' and 'Guide matched and stored:' in r['line']]
            imported=[r for r in current if r['kind']=='stage2-imported']
            if done and imported:
                result['complete']=True;result['records']=current;break
        elif args.mode=='sports':
            origin=next((r for r in current if r['kind']=='journey-ready' and r.get('journey')=='sports'),None)
            progress=[r for r in current if r['kind']=='sports-progress' and r.get('events',0)>0]
            if origin and progress and 'firstFixturesSeconds' not in result:
                result['firstFixturesSeconds']=(progress[0]['elapsedMs']-origin['elapsedMs'])/1000
                device.capture(args.output/'first-fixtures');captured.add('fixtures')
            for line in log.splitlines():
                if 'First guide matches:' in line and re.search(r'First guide matches: [1-9]',line) and 'liveChannelsSeconds' not in result and origin:
                    stamp=datetime.strptime('2026-'+line[:18],'%Y-%m-%d %H:%M:%S.%f').replace(tzinfo=timezone(timedelta(hours=11)))
                    result['liveChannelsSeconds']=(stamp.timestamp()*1000-origin['wallMs'])/1000
                    texts=device.capture(args.output/'first-live-and-my-sports')
                    result['mySportsCaptureSeconds']=time.monotonic()-start
                    result['mySportsTexts']=texts
                if 'Current TV sports:' in line and 'tvNowFirstMs' not in result:
                    match=re.search(r'; (\d+) ms',line)
                    if match:result['tvNowFirstMs']=int(match[1])
            full=[r for r in current if r['kind']=='load-report' and 'Sports matching took' in r['line']]
            if full and origin:
                result.setdefault('fullPassSeconds',(full[0]['elapsedMs']-origin['elapsedMs'])/1000)
                result.setdefault('fullPassReport',full[0]['line'])
            if 'fullPassSeconds' in result and ('liveChannelsSeconds' in result or args.pathological):
                device.capture(args.output/'full-pass');result['complete']=True;result['records']=current;break
        elif args.mode=='game':
            target=next((r for r in current if r['kind']=='stage2-game-target'),None)
            if target:
                texts=device.capture(args.output/'game-page')
                if any('Sports Network' in x for x in texts):
                    result.update(complete=True,channelsShownUpperBoundSeconds=time.monotonic()-start,target=target,texts=texts);break
        elif args.mode=='contract':
            done=[r for r in current if r['kind']=='stage2-contract-complete']
            if done:
                result.update(complete=True,records=current,summary=done[-1])
                device.adb('pull','/data/user/0/'+PACKAGE+'/files/stage2-contract.json',str(args.output/'matches.json'))
                break
        time.sleep(2)
    else:result['failure']='Observer deadline; no retry was started'
    result['hostObservationSeconds']=time.monotonic()-start
    (args.output/'result.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in ('records','mySportsTexts','texts','target')},ensure_ascii=True))
    if not result.get('complete'):raise SystemExit(1)

if __name__=='__main__':main()
