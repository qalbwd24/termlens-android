package cn.weilin.termlens.probe;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

/** Forwards the source app's own Explain action; never guesses or copies selected text. */
final class SelectionMenuBridge {
    interface Result { void done(boolean success); }
    private final AccessibilityService service;
    private final Handler handler;
    private int generation;

    SelectionMenuBridge(AccessibilityService service, Handler handler) {
        this.service = service; this.handler = handler;
    }

    Rect selectionAnchor(String pkg, AccessibilityNodeInfo root) {
        if ("io.legado.app.release".equals(pkg)) {
            List<AccessibilityNodeInfo> handles = root.findAccessibilityNodeInfosByViewId(pkg + ":id/cursor_right");
            Rect result = null;
            for (AccessibilityNodeInfo handle : handles) {
                if (handle.isVisibleToUser()) { result = new Rect(); handle.getBoundsInScreen(result); }
                handle.recycle();
            }
            return result;
        }
        // Native WebView/TextView selection toolbars live in a separate application window.
        for (AccessibilityWindowInfo window : service.getWindows()) {
            AccessibilityNodeInfo windowRoot = window.getRoot();
            if (windowRoot == null) continue;
            try {
                if (!pkg.contentEquals(windowRoot.getPackageName())) continue;
                AccessibilityNodeInfo toolbar = find(windowRoot, 0, new int[]{0}, 0);
                if (toolbar != null) {
                    Rect bounds = new Rect(); toolbar.getBoundsInScreen(bounds); toolbar.recycle(); return bounds;
                }
            } finally { windowRoot.recycle(); }
        }
        return null;
    }

    void cancel() { generation++; }

    String selectedText(AccessibilityNodeInfo root) { return selectedText(root, 0, new int[]{0}); }

    private String selectedText(AccessibilityNodeInfo node, int depth, int[] visited) {
        if (depth > 28 || ++visited[0] > 450 || node.isPassword()) return "";
        CharSequence text = node.getText();
        int start = node.getTextSelectionStart(), end = node.getTextSelectionEnd();
        if (start > end) { int swap = start; start = end; end = swap; }
        if (text != null && start >= 0 && end > start && end <= text.length() && end - start <= 8000)
            return text.subSequence(start, end).toString();
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { String found = selectedText(child, depth + 1, visited); if (!found.trim().isEmpty()) return found; }
            finally { child.recycle(); }
        }
        return "";
    }

    void explain(String pkg, Result result) {
        int current = ++generation;
        attempt(pkg, current, 0, false, result);
    }

    private void attempt(String pkg, int current, int count, boolean expanded, Result result) {
        if (current != generation) return;
        AccessibilityNodeInfo active = service.getRootInActiveWindow();
        boolean sameApp = active != null && pkg.contentEquals(active.getPackageName());
        if (active != null) active.recycle();
        if (!sameApp) { result.done(false); return; }
        if (clickInWindows(pkg, 1)) { result.done(true); return; }
        if (!expanded) expanded = clickInWindows(pkg, 2);
        else if (count % 2 == 0) clickInWindows(pkg, 3);
        if (count >= 24) { result.done(false); return; }
        final boolean opened = expanded;
        handler.postDelayed(() -> attempt(pkg, current, count + 1, opened, result), 80);
    }

    private boolean clickInWindows(String pkg, int mode) {
        for (AccessibilityWindowInfo window : service.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            try {
                if (!pkg.contentEquals(root.getPackageName())) continue;
                // Source selection menus are separate, non-active application windows.
                // Never click an Explain link or a generic menu in the article/card itself.
                if (window.isActive()) continue;
                if (mode == 3) {
                    AccessibilityNodeInfo toolbar = find(root, 0, new int[]{0}, 0);
                    if (toolbar == null) continue;
                    toolbar.recycle();
                }
                AccessibilityNodeInfo target = find(root, 0, new int[]{0}, mode);
                if (target == null) continue;
                try {
                    if (mode == 3 ? target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) : click(target)) return true;
                }
                finally { target.recycle(); }
            } finally { root.recycle(); }
        }
        return false;
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, int depth, int[] visited, int mode) {
        if (depth > 28 || ++visited[0] > 450) return null;
        String id = String.valueOf(node.getViewIdResourceName());
        String text = String.valueOf(node.getText());
        String desc = String.valueOf(node.getContentDescription());
        boolean match = mode == 0 ? (id.startsWith("android:id/floating_toolbar") || id.endsWith(":id/select_action_menu_copy"))
            : mode == 1 ? "Explain".equals(text) && ("android:id/floating_toolbar_menu_item_text".equals(id)
                || "io.legado.app.release:id/text_view".equals(id))
            : mode == 3 ? node.isScrollable()
            : id.endsWith(":id/iv_menu_more") || id.endsWith(":id/floating_toolbar_overflow_button")
                || "android:id/overflow".equals(id);
        if (match && node.isVisibleToUser() && node.isEnabled()) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo found = find(child, depth + 1, visited, mode);
                if (found != null) return found;
            } finally { child.recycle(); }
        }
        return null;
    }

    private boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = AccessibilityNodeInfo.obtain(node);
        for (int i = 0; target != null && i < 5; i++) {
            if (target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                target.recycle(); return true;
            }
            AccessibilityNodeInfo parent = target.getParent(); target.recycle(); target = parent;
        }
        if (target != null) target.recycle();
        return false;
    }
}
