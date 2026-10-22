package cn.weilin.termlens.probe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Device-local TermLens settings. Prompt export deliberately excludes credentials. */
public final class SettingsActivity extends Activity {
    private static final int PICK_IMPORT = 41;
    private static final int PICK_EXPORT = 42;
    private Switch resident;
    private EditText baseUrl, model, apiKey;
    private EditText speechVoice,speechRate;
    private EditText primaryName, primaryPrompt, secondaryName, secondaryPrompt;
    private RadioGroup buttonPosition;
    private Switch translucent, positionLocked, hideOnKeyboard;
    private boolean switchingScene;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("TermLens 设置");
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.CANVAS);
        LinearLayout body = Ui.column(this);
        int p = Ui.dp(this, 14);
        body.setPadding(p, p, p, p);
        body.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(p + insets.getSystemWindowInsetLeft(), p + insets.getSystemWindowInsetTop(),
                p + insets.getSystemWindowInsetRight(), p + insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.addView(body);
        setContentView(scroll);
        body.requestApplyInsets();

        body.addView(Ui.title(this, "TermLens 设置", 20));
        Ui.space(this, body, 6);
        body.addView(Ui.text(this, "设置自动保存在本机。备份不含密钥。", 11, Ui.MUTED));
        Ui.space(this, body, 12);

        body.addView(section("交互"));
        body.addView(Ui.text(this, "按钮位置", 13, Ui.INK));
        body.addView(Ui.text(this,"未锁定时，长按按钮即可拖动。",11,Ui.MUTED));
        buttonPosition = new RadioGroup(this);
        String[] positions = {"自动避让", "右下角", "左下角", "自定义位置"};
        String savedPosition = TermLensPrefs.buttonPosition(this);
        for (int i = 0; i < positions.length; i++) {
            RadioButton option = new RadioButton(this); option.setId(1001 + i);
            option.setText(positions[i]); option.setTextSize(13); option.setTextColor(Ui.INK);option.setMinHeight(Ui.dp(this,40));
            buttonPosition.addView(option);
        }
        buttonPosition.check("custom".equals(savedPosition) ? 1004 : "right".equals(savedPosition) ? 1002 : "left".equals(savedPosition) ? 1003 : 1001);
        body.addView(buttonPosition);
        positionLocked = toggle("锁定位置", "关闭后，长按按钮再拖动；松手自动记住位置。");
        positionLocked.setChecked(TermLensPrefs.buttonLocked(this)); body.addView(positionLocked);
        positionLocked.setOnCheckedChangeListener((v, checked) -> {
            TermLensPrefs.put(this, TermLensPrefs.BUTTON_LOCKED, checked); SelectionOverlayService.refreshSettings();
        });
        hideOnKeyboard = toggle("键盘弹出时隐藏按钮", "收起键盘后恢复，避免遮住输入和发送按钮。");
        hideOnKeyboard.setChecked(TermLensPrefs.hideOnKeyboard(this)); body.addView(hideOnKeyboard);
        hideOnKeyboard.setOnCheckedChangeListener((v, checked) -> {
            TermLensPrefs.put(this, TermLensPrefs.HIDE_ON_KEYBOARD, checked); SelectionOverlayService.refreshSettings();
        });
        translucent = toggle("半透明划词按钮", "按钮淡显，按下时清晰显示。");
        translucent.setChecked(TermLensPrefs.buttonTranslucent(this)); body.addView(translucent);
        Ui.space(this, body, 12);
        resident = toggle("常驻 Explain 按钮", "跨页面保留角落按钮；先选中文字，再点一下翻译。");
        android.widget.Button overlayPermission = Ui.button(this, "悬浮窗授权");
        overlayPermission.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName()))));
        body.addView(overlayPermission);
        body.addView(resident);
        Ui.space(this, body, 12);


        LinearLayout mainBody=body;
        body=accordion(mainBody,"模型连接");
        baseUrl = field(body, "API 地址", TermLensPrefs.baseUrl(this), false,
            "OpenAI 兼容接口地址，例如 https://api.deepseek.com");
        model = field(body, "模型", TermLensPrefs.model(this), false, "模型名称，例如 deepseek-chat");
        apiKey = field(body, "API 密钥", TermLensPrefs.apiKey(this), true, "只保存在本机，不会导出");
        Ui.space(this, body, 16);

        body=accordion(mainBody,"朗读");
        body.addView(Ui.text(this,"内置 Edge 语音，手机独立使用。原文和解释自动预缓存，收藏音频长期保存。",13,Ui.MUTED));
        speechVoice=field(body,"声音",TermLensPrefs.speechVoice(this),false,"如 en-US-AndrewMultilingualNeural（支持中英文）");
        speechRate=field(body,"语速百分比",Integer.toString(TermLensPrefs.speechRate(this)),false,"-50 到 50，0 为正常语速");
        body=accordion(mainBody,"阅读场景与提示词");
        android.widget.Button scene=Ui.button(this,"切换阅读场景");body.addView(scene);
        scene.setOnClickListener(v->{saveValues();org.json.JSONArray profiles=SceneProfiles.library(this).optJSONArray("PromptPresets");String[] names=new String[profiles.length()];for(int i=0;i<names.length;i++)names[i]=profiles.optJSONObject(i).optString("Name");
            new android.app.AlertDialog.Builder(this).setTitle("阅读场景").setItems(names,(d,which)->{switchingScene=true;SceneProfiles.select(this,profiles.optJSONObject(which).optString("Id"));recreate();}).show();});
        primaryName = field(body, "主提示词名称", TermLensPrefs.primaryName(this), false, "例如：聊天与日常阅读");
        primaryPrompt = field(body, "主提示词", TermLensPrefs.primaryPrompt(this), false, "用于第一层 Explain");
        primaryPrompt.setMinLines(5);
        primaryPrompt.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        body.addView(Ui.text(this, "已同步电脑端主、二级提示词；在结果里选词后，直接使用二级提示词和上一层语境。", 13, Ui.MUTED));
        secondaryName = field(body, "二级提示词名称", TermLensPrefs.secondaryName(this), false, "用于结果页继续查询");
        secondaryPrompt = field(body, "二级提示词", TermLensPrefs.secondaryPrompt(this), false, "用于第二层 Explain");
        secondaryPrompt.setMinLines(5);
        secondaryPrompt.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        Ui.space(this, body, 10);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.Button save = Ui.button(this, "保存设置");
        save.setOnClickListener(v -> { saveValues(); toast("设置已保存"); });
        android.widget.Button export = Ui.quietButton(this, "导出提示词");
        export.setOnClickListener(v -> { saveValues(); startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "termlens-prompts.json"), PICK_EXPORT); });
        android.widget.Button importButton = Ui.quietButton(this, "导入提示词");
        importButton.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), PICK_IMPORT));
        actions.addView(export, new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1));
        actions.addView(new View(this), new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
        actions.addView(importButton, new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1));
        body.addView(actions);
        Ui.space(this, body, 12);
        body.addView(Ui.text(this, "导出文件只包含提示词名称、内容和默认二级设置，不包含 API 地址、模型或 API 密钥。", 13, Ui.MUTED));
        Ui.space(this,mainBody,16);mainBody.addView(save,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));
    }
    private LinearLayout accordion(LinearLayout parent,String label){
        android.widget.Button heading=Ui.quietButton(this,label+"  ⌄");heading.setGravity(android.view.Gravity.CENTER_VERTICAL|android.view.Gravity.START);heading.setPadding(Ui.dp(this,12),0,Ui.dp(this,12),0);heading.setTextSize(14);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Ui.dp(this,48));lp.topMargin=Ui.dp(this,10);parent.addView(heading,lp);
        LinearLayout pane=Ui.column(this);pane.setPadding(Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,12));parent.addView(pane);pane.setVisibility(View.GONE);
        heading.setOnClickListener(v->{boolean open=pane.getVisibility()!=View.VISIBLE;pane.setVisibility(open?View.VISIBLE:View.GONE);heading.setText(label+(open?"  ⌃":"  ⌄"));});return pane;
    }

    private TextView section(String text) {
        TextView title = Ui.title(this, text, 15);
        title.setTextColor(Ui.ACCENT);
        return title;
    }

    private Switch toggle(String label, String summary) {
        Switch button = new Switch(this);
        button.setText(label);button.setContentDescription(label+"，"+summary);
        button.setTextSize(13);button.setMinHeight(Ui.dp(this,46));button.setSingleLine(true);
        button.setTextColor(Ui.INK);
        button.setLineSpacing(Ui.dp(this, 3), 1);
        button.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        return button;
    }

    private EditText field(LinearLayout parent, String label, String value, boolean secret, String hint) {
        parent.addView(Ui.text(this, label, 12, Ui.MUTED));Ui.space(this,parent,5);
        EditText edit = edit(hint, value, secret);
        boolean paragraph=label.equals("主提示词")||label.equals("二级提示词");edit.setSingleLine(!paragraph);edit.setTextSize(13);
        parent.addView(edit, new LinearLayout.LayoutParams(-1, Ui.dp(this, paragraph ? 150 : 48)));
        Ui.space(this, parent, 10);
        return edit;
    }

    private EditText edit(String hint, String value, boolean secret) {
        EditText edit = new EditText(this);
        edit.setId(View.generateViewId());
        edit.setText(value == null ? "" : value);
        edit.setHint(hint);
        edit.setTextSize(15);
        edit.setTextColor(Ui.INK);
        edit.setHintTextColor(Ui.MUTED);
        edit.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        edit.setBackground(Ui.surface(this, Color.WHITE, 14));
        edit.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return edit;
    }

    @Override protected void onResume() {
        super.onResume();
        if (resident == null) return;
        resident.setChecked(TermLensPrefs.resident(this));
    }

    @Override protected void onPause() { if(!switchingScene)saveValues(); super.onPause(); }

    private void saveValues() {
        if (resident == null) return;
        TermLensPrefs.put(this, TermLensPrefs.RESIDENT, resident.isChecked());
        int corner = buttonPosition.getCheckedRadioButtonId();
        TermLensPrefs.put(this, TermLensPrefs.BUTTON_POSITION, corner == 1004 ? "custom" : corner == 1002 ? "right" : corner == 1003 ? "left" : "auto");
        TermLensPrefs.put(this, TermLensPrefs.BUTTON_LOCKED, positionLocked.isChecked());
        TermLensPrefs.put(this, TermLensPrefs.HIDE_ON_KEYBOARD, hideOnKeyboard.isChecked());
        TermLensPrefs.put(this, TermLensPrefs.BUTTON_TRANSLUCENT, translucent.isChecked());
        TermLensPrefs.put(this, TermLensPrefs.BASE_URL, baseUrl.getText().toString().trim());
        TermLensPrefs.put(this, TermLensPrefs.MODEL, model.getText().toString().trim());
        TermLensPrefs.put(this, TermLensPrefs.API_KEY, apiKey.getText().toString().trim());
        TermLensPrefs.put(this,"speech_voice",speechVoice.getText().toString().trim());TermLensPrefs.put(this,"speech_rate",speechRate.getText().toString().trim());
        TermLensPrefs.put(this, TermLensPrefs.PRIMARY_NAME, primaryName.getText().toString().trim());
        TermLensPrefs.put(this, TermLensPrefs.PRIMARY_PROMPT, primaryPrompt.getText().toString().trim());
        TermLensPrefs.put(this, TermLensPrefs.SECONDARY_NAME, secondaryName.getText().toString().trim());
        TermLensPrefs.put(this, TermLensPrefs.SECONDARY_PROMPT, secondaryPrompt.getText().toString().trim());
        SelectionOverlayService.refreshSettings();
        SceneProfiles.updateCurrent(this);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == PICK_EXPORT) writeExport(uri);
        else if (requestCode == PICK_IMPORT) readImport(uri);
    }

    private void writeExport(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new Exception("无法打开导出文件");
            output.write(TermLensPrefs.exportPrompts(this).toString(2).getBytes(StandardCharsets.UTF_8));
            toast("提示词已导出");
        } catch (Exception e) { toast("导出失败：" + e.getMessage()); }
    }

    private void readImport(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("无法打开导入文件");
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
            if (!TermLensPrefs.importPrompts(this, new JSONObject(text.toString()))) throw new Exception("格式不受支持");
            primaryName.setText(TermLensPrefs.primaryName(this));
            primaryPrompt.setText(TermLensPrefs.primaryPrompt(this));
            secondaryName.setText(TermLensPrefs.secondaryName(this));
            secondaryPrompt.setText(TermLensPrefs.secondaryPrompt(this));
            toast("提示词已导入");
        } catch (Exception e) { toast("导入失败：" + e.getMessage()); }
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
}
