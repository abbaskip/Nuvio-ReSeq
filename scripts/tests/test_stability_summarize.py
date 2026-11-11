import unittest
import json
from pathlib import Path
import tempfile
from scripts.stability_summarize import memory, summarize


class MemoryParsingTest(unittest.TestCase):
    def test_pss_is_not_confused_with_heap_allocation_or_rss(self):
        result = memory('''  Native Heap 10 11 12 13 14 15 16 17
  Dalvik Heap 20 21 22 23 24 25 26 27
 Java Heap: 30 31
 Native Heap: 40 41
 Graphics: 50 51
 TOTAL PSS: 60 TOTAL RSS: 61
''')
        self.assertEqual(30, result['Java HeapPssKiB'])
        self.assertEqual(40, result['Native HeapPssKiB'])
        self.assertEqual(16, result['Native HeapAllocKiB'])
        self.assertEqual(26, result['Dalvik HeapAllocKiB'])
        self.assertEqual(60, result['TOTAL PSSPssKiB'])

    def test_missing_measurement_is_not_zero(self):
        self.assertEqual({}, memory('No process found'))

    def test_gc_units_and_phase_boundaries_remain_distinct(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            events = [dict(kind='sample', wallMs=i, elapsedMs=i, dispatcherBlocked=0,
                hostPhase=phase) for i, phase in enumerate(('cricket-1', 'nntp-focused'))]
            (folder/'events.json').write_text(json.dumps(events))
            (folder/'cleanup.json').write_text('{"errors": []}')
            (folder/'logcat-cricket.txt').write_text(
                'NuvioStability: '+json.dumps(events[0])+'\nGC paused 500us,1.5ms total 100ms\n'+
                'NuvioStability: '+json.dumps(events[1])+'\nGC paused 4ms total 100ms\n')
            result = summarize(folder)
            self.assertEqual(4, result['gcPauseMaxMs'])
            self.assertEqual({'cricket-1': 2, 'nntp-focused': 4}, result['gcPauseMaxByPhaseMs'])
