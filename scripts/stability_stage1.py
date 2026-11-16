"""One emulator-only CW/Live TV journey; invariant failures fail the run, not just a report."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.request import urlopen
from stability_run import Session, device_session, PACKAGE, ACTIVITY

EXPECTED = {'tt9900001', 'tt9900002', 'tt9900003'}
CONTROL = 'tt9900003'


class InfrastructureFailure(RuntimeError):
    pass


class Stage1Session(Session):
    def check(self, interval=30):
        self.read_events()
        if self.logger and self.logger.poll() is not None:
            raise InfrastructureFailure('Logcat collector stopped')
        result = subprocess.run([self.args.adb, '-s', self.args.serial, 'shell', 'pidof', PACKAGE],
                                capture_output=True, text=True, timeout=30)
        with (self.out/'pid-observations.jsonl').open('a', encoding='utf-8') as output:
            output.write(json.dumps({'phase': self.phase, 'expected': self.pid, 'returncode': result.returncode,
                                     'stdout': result.stdout, 'stderr': result.stderr})+'\n')
        observed = result.stdout.strip()
        if result.returncode != 0 or not observed.isdigit():
            # Preserve the failed observation, distinguishing transport from actual app death.
            proc = self.adb('shell', 'cat', f'/proc/{self.pid}/cmdline', check=False)
            if PACKAGE in proc or result.returncode not in (0, 1) or result.stderr.strip():
                raise InfrastructureFailure('ADB PID observation failed; see pid-observations.jsonl')
            raise AssertionError('Fixture process exited; see retained PID observation')
        if observed != self.pid:
            raise AssertionError('Fixture process restarted')
        if time.monotonic()-self.last_sample >= interval:
            self.save(f'meminfo-{self.sample_number:04}-{self.phase}.txt', self.adb('shell', 'dumpsys', 'meminfo', PACKAGE))
            self.last_sample = time.monotonic()
            self.sample_number += 1
            anr = self.test_anr()
            if anr and anr != self.initial_anr:
                self.save('test-anr.txt', anr)
                raise AssertionError('New fixture ANR')


def require(value, message):
    if not value:
        raise AssertionError(message)


def latest(s):
    sample = next((e for e in reversed(s.events) if e.get('kind') == 'sample' and e.get('hostPhase') == s.phase), None)
    require(sample is not None, 'Missing sample for '+s.phase)
    return sample


def membership(sample):
    require(EXPECTED.issubset(sample['cwIds']), 'Supported show missing from Home: '+str(sample['cwIds']))
    require(EXPECTED.issubset({row.split(':')[0] for row in sample['snapshot']}),
            'Supported show missing from durable projection: '+str(sample['snapshot']))


def completed_offer(sample):
    membership(sample)
    rows = [row for row in sample['snapshot'] if row.startswith(CONTROL+':')]
    require(rows in ([CONTROL+':0'], [CONTROL+':2']), 'Finished episode or invalid offer in projection: '+str(rows))
    for platform in ('launcher', 'watchNext'):
        offers = [row for row in sample[platform] if CONTROL in row]
        require(bool(offers), 'Missing control in '+platform)
        require(all(';i.episode=1;' not in row for row in offers), 'Finished episode remains in '+platform)
        require(all('S.launchMode=detail;' in row or ';i.episode=2;' in row for row in offers),
                'Offer must open a proven next episode or series details: '+str(offers))


def execute(s, minutes):
    started = time.monotonic()
    s.control('tvdb=available&completed=false&slowImport=false')
    s.phase = 'minified-launch'
    s.adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/com.nuvio.tv.MainActivity')
    s.start_log('launch')
    s.wait(20)
    s.phase = 'seed'
    s.journey('seed', 300)
    s.journey('home')
    s.wait(30)
    s.stop_log()
    s.adb('shell', 'am', 'force-stop', PACKAGE)
    s.adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/'+ACTIVITY, '--es', 'journey', 'home')
    s.start_log('cw')
    s.phase = 'cw-cold-start'
    cold = time.monotonic()
    for at in (0, 60, 120, 300):
        s.wait(max(0, cold+at-time.monotonic()))
        s.journey('sample')
        membership(latest(s))
        s.observations.append({'check': 'cold-membership', 'at': at, 'passed': True})
    # The existing fake-tracker/TVDB sequence, now with mandatory assertions.
    for mode in ('missing', 'invalid', 'timeout', 'unavailable', 'available'):
        s.phase = 'tvdb-'+mode
        s.control('tvdb='+('available' if mode == 'missing' else mode))
        s.journey('tvdb-'+mode)
        s.journey('refresh')
        s.wait(30)
        s.journey('sample')
        membership(latest(s))
        s.observations.append({'check': s.phase, 'passed': True})
    s.phase = 'completion-off-home'
    s.journey('complete')
    s.wait(5)
    s.journey('sample')
    completed_offer(latest(s))
    s.observations.append({'check': s.phase, 'passed': True})
    s.phase = 'completion-background'
    s.journey('background')
    s.wait(35)
    completed_offer(latest(s))
    s.phase = 'completion-return'
    s.journey('home')
    s.wait(10)
    s.journey('refresh')
    s.journey('sample')
    completed_offer(latest(s))
    s.phase = 'live-tv-stream-list'
    s.journey('preview')
    s.wait(10)
    s.journey('stream-list')
    s.wait(3)
    s.journey('sample')
    require(not latest(s)['liveTvAttached'], 'Stream list retained Live TV connection')
    s.phase = 'live-tv-trailer'
    s.journey('preview')
    s.wait(10)
    s.journey('trailer')
    s.wait(10)
    s.journey('sample')
    require(not latest(s)['liveTvAttached'], 'Trailer retained Live TV connection')
    s.phase = 'live-tv-background-from-home'
    s.journey('home')
    s.journey('preview')
    s.wait(10)
    s.journey('home-with-live')
    require(latest(s)['liveTvPlaying'], 'Live TV fixture did not play before background check')
    s.journey('background')
    s.wait(35)
    require(not latest(s)['liveTvPlaying'], 'Live TV continued playing in background from Home')
    s.phase = 'cw-settled'
    s.journey('home')
    s.wait(max(0, started+minutes*60-time.monotonic()))
    s.journey('sample')
    completed_offer(latest(s))
    s.observations.append({'check': 'cw-and-live-tv', 'elapsedSeconds': time.monotonic()-started, 'passed': True})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--aapt', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--minutes', type=int, default=30)
    parser.add_argument('--scenario', choices=('cw', 'cw-unwatch', 'office'), default='cw')
    args = parser.parse_args()
    # This check precedes every ADB command. Physical devices are never queried.
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Stage 1 permits disposable emulators only')
    if not 1 <= args.minutes <= 30:
        parser.error('Stage duration must be between 1 and 30 minutes')
    args.device = 'emulator'
    args.output.mkdir(parents=True, exist_ok=False)
    s = Stage1Session(args)
    error = None
    try:
        with device_session(s):
            if args.scenario == 'office':
                s.control('office=true&tvdb=available')
                try:
                    s.adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/com.nuvio.tv.MainActivity')
                    s.start_log('office')
                    s.phase = 'office-split'
                    s.journey('office-split', 180)
                    with urlopen('http://127.0.0.1:38791/office-calls', timeout=5) as response:
                        calls = json.load(response)
                    s.save('tracker-requests.json', json.dumps(calls, indent=2))
                    expected = [['start', 6, 16, 0], ['pause', 6, 16, 23.5],
                                ['start', 6, 16, 23.5], ['pause', 6, 16, 47.1796]]
                    require(calls == expected, 'Trakt requests differ from published 9.8')
                    s.observations.append({'check': 'split-episode-round-trip', 'passed': True})
                finally:
                    s.control('office=false')
            else:
                execute(s, args.minutes)
                if args.scenario == 'cw-unwatch':
                    s.stop_log()
                    subprocess.run([sys.executable, str(Path(__file__).with_name('stability_unwatch.py')),
                                    '--adb', args.adb, '--adb-port', os.environ.get('ANDROID_ADB_SERVER_PORT', '5037'),
                                    '--serial', args.serial, '--package', PACKAGE, '--scenario', 'tracker',
                                    '--output', str(args.output/'unwatch')], check=True)
                    s.observations.append({'check': 'details-unwatch', 'passed': True})
    except BaseException as failure:
        error = {'type': type(failure).__name__, 'message': str(failure)}
    finally:
        s.save('events.json', json.dumps(s.events, indent=2))
        s.save('assertions.json', json.dumps(s.observations, indent=2))
        s.save('result.json', json.dumps({'passed': error is None, 'failure': error}, indent=2))
    if error:
        raise SystemExit(str(error))
    print('Stage 1 emulator assertions passed', flush=True)


if __name__ == '__main__':
    main()
