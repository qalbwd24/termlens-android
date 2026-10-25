from pathlib import Path
import unittest
SRC=Path(__file__).resolve().parents[1]/'src/cn/weilin/termlens/probe'
class NestedContracts(unittest.TestCase):
 def setUp(self):self.ask=(SRC/'AskActivity.java').read_text();self.translation=(SRC/'ExplainActivity.java').read_text()
 def test_cancelled_side_rail_absent(self):
  for name in ['StudyToolbar.java','StudyLayout.java']:self.assertFalse((SRC/name).exists())
  self.assertNotIn('TOOLBAR_POSITION',(SRC/'TermLensPrefs.java').read_text())
  self.assertIn('footer.setGravity(Gravity.CENTER_VERTICAL)',self.ask)
  self.assertIn('toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL)',self.translation)
 def test_ask_does_not_open_another_ask(self):
  self.assertNotIn('private void openAsk(',self.ask)
  self.assertNotIn('ic_termlens_ask',self.ask)
  self.assertIn('new View(this)',self.ask)
 def test_translation_still_opens_ask(self):self.assertIn('ask.setOnClickListener(v -> openAsk())',self.translation)
 def test_selected_answer_and_current_question_dispatch(self):
  self.assertIn('if(!chosen.trim().isEmpty())openTranslation(chosen);else submit()',self.ask)
  self.assertIn('selected?"查询":"询问"',self.ask)
  self.assertIn('history.length()>0?"继续询问":"询问输入的问题"',self.ask)
 def test_edit_clears_stale_selection(self):
  self.assertIn('edit.setOnClickListener(v->{clearAnswerSelection()',self.ask)
  self.assertIn('question.setOnTouchListener',self.ask)
  self.assertIn('android.text.Selection.removeSelection',self.ask)
 def test_child_translation_does_not_clear_task(self):
  block=self.ask.split('private void openTranslation(')[1].split('private JSONObject message(')[0]
  self.assertIn('new Intent(this,ExplainActivity.class)',block)
  for flag in ['FLAG_ACTIVITY_NEW_TASK','FLAG_ACTIVITY_CLEAR_TOP','FLAG_ACTIVITY_CLEAR_TASK']:self.assertNotIn(flag,block)
  self.assertIn('StudyNavigation.LOOKUP',block);self.assertIn('StudyNavigation.PROFILE',block)
 def test_child_translation_uses_secondary_prompt(self):
  self.assertIn('levels.size() > 1 || inheritedLookup != null',self.translation)
  self.assertIn('contextual ? secondarySystem : primarySystem',self.translation)
  self.assertIn('inheritedLookup.optString("parentAnswer")',self.translation)
 def test_back_returns_current_child_only(self):
  self.assertIn('if (levels.size() <= 1) { finish(); return; }',self.translation)
  self.assertIn('private void goBack(){if(hideKeyboard())return;finish();}',self.ask)
 def test_audio_is_on_demand_and_stopped_when_leaving(self):
  self.assertIn('listen.setOnClickListener(v->SpeechOutput.get(this).speakTemporary',self.ask)
  self.assertIn('onPause(){SpeechOutput.get(this).stop()',self.ask)
  self.assertNotIn('.preload',self.ask)
  speech=(SRC/'SpeechOutput.java').read_text().split('void speakTemporary(')[1].split('void speak(JSONObject')[0]
  self.assertNotIn('.begin(',speech);self.assertIn('"ask-"',speech)
 def test_context_not_full_stack(self):
  self.assertIn('LookupInput.clip(answer,3000,selected)',self.ask)
  self.assertIn('rootAnswer", LookupInput.clip',self.translation)
  self.assertNotIn('levels.toString()',self.translation)
if __name__=='__main__':unittest.main(verbosity=2)
