package cn.weilin.termlens.probe;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Stable desktop preset IDs + immutable per-query profiles. Future source routing stays outside storage/network. */
final class SceneProfiles {
    private static final String PREF="scene_library_v1";
    static JSONObject library(Context c) {
        String saved=TermLensPrefs.open(c).getString(PREF,"");
        if(!saved.isEmpty())return AppData.object(saved);
        try(java.io.InputStream input=c.getResources().openRawResource(R.raw.scene_library);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
            JSONObject library=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            // Preserve any edits already made in the mobile 0.4.1 settings.
            for(int i=0;i<library.getJSONArray("PromptPresets").length();i++) {JSONObject p=library.getJSONArray("PromptPresets").getJSONObject(i);
                if(p.getString("Id").equals(library.getString("ActivePromptPresetId"))){AppData.put(p,"Text",TermLensPrefs.primaryPrompt(c));AppData.put(p,"SecondaryText",TermLensPrefs.secondaryPrompt(c));AppData.put(p,"Name",TermLensPrefs.primaryName(c));}}
            TermLensPrefs.put(c,PREF,library.toString());return library;
        }catch(Exception e){throw new IllegalStateException("无法读取场景方案",e);}
    }
    static JSONObject active(Context c) {
        JSONObject lib=library(c);JSONArray presets=lib.optJSONArray("PromptPresets");
        for(int i=0;i<presets.length();i++){JSONObject p=presets.optJSONObject(i);if(p.optString("Id").equals(lib.optString("ActivePromptPresetId")))return p;}
        throw new IllegalStateException("当前场景不存在");
    }
    static void updateCurrent(Context c) {
        JSONObject lib=library(c);JSONArray presets=lib.optJSONArray("PromptPresets");
        for(int i=0;i<presets.length();i++){JSONObject p=presets.optJSONObject(i);if(p.optString("Id").equals(lib.optString("ActivePromptPresetId"))){AppData.put(p,"Name",TermLensPrefs.primaryName(c));AppData.put(p,"Text",TermLensPrefs.primaryPrompt(c));AppData.put(p,"SecondaryText",TermLensPrefs.secondaryPrompt(c));}}
        TermLensPrefs.put(c,PREF,lib.toString());
    }
    static void select(Context c,String id) {
        JSONObject lib=library(c);JSONArray presets=lib.optJSONArray("PromptPresets");
        for(int i=0;i<presets.length();i++){JSONObject p=presets.optJSONObject(i);if(p.optString("Id").equals(id)){
            AppData.put(lib,"ActivePromptPresetId",id);TermLensPrefs.put(c,PREF,lib.toString());
            TermLensPrefs.open(c).edit().putString(TermLensPrefs.PRIMARY_NAME,p.optString("Name")).putString(TermLensPrefs.SECONDARY_NAME,p.optString("Name")+" · 词义")
                .putString(TermLensPrefs.PRIMARY_PROMPT,p.optString("Text")).putString(TermLensPrefs.SECONDARY_PROMPT,p.optString("SecondaryText")).apply();return;}}
        throw new IllegalArgumentException("场景不存在");
    }
    static JSONObject snapshot(Context c) {
        updateCurrent(c);JSONObject p=active(c),result=new JSONObject();AppData.put(result,"version",1);
        AppData.put(result,"id",p.optString("Id"));AppData.put(result,"name",p.optString("Name"));AppData.put(result,"primaryText",p.optString("Text"));AppData.put(result,"secondaryText",p.optString("SecondaryText"));AppData.put(result,"templateId",p.optString("TemplateId"));AppData.put(result,"legacyFallback",false);return result;
    }
    static void merge(Context c,JSONObject incoming) throws Exception {
        validate(incoming);
        JSONArray input=incoming.getJSONArray("PromptPresets");if(input.length()>100)throw new Exception("场景数量超过 100");
        JSONObject lib=library(c);JSONArray output=lib.getJSONArray("PromptPresets");java.util.Set<String> ids=new java.util.HashSet<>(),names=new java.util.HashSet<>(),seen=new java.util.HashSet<>();
        for(int i=0;i<output.length();i++){ids.add(output.getJSONObject(i).getString("Id"));names.add(output.getJSONObject(i).getString("Name").toLowerCase(java.util.Locale.ROOT));}
        for(int i=0;i<input.length();i++){JSONObject p=input.getJSONObject(i);String id=p.getString("Id"),name=p.getString("Name");
            if(!AppData.safeId(id)||!seen.add(id)||name.trim().isEmpty()||p.getString("Text").trim().isEmpty()||p.getString("SecondaryText").trim().isEmpty())throw new Exception("场景方案无效");
            if(!ids.contains(id)&&!names.contains(name.toLowerCase(java.util.Locale.ROOT))){if(output.length()>=100)throw new Exception("场景数量超过 100");output.put(p);ids.add(id);names.add(name.toLowerCase(java.util.Locale.ROOT));}}
        TermLensPrefs.put(c,PREF,lib.toString());
    }
    static void validate(JSONObject incoming) throws Exception {
        JSONArray profiles=incoming.getJSONArray("PromptPresets");if(profiles.length()==0||profiles.length()>100)throw new Exception("场景数量无效");java.util.Set<String> ids=new java.util.HashSet<>(),names=new java.util.HashSet<>();
        for(int i=0;i<profiles.length();i++){JSONObject p=profiles.getJSONObject(i);if(!AppData.safeId(p.getString("Id"))||!ids.add(p.getString("Id"))||!names.add(p.getString("Name").trim().toLowerCase(java.util.Locale.ROOT))||p.getString("Name").trim().isEmpty()||p.getString("Text").trim().isEmpty()||p.getString("Text").length()>200000||p.getString("SecondaryText").trim().isEmpty()||p.getString("SecondaryText").length()>200000)throw new Exception("场景方案无效");}
        if(!ids.contains(incoming.getString("ActivePromptPresetId")))throw new Exception("活动场景不存在");
    }
}
