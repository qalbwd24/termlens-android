package cn.weilin.termlens.probe;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Small, device-local settings store. API keys are never included in exports. */
final class TermLensPrefs {
    private static final String NAME = "termlens";
    static final String RESIDENT = "resident_button";
    static final String NATIVE_MENU = "keep_native_menu";
    static final String DEFAULT_SECONDARY = "default_secondary";
    static final String MAIN_TERM = "main_term";
    static final String BASE_URL = "base_url";
    static final String MODEL = "model";
    static final String API_KEY = "api_key";
    static final String BUTTON_POSITION = "button_position";
    static final String BUTTON_TRANSLUCENT = "button_translucent";
    static final String BUTTON_LOCKED = "button_locked";
    static final String HIDE_ON_KEYBOARD = "hide_on_keyboard";
    static final String PRIMARY_NAME = "primary_name";
    static final String PRIMARY_PROMPT = "primary_prompt";
    static final String SECONDARY_NAME = "secondary_name";
    static final String SECONDARY_PROMPT = "secondary_prompt";

    static SharedPreferences open(Context context) { return context.getSharedPreferences(NAME, Context.MODE_PRIVATE); }
    static boolean resident(Context c) { return open(c).getBoolean(RESIDENT, true); }
    static void enableDirectDefaults(Context c) {
        if (!open(c).getBoolean("direct_button_v3_initialized", false)) {
            open(c).edit().putBoolean(RESIDENT, true).putBoolean("direct_button_v3_initialized", true).apply();
        }
    }
    static boolean keepNativeMenu(Context c) { return open(c).getBoolean(NATIVE_MENU, true); }
    static boolean defaultSecondary(Context c) { return open(c).getBoolean(DEFAULT_SECONDARY, true); }
    static String mainTerm(Context c) { return open(c).getString(MAIN_TERM, "").trim(); }
    static String baseUrl(Context c) { return open(c).getString(BASE_URL, "https://api.deepseek.com").trim(); }
    static String model(Context c) { return open(c).getString(MODEL, "deepseek-chat").trim(); }
    static String apiKey(Context c) { return open(c).getString(API_KEY, "").trim(); }
    static String buttonPosition(Context c) { return open(c).getString(BUTTON_POSITION, "auto"); }
    static boolean buttonTranslucent(Context c) { return open(c).getBoolean(BUTTON_TRANSLUCENT, true); }
    static boolean buttonLocked(Context c) { return open(c).getBoolean(BUTTON_LOCKED, false); }
    static String speechVoice(Context c){return open(c).getString("speech_voice","en-US-AndrewMultilingualNeural");}
    static int speechRate(Context c){try{return Math.max(-50,Math.min(50,Integer.parseInt(open(c).getString("speech_rate","0"))));}catch(Exception e){return 0;}}
    static boolean hideOnKeyboard(Context c) { return open(c).getBoolean(HIDE_ON_KEYBOARD, true); }
    static String primaryName(Context c) { DesktopPrompts.migrate(c); return open(c).getString(PRIMARY_NAME, DesktopPrompts.value(c, "primaryName")); }
    static String primaryPrompt(Context c) { DesktopPrompts.migrate(c); return open(c).getString(PRIMARY_PROMPT, DesktopPrompts.value(c, "primaryPrompt")); }
    static String secondaryName(Context c) { DesktopPrompts.migrate(c); return open(c).getString(SECONDARY_NAME, DesktopPrompts.value(c, "secondaryName")); }
    static String secondaryPrompt(Context c) { DesktopPrompts.migrate(c); return open(c).getString(SECONDARY_PROMPT, DesktopPrompts.value(c, "secondaryPrompt")); }

    static void put(Context c, String key, Object value) {
        SharedPreferences.Editor e = open(c).edit();
        if (value instanceof Boolean) e.putBoolean(key, (Boolean) value);
        else e.putString(key, String.valueOf(value));
        e.apply();
    }

    static JSONObject exportPrompts(Context c) {
        JSONObject json = new JSONObject();
        try {
            json.put("format", "termlens-prompt-library-v1");
            json.put("primaryName", primaryName(c));
            json.put("primaryPrompt", primaryPrompt(c));
            json.put("secondaryName", secondaryName(c));
            json.put("secondaryPrompt", secondaryPrompt(c));
            json.put("defaultSecondary", defaultSecondary(c));
        } catch (Exception ignored) { }
        return json;
    }

    static boolean importPrompts(Context c, JSONObject json) {
        if (json == null || !"termlens-prompt-library-v1".equals(json.optString("format"))) return false;
        String primary = json.optString("primaryPrompt", "").trim();
        String secondary = json.optString("secondaryPrompt", "").trim();
        if (primary.isEmpty() || secondary.isEmpty()) return false;
        SharedPreferences.Editor e = open(c).edit();
        e.putString(PRIMARY_NAME, json.optString("primaryName", "聊天与日常阅读"));
        e.putString(PRIMARY_PROMPT, primary);
        e.putString(SECONDARY_NAME, json.optString("secondaryName", "二级解释"));
        e.putString(SECONDARY_PROMPT, secondary);
        e.putBoolean(DEFAULT_SECONDARY, json.optBoolean("defaultSecondary", true));
        e.apply();
        return true;
    }

    static String defaultPrimary() {
        return "Help a Chinese learner understand the selected English word, phrase, or sentence. " +
            "Give a direct, natural translation or easier-English restatement first. Preserve context, " +
            "tone, negation, uncertainty, and important technical meaning. Then explain only the wording " +
            "needed to understand it. Keep the answer compact. Use Markdown when it improves readability. " +
            "Do not add unrelated meanings, long introductions, or follow-up questions.";
    }

    static String defaultSecondary() {
        return "Explain the newly selected text using the immediate parent result as context. " +
            "Return a direct easier-English translation first, then a short explanation below it. " +
            "Preserve the grammatical role, tense, tone, negation, uncertainty, and technical meaning. " +
            "Do not repeat the parent passage or add unrelated examples. Keep the response concise and use Markdown when helpful.";
    }
}
