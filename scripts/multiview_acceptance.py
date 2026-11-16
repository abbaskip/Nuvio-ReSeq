"""One lean, disposable emulator session: M1 plus both dev regressions and existing CW flows."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.request import urlopen
import xml.etree.ElementTree as ET
from stability_run import device_session, PACKAGE, ACTIVITY
from stability_stage1 import Stage1Session, execute, require, latest, membership, completed_offer

PORT = int(os.environ.get('M1_FIXTURE_PORT', '48791'))


class M1Session(Stage1Session):
    def control(self, query):
        with urlopen(f'http://127.0.0.1:{PORT}/control?'+query, timeout=10) as response:
            return json.load(response)

    def adb(self, *command, **kwargs):
        if len(command) == 3 and command[0] == 'reverse' and command[1] in ('tcp:38791', 'tcp:38792', 'tcp:38793'):
            command = (command[0], command[1], 'tcp:'+str(PORT+int(command[1][4:])-38791))
        return super().adb(*command, **kwargs)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--apk', type=Path, required=True)
    p.add_argument('--adb', required=True)
    p.add_argument('--aapt', required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--skip-cw', action='store_true', help='Resume only the M1/regression acceptance after a recorded CW pass')
    p.add_argument('--cw-tail', action='store_true', help='Rerun the affected playback/background tail after recorded cold-start and TVDB passes')
    p.add_argument('--remaining', action='store_true', help='Resume isolated unwatch and M1 checks after recorded CW/shuffle passes')
    p.add_argument('--pause-rewatch-only', action='store_true', help='Verify removal of a paused rewatch while preserving older split history')
    p.add_argument('--regressions-only', action='store_true', help='Resume only tracker/CW regressions after a recorded two-pane pass')
    a = p.parse_args()
    if not re.fullmatch(r'emulator-\d+', a.serial):
        p.error('Disposable emulator serial required; physical TVs are forbidden')
    a.device = 'emulator'
    a.output.mkdir(parents=True, exist_ok=False)
    s = M1Session(a)

    def fresh(action):
        s.stop_log()
        s.adb('shell', 'am', 'force-stop', PACKAGE)
        s.adb('shell', 'pm', 'clear', PACKAGE)
        s.adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/'+ACTIVITY,
              '--es', 'itemId', 'tt0386676', '--es', 'itemType', 'series', '--es', 'journey', 'sample')
        s.start_log(action)
        s.phase = action
        s.journey(action, 180)

    def data():
        with urlopen(f'http://127.0.0.1:{PORT}/m1/calls', timeout=5) as response:
            return json.load(response)

    def tap_text(text):
        s.adb('shell', 'uiautomator', 'dump', '/sdcard/m1-ui.xml')
        xml = s.adb('shell', 'cat', '/sdcard/m1-ui.xml')
        node = next(n for n in ET.fromstring(xml).iter('node') if text in (n.get('text', '') + n.get('content-desc', '')))
        left, top, right, bottom = map(int, re.findall(r'\d+', node.attrib['bounds']))
        s.adb('shell', 'input', 'tap', str((left+right)//2), str((top+bottom)//2))
        s.wait(1)

    def remove_watch():
        s.adb('shell', 'uiautomator', 'dump', '/sdcard/m1-options.xml')
        xml = s.adb('shell', 'cat', '/sdcard/m1-options.xml')
        s.save('rewatch-options.xml', xml)
        root = ET.fromstring(xml)
        require(any(n.get('focused') == 'true' and any('Remove last watch' in c.get('text', '')
            for c in n.iter('node')) for n in root.iter('node')), 'Remove last watch must own TV focus')
        s.adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_CENTER')
        s.wait(1)

    failure = None
    try:
        with device_session(s):
            if a.pause_rewatch_only:
                fresh('m1-rewatch-seed')
                remove_watch()
                s.journey('m1-rewatch-assert')
                rewatch = data()
                require(rewatch['writes'] == [{'path': '/trakt/sync/playback/4321', 'body': {}}],
                    'Paused rewatch removal must delete only playback ID 4321')
                require(sorted(p['id'] for p in rewatch['plays']) == [1, 3] and rewatch['pause'] is None,
                    'Paused rewatch removal changed completed history')
                s.save('paused-rewatch-tracker.json', json.dumps(rewatch, indent=2))
                s.observations.append({'check': 'a4d01d7de-paused-rewatch-preserves-both-watched-parts-and-cw', 'passed': True})
                print('Paused rewatch emulator acceptance passed', flush=True)
                return
            if not a.skip_cw and not a.remaining:
                if a.cw_tail:
                    s.control('tvdb=available&completed=false&slowImport=false&office=false&trackerUnwatch=false')
                    s.phase = 'cw-tail-seed'
                    s.adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/'+ACTIVITY, '--es', 'journey', 'sample')
                    s.start_log('cw-tail')
                    s.journey('seed', 300)
                    s.journey('home')
                    s.wait(15)
                    s.journey('sample')
                    membership(latest(s))
                    s.journey('complete')
                    s.wait(5)
                    s.journey('sample')
                    completed_offer(latest(s))
                    s.phase = 'live-tv-background-from-home'
                    s.journey('home')
                    s.journey('preview')
                    s.wait(10)
                    s.journey('home-with-live')
                    require(latest(s)['liveTvPlaying'], 'Live TV fixture did not play before background check')
                    s.journey('background')
                    s.wait(35)
                    require(not latest(s)['liveTvPlaying'], 'Live TV continued in background')
                    s.journey('home')
                    s.journey('refresh')
                    s.journey('sample')
                    completed_offer(latest(s))
                    s.observations.append({'check': 'cw-and-live-tv-tail-with-longer-fixture', 'passed': True})
                else:
                    execute(s, 1)
                s.journey('m1-shuffle')
                s.observations.append({'check': 'existing-cw-cold-complete-away-shuffle', 'passed': True})
            if not a.skip_cw:
                s.stop_log()
                for scenario in ('local', 'tracker'):
                    s.adb('shell', 'am', 'force-stop', PACKAGE)
                    require('Success' in s.adb('shell', 'pm', 'clear', PACKAGE), 'Fixture reset failed')
                    subprocess.run([sys.executable, str(Path(__file__).with_name('stability_unwatch.py')),
                        '--adb', a.adb, '--adb-port', os.environ.get('ANDROID_ADB_SERVER_PORT', '5037'),
                        '--serial', a.serial, '--package', PACKAGE, '--scenario', scenario,
                        '--fixture-port', str(PORT),
                        '--output', str(a.output/('unwatch-'+scenario))], check=True)
                s.control('trackerUnwatch=false&office=false&completed=false')
            if not a.regressions_only:
                fresh('m1-multiview')
                (s.out/'two-panes.png').write_bytes(subprocess.check_output([a.adb, '-s', a.serial, 'exec-out', 'screencap', '-p']))
                s.adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
                s.wait(2)
                s.journey('m1-background-assert')
                s.journey('m1-stop')
                s.journey('m1-screen')
                tap_text('All channels')
                tap_text('Fixture 1')
                s.adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_RIGHT')
                s.adb('shell', 'input', 'keyevent', 'KEYCODE_DPAD_CENTER')
                s.wait(1)
                tap_text('Fixture 2')
                s.journey('m1-screen-assert')
                (s.out/'production-screen-two-panes.png').write_bytes(subprocess.check_output([a.adb, '-s', a.serial, 'exec-out', 'screencap', '-p']))
                s.observations.append({'check': 'two-panes-background-stop', 'passed': True})
            fresh('m1-rewatch-seed')
            remove_watch()
            s.journey('m1-rewatch-assert')
            rewatch = data()
            require([w['body'] for w in rewatch['writes']] == [{'ids': [2]}], 'Removal must target only history ID 2')
            require(sorted(p['id'] for p in rewatch['plays']) == [1, 3], 'Older and unrelated history must survive')
            s.save('rewatch-tracker.json', json.dumps(rewatch, indent=2))
            s.observations.append({'check': 'a4d01d7de-details-rewatch-split-history-cw', 'passed': True})
            fresh('m1-stale-pause')
            s.observations.append({'check': '8bd4f83b2-stale-card-current-pause', 'passed': True})
            fresh('m1-contract')
            contract = data()
            compact = []
            for write in contract['writes']:
                body = write['body']
                if '/scrobble/' in write['path']:
                    ep = body['episode']
                    compact.append([write['path'].rsplit('/', 1)[1], ep['season'], ep['number'], body['progress']])
                else:
                    compact.append(['unwatched' if write['path'].endswith('/remove') else 'watched',
                        [[season['number'], ep['number']] for show in body.get('shows', [])
                         for season in show.get('seasons', []) for ep in season.get('episodes', [])]])
            expected = [['start', 5, 14, 0], ['pause', 5, 14, 79], ['start', 5, 14, 79],
                ['stop', 5, 14, 100], ['stop', 5, 15, 100], ['watched', [[5, 14], [5, 15]]],
                ['unwatched', [[5, 14], [5, 15]]], ['start', 6, 16, 0], ['pause', 6, 16, 23.5],
                ['start', 6, 16, 23.5], ['pause', 6, 16, 47.1796]]
            s.save('tracker-contract.json', json.dumps({'actual': compact, 'expected98': expected, 'requests': contract}, indent=2))
            require(compact == expected, 'Emulator tracker writes differ from the published 9.8 contract')
            s.observations.append({'check': '9.8-normal-play-watched-unwatched-contract', 'passed': True})
    except BaseException as error:
        failure = {'type': type(error).__name__, 'message': str(error)}
    finally:
        s.save('events.json', json.dumps(s.events, indent=2))
        s.save('assertions.json', json.dumps(s.observations, indent=2))
        s.save('result.json', json.dumps({'passed': failure is None, 'failure': failure}, indent=2))
    if failure:
        raise SystemExit(str(failure))
    print('M1 and dev regression emulator acceptance passed', flush=True)


if __name__ == '__main__':
    main()
