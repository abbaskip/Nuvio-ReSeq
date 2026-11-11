"""Summarise recorded baseline artifacts; never turn missing evidence into zero/pass."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re


def memory(text):
    values = {}
    for label in ('Java Heap', 'Native Heap', 'Graphics', 'TOTAL PSS'):
        match = re.search(r'^\s*'+label+r':\s+(\d+)', text, re.M)
        if match:
            values[label+'PssKiB'] = int(match[1])
    for label in ('Dalvik Heap', 'Native Heap'):
        match = re.search(r'^\s*'+label+r'\s+((?:\d+\s+){7}\d+)\s*$', text, re.M)
        if match:
            values[label+'AllocKiB'] = int(match[1].split()[-2])
    return values


def summarize(folder):
    events = json.loads((folder/'events.json').read_text())
    phases = defaultdict(list)
    for file in sorted(folder.glob('meminfo-[0-9]*.txt')):
        phase = file.stem.split('-', 2)[2]
        phases[phase].append(memory(file.read_text()))
    memory_summary = {}
    for phase, records in phases.items():
        keys = set().union(*(row.keys() for row in records))
        memory_summary[phase] = {'samples': len(records), **{key: {
            'first': records[0].get(key), 'last': records[-1].get(key),
            'peak': max(row[key] for row in records if key in row)} for key in sorted(keys)}}
    samples = [e for e in events if e.get('kind') == 'sample']
    pauses = []
    for file in folder.glob('logcat-*.txt'):
        for match in re.finditer(r'paused ([\d.,]+(?:us|ms)(?:,[\d.]+(?:us|ms))*)', file.read_text(errors='replace')):
            durations = re.findall(r'([\d.]+)(us|ms)', match[1])
            pauses.append(sum(float(value)/(1000 if unit == 'us' else 1) for value, unit in durations))
    return {
        'result': json.loads((folder/'result.json').read_text()) if (folder/'result.json').exists() else None,
        'environment': [e for e in events if e.get('kind') == 'environment'],
        'memory': memory_summary,
        'runtimeSamples': [{key: e.get(key) for key in ('hostPhase', 'elapsedMs', 'route', 'heapUsedBytes',
            'nativeBytes', 'threadCount', 'dispatcherBlocked', 'gc', 'cwIds', 'snapshot', 'snapshotVersion',
            'launcher', 'rawProgress', 'guideBuilding', 'heavyJobs')} for e in samples],
        'gcPauseMaxMs': max(pauses) if pauses else None,
        'gcLogEvents': len(pauses),
        'reminders': [{**e, 'delayMs': e['shownMs']-e['dueMs']} for e in events if e.get('kind') == 'reminder-shown'],
        'maxBlockedDispatcherWorkers': max((e['dispatcherBlocked'] for e in samples), default=None),
        'maxQuietQueue': max((e['queued'] for e in events if e.get('kind') == 'pool' and e.get('pool') == 'quiet'), default=None),
        'cricketOpens': [e for e in events if e.get('kind') == 'sports-game-opened'],
        'tvdb': [e for e in events if e.get('kind') == 'tvdb-probe'],
        'nntp': [e for e in events if e.get('kind', '').startswith('nntp-')],
        'gates': [e for e in events if e.get('kind', '').startswith('gate-')],
        'cleanup': json.loads((folder/'cleanup.json').read_text()),
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path)
    args = parser.parse_args()
    path = args.folder/'summary.json'
    path.write_text(json.dumps(summarize(args.folder), indent=2), encoding='utf-8')
    print(path)
