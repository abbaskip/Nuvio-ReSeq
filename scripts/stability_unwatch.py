"""Assert the details -> committed unwatch -> durable CW -> Home flow on a synthetic emulator.

Requires an installed -PstabilityFixtures=true APK and stability_fixtures.py on port 38791.
Never accepts a physical-device serial. Does not install, play media or use real accounts.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--adb-port', default='5039')
    parser.add_argument('--serial', default='emulator-5582')
    parser.add_argument('--package', default='io.github.abbaskip.nuvioreseq.iptvdebug',
                        choices=['io.github.abbaskip.nuvioreseq.iptvdebug', 'io.github.abbaskip.nuvioreseq.debug'])
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('Synthetic emulator only; physical TVs are forbidden')

    def adb(*command):
        return subprocess.check_output([args.adb, '-P', args.adb_port, '-s', args.serial, *command], timeout=30)

    assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == b'1', 'Not an emulator'
    args.output.mkdir(parents=True, exist_ok=True)
    for port in (38791, 38792, 38793):
        adb('reverse', f'tcp:{port}', f'tcp:{port}')
    # Only the separate fixture app is restarted, never the release package.
    adb('shell', 'am', 'force-stop', args.package)
    result = []
    for scenario in ('resume', 'history'):
        directory = args.output / scenario
        directory.mkdir(exist_ok=True)
        for action, capture in (('unwatch-seed' if scenario == 'resume' else 'unwatch-seed-history', 'before'),
                                ('unwatch-latest', 'after')):
            adb('logcat', '-c')
            adb('shell', 'am', 'start', '-n', args.package+'/com.nuvio.tv.profiling.StabilityJourneyActivity',
                '--es', 'itemId', 'tt9900002', '--es', 'itemType', 'series', '--es', 'journey', action)
            deadline = time.monotonic() + 120
            while True:
                log = adb('logcat', '-d', '-v', 'brief', 'NuvioStability:I', '*:S').decode(errors='replace')
                events = []
                for line in log.splitlines():
                    if '{' in line:
                        try:
                            events.append(json.loads(line[line.index('{'):]))
                        except json.JSONDecodeError:
                            pass
                failed = [e for e in events if e.get('kind') == 'journey-failed']
                ready = any(e.get('kind') == 'journey-ready' and e.get('journey') == action for e in events)
                if failed or ready or time.monotonic() >= deadline:
                    (directory/(capture+'.log')).write_text(log, encoding='utf-8')
                    (directory/(capture+'.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
                    assert not failed, failed
                    assert ready, f'Timeout waiting for {action}; no automatic retry'
                    if capture == 'after':
                        assert any(e.get('kind') == 'unwatch-assertions-passed' for e in events), 'Missing assertions'
                    break
                time.sleep(2)
        result.append({'scenario': scenario, 'passed': True})
        print(f'{scenario}: PASS', flush=True)
    (args.output/'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
