"""Static wiring checks, not device UI tests."""
from pathlib import Path
import unittest, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'src/cn/weilin/termlens/probe'
class AskContracts(unittest.TestCase):
 def test_separate_private_activity(self):
  m=ET.parse(ROOT/'AndroidManifest.xml').getroot();a='{http://schemas.android.com/apk/res/android}'
  self.assertEqual(next(x for x in m.findall('application/activity') if x.get(a+'name')=='.AskActivity').get(a+'exported'),'false')
 def test_no_translation_history_or_prefetch(self):
  s=(SRC/'AskActivity.java').read_text()
  self.assertNotIn('.begin(',s);self.assertNotIn('.preload',s);self.assertIn('speakTemporary',s)
  self.assertIn('LlmClient.requestConversation(this,turns,AskPrompt.SYSTEM',s)
 def test_manual_draft_does_not_attach_stale_answer(self):
  s=(SRC/'ExplainActivity.java').read_text().split('private void openAsk()')[1].split('private void beginEditing()')[0]
  self.assertIn('AskPrompt.context(manualDraft, "", "", "", "", false)',s)
 def test_same_layout_labels_and_focus(self):
  ask=(SRC/'AskActivity.java').read_text();explain=(SRC/'ExplainActivity.java').read_text()
  self.assertIn('Ui.title(this,"Termlens",18)',ask);self.assertIn('Ui.title(this, "Termlens", 18)',explain)
  self.assertIn('question.requestFocus()',ask);self.assertIn('showSoftInput(question',ask);self.assertIn('!hasWindowFocus()',ask)
  self.assertNotIn('IME_FLAG_FORCE_ASCII',ask)
 def test_no_request_on_open(self):
  s=(SRC/'AskActivity.java').read_text().split('private void submit()')[0]
  self.assertNotIn('LlmClient.requestConversation',s)
 def test_stale_reply_and_duplicate_guard(self):
  s=(SRC/'AskActivity.java').read_text();self.assertIn('if(busy)return;',s);self.assertIn('generation!=token',s)
 def test_context_automatic_without_visible_controls(self):
  s=(SRC/'AskActivity.java').read_text();self.assertNotIn('new CheckBox',s);self.assertNotIn('查看上下文',s);self.assertIn('attachContext?context:""',s)
 def test_bounded_history_and_rotation(self):
  s=(SRC/'AskActivity.java').read_text();self.assertIn('history.length()>6',s);self.assertIn('MAX_HISTORY_CHARS = 8000',s);self.assertIn('state.putString("history",history.toString())',s)
if __name__=='__main__':unittest.main(verbosity=2)
