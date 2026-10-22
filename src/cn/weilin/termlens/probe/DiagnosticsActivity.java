package cn.weilin.termlens.probe;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Read-only package/selection diagnostics. Never adds a custom Explain menu item. */
public final class DiagnosticsActivity extends Activity {
    private TextView registration;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.CANVAS);
        LinearLayout body = Ui.column(this);
        int p = Ui.dp(this, 22);
        body.setPadding(p, p, p, p);
        body.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(p + insets.getSystemWindowInsetLeft(), p + insets.getSystemWindowInsetTop(),
                p + insets.getSystemWindowInsetRight(), p + insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.addView(body);
        setContentView(scroll);
        body.requestApplyInsets();
        body.addView(Ui.title(this, "Explain 入口诊断", 25));
        Ui.space(this, body, 8);
        body.addView(Ui.text(this, "原生划词入口 · 无需无障碍权限", 14, Ui.MUTED));
        Ui.space(this, body, 20);
        registration = Ui.text(this, "", 14, Ui.INK);
        registration.setTextIsSelectable(true);
        registration.setPadding(p, p, p, p);
        registration.setBackground(Ui.surface(this, Color.WHITE, 16));
        body.addView(registration);
        Ui.space(this, body, 14);
        Button refresh = Ui.button(this, "重新检查注册");
        refresh.setOnClickListener(view -> checkRegistration());
        body.addView(refresh, new LinearLayout.LayoutParams(-1, Ui.dp(this, 50)));
        Ui.space(this, body, 12);
        Button systemEntry = Ui.button(this, "测试系统调用入口");
        systemEntry.setOnClickListener(view -> {
            Intent intent = processIntent();
            intent.putExtra(Intent.EXTRA_PROCESS_TEXT, "limitation");
            intent.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
            try {
                startActivity(Intent.createChooser(intent, "选择文字处理工具"));
            } catch (ActivityNotFoundException | SecurityException error) {
                registration.append("\n系统调用失败：" + error.getClass().getSimpleName());
            }
        });
        body.addView(systemEntry, new LinearLayout.LayoutParams(-1, Ui.dp(this, 50)));
        Ui.space(this, body, 10);
        body.addView(Ui.text(this,
            "这个按钮测试系统能否调用入口，不等于外部 App 的选词菜单已经兼容。", 13, Ui.MUTED));
        Ui.space(this, body, 24);
        body.addView(Ui.title(this, "普通正文选词", 19));
        Ui.space(this, body, 8);
        TextView readMenu = Ui.text(this, "尚未观察到选词菜单。", 13, Ui.MUTED);
        TextView read = Ui.text(this, "A small limitation can have a significant impact.", 18, Ui.INK);
        read.setTextIsSelectable(true);
        read.setPadding(p, p, p, p);
        read.setBackground(Ui.surface(this, Color.WHITE, 16));
        observeMenu(read, readMenu);
        body.addView(read);
        Ui.space(this, body, 8);
        body.addView(readMenu);
        Ui.space(this, body, 24);
        body.addView(Ui.title(this, "可编辑输入框选词", 19));
        Ui.space(this, body, 8);
        TextView editMenu = Ui.text(this, "尚未观察到选词菜单。", 13, Ui.MUTED);
        EditText edit = new EditText(this);
        edit.setId(android.view.View.generateViewId());
        edit.setText("Reading the news helps us understand different perspectives.");
        edit.setTextSize(18);
        edit.setTextColor(Ui.INK);
        edit.setPadding(p, p, p, p);
        edit.setBackground(Ui.surface(this, Color.WHITE, 16));
        edit.setSelectAllOnFocus(false);
        observeMenu(edit, editMenu);
        body.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        Ui.space(this, body, 8);
        body.addView(editMenu);
        Ui.space(this, body, 22);
        body.addView(Ui.text(this,
            "分别长按上面两处英文，再打开“更多”。本页只观察原生菜单，不手动塞入 Explain。\n\n"
            + "请反馈：系统查询结果、普通正文菜单、输入框菜单，以及系统调用是否能打开 Explain。"
            + "这些诊断结果只显示在页面中，不上传、不保存。", 14, Ui.MUTED));
        checkRegistration();
    }

    private Intent processIntent() {
        return new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain");
    }

    private void checkRegistration() {
        PackageManager pm = getPackageManager();
        StringBuilder report = new StringBuilder();
        report.append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append("\nAndroid ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT)
            .append("\n\n");
        try {
            ActivityInfo info = pm.getActivityInfo(new ComponentName(this, ExplainActivity.class), 0);
            report.append("Explain 页面：已安装\n导出：").append(info.exported ? "是" : "否")
                .append("\n启用：").append(info.enabled && info.applicationInfo.enabled ? "是" : "否")
                .append("\n调用所需权限：").append(info.permission == null ? "无" : info.permission).append("\n");
            List<ResolveInfo> matches = pm.queryIntentActivities(processIntent(), PackageManager.MATCH_DEFAULT_ONLY);
            boolean own = false;
            for (ResolveInfo match : matches) {
                if (getPackageName().equals(match.activityInfo.packageName)
                    && ExplainActivity.class.getName().equals(match.activityInfo.name)) own = true;
            }
            report.append("系统查询能找到 Explain：").append(own ? "是" : "否")
                .append("\n可发现的文字处理入口：").append(matches.size());
            for (ResolveInfo match : matches) {
                report.append("\n• ").append(match.loadLabel(pm)).append(" — ")
                    .append(match.activityInfo.packageName);
            }
        } catch (PackageManager.NameNotFoundException | SecurityException error) {
            report.append("检查失败：").append(error.getClass().getSimpleName());
        }
        registration.setText(report.toString());
    }

    private void observeMenu(TextView source, TextView output) {
        source.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            private void snapshot(Menu menu) {
                // Read after Android has populated its standard actions. No menu mutation.
                source.post(() -> {
                    StringBuilder titles = new StringBuilder("观察到的原生菜单：");
                    boolean own = false;
                    for (int i = 0; i < menu.size(); i++) {
                        MenuItem item = menu.getItem(i);
                        if (i > 0) titles.append("、");
                        titles.append(item.getTitle());
                        Intent intent = item.getIntent();
                        if (intent != null && intent.getComponent() != null
                            && getPackageName().equals(intent.getComponent().getPackageName())
                            && ExplainActivity.class.getName().equals(intent.getComponent().getClassName())) own = true;
                        if (item.getTitle() != null && "Explain".contentEquals(item.getTitle())) own = true;
                    }
                    titles.append("\n包含 Explain：").append(own ? "是" : "否");
                    output.setText(titles.toString());
                });
            }
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) { snapshot(menu); return true; }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { snapshot(menu); return false; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }
            @Override public void onDestroyActionMode(ActionMode mode) { }
        });
    }
}
