import unittest
from scripts.stability_summarize import memory


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
