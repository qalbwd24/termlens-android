"""Source/manifest contracts, not Android device/UI tests."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'src/cn/weilin/termlens/probe'
A = '{http://schemas.android.com/apk/res/android}'

class ManualContracts(unittest.TestCase):
    def setUp(self):
        self.activity = (SRC / 'ExplainActivity.java').read_text()
    def test_empty_query_guard_precedes_database_and_network(self):
        request=self.activity.split('private void requestCurrent() {',1)[1]
        self.assertLess(request.index('current().original.trim().isEmpty()'),request.index('AppData.get(this).begin'))
        self.assertLess(request.index('current().original.trim().isEmpty()'),request.index('LlmClient.request'))
    def test_editor_and_button_submit_same_method(self):
        self.assertIn('if (manualMode) submitManual(); else openLookup(text)', self.activity)
        self.assertIn('event.getAction() == KeyEvent.ACTION_UP) submitManual()', self.activity)
        self.assertIn('if (!manualMode || levels.isEmpty()) return;', self.activity)
    def test_nested_edit_replaces_only_current_level(self):
        submit=self.activity.split('private void submitManual() {',1)[1].split('private void focusManualInput()',1)[0]
        self.assertIn('levels.set(levels.size() - 1, new Level(query))',submit)
        self.assertNotIn('levels.clear()',submit)
    def test_hardware_gesture_and_close_return_one_level(self):
        self.assertIn('backCallback = this::previousLevel',self.activity)
        self.assertIn('onBackPressed() { previousLevel(); }',self.activity)
        self.assertIn('close.setOnClickListener(v -> previousLevel())',self.activity)
        self.assertIn('ask.setOnClickListener(v -> openAsk())',self.activity)
        back=self.activity.split('private void previousLevel() {',1)[1].split('private void speak(',1)[0]
        self.assertIn('if (hideKeyboard()) return;',back)
        self.assertIn('if (levels.size() <= 1) { finish(); return; }',back)
        self.assertIn('levels.remove(levels.size() - 1)',back)
    def test_keyboard_attach_focus_and_insets(self):
        self.assertIn('!hasWindowFocus()',self.activity)
        self.assertIn('onWindowFocusChanged',self.activity)
        self.assertIn('editor.requestFocus()',self.activity)
        self.assertIn('showSoftInput(editor',self.activity)
        self.assertIn('FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM',self.activity)
        self.assertIn('Type.systemBars() | android.view.WindowInsets.Type.ime()',self.activity)
    def test_pencil_left_and_right_action_retained(self):
        self.assertIn('BottomTools.add(toolbar,lookup,listen,favorite,copy,edit,ask)',self.activity)
        self.assertIn('lookup.setText("查询")',self.activity)
        self.assertIn('lookup.setEnabled(manualMode ? canSubmit : !busy && selected)',self.activity)
    def test_stale_requests_and_parent_scroll_guarded(self):
        self.assertIn('generation != requestId',self.activity)
        self.assertIn('current() != level',self.activity)
        self.assertIn('scroll.scrollTo(0, level.scrollY)',self.activity)
    def test_no_new_permissions(self):
        manifest=ET.parse(ROOT/'AndroidManifest.xml').getroot()
        self.assertEqual([x.get(A+'name') for x in manifest.findall('uses-permission')],['android.permission.INTERNET','android.permission.SYSTEM_ALERT_WINDOW'])
    def test_voice_rate_cache_identity(self):
        audio=(SRC/'SpeechOutput.java').read_text()
        self.assertIn('provider+"\\n"+voice+"\\n"+rate',audio)
        self.assertIn('voice.equals(m.optString("Voice"))&&rate==m.optInt("Rate")',audio)
    def test_distinct_test_package_and_forwarded_menu(self):
        build=(ROOT/'build.py').read_text()
        self.assertIn('PACKAGE + ".manualtest" if args.test_app else PACKAGE',build)
        self.assertIn('"--custom-package", PACKAGE',build)
        self.assertIn('"Explain 测试"',build)
        bridge=(SRC/'SelectionMenuBridge.java').read_text()
        self.assertIn('service.getString(R.string.explain_action_label).equals(text)',bridge)

if __name__ == '__main__': unittest.main(verbosity=2)
