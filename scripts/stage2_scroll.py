"""One grid-scroll pair on an owned fixture emulator, with a real muted preview."""
import argparse
import json
import re
import time
from pathlib import Path
from urllib.request import urlopen
from stage2_measure import Device, PACKAGE, records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--serial', default='emulator-5590')
    parser.add_argument('--revision', type=int, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    device.journey('guide-preview')
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        samples = [x for x in records(device.log()) if x['kind'] == 'sample']
        if samples and samples[-1].get('liveTvPlaying'):
            break
        time.sleep(1)
    else:
        raise RuntimeError('Preview did not start; no scroll measurement')
    # Enter the channel grid, not the group list. The screenshots remain the
    # reviewable proof of focus and actual preview playback.
    texts = device.capture(args.output / 'initial-focus')
    if not any(x in ('Today', 'All channels', 'Favourites') for x in texts):
        device.adb('shell', 'input', 'keyevent', '4')
        time.sleep(1)
        texts = device.capture(args.output / 'preview-focus')
    if not any('Sports Network' in x for x in texts):
        device.adb('shell', 'input', 'keyevent', '21')
        device.capture(args.output / 'groups')
        xml = (args.output / 'groups.xml').read_text(encoding='utf-8')
        import xml.etree.ElementTree as ET
        node = next(n for n in ET.fromstring(xml).iter('node') if n.get('text') == 'All channels')
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
        device.adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(2)
    device.adb('shell', 'input', 'keyevent', '22')
    device.capture(args.output / 'grid-focus')

    def stage_rows():
        return int(device.adb('shell', "sqlite3 '/data/user/0/"+PACKAGE+
                              "/databases/livetv_guide.db' 'select count(*) from stage;'").strip())

    result = {}
    for phase, direction in [('idle', '20'), ('running', '19')]:
        if phase == 'running':
            with urlopen('http://127.0.0.1:39891/stage2-control?guide='+str(args.revision)) as response:
                response.read()
            device.journey('guide-update')
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                count = stage_rows()
                if 0 < count < 2_400_048:
                    break
                time.sleep(.25)
            else:
                raise RuntimeError('No active guide staging; refusing a false running measurement')
            result['stageRowsBefore'] = count
        floor = max((x['elapsedMs'] for x in records(device.log())), default=0)
        device.journey('frames-reset')
        device.adb('shell', 'for i in $(seq 1 20); do input keyevent '+direction+'; sleep 0.15; done')
        device.journey('frames-report')
        time.sleep(1)
        frame = [x for x in records(device.log()) if x['kind']=='guide-frames' and x['elapsedMs'] >= floor][-1]
        result[phase] = frame
        device.capture(args.output / phase)
        if phase == 'running':
            result['stageRowsAfter'] = stage_rows()
    (args.output / 'logcat.txt').write_text(device.log(), encoding='utf-8')
    (args.output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
