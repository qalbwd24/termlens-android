package cn.weilin.termlens.probe;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;

/** Quiet setup hub; everyday reading stays in the source app and Explain. */
public final class MainActivity extends Activity {
    public void onCreate(Bundle state){
        super.onCreate(state);TermLensPrefs.enableDirectDefaults(this);SpeechOutput.get(this).maintain();
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Ui.CANVAS);LinearLayout body=Ui.column(this);int p=Ui.dp(this,18);
        body.setPadding(p,p,p,p);body.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(p,i.getSystemWindowInsetTop()+p,p,i.getSystemWindowInsetBottom()+p);return i;});scroll.addView(body);setContentView(scroll);body.requestApplyInsets();
        body.addView(Ui.title(this,"TermLens",25));Ui.space(this,body,5);body.addView(Ui.text(this,"在原页面阅读，随时划词解释",13,Ui.MUTED));Ui.space(this,body,22);
        LinearLayout card=Ui.column(this);int pad=Ui.dp(this,14);card.setPadding(pad,pad,pad,pad);card.setBackground(Ui.surface(this,android.graphics.Color.WHITE,12));
        card.addView(Ui.text(this,"选中文字 → 点 ✦ → 返回继续阅读",14,Ui.INK));Ui.space(this,card,9);card.addView(Ui.text(this,"未划词时点 ✦ 可输入英文；长按可拖动。",11,Ui.MUTED));body.addView(card);Ui.space(this,body,16);
        row(body,"输入英文","无需划词，打开输入栏后回车翻译",()->startActivity(new Intent(this,ExplainActivity.class).putExtra(ExplainActivity.MANUAL_INPUT,true)));
        boolean connected=SelectionOverlayService.status().startsWith("服务：已连接");
        Button enable=Ui.button(this,connected?"管理划词按钮 · 已开启":"开启划词按钮");enable.setTextSize(14);enable.setOnClickListener(v->startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));body.addView(enable,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));
        Ui.space(this,body,20);row(body,"设置","按钮位置、模型、声音与阅读场景",()->startActivity(new Intent(this,SettingsActivity.class)));
        row(body,"历史与收藏","查询记录、选词与含音频的完整备份",()->startActivity(new Intent(this,HistoryActivity.class)));
        Ui.space(this,body,16);body.addView(Ui.text(this,"检查与帮助",12,Ui.MUTED));Ui.space(this,body,8);
        row(body,"响应速度检测","查看首段文字和完整回答的等待时间",()->startActivity(new Intent(this,PerformanceActivity.class)));
        row(body,"文字处理入口","检查原生划词菜单是否可用",()->startActivity(new Intent(this,DiagnosticsActivity.class)));
        Ui.space(this,body,18);body.addView(Ui.text(this,"0.5.6 · 内置 Edge 语音 · 数据保存在本机",10,Ui.MUTED));
    }
    private void row(LinearLayout parent,String label,String detail,Runnable action){
        LinearLayout item=Ui.column(this);int p=Ui.dp(this,12);item.setPadding(p,p,p,p);item.setBackground(Ui.surface(this,android.graphics.Color.WHITE,10));
        item.addView(Ui.text(this,label+"  ›",15,Ui.INK));Ui.space(this,item,5);item.addView(Ui.text(this,detail,11,Ui.MUTED));item.setOnClickListener(v->action.run());item.setMinimumHeight(Ui.dp(this,58));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=Ui.dp(this,9);parent.addView(item,lp);
    }
}
