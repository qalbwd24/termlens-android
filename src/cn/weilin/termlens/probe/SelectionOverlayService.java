package cn.weilin.termlens.probe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.Toast;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/** Offline selection probe. Only keeps the current selection in memory. */
public final class SelectionOverlayService extends AccessibilityService {
    private static SelectionOverlayService active;
    private static int events, valid, shown, clicked;
    private static int viaEvents;
    private static String lastOutcome = "尚未收到选区";
    static final String[] PACKAGES = {"cn.weilin.termlens.probe", "mark.via.gp", "com.twitter.android",
        "com.google.android.youtube", "com.openai.chatgpt", "com.microsoft.emmx", "com.android.chrome", "com.ichi2.anki", "io.legado.app.release"};
    private static final int[] perAppSelection = new int[PACKAGES.length];
    private static final int[] perAppLongPress = new int[PACKAGES.length];
    private static final String[] outcomes = new String[PACKAGES.length];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private Button button;
    private String selection;
    private String foregroundClass = "";
    private long shownAt;
    private boolean receiverRegistered;
    private boolean scanScheduled;
    private boolean resident;
    private String menuPackage;
    private SelectionMenuBridge menuBridge;
    private boolean forwardingMenu;
    private WindowManager.LayoutParams buttonParams;
    private Runnable pendingButtonHold;
    private final Runnable scanSelection = () -> {
        scanScheduled = false;
        if (forwardingMenu || resident) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            int app = appIndex(root.getPackageName());
            if (app >= 1) {
                if (findSelectedChild(root, 0, new int[] {0})) outcomes[app] = "页面节点有效选区";
                else {
                    String pkg = String.valueOf(root.getPackageName());
                    Rect anchor = menuBridge.selectionAnchor(pkg, root);
                    if (anchor != null && !pkg.equals(menuPackage)) {
                        show(null, anchor, false); menuPackage = pkg;
                        outcomes[app] = "选区就绪 · 独立 Explain 按钮";
                    } else if (anchor == null && menuPackage != null) hide();
                }
            }
        } finally { root.recycle(); }
    };
    private final Runnable expire = () -> hide();
    private final Runnable checkForeground = new Runnable() {
        @Override public void run() {
            if (button == null) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try { if (getPackageName().contentEquals(root.getPackageName()) || (!resident && !allowed(root.getPackageName()))) { hide(); return; } }
                finally { root.recycle(); }
            }
            if (!resident) handler.postDelayed(this, 700);
        }
    };
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { hide(); }
    };
    public static String status() {
        return "服务：" + (active == null ? "未连接" : "已连接")
            + "\n选区事件 " + events + " · 有效 " + valid
            + " · 显示 " + shown + " · 点击 " + clicked + "\n" + lastOutcome
            + "\nX：" + appStatus(2) + "\nYouTube：" + appStatus(3) + "\nChatGPT：" + appStatus(4)
            + "\nAnki：" + appStatus(7) + "\n阅读：" + appStatus(8);
    }
    private static String appStatus(int index) {
        return "选区 " + perAppSelection[index] + " / 长按 " + perAppLongPress[index]
            + (outcomes[index] == null ? "" : " / " + outcomes[index]);
    }
    public static void stopProbe() {
        if (active != null) {
            active.hide();
            active.disableSelf();
            active = null;
            lastOutcome = "已停止";
        }
    }
    public static void refreshSettings() {
        if (active == null) return;
        boolean nextResident = TermLensPrefs.resident(active);
        active.hide(); active.resident = nextResident; active.configureEvents();
        if (nextResident) active.handler.postDelayed(() -> { if (active != null) active.showResident(); }, 250);
    }
    @Override protected void onServiceConnected() {
        PACKAGES[0] = getPackageName();
        active = this;
        TermLensPrefs.enableDirectDefaults(this);
        resident = TermLensPrefs.resident(this);
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        menuBridge = new SelectionMenuBridge(this, handler);
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        receiverRegistered = true;
        configureEvents();
        lastOutcome = "角落按钮就绪 · AnkiDroid / 阅读 / 浏览器";
        if (resident) handler.postDelayed(() -> showResident(), 250);
    }
    private void configureEvents() {
        AccessibilityServiceInfo info = getServiceInfo();
        info.packageNames = resident ? null : PACKAGES.clone();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        if (!resident) {
            info.eventTypes |= AccessibilityEvent.TYPE_VIEW_LONG_CLICKED;
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            info.eventTypes |= AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED | AccessibilityEvent.TYPE_VIEW_SCROLLED;
        }
        // Selectable children may be excluded when their parent describes them.
        info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }
    private boolean allowed(CharSequence pkg) {
        return appIndex(pkg) >= 0;
    }
    private int appIndex(CharSequence pkg) {
        if (pkg != null) for (int i = 0; i < PACKAGES.length; i++) if (PACKAGES[i].contentEquals(pkg)) return i;
        return -1;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (resident) {
            if (event == null || forwardingMenu) return;
            if (keyboardVisible()) { hide(); return; }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    if (hideOnPackage(root.getPackageName())) { hide(); return; }
                    if (button == null) showResident();
                    if (allowed(root.getPackageName())) avoidSelection(String.valueOf(root.getPackageName()), root);
                } finally { root.recycle(); }
            }
            return;
        }
        if (event == null || !allowed(event.getPackageName())) return;
        int app = appIndex(event.getPackageName());
        if (app >= 1 && !scanScheduled) {
            scanScheduled = true;
            handler.postDelayed(scanSelection, 150);
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            perAppLongPress[app]++;
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                try { outcomes[app] = "长按节点 " + source.getClassName()
                    + " · 文字 " + (source.getText() == null ? 0 : source.getText().length()) + " 字符"; }
                finally { source.recycle(); }
            }
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String nextClass = String.valueOf(event.getClassName());
            // The floating text toolbar also reports window events. Keep the button
            // when it is the same Activity; remove it on an actual Activity change.
            if (nextClass.endsWith("Activity") && !nextClass.equals(foregroundClass)) {
                foregroundClass = nextClass;
                hide();
                if (resident && allowed(event.getPackageName())) handler.postDelayed(() -> showResident(), 240);
            }
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Text selection can cause the host to reveal the selection by scrolling.
            // Do not dismiss the new button during that first layout adjustment.
            if (android.os.SystemClock.uptimeMillis() - shownAt > 600) hide();
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            if (resident && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && button == null)
                handler.postDelayed(() -> showResident(), 220);
            return;
        }
        events++;
        perAppSelection[app]++;
        if ("mark.via.gp".contentEquals(event.getPackageName())) viaEvents++;
        AccessibilityNodeInfo node = event.getSource();
        if (node == null) { lastOutcome = "选区事件没有文字节点"; outcomes[app] = lastOutcome; hide(); return; }
        try {
            if ("TermLens 解释弹窗原文".contentEquals(String.valueOf(node.getContentDescription()))) return;
            if (node.isPassword()) { hide(); return; }
            CharSequence full = node.getText();
            int start = event.getFromIndex(), end = event.getToIndex();
            if (full == null) {
                if (findSelectedChild(node, 0, new int[] {0})) { outcomes[app] = "子节点有效选区"; return; }
                lastOutcome = "选区节点没有原文"; outcomes[app] = lastOutcome; hide(); return;
            }
            if (start > end) { int swap = start; start = end; end = swap; }
            if (start < 0 || end <= start || end > full.length() || end - start > 8000) {
                if (findSelectedChild(node, 0, new int[] {0})) { outcomes[app] = "子节点有效选区"; return; }
                lastOutcome = event.getPackageName() + "：范围 " + start + "~" + end
                    + " / 全文 " + full.length() + "\n节点 " + node.getClassName()
                    + " / 节点选区 " + node.getTextSelectionStart() + "~" + node.getTextSelectionEnd()
                    + "\n事件文本条数 " + event.getText().size() + " / " + extendedSelectionState(node);
                outcomes[app] = "无有效索引 · 全文 " + full.length() + " 字符";
                hide(); return;
            }
            String selected = full.subSequence(start, end).toString();
            if (selected.trim().isEmpty()) { hide(); return; }
            valid++;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            lastOutcome = "收到有效选区 · " + (end - start) + " 字符 · " + event.getPackageName();
            outcomes[app] = "有效选区 " + (end - start) + " 字符";
            show(selected, bounds, false);
        } finally { node.recycle(); }
    }
    private String extendedSelectionState(AccessibilityNodeInfo node) {
        // Public API introduced in SDK 36.1; reflection is solely for compatibility
        // with the API-35 compiler and does not access hidden methods.
        try {
            Object extended = AccessibilityNodeInfo.class.getMethod("getSelection").invoke(node);
            return "扩展选区" + (extended == null ? "为空" : "存在（当前探针未解析）");
        } catch (NoSuchMethodException e) { return "系统未提供扩展选区 API"; }
        catch (ReflectiveOperationException e) { return "扩展选区查询失败"; }
    }
    private boolean findSelectedChild(AccessibilityNodeInfo node, int depth, int[] visited) {
        if (depth > 24 || ++visited[0] > 300) return false;
        if ("TermLens 解释弹窗原文".contentEquals(String.valueOf(node.getContentDescription()))) return false;
        CharSequence text = node.getText();
        int start = node.getTextSelectionStart(), end = node.getTextSelectionEnd();
        if (start > end) { int swap = start; start = end; end = swap; }
        if (node.isVisibleToUser() && !node.isPassword() && text != null && start >= 0 && end > start
            && end <= text.length() && end - start <= 8000) {
            String selected = text.subSequence(start, end).toString();
            if (!selected.trim().isEmpty()) {
                if (button != null && selected.equals(selection)) return true;
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                valid++;
                lastOutcome = "子节点有效选区 · " + (end - start) + " 字符";
                show(selected, bounds, false);
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (findSelectedChild(child, depth + 1, visited)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void showResident() {
        if (!resident || forwardingMenu || button != null || !((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            try { if (hideOnPackage(root.getPackageName())) return; }
            finally { root.recycle(); }
        }
        show(null, new Rect(), true);
    }
    private boolean hideOnPackage(CharSequence pkg) {
        return pkg != null && (getPackageName().contentEquals(pkg) || "com.android.settings".contentEquals(pkg)
            || "com.android.permissioncontroller".contentEquals(pkg));
    }
    private void show(String selected, Rect bounds, boolean permanent) {
        if (keyboardVisible()) return;
        if (!((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) return;
        hide();
        selection = selected;
        button = new Button(this);
        button.setText("✦");
        button.setContentDescription("TermLens 悬浮 Explain");
        button.setAllCaps(false);
        button.setTextSize(18);
        button.setTextColor(Color.WHITE);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Ui.ACCENT);
        background.setCornerRadius(dp(22));
        button.setBackground(background);
        button.setElevation(dp(6));
        final float idleAlpha = TermLensPrefs.buttonTranslucent(this) ? 0.62f : 1f;
        button.setAlpha(idleAlpha);
        int overlayType = android.os.Build.VERSION.SDK_INT >= 26 && android.provider.Settings.canDrawOverlays(this)
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(dp(44), dp(44), overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        String position = TermLensPrefs.buttonPosition(this);
        boolean left = "left".equals(position) || ("auto".equals(position) && bounds.centerX() > width / 2);
        params.x = left ? dp(12) : Math.max(dp(12), width - dp(56));
        // Fixed lower corners, above Anki grading/navigation and reader footers.
        params.y = Math.max(dp(80), height - dp(170));
        if ("custom".equals(position)) {
            params.x = Math.round(TermLensPrefs.open(this).getFloat("button_x_fraction", 0.85f) * Math.max(1, width - dp(44)));
            params.y = Math.round(TermLensPrefs.open(this).getFloat("button_y_fraction", 0.65f) * Math.max(1, height - dp(44)));
        }
        clampButton(params);
        button.setContentDescription(TermLensPrefs.buttonLocked(this) ? "Explain，位置已锁定" : "Explain，长按可拖动");
        button.setOnTouchListener(new android.view.View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean held, cancelled;
            final int slop = ViewConfiguration.get(SelectionOverlayService.this).getScaledTouchSlop();
            final Runnable hold = () -> {
                held = true;
                if (!TermLensPrefs.buttonLocked(SelectionOverlayService.this) && button != null)
                    button.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            };
            public boolean onTouch(android.view.View view, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY(); startX = params.x; startY = params.y;
                    held = false; cancelled = false; view.setAlpha(1f);
                    pendingButtonHold = hold;
                    handler.postDelayed(hold, ViewConfiguration.getLongPressTimeout()); return true;
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (!held && Math.hypot(dx, dy) > slop) { cancelled = true; handler.removeCallbacks(hold); }
                    if (held && !cancelled && !TermLensPrefs.buttonLocked(SelectionOverlayService.this)) {
                        params.x = startX + Math.round(dx); params.y = startY + Math.round(dy); clampButton(params);
                        if (button == view) windows.updateViewLayout(view, params);
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    handler.removeCallbacks(hold); view.setAlpha(idleAlpha);
                    if (action == MotionEvent.ACTION_UP && held && !cancelled && !TermLensPrefs.buttonLocked(SelectionOverlayService.this)) {
                        TermLensPrefs.open(SelectionOverlayService.this).edit()
                            .putString(TermLensPrefs.BUTTON_POSITION, "custom")
                            .putFloat("button_x_fraction", (float) params.x / Math.max(1, width - dp(44)))
                            .putFloat("button_y_fraction", (float) params.y / Math.max(1, height - dp(44))).apply();
                    } else if (action == MotionEvent.ACTION_UP && !held && !cancelled) view.performClick();
                    return true;
                }
                return true;
            }
        });
        button.setOnClickListener(v -> {
            String current = selection;
            String sourceMenu = menuPackage;
            if (resident) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) { openManualInput(); return; }
                try {
                    String pkg = String.valueOf(root.getPackageName());
                    if (!allowed(pkg) || getPackageName().equals(pkg)) {
                        openManualInput(); return;
                    }
                    current = menuBridge.selectedText(root);
                    if (current.trim().isEmpty()) {
                        if (menuBridge.selectionAnchor(pkg, root) == null) {
                            openManualInput(); return;
                        }
                        sourceMenu = pkg;
                    }
                } finally { root.recycle(); }
            }
            forwardingMenu = true;
            hide();
            clicked++;
            if (sourceMenu != null) {
                menuBridge.explain(sourceMenu, success -> {
                    lastOutcome = success ? "已直接调用源 App 的 Explain" : "源 App 的选词菜单暂未提供可调用的 Explain";
                    handler.postDelayed(() -> { forwardingMenu = false; if (resident) showResident(); }, success ? 700 : 0);
                    if (!success) Toast.makeText(this, lastOutcome, Toast.LENGTH_SHORT).show();
                });
                return;
            }
            if (current == null || current.trim().isEmpty()) {
                current = findMainTerm();
                if (current.isEmpty()) {
                    startActivity(new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                }
            }
            Intent intent = new Intent(this, ExplainActivity.class)
                .setAction(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, current)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(intent); }
            catch (RuntimeException e) { lastOutcome = "系统未允许打开弹窗：" + e.getClass().getSimpleName(); }
            handler.postDelayed(() -> { forwardingMenu = false; if (resident) showResident(); }, 700);
        });
        try {
            windows.addView(button, params);
            buttonParams = params;
            shownAt = android.os.SystemClock.uptimeMillis();
            shown++;
            if (!permanent && selected != null) handler.postDelayed(expire, 12000);
            handler.postDelayed(checkForeground, 700);
        } catch (RuntimeException e) {
            button = null;
            selection = null;
            lastOutcome = "悬浮层未显示：" + e.getClass().getSimpleName();
        }
    }
    private void openManualInput() {
        forwardingMenu = true; hide(); clicked++;
        try { startActivity(new Intent(this, ExplainActivity.class)
            .putExtra(ExplainActivity.MANUAL_INPUT, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException e) { lastOutcome = "系统未允许打开输入页：" + e.getClass().getSimpleName(); }
        handler.postDelayed(() -> { forwardingMenu = false; if (resident) showResident(); }, 700);
    }
    private void hide() {
        if (pendingButtonHold != null) { handler.removeCallbacks(pendingButtonHold); pendingButtonHold = null; }
        handler.removeCallbacks(expire);
        handler.removeCallbacks(checkForeground);
        if (button != null) {
            try { windows.removeView(button); } catch (RuntimeException ignored) { }
            button = null;
        }
        selection = null;
        menuPackage = null;
        buttonParams = null;
    }

    private void avoidSelection(String pkg, AccessibilityNodeInfo root) {
        if (button == null || buttonParams == null || TermLensPrefs.buttonLocked(this) || !"auto".equals(TermLensPrefs.buttonPosition(this))) return;
        Rect anchor = menuBridge.selectionAnchor(pkg, root);
        if (anchor == null) return;
        int[] location = new int[2]; button.getLocationOnScreen(location);
        Rect occupied = new Rect(location[0], location[1], location[0] + button.getWidth(), location[1] + button.getHeight());
        if (!Rect.intersects(occupied, anchor)) return;
        int width = getResources().getDisplayMetrics().widthPixels;
        int targetX = occupied.centerX() > width / 2 ? dp(12) : width - dp(56);
        Rect alternate = new Rect(targetX, occupied.top, targetX + occupied.width(), occupied.bottom);
        if (!Rect.intersects(alternate, anchor)) { buttonParams.x = targetX; windows.updateViewLayout(button, buttonParams); }
    }
    private boolean keyboardVisible() {
        if (!TermLensPrefs.hideOnKeyboard(this)) return false;
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) return true;
        }
        return false;
    }
    private void clampButton(WindowManager.LayoutParams params) {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int statusId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int navigationId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        int top = statusId == 0 ? dp(24) : getResources().getDimensionPixelSize(statusId);
        int bottom = navigationId == 0 ? dp(24) : getResources().getDimensionPixelSize(navigationId);
        params.x = Math.max(dp(4), Math.min(params.x, metrics.widthPixels - dp(48)));
        params.y = Math.max(top, Math.min(params.y, metrics.heightPixels - bottom - dp(48)));
    }
    private String findMainTerm() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return TermLensPrefs.mainTerm(this);
        try {
            String marked = findMarkedTerm(root, 0, new int[]{0});
            return marked.isEmpty() ? TermLensPrefs.mainTerm(this) : marked;
        } finally { root.recycle(); }
    }
    private String findMarkedTerm(AccessibilityNodeInfo node, int depth, int[] visited) {
        if (node == null || depth > 24 || ++visited[0] > 500) return "";
        CharSequence description = node.getContentDescription();
        if (description != null) {
            String value = description.toString();
            String prefix = "termlens-term:";
            int at = value.toLowerCase().indexOf(prefix);
            if (at >= 0) return value.substring(at + prefix.length()).trim();
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { String found = findMarkedTerm(child, depth + 1, visited); if (!found.isEmpty()) return found; }
            finally { child.recycle(); }
        }
        return "";
    }
    @Override public void onInterrupt() { hide(); }
    // ADB diagnostics contain structure, lengths and indices only, never text.
    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println(status());
        writer.println("button_visible=" + (button != null) + " locked=" + TermLensPrefs.buttonLocked(this)
            + " position=" + TermLensPrefs.buttonPosition(this) + " keyboard_visible=" + keyboardVisible());
        if (buttonParams != null) writer.println("button_xy=" + buttonParams.x + "," + buttonParams.y);
        AccessibilityServiceInfo info = getServiceInfo();
        writer.println("flags=" + info.flags + " packages=" + java.util.Arrays.toString(info.packageNames));
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            try { if (appIndex(root.getPackageName()) >= 2) dumpNodes(root, writer, 0, new int[] {0}); }
            finally { root.recycle(); }
        }
        for (AccessibilityWindowInfo window : getWindows()) {
            writer.println("window id=" + window.getId() + " type=" + window.getType() + " active=" + window.isActive());
            AccessibilityNodeInfo windowRoot = window.getRoot();
            if (windowRoot != null) {
                try { if (allowed(windowRoot.getPackageName())) dumpNodes(windowRoot, writer, 0, new int[] {0}); }
                finally { windowRoot.recycle(); }
            }
        }
    }
    private void dumpNodes(AccessibilityNodeInfo node, PrintWriter writer, int depth, int[] visited) {
        if (depth > 24 || ++visited[0] > 300) return;
        CharSequence text = node.getText();
        int start = node.getTextSelectionStart(), end = node.getTextSelectionEnd();
        CharSequence description = node.getContentDescription();
        if (text != null || description != null || start != -1 || end != -1) writer.println(node.getClassName()
            + " textLength=" + (text == null ? 0 : text.length()) + " range=" + start + "~" + end
            + " descLength=" + (description == null ? 0 : description.length())
            + " id=" + node.getViewIdResourceName() + " selected=" + node.isSelected()
            + " sensitive=" + node.isAccessibilityDataSensitive());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { dumpNodes(child, writer, depth + 1, visited); } finally { child.recycle(); }
        }
    }
    @Override public void onDestroy() {
        if (menuBridge != null) menuBridge.cancel();
        hide();
        handler.removeCallbacks(scanSelection);
        if (receiverRegistered) unregisterReceiver(screenOff);
        if (active == this) active = null;
        super.onDestroy();
    }
}
