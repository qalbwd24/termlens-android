package cn.weilin.termlens.probe;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Credential-free snapshot of the active desktop prompt pair and request rules. */
final class DesktopPrompts {
    private static JSONObject snapshot;
    static synchronized JSONObject data(Context context) {
        if (snapshot != null) return snapshot;
        try (InputStream input = context.getResources().openRawResource(R.raw.desktop_prompts)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            snapshot = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            return snapshot;
        } catch (Exception error) { throw new IllegalStateException("电脑端提示词资源无法读取", error); }
    }
    static String value(Context c, String key) { return data(c).optString(key, ""); }
    static int maxTokens(Context c) { return data(c).optInt("maxTokens", 3000); }
    static synchronized void migrate(Context c) {
        SharedPreferences prefs = TermLensPrefs.open(c);
        if (prefs.getInt("desktop_prompt_revision", 0) >= 1) return;
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : new String[]{TermLensPrefs.PRIMARY_NAME, TermLensPrefs.PRIMARY_PROMPT,
            TermLensPrefs.SECONDARY_NAME, TermLensPrefs.SECONDARY_PROMPT}) {
            if (prefs.contains(key)) edit.putString("before_desktop_" + key, prefs.getString(key, ""));
        }
        edit.putString(TermLensPrefs.PRIMARY_NAME, value(c, "primaryName"));
        edit.putString(TermLensPrefs.PRIMARY_PROMPT, value(c, "primaryPrompt"));
        edit.putString(TermLensPrefs.SECONDARY_NAME, value(c, "secondaryName"));
        edit.putString(TermLensPrefs.SECONDARY_PROMPT, value(c, "secondaryPrompt"));
        edit.putInt("desktop_prompt_revision", 1).commit();
    }
    static String system(Context c, String preference, boolean contextual) {
        return preference.trim() + "\n\n" + value(c, "appRules").trim()
            + (contextual ? "\n\n" + value(c, "contextInstructions").trim() : "");
    }
}
