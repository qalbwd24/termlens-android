package cn.weilin.termlens.probe;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** A reading list with separate search, filters, and infrequent backup actions. */
public final class HistoryActivity extends Activity {
    private static final int EXPORT=71,IMPORT=72;
    private final Handler main=new Handler();
    private EditText search;
    private TextView count;
    private ListView list;
    private JSONArray items=new JSONArray();
    private int page;
    private final Button[] tabs=new Button[3];
    private boolean exportFavorites;
    private final Runnable searchChanged=()->refresh();
    private final BaseAdapter adapter=new BaseAdapter(){
        public int getCount(){return items.length();}public Object getItem(int at){return items.optJSONObject(at);}public long getItemId(int at){return at;}
        public View getView(int at,View old,android.view.ViewGroup parent){
            LinearLayout card;TextView title,preview,detail;
            if(old instanceof LinearLayout){card=(LinearLayout)old;title=(TextView)card.getChildAt(0);preview=(TextView)card.getChildAt(1);detail=(TextView)card.getChildAt(2);}
            else{card=Ui.column(HistoryActivity.this);int p=Ui.dp(HistoryActivity.this,12);card.setPadding(p,p,p,p);card.setBackground(Ui.surface(HistoryActivity.this,android.graphics.Color.WHITE,10));
                title=Ui.text(HistoryActivity.this,"",15,Ui.INK);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);card.addView(title);
                preview=Ui.text(HistoryActivity.this,"",12,Ui.MUTED);preview.setMaxLines(2);preview.setEllipsize(TextUtils.TruncateAt.END);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.topMargin=Ui.dp(HistoryActivity.this,5);card.addView(preview,pp);
                detail=Ui.text(HistoryActivity.this,"",10,Ui.MUTED);LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,-2);dp.topMargin=Ui.dp(HistoryActivity.this,7);card.addView(detail,dp);}
            JSONObject item=items.optJSONObject(at);title.setText(("1".equals(item.optString("saved"))?"★ ":"")+item.optString("text"));
            String raw=page==2?item.optString("scene_name"):"ask".equals(item.optString("kind"))?item.optString("answer"):LookupInput.parts(item.optString("answer"))[0];if(raw.isEmpty()&&page!=2)raw=item.optString("answer");
            preview.setText(SpeechOutput.plain(raw));preview.setVisibility(raw.isEmpty()?View.GONE:View.VISIBLE);
            long date=AppData.parseDate(item.optString("created"));String time=date<0?"":new java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.getDefault()).format(new java.util.Date(date));
            String status=page==2?"选词记录":"complete".equals(item.optString("status"))?"已缓存":"cancelled".equals(item.optString("status"))?"已取消":"未完成";
            detail.setText(time+"  ·  "+status);return card;
        }
    };
    public void onCreate(Bundle state){
        super.onCreate(state);if(state!=null){page=state.getInt("page",0);exportFavorites=state.getBoolean("export_favorites",false);}
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.CANVAS);int p=Ui.dp(this,14);root.setPadding(p,0,p,0);
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(p,i.getSystemWindowInsetTop(),p,i.getSystemWindowInsetBottom());return i;});setContentView(root);root.requestApplyInsets();
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        Button back=Ui.quietButton(this,"‹");back.setTextSize(26);back.setContentDescription("返回");back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(Ui.dp(this,40),Ui.dp(this,48)));
        header.addView(Ui.title(this,"历史与收藏",19),new LinearLayout.LayoutParams(0,-2,1));Button backup=Ui.quietButton(this,"备份");backup.setOnClickListener(v->backupMenu());header.addView(backup,new LinearLayout.LayoutParams(Ui.dp(this,56),Ui.dp(this,44)));root.addView(header);
        Ui.space(this,root,8);LinearLayout filters=new LinearLayout(this);String[] labels={"历史","收藏","选词"};
        for(int i=0;i<3;i++){final int selected=i;tabs[i]=Ui.quietButton(this,labels[i]);tabs[i].setOnClickListener(v->{page=selected;refresh();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,Ui.dp(this,42),1);if(i>0)lp.leftMargin=Ui.dp(this,8);filters.addView(tabs[i],lp);}root.addView(filters);
        Ui.space(this,root,12);search=new EditText(this);search.setSingleLine(true);search.setTextSize(13);search.setHint("搜索文字或解释");search.setTextColor(Ui.INK);search.setPadding(Ui.dp(this,12),0,Ui.dp(this,12),0);search.setBackground(Ui.surface(this,android.graphics.Color.WHITE,9));root.addView(search,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int after){}public void onTextChanged(CharSequence s,int a,int before,int count){main.removeCallbacks(searchChanged);main.postDelayed(searchChanged,180);}public void afterTextChanged(Editable e){}});
        Ui.space(this,root,9);count=Ui.text(this,"",11,Ui.MUTED);root.addView(count);Ui.space(this,root,8);
        list=new ListView(this);list.setAdapter(adapter);list.setDivider(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));list.setDividerHeight(Ui.dp(this,8));list.setClipToPadding(false);list.setPadding(0,0,0,Ui.dp(this,12));root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        TextView empty=Ui.text(this,"还没有记录",14,Ui.MUTED);empty.setGravity(Gravity.CENTER);root.addView(empty,new LinearLayout.LayoutParams(-1,0,1));list.setEmptyView(empty);
        list.setOnItemClickListener((parent,view,at,id)->open(items.optJSONObject(at)));
        TextView note=Ui.text(this,"解释与音频 15 天 · 选词与收藏长期保存",10,Ui.MUTED);note.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,8));root.addView(note);
    }
    protected void onResume(){super.onResume();if(search!=null)refresh();}
    private void refresh(){
        String query=search.getText().toString();if(page<2)items=AppData.get(this).list(page==1,query);
        else {JSONArray all=AppData.get(this).export(false).optJSONArray("selection_events");items=new JSONArray();String match=AppData.key(query);
            for(int i=all.length()-1;i>=0&&items.length()<500;i--){JSONObject item=all.optJSONObject(i);if(AppData.key(item.optString("text")).contains(match))items.put(item);}}
        for(int i=0;i<tabs.length;i++){tabs[i].setTextColor(i==page?android.graphics.Color.WHITE:Ui.ACCENT);tabs[i].setBackground(Ui.surface(this,i==page?Ui.ACCENT:android.graphics.Color.WHITE,9));}
        count.setText(items.length()+" 条"+(items.length()==500?" · 显示最近 500 条":""));adapter.notifyDataSetChanged();
    }
    private void open(JSONObject item){
        JSONObject record=page==2?AppData.get(this).find(item.optString("id")):item;
        if(record!=null){startActivity(new Intent(this,"ask".equals(record.optString("kind"))?AskActivity.class:ExplainActivity.class).putExtra("query_id",record.optString("id")));return;}
        new AlertDialog.Builder(this).setMessage(item.optString("text")+"\n\n解释缓存已过期，选词仍保留。").setPositiveButton("重新解释",(d,w)->startActivity(new Intent(this,ExplainActivity.class).putExtra(Intent.EXTRA_PROCESS_TEXT,item.optString("text")))).setNegativeButton("返回",null).show();
    }
    private void backupMenu(){new AlertDialog.Builder(this).setTitle("备份与导入").setItems(new String[]{"导出历史 JSON","导出收藏 JSON","导入 JSON","导出完整备份（含音频）","导入完整备份"},(d,which)->{
        exportFavorites=which==1;
        if(which<=1)startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE,which==1?"Termlens-favorites.json":"Termlens-history.json"),EXPORT);
        else if(which==2)startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"),IMPORT);
        else if(which==3)startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"Termlens-data.zip"),73);
        else startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip"),74);
    }).show();}
    protected void onSaveInstanceState(Bundle state){state.putInt("page",page);state.putBoolean("export_favorites",exportFavorites);super.onSaveInstanceState(state);}
    protected void onDestroy(){main.removeCallbacks(searchChanged);super.onDestroy();}
    protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();final boolean onlySaved=exportFavorites;
        io.execute(()->{String message;
            try {
                if(code==EXPORT){try(OutputStream output=getContentResolver().openOutputStream(data.getData())){output.write(AppData.get(this).export(onlySaved).toString().getBytes(StandardCharsets.UTF_8));}message="JSON 已导出，不含密钥";}
                else if(code==IMPORT){try(InputStream input=getContentResolver().openInputStream(data.getData());ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>64*1024*1024)throw new Exception("文件超过 64 MB");}message=AppData.get(this).importData(new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)));}}
                else if(code==73){try(OutputStream output=getContentResolver().openOutputStream(data.getData())){DataArchive.write(this,output);}message="完整备份已导出，包含音频，不含密钥";}
                else {try(InputStream input=getContentResolver().openInputStream(data.getData())){message=DataArchive.read(this,input);}}
            }catch(Exception e){message="操作失败："+e.getMessage();}
            final String display=message;runOnUiThread(()->{if(!isFinishing()){new AlertDialog.Builder(this).setMessage(display).setPositiveButton("确定",null).show();refresh();}});io.shutdown();
        });
    }
}
