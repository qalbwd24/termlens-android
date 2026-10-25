package cn.weilin.termlens.probe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import org.json.JSONObject;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;

/** Fixed controls around a single reading viewport; selected-word lookups keep their parent. */
public final class ExplainActivity extends Activity {
    private static final int EDIT_CONNECTION = 51;
    private static final int EXPLAIN_SELECTION = 0x544c;
    private static final int MAX_LEVELS = StudyNavigation.MAX_DEPTH + 1;
    private final ArrayList<Level> levels = new ArrayList<>();
    private LinearLayout root, content;
    private ScrollView scroll;
    private TextView levelLabel;
    private ImageButton ask, listen, favorite, copy, edit;
    private Button lookup;
    private JSONObject profile;
    private JSONObject inheritedLookup;
    private int navigationDepth;
    private StudyText liveTranslation, liveExplanation;
    private TextView liveStatus;
    private View liveRule;
    private long requestStarted, firstDrawMs=-1, maxRenderMs;
    private LlmClient.Timing lastTiming;
    private LlmClient.Request pending;
    private String primarySystem = "", secondarySystem = "";
    private String selection = "", pressedSelection = "";
    private StudyText selectionView;
    private ActionMode selectionMode;
    private final BlankContentTap blankTap=new BlankContentTap();
    private boolean busy, rendering, destroyed;
    private int generation;
    private String lastRequestUser = "", lastRequestSystem = "";
    private LinearLayout toolbar;
    static final String MANUAL_INPUT = "termlens_manual_input";
    private boolean manualMode, keyboardPending;
    private String manualDraft = "";
    private EditText manualInput;
    private ImageButton close;
    private android.window.OnBackInvokedCallback backCallback;

    private static final class Level {
        final String original;
        String raw = "", error = "";
        JSONObject record;
        int scrollY;
        boolean sourceExpanded;
        Level(String original) { this.original = original; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        JSONObject historical = historical(getIntent());
        String input = historical == null ? readInput(getIntent()) : historical.optString("text");
        manualMode = state != null ? state.getBoolean("manual_mode", false)
            : historical == null && input.trim().isEmpty();
        manualDraft = state == null ? "" : state.getString("manual_draft", "");
        keyboardPending = manualMode && (state == null || state.getBoolean("manual_editing", true));
        snapshotPrompts();
        readNavigation(getIntent(), state);
        SpeechOutput.get(this).maintain();
        if (historical != null) restoreProfile(historical);
        if (state != null) {
            primarySystem = state.getString("primary_system", primarySystem);
            secondarySystem = state.getString("secondary_system", secondarySystem);
            int count = Math.min(MAX_LEVELS, state.getInt("level_count", 0));
            for (int i = 0; i < count; i++) {
                Level level = new Level(state.getString("original_" + i, input));
                level.raw = state.getString("raw_" + i, ""); level.error = state.getString("error_" + i, "");
                level.scrollY = state.getInt("scroll_" + i, 0); level.sourceExpanded = state.getBoolean("expanded_" + i);
                String id=state.getString("record_"+i,""); if(!id.isEmpty())level.record=AppData.get(this).find(id);
                levels.add(level);
            }
        }
        if (levels.isEmpty()) {
            Level level=new Level(input);levels.add(level);
            if(historical!=null){level.record=historical;level.raw=historical.optString("answer");level.error="complete".equals(historical.optString("status"))?"":historical.optString("error","此记录未完成，可重新查询");if(level.error.isEmpty()&&!"complete".equals(historical.optString("status")))level.error="此记录未完成，可重新查询";}
        }
        if(!levels.isEmpty()&&levels.get(0).record!=null)restoreProfile(levels.get(0).record);
        buildPage();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backCallback = this::previousLevel;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        render();
        Level level = current();
        if (!manualMode && !level.original.trim().isEmpty() && level.raw.isEmpty() && level.error.isEmpty()) requestCurrent();
        else if(level.record!=null)SpeechOutput.get(this).preload(level.record);
    }

    private void snapshotPrompts() {
        profile = SceneProfiles.snapshot(this);
        primarySystem = DesktopPrompts.system(this, TermLensPrefs.primaryPrompt(this), false);
        secondarySystem = DesktopPrompts.system(this, TermLensPrefs.secondaryPrompt(this), true);
    }
    private void readNavigation(Intent intent, Bundle state) {
        navigationDepth = StudyNavigation.depth(state == null ? intent.getIntExtra(StudyNavigation.DEPTH, 0) : state.getInt(StudyNavigation.DEPTH, 0));
        String lookup = state == null ? intent.getStringExtra(StudyNavigation.LOOKUP) : state.getString(StudyNavigation.LOOKUP);
        inheritedLookup = lookup == null || lookup.isEmpty() ? null : AppData.object(lookup);
        String savedProfile = state == null ? intent.getStringExtra(StudyNavigation.PROFILE) : state.getString(StudyNavigation.PROFILE);
        if (savedProfile != null) {
            JSONObject holder = new JSONObject(), saved = new JSONObject();
            AppData.put(saved, "promptProfile", AppData.object(savedProfile)); AppData.put(holder, "context", saved.toString()); restoreProfile(holder);
        }
    }
    private int studyDepth() { return navigationDepth + Math.max(0, levels.size() - 1); }
    private JSONObject rootContext() {
        JSONObject inherited = inheritedLookup;
        if (inherited == null && !levels.isEmpty() && levels.get(0).record != null)
            inherited = AppData.context(levels.get(0).record.optString("context")).optJSONObject("lookupContext");
        JSONObject value = new JSONObject(); Level first = levels.get(0);
        AppData.put(value,"rootOriginal", LookupInput.clip(inherited == null ? first.original : inherited.optString("rootOriginal",first.original),3000,null));
        AppData.put(value,"rootAnswer", LookupInput.clip(inherited == null ? first.raw : inherited.optString("rootAnswer",first.raw),5000,null));
        AppData.put(value,"rootSourceId", inherited == null ? first.record == null ? "" : first.record.optString("id") : inherited.optString("rootSourceId"));
        return value;
    }
    private JSONObject historical(Intent intent) {String id=intent.getStringExtra("query_id");return id==null?null:AppData.get(this).find(id);}
    private void restoreProfile(JSONObject record) {
        JSONObject saved=AppData.context(record.optString("context","{}")).optJSONObject("promptProfile");
        if(saved!=null&&!saved.optString("primaryText").isEmpty()&&!saved.optString("secondaryText").isEmpty()){
            profile=saved;primarySystem=DesktopPrompts.system(this,saved.optString("primaryText"),false);secondarySystem=DesktopPrompts.system(this,saved.optString("secondaryText"),true);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        JSONObject historical=historical(intent);String input = historical==null?readInput(intent):historical.optString("text");
        manualMode = historical == null && input.trim().isEmpty();
        manualDraft = ""; keyboardPending = manualMode;
        cancelRequest(); snapshotPrompts(); readNavigation(intent, null); levels.clear();Level level=new Level(input);levels.add(level);
        if(historical!=null){restoreProfile(historical);level.record=historical;level.raw=historical.optString("answer");level.error="complete".equals(historical.optString("status"))?"":historical.optString("error","此记录未完成");render();SpeechOutput.get(this).preload(level.record);}
        else {render();if(!input.trim().isEmpty())requestCurrent();else focusManualInput();}
    }

    private String readInput(Intent intent) {
        if (intent == null) return "";
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (text != null && !text.toString().trim().isEmpty()) return text.toString();
        String fallback = intent.getStringExtra("termlens_main_term"); return fallback == null ? "" : fallback;
    }
    private Level current() { return levels.get(levels.size() - 1); }

    private void buildPage() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        getWindow().setStatusBarColor(Ui.CANVAS); getWindow().setNavigationBarColor(Ui.CANVAS);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root = Ui.column(this); root.setBackgroundColor(Ui.CANVAS); root.setFocusableInTouchMode(true);
        int p = Ui.dp(this, 12);
        root.setPadding(p, p, p, p);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
                view.setPadding(p + safe.left, safe.top, p + safe.right, Ui.dp(this, 4) + safe.bottom);
            } else view.setPadding(p + insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                p + insets.getSystemWindowInsetRight(), Ui.dp(this, 4) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root); root.requestApplyInsets();
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.title(this, "Termlens", 18), new LinearLayout.LayoutParams(0, -2, 1));
        levelLabel = Ui.text(this, "原文", 12, Ui.MUTED); levelLabel.setSingleLine(true);
        header.addView(levelLabel);
        // Keep the header small; the close action is always visible, even on a long answer.
        close = icon(R.drawable.ic_termlens_close, "返回原页面"); close.setOnClickListener(v -> previousLevel());
        header.addView(close, size(40)); root.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)));
        scroll = new ScrollView(this); scroll.setId(0x544c01); scroll.setFillViewport(false);
        content = Ui.column(this); content.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        View rule = new View(this); rule.setBackgroundColor(Color.rgb(220, 226, 221));
        root.addView(rule, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        ask = icon(R.drawable.ic_termlens_ask, "Ask... 快捷询问"); ask.setOnClickListener(v -> openAsk());
        listen = icon(R.drawable.ic_termlens_speaker, "朗读选中内容或当前结果");
        listen.setOnClickListener(v -> speak(selection.trim().isEmpty() ? current().raw : selection));
        favorite = icon(R.drawable.ic_termlens_star, "收藏当前词条"); favorite.setOnClickListener(v -> {
            if(current().record==null)return;AppData store=AppData.get(this);boolean saved=!store.saved(current().record);
            store.setSaved(current().record,saved);if(saved)SpeechOutput.get(this).saveFavorite(current().record);updateActions();toast(saved?"已收藏，音频在后台保存":"已取消收藏");
        });
        copy = icon(R.drawable.ic_termlens_copy, "复制当前结果"); copy.setOnClickListener(v -> {
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("TermLens", current().raw));
            toast("已复制");
        });
        edit = icon(R.drawable.ic_termlens_edit, "编辑当前原文");
        edit.setOnClickListener(v -> beginEditing());
        lookup = Ui.button(this, "查询"); lookup.setTextSize(12); lookup.setFocusable(false);
        lookup.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        lookup.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) pressedSelection = selection;
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) pressedSelection = "";
            return false;
        });
        lookup.setOnClickListener(v -> { String text = pressedSelection.isEmpty() ? selection : pressedSelection;
            pressedSelection = ""; if (manualMode) submitManual(); else openLookup(text); });
        BottomTools.add(toolbar,lookup,listen,favorite,copy,edit,ask);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        boolean clear=blankTap.observe(event,scroll);
        boolean handled=super.dispatchTouchEvent(event);
        if(clear){
            if(selectionMode!=null)selectionMode.finish();
            rendering=true;
            if(selectionView!=null&&selectionView.getText() instanceof android.text.Spannable)
                android.text.Selection.removeSelection((android.text.Spannable)selectionView.getText());
            rendering=false;selection="";pressedSelection="";selectionView=null;updateActions();
        }
        return handled;
    }

    private LinearLayout.LayoutParams size(int dp) { return new LinearLayout.LayoutParams(Ui.dp(this, dp), Ui.dp(this, dp)); }
    private ImageButton icon(int drawable, String description) {
        ImageButton button = new ImageButton(this); button.setImageResource(drawable);
        button.setContentDescription(description); button.setColorFilter(Ui.ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
        button.setBackground(Ui.surface(this, Color.TRANSPARENT, 12)); button.setFocusable(false);
        button.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE); return button;
    }

    private final class StudyText extends TextView {
        StudyText(String value, int fontSize, boolean markdown) {
            super(ExplainActivity.this); setId(View.generateViewId()); setIncludeFontPadding(false);
            setTextSize(fontSize); setTextColor(Ui.INK); setLineSpacing(0, 1.12f);
            setText(markdown ? fromHtml(Markdown.toHtml(value)) : value);
            setTextIsSelectable(true);
            setCustomSelectionActionModeCallback(new ActionMode.Callback() {
                public boolean onCreateActionMode(ActionMode mode, Menu menu) { selectionMode=mode; addExplain(menu); return true; }
                public boolean onPrepareActionMode(ActionMode mode, Menu menu) { addExplain(menu); return true; }
                private void addExplain(Menu menu) {
                    for (int i = menu.size() - 1; i >= 0; i--) {
                        MenuItem item = menu.getItem(i); Intent target = item.getIntent();
                        if (target != null && Intent.ACTION_PROCESS_TEXT.equals(target.getAction())) menu.removeItem(item.getItemId());
                    }
                    if (menu.findItem(EXPLAIN_SELECTION) == null)
                        menu.add(0, EXPLAIN_SELECTION, 0, "Explain").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                }
                public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                    if (item.getItemId() != EXPLAIN_SELECTION) return false;
                    int start = Math.min(getSelectionStart(), getSelectionEnd()), end = Math.max(getSelectionStart(), getSelectionEnd());
                    String text = start >= 0 && end > start ? getText().subSequence(start, end).toString() : selection;
                    mode.finish(); openLookup(text); return true;
                }
                public void onDestroyActionMode(ActionMode mode) { if(selectionMode==mode)selectionMode=null; }
            });
        }
        @Override protected void onSelectionChanged(int start, int end) {
            super.onSelectionChanged(start, end);
            if (rendering || lookup == null || levels.isEmpty()) return;
            int first = Math.min(start, end), last = Math.max(start, end);
            if (first >= 0 && last > first && last <= getText().length()) {
                selectionView = this; selection = getText().subSequence(first, last).toString();
            } else if (selectionView == this) selection = "";
            updateActions();
        }
    }

    private Spanned fromHtml(String html) {
        Spanned value = android.os.Build.VERSION.SDK_INT >= 24 ? Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT) : Html.fromHtml(html);
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '\n') end--;
        return new android.text.SpannedString(value.subSequence(0, end));
    }
    private void render() {
        if (content == null || levels.isEmpty()) return;
        long renderStarted=android.os.SystemClock.elapsedRealtime();
        rendering = true; selection = ""; selectionView = null; pressedSelection = ""; content.removeAllViews();
        liveTranslation=null;liveExplanation=null;liveStatus=null;liveRule=null;
        Level level = current(); levelLabel.setText(levels.size() == 1 && inheritedLookup == null ? "原文" : "词义 · " + (studyDepth()+1));
        LinearLayout source = Ui.column(this); source.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        source.setBackground(Ui.surface(this, Color.WHITE, 12));
        LinearLayout sourceHeader = new LinearLayout(this); sourceHeader.setGravity(Gravity.CENTER_VERTICAL);
        StudyText original = new StudyText(level.original, 14, false);
        manualInput = null;
        if (manualMode) {
            manualInput = new EditText(this); manualInput.setId(0x544c02);
            manualInput.setTextSize(14); manualInput.setTextColor(Ui.INK);
            manualInput.setHint("Termlens");
            manualInput.setContentDescription("英文输入栏");
            manualInput.setSingleLine(true);
            manualInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            manualInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            if (android.os.Build.VERSION.SDK_INT >= 24) manualInput.setImeHintLocales(new android.os.LocaleList(Locale.US));
            manualInput.setText(manualDraft);
            manualInput.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
                public void onTextChanged(CharSequence s,int start,int before,int count) { manualDraft=s.toString(); updateActions(); }
                public void afterTextChanged(Editable s) { }
            });
            manualInput.setOnEditorActionListener((v, action, event) -> {
                boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
                if (enter || action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                    if (!enter || event.getAction() == KeyEvent.ACTION_UP) submitManual();
                    return true;
                }
                return false;
            });
            sourceHeader.addView(manualInput, new LinearLayout.LayoutParams(0, -2, 1));
        } else sourceHeader.addView(original, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton originalSpeaker = icon(R.drawable.ic_termlens_speaker, "朗读原文");
        originalSpeaker.setOnClickListener(v -> speak(level.original)); sourceHeader.addView(originalSpeaker, size(30));
        source.addView(sourceHeader);
        originalSpeaker.setEnabled(level.record != null);
        if (!manualMode && level.original.length() > 100) {
            original.setMaxLines(level.sourceExpanded ? Integer.MAX_VALUE : 3); original.setEllipsize(level.sourceExpanded ? null : TextUtils.TruncateAt.END);
            TextView expand = Ui.text(this, level.sourceExpanded ? "收起原文" : "展开原文", 12, Ui.ACCENT);
            expand.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4)); expand.setOnClickListener(v -> {
                level.sourceExpanded = !level.sourceExpanded; original.setMaxLines(level.sourceExpanded ? Integer.MAX_VALUE : 3);
                original.setEllipsize(level.sourceExpanded ? null : TextUtils.TruncateAt.END); expand.setText(level.sourceExpanded ? "收起原文" : "展开原文");
            }); source.addView(expand);
        }
        content.addView(source); Ui.space(this, content, 8);
        liveTranslation=new StudyText("",16,true);content.addView(liveTranslation);
        liveRule=new View(this);liveRule.setBackgroundColor(Color.rgb(216,223,217));
        LinearLayout.LayoutParams lineParams=new LinearLayout.LayoutParams(-1,Ui.dp(this,1));lineParams.setMargins(0,Ui.dp(this,8),0,Ui.dp(this,8));content.addView(liveRule,lineParams);
        liveExplanation=new StudyText("",14,true);content.addView(liveExplanation);
        liveStatus=Ui.text(this,"",12,Ui.MUTED);content.addView(liveStatus);
        updateStream();
        if (!level.error.isEmpty()) {
            content.addView(Ui.text(this, level.error, 14, Ui.MUTED));
            Button retry = Ui.button(this, "重试"); retry.setOnClickListener(v -> { if (manualMode) submitManual(); else requestCurrent(); }); content.addView(retry);
            Ui.space(this, content, 8);
            Button settings = Ui.button(this, "模型连接设置");
            settings.setOnClickListener(v -> startActivityForResult(new Intent(this, SettingsActivity.class), EDIT_CONNECTION)); content.addView(settings);
        }
        rendering = false; updateActions();
        if (keyboardPending) focusManualInput(); else root.requestFocus();
        scroll.post(() -> { if (!levels.isEmpty() && current() == level) scroll.scrollTo(0, level.scrollY); });
        maxRenderMs=Math.max(maxRenderMs,android.os.SystemClock.elapsedRealtime()-renderStarted);
    }
    private void updateStream() {
        if(liveTranslation==null||levels.isEmpty())return;long start=android.os.SystemClock.elapsedRealtime();boolean before=rendering;rendering=true;
        String raw=current().raw;String[] parts=LookupInput.parts(raw);
        if(busy&&parts[0].isEmpty()&&!java.util.regex.Pattern.compile("(?m)^---\\s*$").matcher(raw).find())parts=new String[]{raw,""};
        updateText(liveTranslation,parts[0]);updateText(liveExplanation,parts[1]);
        liveTranslation.setVisibility(parts[0].isEmpty()?View.GONE:View.VISIBLE);liveExplanation.setVisibility(parts[1].isEmpty()?View.GONE:View.VISIBLE);
        liveRule.setVisibility(parts[0].isEmpty()||parts[1].isEmpty()?View.GONE:View.VISIBLE);
        liveStatus.setText(raw.isEmpty()?"正在解释…":"正在接收…");liveStatus.setVisibility(busy?View.VISIBLE:View.GONE);rendering=before;
        maxRenderMs=Math.max(maxRenderMs,android.os.SystemClock.elapsedRealtime()-start);
        if(!raw.isEmpty()&&firstDrawMs<0&&busy){final int requestId=generation;
            android.view.ViewTreeObserver.OnDrawListener draw=new android.view.ViewTreeObserver.OnDrawListener(){public void onDraw(){
                if(firstDrawMs<0&&generation==requestId){firstDrawMs=android.os.SystemClock.elapsedRealtime()-requestStarted;android.util.Log.i("TLPerf","first_draw_ms="+firstDrawMs);}
                root.post(()->{if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnDrawListener(this);});
            }};root.getViewTreeObserver().addOnDrawListener(draw);
        }
    }
    private void updateText(StudyText view,String markdown){
        Spanned next=fromHtml(Markdown.toHtml(markdown));if(view.getText().toString().equals(next.toString()))return;
        int start=view.getSelectionStart(),end=view.getSelectionEnd();String selected=start>=0&&end>start?view.getText().subSequence(start,end).toString():"";
        view.setText(next);
        if(!selected.isEmpty()&&end<=next.length()&&next.subSequence(start,end).toString().equals(selected)&&view.getText() instanceof android.text.Spannable)android.text.Selection.setSelection((android.text.Spannable)view.getText(),start,end);
    }

    private void updateActions() {
        if (lookup == null || levels.isEmpty()) return;
        boolean selected = !selection.trim().isEmpty();
        boolean canSubmit = !busy && !manualDraft.trim().isEmpty();
        lookup.setText("查询");
        lookup.setContentDescription(busy ? "正在查询" : manualMode ? "查询输入的英文" : "查询选中内容");
        lookup.setEnabled(manualMode ? canSubmit : !busy && selected);
        lookup.setAlpha(lookup.isEnabled() ? 1f : 0.4f);
        edit.setEnabled(!manualMode);
        listen.setEnabled(!current().raw.isEmpty() || selected); copy.setEnabled(!current().raw.isEmpty());
        favorite.setEnabled(!busy&&current().record!=null&&!current().raw.isEmpty());
        favorite.setAlpha(current().record!=null&&AppData.get(this).saved(current().record)?1f:0.5f);
        close.setContentDescription(levels.size() > 1 ? "关闭本层，返回上一层" : "返回原页面");
    }
    private void openLookup(String query) {
        if (busy) return;
        if (query == null || query.trim().isEmpty()) { toast("先在译文或解释里长按选词，再点“查询”"); return; }
        if (!StudyNavigation.canOpen(studyDepth())) { toast("请先返回上一层，再选择其他词语"); return; }
        current().scrollY = scroll.getScrollY(); cancelRequest(); manualMode = false; hideKeyboard();
        levels.add(new Level(query.trim())); requestCurrent();
    }
    private void openAsk() {
        if (levels.isEmpty()) return;
        if (!StudyNavigation.canOpen(studyDepth())) { toast("层级较多，请先返回几层后继续"); return; }
        current().scrollY = scroll.getScrollY();
        JSONObject root = rootContext();
        String context;
        if (manualMode) {
            // A draft is not paired with the old query's answer.
            context = AskPrompt.context(manualDraft, "", "", "", "", false);
            root = new JSONObject(); AppData.put(root,"rootOriginal",LookupInput.clip(manualDraft,3000,null)); AppData.put(root,"rootAnswer","");
        } else {
            Level current = current(), first = levels.get(0);
            Level parent = levels.size() > 1 ? levels.get(levels.size() - 2) : null;
            JSONObject stored = current.record == null ? null : AppData.context(current.record.optString("context")).optJSONObject("lookupContext");
            boolean selected = !selection.trim().isEmpty();
            context = AskPrompt.context(selected ? selection : current.original, current.raw,
                selected ? current.original : parent != null ? parent.original : stored == null ? "" : stored.optString("parentQuery"),
                selected ? "" : parent != null ? parent.raw : stored == null ? "" : stored.optString("parentAnswer"),
                stored == null ? first.original : stored.optString("rootOriginal", first.original), busy || !current.error.isEmpty());
        }
        if (busy) { cancelRequest(); current().error = "查询已暂停，可重试"; }
        hideKeyboard(); SpeechOutput.get(this).stop();
        startActivity(new Intent(this, AskActivity.class).putExtra(AskActivity.CONTEXT, context)
            .putExtra(StudyNavigation.ROOT, root.toString()).putExtra(StudyNavigation.PROFILE, profile.toString())
            .putExtra(StudyNavigation.DEPTH, StudyNavigation.child(studyDepth())));
    }
    private void beginEditing() {
        if (levels.isEmpty()) return;
        current().scrollY = scroll.getScrollY();
        cancelRequest(); SpeechOutput.get(this).stop();
        manualMode = true; manualDraft = current().original; keyboardPending = true;
        render(); scroll.post(() -> scroll.scrollTo(0, 0));
    }
    private void submitManual() {
        if (!manualMode || levels.isEmpty()) return;
        if (busy) return;
        String query = manualDraft.trim();
        if (query.isEmpty()) { if (manualInput != null) manualInput.setError("请输入英文"); return; }
        if (busy && query.equals(current().original)) return;
        hideKeyboard(); cancelRequest(); SpeechOutput.get(this).stop();
        manualDraft = query; manualMode = false;
        levels.set(levels.size() - 1, new Level(query)); requestCurrent();
    }
    private void focusManualInput() {
        final EditText editor = manualInput;
        if (editor == null || !keyboardPending) return;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        editor.requestFocus(); editor.setSelection(editor.length());
        editor.post(() -> {
            if (destroyed || isFinishing() || editor != manualInput || !keyboardPending || !hasWindowFocus()) return;
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
            if (android.os.Build.VERSION.SDK_INT >= 30 && editor.getWindowInsetsController() != null)
                editor.getWindowInsetsController().show(android.view.WindowInsets.Type.ime());
            keyboardPending = false;
        });
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); if (focused && keyboardPending) focusManualInput();
    }
    private boolean hideKeyboard() {
        boolean visible = false;
        if (root != null) {
            if (android.os.Build.VERSION.SDK_INT >= 30 && root.getRootWindowInsets() != null)
                visible = root.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime());
            else { android.graphics.Rect frame=new android.graphics.Rect(); root.getWindowVisibleDisplayFrame(frame);
                visible=root.getRootView().getHeight()-frame.bottom > Ui.dp(this,150); }
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);
        }
        keyboardPending=false;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if(manualInput!=null)manualInput.clearFocus();
        return visible;
    }
    private void requestCurrent() {
        if (levels.isEmpty() || current().original.trim().isEmpty()) return;
        cancelRequest(); final int requestId = generation; Level level = current();
        busy = true; level.error = ""; level.raw=""; render();
        boolean contextual = levels.size() > 1 || inheritedLookup != null;
        lastRequestSystem = contextual ? secondarySystem : primarySystem;
        JSONObject requestRoot = rootContext();
        if (!contextual) lastRequestUser = level.original;
        else {
            Level parent = levels.size() > 1 ? levels.get(levels.size() - 2) : null;
            lastRequestUser = LookupInput.build(requestRoot.optString("rootOriginal"), requestRoot.optString("rootAnswer"),
                parent == null ? inheritedLookup.optString("parentQuery") : parent.original,
                parent == null ? inheritedLookup.optString("parentAnswer") : parent.raw, level.original);
        }
        JSONObject context=new JSONObject();AppData.put(context,"requestUserMessage",lastRequestUser);AppData.put(context,"recentTerms",new org.json.JSONArray());AppData.put(context,"promptProfile",profile);
        if(contextual){int begin=lastRequestUser.indexOf('{'),end=lastRequestUser.lastIndexOf('}');if(begin>=0&&end>begin){JSONObject quoted=AppData.object(lastRequestUser.substring(begin,end+1));
            AppData.put(quoted,"rootSourceId",requestRoot.optString("rootSourceId"));AppData.put(context,"lookupContext",quoted);}}
        else AppData.put(context,"lookupContext",JSONObject.NULL);
        String sourcePackage=getCallingPackage();AppData.put(context,"sourceApp",sourcePackage==null?"":sourcePackage);
        JSONObject quoted=context.optJSONObject("lookupContext");
        level.record=AppData.get(this).begin(level.original,contextual,quoted==null?levels.get(0).original:quoted.optString("rootOriginal"),context.toString(),TermLensPrefs.model(this),lastRequestSystem);
        SpeechOutput.get(this).preloadOriginal(level.record);
        level.raw="";requestStarted=android.os.SystemClock.elapsedRealtime();firstDrawMs=-1;maxRenderMs=0;
        pending = LlmClient.request(this, lastRequestUser, lastRequestSystem, answer->{
            if(destroyed||isFinishing()||generation!=requestId||current()!=level)return;
            level.raw=answer;updateStream();
        }, (answer, error, timing) -> {
            if (destroyed || isFinishing() || generation != requestId || levels.isEmpty() || current() != level) return;
            busy = false; level.raw = answer; level.error = error == null ? "" : error;lastTiming=timing;
            AppData.get(this).finish(level.record,answer,level.error.isEmpty()?"complete":"failed",level.error);
            String oldFavorite="favorite_"+level.original.hashCode();
            if(level.error.isEmpty()&&TermLensPrefs.open(this).getBoolean(oldFavorite,false)&&!TermLensPrefs.open(this).getBoolean("migrated_"+oldFavorite,false)){
                AppData.get(this).setSaved(level.record,true);TermLensPrefs.put(this,"migrated_"+oldFavorite,true);SpeechOutput.get(this).saveFavorite(level.record);
            }
            render();android.util.Log.i("TLPerf","ui first_draw_ms="+firstDrawMs+" max_render_ms="+maxRenderMs);
            if(level.error.isEmpty())SpeechOutput.get(this).preload(level.record);
        });
    }
    private void cancelRequest() { generation++; if(busy&&!levels.isEmpty()&&current().record!=null)AppData.get(this).finish(current().record,current().raw,"cancelled","查询已取消");if (pending != null) { pending.cancel(); pending = null; } busy = false; }
    private void previousLevel() {
        if (hideKeyboard()) return;
        if (manualMode && !current().original.trim().isEmpty()) {
            manualMode = false; render(); return;
        }
        SpeechOutput.get(this).stop();
        if (levels.size() <= 1) { finish(); return; }
        cancelRequest(); manualMode = false; levels.remove(levels.size() - 1); render();
    }
    private void speak(String text) {
        if(current().record==null||text==null||text.trim().isEmpty())return;
        JSONObject record=current().record;String role=text.equals(current().original)?"Word":"Explanation";
        if(!text.equals(current().original)&&!text.equals(current().raw)){record=AppData.object(record.toString());AppData.put(record,"id",record.optString("id")+"-selected");role="Word";}
        SpeechOutput.get(this).speak(record,text,role);
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data); if (code == EDIT_CONNECTION && !levels.isEmpty() && !manualMode) requestCurrent();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (!levels.isEmpty() && scroll != null) current().scrollY = scroll.getScrollY();
        state.putInt(StudyNavigation.DEPTH, navigationDepth);
        if (inheritedLookup != null) state.putString(StudyNavigation.LOOKUP, inheritedLookup.toString());
        state.putString(StudyNavigation.PROFILE, profile.toString());
        state.putBoolean("manual_mode", manualMode); state.putString("manual_draft", manualDraft);
        state.putBoolean("manual_editing", manualInput != null && manualInput.hasFocus());
        state.putString("primary_system", primarySystem); state.putString("secondary_system", secondarySystem);
        state.putInt("level_count", levels.size());
        for (int i = 0; i < levels.size(); i++) {
            Level level = levels.get(i); state.putString("original_" + i, level.original); state.putString("raw_" + i, level.raw);
            state.putString("error_" + i, level.error); state.putInt("scroll_" + i, level.scrollY); state.putBoolean("expanded_" + i, level.sourceExpanded);
            if(level.record!=null)state.putString("record_"+i,level.record.optString("id"));
        }
        super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() { previousLevel(); }
    @Override public void finish() { cancelRequest(); setResult(RESULT_CANCELED); super.finish(); }
    @Override protected void onPause(){SpeechOutput.get(this).stop();if(busy&&!levels.isEmpty()&&current().record!=null)AppData.get(this).finish(current().record,current().raw,"pending","");super.onPause();}
    @Override protected void onDestroy() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback != null)
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        destroyed = true; cancelRequest(); SpeechOutput.get(this).stop(); super.onDestroy(); }

    private String fingerprint(String value) {
        try { byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(); for (byte b : hash) text.append(String.format("%02x", b & 255)); return text.toString();
        } catch (Exception e) { return "unavailable"; }
    }
    @Override public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println(prefix + "termlens.levels=" + levels.size() + " busy=" + busy);
        writer.println(prefix+"termlens.first_draw_ms="+firstDrawMs+" max_render_ms="+maxRenderMs);
        if(lastTiming!=null)writer.println(prefix+"termlens.timing="+lastTiming.json());
        writer.println(prefix+SpeechOutput.get(this).status());
        if (!levels.isEmpty()) writer.println(prefix + "termlens.query_sha256=" + fingerprint(current().original));
        writer.println(prefix + "termlens.system_sha256=" + fingerprint(lastRequestSystem));
        writer.println(prefix + "termlens.user_sha256=" + fingerprint(lastRequestUser));
        writer.println(prefix + "termlens.root_context=" + lastRequestUser.contains("\"rootOriginal\""));
        writer.println(prefix + "termlens.parent_context=" + lastRequestUser.contains("\"parentAnswer\""));
        if (toolbar != null) { int[] at = new int[2]; toolbar.getLocationOnScreen(at);
            writer.println(prefix + "termlens.toolbar=" + at[0] + "," + at[1] + "," + toolbar.getWidth() + "," + toolbar.getHeight()); }
    }
}
