package cn.weilin.termlens.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class OverlayTestActivity extends Activity {
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.CANVAS);
        LinearLayout body = Ui.column(this);
        int pad = Ui.dp(this, 24);
        body.setPadding(pad, pad, pad, pad);
        body.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(pad, pad + insets.getSystemWindowInsetTop(), pad,
                pad + insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.addView(body);
        setContentView(scroll);
        body.requestApplyInsets();
        body.addView(Ui.title(this, "悬浮按钮测试", 25));
        Ui.space(this, body, 10);
        body.addView(Ui.text(this, "开启服务后，长按下面的英文。等 Explain 小按钮出现，点击查看原文。", 15, Ui.INK));
        Ui.space(this, body, 12);
        Button enable = Ui.button(this, "打开无障碍设置");
        enable.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        body.addView(enable, new LinearLayout.LayoutParams(-1, Ui.dp(this, 50)));
        Ui.space(this, body, 10);
        status = Ui.text(this, "", 13, Ui.MUTED);
        body.addView(status);
        Ui.space(this, body, 18);
        TextView sample = Ui.text(this, "A small limitation can have a significant impact.\n\nReading the news helps us understand different perspectives.", 19, Ui.INK);
        sample.setTextIsSelectable(true);
        sample.setPadding(pad, pad, pad, pad);
        sample.setBackground(Ui.surface(this, android.graphics.Color.WHITE, 16));
        body.addView(sample);
        Ui.space(this, body, 18);
        Button refresh = Ui.button(this, "刷新测试状态");
        refresh.setOnClickListener(v -> updateStatus());
        body.addView(refresh);
        Ui.space(this, body, 12);
        Button stop = Ui.button(this, "停止划词按钮服务");
        stop.setOnClickListener(v -> {
            SelectionOverlayService.stopProbe();
            updateStatus();
        });
        body.addView(stop);
        Ui.space(this, body, 16);
        body.addView(Ui.text(this, "本次测试 TermLens、Via、X、YouTube 和 ChatGPT。不联网，不截图，不读取剪贴板，不保存选中文字。按钮在有效选词后出现，取消选区、滚动、离开页面或 12 秒后关闭。", 13, Ui.MUTED));
    }
    @Override protected void onResume() { super.onResume(); updateStatus(); }
    private void updateStatus() {
        if (status != null) status.setText(SelectionOverlayService.status());
    }
}
