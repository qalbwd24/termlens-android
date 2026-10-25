package cn.weilin.termlens.probe;

/** One-time upgrade policy, independent of Android so its preservation rules are testable. */
final class SpeechDefaults {
    static final String LEGACY_VOICE = "en-US-AndrewMultilingualNeural";
    static final String VOICE = "en-US-AndrewNeural";
    static String[] migrate(String voice, String rate) {
        if (LEGACY_VOICE.equals(voice) && "0".equals(rate)) return new String[]{VOICE, "10"};
        return new String[]{voice, rate};
    }
}
