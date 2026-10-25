package cn.weilin.termlens.probe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** A temporary learning-question window. No dictionary history or speech prefetch side effects. */
public final class AskActivity extends Activity {
    static final String CONTEXT = "termlens_ask_context";
    private static final int MAX_HISTORY_CHARS = 8000;
    private LinearLayout root;
    private EditText question;
    private TextView answerView, status;
    private Button send;
    private ImageButton copy, listen, edit, favorite;
    private ActionMode selectionMode;
    private final BlankContentTap blankTap=new BlankContentTap();
    private JSONObject favoriteRecord;
    private String selection = "", pressedSelection = "";
    private boolean rendering;
    private JSONObject rootData = new JSONObject();
    private String profileJson = "{}";
    private int navigationDepth;
    private boolean attachContext;
    private ScrollView answerScroll;
    private String context = "", answer = "", error = "", submitted = "";
    private JSONArray history = new JSONArray();
    private LlmClient.Request pending;
    private boolean busy, destroyed, keyboardPending = true;
    private int generation;
    private android.window.OnBackInvokedCallback backCallback;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        navigationDepth = StudyNavigation.depth(state == null ? getIntent().getIntExtra(StudyNavigation.DEPTH,0) : state.getInt(StudyNavigation.DEPTH,0));
        String inheritedRoot = state == null ? getIntent().getStringExtra(StudyNavigation.ROOT) : state.getString(StudyNavigation.ROOT);
        rootData = AppData.object(inheritedRoot == null ? "{}" : inheritedRoot);
        String inheritedProfile = state == null ? getIntent().getStringExtra(StudyNavigation.PROFILE) : state.getString(StudyNavigation.PROFILE);
        profileJson = inheritedProfile == null ? SceneProfiles.snapshot(this).toString() : inheritedProfile;
        String incoming = getIntent().getStringExtra(CONTEXT);
        context = state == null ? (incoming == null ? "" : incoming) : state.getString("context", "");
        if(context.length()>AskPrompt.CONTEXT_LIMIT)context=LookupInput.clip(context,AskPrompt.CONTEXT_LIMIT,null);
        String draft=state==null?"":state.getString("question","");
        boolean attached=state==null||state.getBoolean("attached",true);
        if(state!=null){
            answer=state.getString("answer","");error=state.getString("error","");submitted=state.getString("submitted","");
            keyboardPending=state.getBoolean("editing",false);
            if(state.getBoolean("busy",false))error="请求已中断，点击发送可重试。";
            try{history=new JSONArray(state.getString("history","[]"));}catch(Exception ignored){history=new JSONArray();}
            pruneHistory();
        }
        if(state!=null&&state.containsKey("favorite_record"))favoriteRecord=AppData.object(state.getString("favorite_record"));
        if(state==null&&getIntent().hasExtra("query_id")){
            JSONObject record=AppData.get(this).find(getIntent().getStringExtra("query_id"));
            if(record!=null&&"ask".equals(record.optString("kind"))){
                favoriteRecord=record;submitted=record.optString("text");draft=submitted;answer=record.optString("answer");
                JSONObject snapshot=AskFavorite.snapshot(record);context=snapshot.optString("context");attached=snapshot.optBoolean("attached",false);
                rootData=snapshot.optJSONObject("root");if(rootData==null)rootData=new JSONObject();
                JSONObject profile=snapshot.optJSONObject("profile");if(profile!=null&&!profile.optString("primaryText").isEmpty()&&!profile.optString("secondaryText").isEmpty())profileJson=profile.toString();
                history=snapshot.optJSONArray("history");if(history==null)history=new JSONArray();pruneHistory();keyboardPending=false;
            }
        }
        buildPage(draft,attached); renderAnswer(); updateActions();
        if(android.os.Build.VERSION.SDK_INT>=33){backCallback=this::goBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);}
        if(state!=null)answerScroll.post(()->answerScroll.scrollTo(0,state.getInt("scroll",0)));
        if(keyboardPending)focusQuestion();
    }
    private void buildPage(String draft,boolean attached){
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        getWindow().setStatusBarColor(Ui.CANVAS);getWindow().setNavigationBarColor(Ui.CANVAS);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root=Ui.column(this);root.setBackgroundColor(Ui.CANVAS);root.setFocusableInTouchMode(true);final int p=Ui.dp(this,12);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());view.setPadding(p+safe.left,safe.top,p+safe.right,Ui.dp(this,4)+safe.bottom);}
            else view.setPadding(p+insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),p+insets.getSystemWindowInsetRight(),Ui.dp(this,4)+insets.getSystemWindowInsetBottom());
            return insets;
        });setContentView(root);root.requestApplyInsets();
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=Ui.title(this,"Termlens",18);brand.setSingleLine(true);brand.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView levelLabel=Ui.text(this,"疑惑 · "+(navigationDepth+1),12,Ui.MUTED);levelLabel.setSingleLine(true);
        levelLabel.setContentDescription("当前第 "+(navigationDepth+1)+" 层询问");header.addView(levelLabel);
        ImageButton close=new ImageButton(this);close.setImageResource(R.drawable.ic_termlens_close);close.setContentDescription("关闭询问，返回原文");close.setColorFilter(Ui.ACCENT);close.setBackgroundColor(Color.TRANSPARENT);close.setOnClickListener(v->goBack());
        header.addView(close,new LinearLayout.LayoutParams(Ui.dp(this,40),Ui.dp(this,40)));root.addView(header,new LinearLayout.LayoutParams(-1,Ui.dp(this,46)));
        LinearLayout card=Ui.column(this);card.setPadding(Ui.dp(this,10),Ui.dp(this,8),Ui.dp(this,10),Ui.dp(this,8));card.setBackground(Ui.surface(this,Color.WHITE,12));
        question=new EditText(this);question.setId(0x544c11);question.setTextSize(14);question.setTextColor(Ui.INK);question.setHint("Ask...");question.setContentDescription("询问输入栏");
        question.setSingleLine(true);question.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        question.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        if(android.os.Build.VERSION.SDK_INT>=24)question.setImeHintLocales(new android.os.LocaleList(Locale.US));
        question.setFilters(new InputFilter[]{new InputFilter.LengthFilter(AskPrompt.QUESTION_LIMIT)});question.setText(draft);
        question.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void onTextChanged(CharSequence s,int a,int b,int c){selection="";pressedSelection="";updateActions();}public void afterTextChanged(Editable e){}});
        question.setOnFocusChangeListener((v,focused)->{if(focused)clearAnswerSelection();});
        question.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN)clearAnswerSelection();return false;});
        question.setOnEditorActionListener((v,action,event)->{boolean enter=event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER;
            if(enter||action==EditorInfo.IME_ACTION_SEARCH||action==EditorInfo.IME_ACTION_GO||action==EditorInfo.IME_ACTION_DONE){if(!enter||event.getAction()==KeyEvent.ACTION_UP)submit();return true;}return false;});
        LinearLayout questionRow=new LinearLayout(this);questionRow.setGravity(Gravity.CENTER_VERTICAL);
        questionRow.addView(question,new LinearLayout.LayoutParams(0,-2,1));
        ImageButton readQuestion=icon(R.drawable.ic_termlens_speaker,"朗读问题");
        readQuestion.setOnClickListener(v->SpeechOutput.get(this).speakTemporary(question.getText().toString()));
        questionRow.addView(readQuestion,new LinearLayout.LayoutParams(Ui.dp(this,30),Ui.dp(this,30)));
        card.addView(questionRow);root.addView(card);
        attachContext=!context.isEmpty()&&attached;
        answerScroll=new ScrollView(this);LinearLayout content=Ui.column(this);content.setPadding(0,Ui.dp(this,4),0,Ui.dp(this,8));
        answerView=new AnswerText();answerView.setId(0x544c12);content.addView(answerView);
        status=Ui.text(this,"",12,Ui.MUTED);content.addView(status);answerScroll.addView(content);root.addView(answerScroll,new LinearLayout.LayoutParams(-1,0,1));
        View rule=new View(this);rule.setBackgroundColor(Color.rgb(220,226,221));root.addView(rule,new LinearLayout.LayoutParams(-1,Ui.dp(this,1)));
        LinearLayout footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER_VERTICAL);
        listen=icon(R.drawable.ic_termlens_speaker,"朗读选中内容或当前回答");listen.setOnClickListener(v->SpeechOutput.get(this).speakTemporary(selection.isEmpty()?answer:selection));
        copy=icon(R.drawable.ic_termlens_copy,"复制当前回答");copy.setOnClickListener(v->{if(!answer.isEmpty())((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Ask",answer));});
        edit=icon(R.drawable.ic_termlens_edit,"编辑问题");edit.setOnClickListener(v->{clearAnswerSelection();keyboardPending=true;focusQuestion();updateActions();});
        favorite=icon(R.drawable.ic_termlens_star,"收藏当前问答");
        favorite.setOnClickListener(v->toggleFavorite());
        send=Ui.button(this,"询问");send.setTextSize(12);send.setFocusable(false);
        send.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN)pressedSelection=selection;if(event.getActionMasked()==MotionEvent.ACTION_CANCEL)pressedSelection="";return false;});
        send.setOnClickListener(v->{String chosen=pressedSelection.isEmpty()?selection:pressedSelection;pressedSelection="";if(!chosen.trim().isEmpty())openTranslation(chosen);else submit();});
        BottomTools.add(footer,send,listen,favorite,copy,edit);root.addView(footer,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        boolean clear=blankTap.observe(event,answerScroll);boolean handled=super.dispatchTouchEvent(event);
        if(clear)clearAnswerSelection();return handled;
    }
    private void clearAnswerSelection(){
        if(selectionMode!=null)selectionMode.finish();
        selection="";pressedSelection="";rendering=true;
        if(answerView!=null&&answerView.getText() instanceof android.text.Spannable)android.text.Selection.removeSelection((android.text.Spannable)answerView.getText());
        rendering=false;updateActions();
    }
    private ImageButton icon(int drawable,String description){ImageButton button=new ImageButton(this);button.setImageResource(drawable);button.setContentDescription(description);
        button.setColorFilter(Ui.ACCENT);button.setBackgroundColor(Color.TRANSPARENT);button.setFocusable(false);button.setPadding(Ui.dp(this,9),Ui.dp(this,9),Ui.dp(this,9),Ui.dp(this,9));button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);return button;}
    private final class AnswerText extends TextView {
        AnswerText(){super(AskActivity.this);setTextSize(14);setTextColor(Ui.INK);setIncludeFontPadding(false);setLineSpacing(0,1.12f);setTextIsSelectable(true);
            setCustomSelectionActionModeCallback(new ActionMode.Callback(){
                public boolean onCreateActionMode(ActionMode mode,Menu menu){selectionMode=mode;prepare(menu);return true;}
                public boolean onPrepareActionMode(ActionMode mode,Menu menu){prepare(menu);return true;}
                private void prepare(Menu menu){for(int i=menu.size()-1;i>=0;i--){Intent intent=menu.getItem(i).getIntent();if(intent!=null&&Intent.ACTION_PROCESS_TEXT.equals(intent.getAction()))menu.removeItem(menu.getItem(i).getItemId());}
                    if(menu.findItem(0x544c21)==null)menu.add(0,0x544c21,0,"Termlens").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
}
                public boolean onActionItemClicked(ActionMode mode,MenuItem item){if(item.getItemId()!=0x544c21)return false;
                    String selected=selectedText();mode.finish();openTranslation(selected);return true;}
                public void onDestroyActionMode(ActionMode mode){if(selectionMode==mode)selectionMode=null;}
            });}
        private String selectedText(){int a=Math.min(getSelectionStart(),getSelectionEnd()),b=Math.max(getSelectionStart(),getSelectionEnd());return a>=0&&b>a?getText().subSequence(a,b).toString():selection;}
        @Override protected void onSelectionChanged(int start,int end){super.onSelectionChanged(start,end);if(rendering||send==null)return;
            int a=Math.min(start,end),b=Math.max(start,end);selection=a>=0&&b>a&&b<=getText().length()?getText().subSequence(a,b).toString():"";updateActions();}
    }
    private boolean canNavigate(){if(StudyNavigation.canOpen(navigationDepth))return true;Toast.makeText(this,"层级较多，请先返回几层后继续",Toast.LENGTH_SHORT).show();return false;}
    private JSONObject navigationRoot(){
        JSONObject value=new JSONObject();boolean attached=attachContext;
        AppData.put(value,"rootOriginal",LookupInput.clip(attached?rootData.optString("rootOriginal",submitted):submitted,3000,null));
        AppData.put(value,"rootAnswer",LookupInput.clip(attached?rootData.optString("rootAnswer",answer):answer,5000,null));
        AppData.put(value,"rootSourceId",attached?rootData.optString("rootSourceId"):"");return value;
    }
    private void pauseForChild(){if(busy){cancel();error="回答已暂停，可再次发送继续。";renderAnswer();updateActions();}hideKeyboard();SpeechOutput.get(this).stop();}
    private void openTranslation(String selected){
        if(busy||selected==null||selected.trim().isEmpty()||!canNavigate())return;
        JSONObject lookup=navigationRoot();AppData.put(lookup,"parentQuery",LookupInput.clip(submitted,1000,selected));AppData.put(lookup,"parentAnswer",LookupInput.clip(answer,3000,selected));
        pauseForChild();
        startActivity(new Intent(this,ExplainActivity.class).setAction(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT,selected.trim()).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY,true)
            .putExtra(StudyNavigation.LOOKUP,lookup.toString()).putExtra(StudyNavigation.PROFILE,profileJson)
            .putExtra(StudyNavigation.DEPTH,StudyNavigation.child(navigationDepth)));
    }
    private JSONObject message(String role,String text){JSONObject item=new JSONObject();AppData.put(item,"role",role);AppData.put(item,"content",text);return item;}
    private void submit(){
        String text=question.getText().toString().trim();if(text.isEmpty()){question.setError("请输入问题或单词");return;}
        if(busy)return;
        cancel();SpeechOutput.get(this).stop();hideKeyboard();selection="";pressedSelection="";submitted=text;favoriteRecord=null;answer="";error="";busy=true;final int token=generation;
        final JSONObject sentSnapshot=favoriteSnapshot();
        JSONArray turns=new JSONArray();for(int i=0;i<history.length();i++)turns.put(history.optJSONObject(i));
        turns.put(message("user",AskPrompt.userMessage(attachContext?context:"",text)));
        renderAnswer();updateActions();answerScroll.scrollTo(0,0);
        pending=LlmClient.requestConversation(this,turns,AskPrompt.SYSTEM,true,partial->{
            if(destroyed||isFinishing()||generation!=token)return;answer=partial;renderAnswer();
        },(response,failure,timing)->{
            if(destroyed||isFinishing()||generation!=token)return;
            busy=false;answer=response;error=failure==null?"":failure;
            if(error.isEmpty()){history.put(message("user",text));history.put(message("assistant",answer));pruneHistory();AppData.put(sentSnapshot,"history",history);if(!answer.trim().isEmpty())favoriteRecord=AskFavorite.create(submitted,answer,sentSnapshot);}
            renderAnswer();updateActions();
        });
    }
    private JSONObject favoriteSnapshot(){
        JSONObject snapshot=new JSONObject();AppData.put(snapshot,"context",context);AppData.put(snapshot,"attached",attachContext);
        AppData.put(snapshot,"root",rootData);AppData.put(snapshot,"profile",AppData.object(profileJson));AppData.put(snapshot,"history",history);return snapshot;
    }
    private void toggleFavorite(){
        if(busy||favoriteRecord==null)return;AppData store=AppData.get(this);boolean saved=!store.saved(favoriteRecord);
        if(saved)store.persistAsk(favoriteRecord);store.setSaved(favoriteRecord,saved);updateActions();
        Toast.makeText(this,saved?"已收藏当前问答":"已取消收藏",Toast.LENGTH_SHORT).show();
    }
    private void pruneHistory(){
        while(history.length()>6||history.toString().length()>MAX_HISTORY_CHARS){
            JSONArray kept=new JSONArray();for(int i=2;i<history.length();i++)kept.put(history.optJSONObject(i));history=kept;
        }
    }
    private void renderAnswer(){
        if(answerView==null)return;
        CharSequence rendered=android.os.Build.VERSION.SDK_INT>=24?Html.fromHtml(Markdown.toHtml(answer),Html.FROM_HTML_MODE_COMPACT):Html.fromHtml(Markdown.toHtml(answer));
        int start=answerView.getSelectionStart(),end=answerView.getSelectionEnd();String selected=selection;
        rendering=true;answerView.setText(rendered);
        if(!selected.isEmpty()&&start>=0&&end>start&&end<=rendered.length()&&rendered.subSequence(start,end).toString().equals(selected)&&answerView.getText() instanceof android.text.Spannable)
            android.text.Selection.setSelection((android.text.Spannable)answerView.getText(),start,end);
        else selection="";
        rendering=false;
        status.setText(busy?"正在回答…":!error.isEmpty()?error:"");
        updateActions();
    }
    private void updateActions(){if(send==null||question==null)return;String text=question.getText().toString().trim();
        boolean selected=!selection.trim().isEmpty();send.setText(selected?"查询":"询问");send.setContentDescription(busy?"正在回答":selected?"查询选中内容":history.length()>0?"继续询问":"询问输入的问题");send.setEnabled(!busy&&(selected||!text.isEmpty()));send.setAlpha(send.isEnabled()?1f:0.4f);copy.setEnabled(!answer.isEmpty());listen.setEnabled(selected||!answer.isEmpty());
        favorite.setEnabled(!busy&&favoriteRecord!=null);favorite.setAlpha(favoriteRecord!=null&&AppData.get(this).saved(favoriteRecord)?1f:0.5f);}
    private void cancel(){generation++;if(pending!=null){pending.cancel();pending=null;}busy=false;}
    private void focusQuestion(){
        if(question==null||!keyboardPending)return;getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        question.requestFocus();question.setSelection(question.length());question.post(()->{if(destroyed||isFinishing()||!hasWindowFocus()||!keyboardPending)return;
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(question,InputMethodManager.SHOW_IMPLICIT);
            if(android.os.Build.VERSION.SDK_INT>=30&&question.getWindowInsetsController()!=null)question.getWindowInsetsController().show(android.view.WindowInsets.Type.ime());keyboardPending=false;});
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&keyboardPending)focusQuestion();}
    private boolean hideKeyboard(){boolean visible=false;if(root!=null){
        if(android.os.Build.VERSION.SDK_INT>=30&&root.getRootWindowInsets()!=null)visible=root.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime());
        else{android.graphics.Rect frame=new android.graphics.Rect();root.getWindowVisibleDisplayFrame(frame);visible=root.getRootView().getHeight()-frame.bottom>Ui.dp(this,150);}
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);}
        keyboardPending=false;getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);if(question!=null)question.clearFocus();return visible;}
    private void goBack(){if(hideKeyboard())return;finish();}
    @Override public void onBackPressed(){goBack();}
    @Override public void finish(){cancel();super.finish();}
    @Override protected void onSaveInstanceState(Bundle state){
        if(favoriteRecord!=null)state.putString("favorite_record",favoriteRecord.toString());
        state.putInt(StudyNavigation.DEPTH,navigationDepth);state.putString(StudyNavigation.ROOT,rootData.toString());state.putString(StudyNavigation.PROFILE,profileJson);
        state.putString("context",context);state.putString("question",question.getText().toString());state.putString("submitted",submitted);
        state.putString("answer",LookupInput.clip(answer,32000,null));state.putString("error",error);state.putString("history",history.toString());
        state.putBoolean("attached",attachContext);state.putBoolean("busy",busy);state.putBoolean("editing",question.hasFocus());state.putInt("scroll",answerScroll.getScrollY());super.onSaveInstanceState(state);
    }
    @Override protected void onPause(){SpeechOutput.get(this).stop();super.onPause();}
    @Override protected void onDestroy(){destroyed=true;cancel();if(android.os.Build.VERSION.SDK_INT>=33&&backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);super.onDestroy();}
}
