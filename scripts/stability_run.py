"""Fixture smoke/CW/soak/cricket runner with explicit serial and Onn restoration.

Only freshly installed minified benchmark packages are accepted. No existing
Nuvio/ReSeq installation is modified. A failed journey is never a passed test.
"""
from __future__ import annotations
import argparse
from contextlib import contextmanager
import json
from pathlib import Path
import re
import subprocess
import time
from urllib.request import urlopen

try:
    from .stability_smoke import PACKAGE, apk_identity, crash_lines, run
except ImportError:
    from stability_smoke import PACKAGE, apk_identity, crash_lines, run

ACTIVITY = 'com.nuvio.tv.profiling.StabilityJourneyActivity'
VOD = 'com.nuvio.tv.profiling.StabilityVodActivity'
PRODUCTION = 'io.github.abbaskip.nuvioreseq'
SETTINGS = [('global', 'stay_on_while_plugged_in'), ('secure', 'screensaver_enabled'),
            ('secure', 'screensaver_activate_on_sleep'), ('system', 'screen_off_timeout')]


def timeline_assertions(events):
    """Record invariant failures as discovery findings, separate from harness failures."""
    result = []
    for phase in ('launcher-timeline', 'launcher-closed-non-home', 'launcher-background', 'launcher-returned-home'):
        samples = [e for e in events if e.get('kind') == 'sample' and e.get('hostPhase') == phase]
        if not samples:
            raise RuntimeError('Missing timeline observation: '+phase)
        sample = samples[-1]
        expected_episode = '1' if phase == 'launcher-timeline' else '2'
        # Sequential non-TVDB control avoids conflating shuffle selection with stale progress.
        snapshot = [row for row in sample['snapshot'] if row.startswith('tt9900003:')]
        launcher = [row for row in sample['launcher'] if 'tt9900003' in row]
        result.append({'phase': phase, 'expectedControlEpisode': expected_episode,
            'snapshot': snapshot, 'launcher': launcher, 'rawProgress': sample['rawProgress'],
            'snapshotVersion': sample['snapshotVersion'],
            'snapshotMatchesExpected': snapshot == ['tt9900003:'+expected_episode],
            'launcherContainsControl': bool(launcher)})
    return result


class Session:
    def __init__(self, args):
        self.args = args
        self.out = args.output
        self.events = []
        self.logger = None
        self.logfile = None
        self.reader = None
        self.pending = ''
        self.pid = None
        self.last_sample = 0
        self.sample_number = 0
        self.phase = 'setup'
        self.observations = []
        self.initial_anr = ''

    def test_anr(self):
        # Filter on the device before collecting anything; never retain another app's ANR.
        return self.adb('shell', "dumpsys activity lastanr | grep -F '"+PACKAGE+"'", check=False)

    def adb(self, *command, **kwargs):
        return run(self.args.adb, '-s', self.args.serial, *command, **kwargs)

    def save(self, name, text):
        (self.out/name).write_text(text, encoding='utf-8')

    def state(self):
        return {
            'model': self.adb('shell', 'getprop', 'ro.product.model').strip(),
            'android': self.adb('shell', 'getprop', 'ro.build.version.release').strip(),
            'fingerprint': self.adb('shell', 'getprop', 'ro.build.fingerprint').strip(),
            'heapgrowthlimit': self.adb('shell', 'getprop', 'dalvik.vm.heapgrowthlimit').strip(),
            'heapsize': self.adb('shell', 'getprop', 'dalvik.vm.heapsize').strip(),
            'settings': {f'{ns}/{key}': self.adb('shell', 'settings', 'get', ns, key).strip() for ns, key in SETTINGS},
            # Filter on the device; no other package data is collected.
            'productionVersion': self.adb('shell', f'dumpsys package {PRODUCTION} | grep -E "versionCode=|versionName="', check=False).strip(),
            'volume': self.adb('shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--get'),
        }

    def start_log(self, label):
        self.stop_log()
        self.pid = self.adb('shell', 'pidof', PACKAGE).strip()
        if not self.pid.isdigit():
            raise RuntimeError('Fixture process did not start')
        path = self.out/f'logcat-{label}.txt'
        self.logfile = path.open('w', encoding='utf-8')
        self.logger = subprocess.Popen([self.args.adb, '-s', self.args.serial, 'logcat', '--pid='+self.pid,
            '-v', 'threadtime', '-b', 'main', '-b', 'system', '-b', 'crash'], stdout=self.logfile, stderr=subprocess.STDOUT)
        self.reader = path.open(encoding='utf-8', errors='replace')
        self.pending = ''

    def stop_log(self):
        if self.logger:
            self.logger.terminate()
            try:
                self.logger.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.logger.kill()
                self.logger.wait(timeout=10)
            self.logfile.close()
            try:
                self.read_events()
            finally:
                self.reader.close()
                self.logger = self.reader = self.logfile = None

    def read_events(self):
        if not self.reader:
            return
        text = self.pending + self.reader.read()
        lines = text.split('\n')
        self.pending = lines.pop()
        for line in lines:
            if crash_lines(line):
                raise RuntimeError('Crash/ANR in test process: '+line)
            if 'NuvioStability:' not in line:
                continue
            try:
                event = json.loads(line.split('NuvioStability:', 1)[1])
            except json.JSONDecodeError:
                continue
            event['hostPhase'] = self.phase
            self.events.append(event)
            if event.get('kind') == 'journey-failed':
                raise RuntimeError('Fixture journey failed: '+str(event))

    def check(self, interval=30):
        self.read_events()
        if self.logger and self.logger.poll() is not None:
            raise RuntimeError('Logcat collector stopped')
        if self.adb('shell', 'pidof', PACKAGE, check=False).strip() != self.pid:
            raise RuntimeError('Test process died or restarted')
        if time.monotonic()-self.last_sample >= interval:
            self.save(f'meminfo-{self.sample_number:04}-{self.phase}.txt', self.adb('shell', 'dumpsys', 'meminfo', PACKAGE))
            self.last_sample = time.monotonic()
            self.sample_number += 1
            anr = self.test_anr()
            if anr and anr != self.initial_anr:
                self.save('test-anr.txt', anr)
                raise RuntimeError('New ANR mentions the fixture package')

    def wait(self, seconds, interval=30):
        end = time.monotonic()+seconds
        while time.monotonic() < end:
            self.check(interval)
            time.sleep(min(1, max(0, end-time.monotonic())))

    def journey(self, name, timeout=90):
        offset = len(self.events)
        output = self.adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/'+ACTIVITY, '--es', 'journey', name)
        if 'Error:' in output:
            raise RuntimeError(output)
        end = time.monotonic()+timeout
        while time.monotonic() < end:
            self.check()
            if any(e.get('kind') == 'journey-ready' and e.get('journey') == name for e in self.events[offset:]):
                return
            time.sleep(1)
        raise RuntimeError(f'Journey {name} did not complete in {timeout}s')

    def control(self, query):
        with urlopen('http://127.0.0.1:38791/control?'+query, timeout=10) as response:
            return json.load(response)


@contextmanager
def device_session(session):
    s = session
    installed = False
    before = None
    restore_needed = False
    cleanup_errors = []
    added_reverses = []
    volume = None
    try:
        apk_identity(run(s.args.aapt, 'dump', 'badging', str(s.args.apk)))
        xml = run(s.args.aapt, 'dump', 'xmltree', str(s.args.apk), 'AndroidManifest.xml')
        if ACTIVITY not in xml:
            raise ValueError('APK lacks the shell-protected fixture harness')
        qemu = s.adb('shell', 'getprop', 'ro.kernel.qemu').strip()
        model = s.adb('shell', 'getprop', 'ro.product.model').strip()
        if s.args.device == 'emulator':
            if not s.args.serial.startswith('emulator-') or qemu != '1':
                raise ValueError('Emulator serial/hardware required')
        elif s.args.serial != '192.168.1.121:5555' or qemu == '1' or 'onn' not in model.lower():
            raise ValueError('Only the already-authorised Onn is permitted')
        if f'package:{PACKAGE}' in s.adb('shell', 'pm', 'list', 'packages', PACKAGE).splitlines():
            raise ValueError('Test package already exists; refusing to replace or clear it')
        before = s.state()
        s.initial_anr = s.test_anr()
        s.save('device-before.json', json.dumps(before, indent=2))
        s.save('meminfo-device.txt', s.adb('shell', 'cat', '/proc/meminfo'))
        s.save('display-before.txt', s.adb('shell', 'dumpsys', 'display'))
        s.save('power-before.txt', s.adb('shell', 'dumpsys', 'power'))
        volume = re.search(r'volume is (\d+)', before['volume'], re.I)
        if not volume:
            raise RuntimeError('Cannot determine original media volume; refusing playback')
        existing_reverses = {}
        for line in s.adb('reverse', '--list').splitlines():
            parts = line.split()
            if len(parts) == 3:
                existing_reverses[parts[1]] = parts[2]
        for port in (38791, 38792, 38793):
            target = f'tcp:{port}'
            if target in existing_reverses and existing_reverses[target] != target:
                raise RuntimeError('Conflicting existing adb reverse: '+target)
        restore_needed = True
        s.adb('shell', 'svc', 'power', 'stayon', 'true')
        for key in ['screensaver_enabled', 'screensaver_activate_on_sleep']:
            s.adb('shell', 'settings', 'put', 'secure', key, '0')
        s.adb('shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--set', '0')
        for port in (38791, 38792, 38793):
            target = f'tcp:{port}'
            if target not in existing_reverses:
                added_reverses.append(target)
                s.adb('reverse', target, target)
        installed = True
        if 'Success' not in s.adb('install', str(s.args.apk), timeout=180):
            raise RuntimeError('Fixture APK install failed')
        yield
    finally:
        # Every restoration is independent: one failure must not skip later restores.
        def cleanup(label, operation):
            try:
                operation()
            except BaseException as error:
                cleanup_errors.append(f'{label}: {error}')
        cleanup('log collector', s.stop_log)
        if installed:
            cleanup('test graphics', lambda: s.save('gfxinfo.txt', s.adb('shell', 'dumpsys', 'gfxinfo', PACKAGE)))
            cleanup('test activity', lambda: s.save('activity.txt', s.adb('shell', 'dumpsys', 'activity', 'activities', PACKAGE)))
            cleanup('stop test package', lambda: s.adb('shell', 'am', 'force-stop', PACKAGE))
            def uninstall():
                if 'Success' not in s.adb('uninstall', PACKAGE):
                    raise RuntimeError('Test uninstall failed')
            cleanup('uninstall test package', uninstall)
        for target in added_reverses:
            cleanup('reverse '+target, lambda target=target: s.adb('reverse', '--remove', target))
        if restore_needed:
            for ns, key in SETTINGS:
                value = before['settings'][f'{ns}/{key}']
                cleanup(key, lambda ns=ns, key=key, value=value: s.adb('shell', 'settings',
                    *(['delete', ns, key] if value == 'null' else ['put', ns, key, value])))
            cleanup('media volume', lambda: s.adb('shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--set', volume[1]))
        if before is not None:
            def verify():
                after = s.state()
                s.save('device-after.json', json.dumps(after, indent=2))
                if before['settings'] != after['settings'] or before['productionVersion'] != after['productionVersion']:
                    raise RuntimeError('Before/after settings or production version mismatch')
                after_volume = re.search(r'volume is (\d+)', after['volume'], re.I)
                if volume and (not after_volume or after_volume[1] != volume[1]):
                    raise RuntimeError('Media volume restoration mismatch')
            cleanup('final verification', verify)
            cleanup('display after', lambda: s.save('display-after.txt', s.adb('shell', 'dumpsys', 'display')))
            cleanup('power after', lambda: s.save('power-after.txt', s.adb('shell', 'dumpsys', 'power')))
        s.save('cleanup.json', json.dumps({'errors': cleanup_errors}, indent=2))
        if cleanup_errors:
            raise RuntimeError('Device cleanup incomplete: '+str(cleanup_errors))


def execute(s):
    s.control('tvdb=available&completed=false&slowImport=false')
    s.phase = 'launch-smoke'
    s.adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/com.nuvio.tv.MainActivity')
    s.start_log('launch')
    s.wait(60)
    s.phase = 'seed'
    s.journey('seed', 300)
    s.journey('home')
    s.wait(30)
    s.stop_log()
    s.adb('shell', 'am', 'force-stop', PACKAGE)
    s.adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/'+ACTIVITY, '--es', 'journey', 'home')
    s.start_log('journeys')
    s.phase = 'cw-cold-start'
    started = time.monotonic()
    for at in ((0, 60, 120, 300) if s.args.device == 'emulator' else (0,)):
        s.wait(max(0, started+at-time.monotonic()))
        if at == 60:
            s.journey('refresh')
        s.journey('sample')
        s.observations.append({'phase': s.phase, 'requestedSeconds': at, 'actualSeconds': time.monotonic()-started,
                               'sample': next((e for e in reversed(s.events) if e.get('kind') == 'sample'), None)})
    s.phase = 'soak'
    started = time.monotonic()
    schedule = [(0, 'home'), (25, 'import-reminder'), (28, 'trailer'), (90, 'sports'),
                (120, 'cricket'), (180, 'preview'), (240, 'ondemand'), (300, 'vod'),
                (360, 'home'), (420, 'nntp-stall'), (480, 'cricket'), (540, 'home')]
    s.control('slowImport=true')
    for at, action in schedule:
        s.wait(max(0, started+at-time.monotonic()))
        if action == 'vod':
            s.adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/'+VOD,
                  '--es', 'streamUrl', 'http://127.0.0.1:38791/movie/fixture/fixture/1.mp4',
                  '--es', 'title', 'Fixture', '--es', 'contentType', 'movie', '--es', 'contentId', 'tt9900099')
        else:
            s.journey('home' if action == 'nntp-stall' and s.args.device == 'onn' else action)
    s.wait(max(0, started+600-time.monotonic()))
    if not any(e.get('kind') == 'vod-state' and e.get('state') == 3 and e.get('positionMs', 0) > 0 for e in s.events):
        raise RuntimeError('Real VOD player never reached READY with advancing position')
    s.control('slowImport=false')
    s.stop_log()
    s.adb('shell', 'am', 'force-stop', PACKAGE)
    s.phase = 'cricket-cold-load'
    s.adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/'+ACTIVITY, '--es', 'journey', 'sports')
    s.start_log('cricket')
    s.wait(30, interval=1)
    for iteration in range(1, 6):
        s.phase = f'cricket-{iteration}'
        s.journey('home')
        s.journey('sports')
        s.wait(5)
        offset = len(s.events)
        s.journey('cricket')
        s.wait(15, interval=1)
        if not any(e.get('kind') == 'sports-game-opened' and e.get('event') == 'espn:cricket/19430:77702' for e in s.events[offset:]):
            raise RuntimeError('Cricket detail was not actually opened')
    if s.args.device == 'onn':
        with urlopen('http://127.0.0.1:38791/stats') as response:
            s.save('fixture-stats.json', response.read().decode())
        return
    s.phase = 'launcher-timeline'
    s.journey('home')
    s.wait(20)
    s.journey('background')
    s.wait(5)
    s.journey('home')
    s.wait(5)
    s.journey('sample')
    s.phase = 'launcher-closed-non-home'
    s.journey('complete')
    s.wait(5)
    s.phase = 'launcher-background'
    s.journey('background')
    s.wait(35)
    s.phase = 'launcher-returned-home'
    s.journey('home')
    s.wait(35)
    s.journey('sample')
    s.save('timeline-assertions.json', json.dumps(timeline_assertions(s.events), indent=2))
    for mode in ('missing', 'invalid', 'timeout', 'unavailable', 'available'):
        s.phase = 'tvdb-'+mode
        s.control('tvdb='+('available' if mode == 'missing' else mode))
        s.journey('tvdb-'+mode)
        s.journey('refresh')
        s.wait(30)
        s.journey('sample')
    with urlopen('http://127.0.0.1:38791/stats') as response:
        s.save('fixture-stats.json', response.read().decode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--device', choices=['emulator', 'onn'], required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--aapt', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.:-]+', args.serial):
        parser.error('Invalid serial')
    args.output.mkdir(parents=True, exist_ok=False)
    s = Session(args)
    error = None
    try:
        with device_session(s):
            execute(s)
    except BaseException as failure:
        error = str(failure) or type(failure).__name__
    finally:
        s.save('events.json', json.dumps(s.events, indent=2))
        s.save('observations.json', json.dumps(s.observations, indent=2))
        s.save('result.json', json.dumps({'status': 'failed' if error else 'completed', 'error': error,
            'device': args.device, 'serial': args.serial,
            'note': 'Completed means journeys ran; invariants and measurements need analysis.'}, indent=2))
    if error:
        raise SystemExit(error)
    print('Fixture journeys completed; analyse artifacts: '+str(args.output))


if __name__ == '__main__':
    main()
