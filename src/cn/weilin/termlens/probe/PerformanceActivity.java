package cn.weilin.termlens.probe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;

/** A real two-request comparison; exposes timings only, not credentials or response bodies. */
public final class PerformanceActivity extends Activity {
    private TextView result;
    private LlmClient.Request pending;
    public void onCreate(Bundle state){
        super.onCreate(state);LinearLayout body=Ui.column(this);int p=Ui.dp(this,18);body.setPadding(p,p,p,p);body.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(p,i.getSystemWindowInsetTop(),p,i.getSystemWindowInsetBottom());return i;});setContentView(body);body.requestApplyInsets();
        body.addView(Ui.title(this,"响应速度检测",22));body.addView(Ui.text(this,"使用当前模型和提示词，分别发送一次相同的非流式和流式查询。正常消耗少量 API 用量。",14,Ui.MUTED));
        Button start=Ui.button(this,"开始检测");body.addView(start);result=Ui.text(this,"等待开始",14,Ui.INK);body.addView(result);
        start.setOnClickListener(v->{start.setEnabled(false);result.setText("正在测非流式请求…");run(false,()->run(true,()->start.setEnabled(true)));});
    }
    private void run(boolean stream,Runnable done){
        String system=DesktopPrompts.system(this,TermLensPrefs.primaryPrompt(this),false);
        pending=LlmClient.request(this,"documentation",system,stream,text->{if(!isFinishing())result.setText(result.getText().toString().replace("正在测流式请求…","流式文字已到达…"));},(answer,error,timing)->{
            if(isFinishing())return;
            String label=stream?"流式":"非流式";result.append("\n"+label+"：首段 "+timing.firstText+" ms，完整 "+timing.complete+" ms"+(error.isEmpty()?"":"\n"+error));
            if(!stream)result.append("\n正在测流式请求…");done.run();
        });
    }
    protected void onDestroy(){if(pending!=null)pending.cancel();super.onDestroy();}
}
