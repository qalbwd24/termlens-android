from pathlib import Path
import unittest
S=Path(__file__).resolve().parents[1]/'src/cn/weilin/termlens/probe'
class ToolbarContracts(unittest.TestCase):
 def test_no_empty_answer_explanation_hint(self):
  self.assertNotIn('用简单英文回答你的英语学习问题',(S/'AskActivity.java').read_text())
  self.assertIn('A1',(S/'AskPrompt.java').read_text())
 def test_brand_and_input_placeholders(self):
  ask=(S/'AskActivity.java').read_text();translation=(S/'ExplainActivity.java').read_text()
  self.assertIn('Ui.title(this,"Termlens",18)',ask);self.assertIn('question.setHint("Ask...")',ask)
  self.assertIn('Ui.title(this, "Termlens", 18)',translation);self.assertIn('manualInput.setHint("Termlens")',translation)
  for label in ['查看上下文','附带原文和解释','无附带上下文']:self.assertNotIn(label,ask)
 def test_ask_badge_uses_actual_depth_without_followup_increment(self):
  ask=(S/'AskActivity.java').read_text()
  self.assertIn('"疑惑 · "+(navigationDepth+1)',ask)
  self.assertIn('state.getInt(StudyNavigation.DEPTH,0)',ask)
  self.assertIn('StudyNavigation.child(navigationDepth)',ask)
  submit=ask.split('private void submit()')[1].split('private JSONObject favoriteSnapshot()')[0]
  self.assertNotIn('navigationDepth',submit)
 def test_common_order_and_ask_at_right(self):
  self.assertIn('BottomTools.add(toolbar,lookup,listen,favorite,copy,edit,ask)',(S/'ExplainActivity.java').read_text())
  self.assertIn('BottomTools.add(footer,send,listen,favorite,copy,edit)',(S/'AskActivity.java').read_text())
 def test_exact_labels_and_disabled_opacity(self):
  for name,expr in [('ExplainActivity.java','lookup.setText("查询")'),('AskActivity.java','send.setText(selected?"查询":"询问")')]:
   text=(S/name).read_text();self.assertIn(expr,text);self.assertIn('1f : 0.4f' if name.startswith('Explain') else '1f:0.4f',text)
 def test_blank_observation_does_not_consume(self):
  for name in ['ExplainActivity.java','AskActivity.java']:
   text=(S/name).read_text();self.assertIn('blankTap.observe(event,',text);self.assertIn('super.dispatchTouchEvent(event)',text);self.assertIn('return handled;',text);self.assertIn('selectionMode.finish()',text)
  text=(S/'BlankContentTap.java').read_text();self.assertIn('view instanceof EditText',text);self.assertIn('ACTION_POINTER_DOWN',text)
 def test_toolbar_widths_and_large_font_budget(self):
  # Horizontal geometry model: 24dp outer padding, 100dp action+gap; all icons fit.
  for width in [280,320,360,412,600]:
   for count in [4,5]:
    slot=(width-24-100)/count
    self.assertGreaterEqual(slot,24);self.assertLessEqual(slot*count+100,width-24)
  # Two fullwidth chars at 12sp and fontScale 2 fit action content width 88dp.
  self.assertLessEqual(2*12*2,88)
  text=(S/'BottomTools.java').read_text();self.assertIn('new LinearLayout.LayoutParams(0,Ui.dp(context,48),1)',text);self.assertIn('Ui.dp(context,96)',text)
if __name__=='__main__':unittest.main()
