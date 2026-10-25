"""Storage SQL model and source contracts, not Android runtime tests."""
from pathlib import Path
import json, sqlite3, unittest
ROOT=Path(__file__).resolve().parents[1]
S=ROOT/'src/cn/weilin/termlens/probe'
class AskFavoriteContract(unittest.TestCase):
    def test_persist_only_query_no_ledger(self):
        s=(S/'AppData.java').read_text().split('synchronized void persistAsk')[1].split('synchronized JSONObject begin')[0]
        self.assertIn('"queries"',s)
        self.assertNotIn('"selection_events"',s)
        self.assertNotIn('SpeechOutput',s)
    def test_history_routes_and_legacy_preserved(self):
        s=(S/'HistoryActivity.java').read_text()
        self.assertIn('"ask".equals(record.optString("kind"))?AskActivity.class:ExplainActivity.class',s)
    def test_completed_only_and_whitelisted_context(self):
        s=(S/'AskFavorite.java').read_text()
        self.assertIn('answer.trim().isEmpty()',s)
        self.assertIn('while(kept.toString().length()>8000)',s)
        self.assertNotIn('TermLensPrefs',s)
        self.assertNotIn('LlmClient',s)
    def test_same_question_favorite_belongs_to_exact_round(self):
        s=(S/'AppData.java').read_text()
        self.assertIn('f.optString("QueryId").equals(record.optString("id"))',s)
        self.assertIn('if(savedOnly&&!exact)continue',s)
    def test_existing_schema_roundtrip_and_repeated_toggle(self):
        db=sqlite3.connect(':memory:')
        db.execute('CREATE TABLE favorites(id TEXT PRIMARY KEY, favorite_key TEXT UNIQUE NOT NULL, kind TEXT NOT NULL, term TEXT NOT NULL, saved INTEGER NOT NULL, record TEXT NOT NULL)')
        old={'Id':'legacy1','Kind':'explain','Term':'word','Saved':True,'Explanation':'meaning'}
        ask={'Id':'ask1','QueryId':'q1','Kind':'ask','Term':'effect?','Saved':True,'Explanation':'A result.','Context':json.dumps({'history':[], 'context':'affect'})}
        for record in [old,ask]:
            db.execute('INSERT OR REPLACE INTO favorites VALUES(?,?,?,?,?,?)',(record['Id'],record['Kind']+':'+record['Term'],record['Kind'],record['Term'],1,json.dumps(record)))
        for saved in [False,True,False,True]:
            ask['Saved']=saved
            db.execute('INSERT OR REPLACE INTO favorites VALUES(?,?,?,?,?,?)',(ask['Id'],'ask:effect?','ask','effect?',int(saved),json.dumps(ask)))
        self.assertEqual(db.execute('SELECT COUNT(*) FROM favorites').fetchone()[0],2)
        exported=[json.loads(row[0]) for row in db.execute('SELECT record FROM favorites WHERE saved=1')]
        restored=json.loads(json.dumps(exported))
        self.assertEqual(restored[0],old)
        self.assertEqual(restored[1]['QueryId'],'q1')
        self.assertEqual(json.loads(restored[1]['Context'])['context'],'affect')
if __name__=='__main__': unittest.main()
