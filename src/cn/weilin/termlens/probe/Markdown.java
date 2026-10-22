package cn.weilin.termlens.probe;

import android.text.TextUtils;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts the safe Markdown subset used by TermLens into TextView-compatible HTML. */
final class Markdown {
    private Markdown() { }
    static String toHtml(String source) {
        if (TextUtils.isEmpty(source)) return "";
        String s = source.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        s = s.replace("\r\n", "\n").replace("\r", "\n");
        s = s.replaceAll("(?m)^\\s*---+\\s*$", "<hr>");
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        s = s.replaceAll("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)", "<i>$1</i>");
        s = s.replaceAll("`([^`]+)`", "<font face=\"monospace\">$1</font>");
        String[] lines = s.split("\\n", -1);
        StringBuilder out = new StringBuilder();
        boolean list = false;
        boolean orderedList = false;
        for (String line : lines) {
            Matcher bullet = Pattern.compile("^\\s*[-*]\\s+(.+)$").matcher(line);
            Matcher ordered = Pattern.compile("^\\s*\\d+[.)]\\s+(.+)$").matcher(line);
            if (bullet.matches() || ordered.matches()) {
                if (!list) { orderedList = ordered.matches(); out.append(orderedList ? "<ol>" : "<ul>"); list = true; }
                out.append("<li>").append(bullet.matches() ? bullet.group(1) : ordered.group(1)).append("</li>");
            } else {
                if (list) { out.append(orderedList ? "</ol>" : "</ul>"); list = false; }
                if (!line.trim().isEmpty()) out.append(line).append("<br>");
                else out.append("<br>");
            }
        }
        if (list) out.append(orderedList ? "</ol>" : "</ul>");
        return out.toString();
    }
}
