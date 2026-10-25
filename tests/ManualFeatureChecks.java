package cn.weilin.termlens.probe;

/** Host-JVM checks; no Android runtime, credentials, LLM or speech network requests. */
public final class ManualFeatureChecks {
    private static int count;
    private static void check(boolean ok, String name) { if(!ok) throw new AssertionError(name); count++; }
    public static void main(String[] args) {
        String[] migrated=SpeechDefaults.migrate(SpeechDefaults.LEGACY_VOICE,"0");
        check(migrated[0].equals("en-US-AndrewNeural") && migrated[1].equals("10"), "old default upgrades to US Andrew +10%");
        migrated=SpeechDefaults.migrate("en-GB-SoniaNeural","0");
        check(migrated[0].equals("en-GB-SoniaNeural") && migrated[1].equals("0"), "custom voice preserved");
        migrated=SpeechDefaults.migrate(SpeechDefaults.LEGACY_VOICE,"20");
        check(migrated[0].equals(SpeechDefaults.LEGACY_VOICE) && migrated[1].equals("20"), "custom rate preserved");
        migrated=SpeechDefaults.migrate(SpeechDefaults.VOICE,"-10");
        check(migrated[0].equals(SpeechDefaults.VOICE) && migrated[1].equals("-10"), "later user override preserved");
        String context=LookupInput.build("root sentence","root answer","parent word","parent answer","child");
        check(context.contains("\"rootOriginal\":\"root sentence\"") && context.contains("\"parentAnswer\":\"parent answer\""), "root and parent context retained");
        check(context.endsWith("SELECTED TEXT:\nchild"), "child query distinct");
        check(LookupInput.parts("translation\n---\nexplanation")[1].equals("explanation"), "existing answer partition preserved");
        check(LookupInput.build("a\"b","x\ny","p","q","z").contains("a\\\"b"), "context escaping preserved");
        System.out.println("PASS: " + count + " manual-feature migration/context host checks");
    }
}
