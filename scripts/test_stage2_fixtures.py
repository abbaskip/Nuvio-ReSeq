"""Fast generator contracts; no network, Android or accounts."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('stage2',Path(__file__).with_name('stage2_fixtures.py'))
stage2=importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage2)

class FixtureRealismTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder=Path(self.temp.name)
        self.payload=json.dumps({'generatedAt':1794866635012,'entries':[
            {'provider':'espn','leagueId':'cricket/19430','payload':{'events':[
                {'id':'1','name':'Australia v England','date':'2026-11-16T08:00Z','status':{'type':{'state':'in'}}}]}},
            {'provider':'espn','leagueId':'basketball/nba','payload':{'events':[
                {'id':'2','name':'Cleveland Cavaliers at Atlanta Hawks','date':'2026-11-17T00:00Z','status':{'type':{'state':'pre'}}}]}}
        ]},ensure_ascii=False).encode()
        feed=self.folder/'feed.json';feed.write_bytes(self.payload)
        self.data=stage2.Dataset(feed,self.folder)
    def test_public_feed_bytes_are_preserved_and_listings_are_mostly_non_sport(self):
        self.assertEqual(self.payload,self.data.bytes)
        self.assertEqual(50001,len(self.data.channels()))
        self.assertEqual(2400048,self.data.count*48)
        self.assertGreater(len({self.data.programme(i,0)[0] for i in range(1001,50002,97)}),15)
        self.assertTrue(all(self.data.programme(i,0)[2]!='Sports' for i in range(1001,50002)))
        self.assertGreater(len({self.data.programme(i,0)[0] for i in range(1,1001)}),15)
        self.assertEqual(2,len({league for league,_ in self.data.events}))
    def test_pathological_mode_remains_deliberately_repetitive(self):
        self.assertEqual(1,len({self.data.programme(i,h,True)[0] for i in range(1,20) for h in range(-6,42)}))
        self.assertIn('Australia v England',self.data.channels(True)[0]['name'])
    def test_guide_revisions_change_only_non_sports_to_compare_identical_matches(self):
        self.assertEqual([self.data.programme(i,0) for i in range(1,1001)],
            [self.data.programme(i,0,revision=1) for i in range(1,1001)])
        self.assertNotEqual(self.data.programme(5000,0),self.data.programme(5000,0,revision=1))

if __name__=='__main__':unittest.main()
