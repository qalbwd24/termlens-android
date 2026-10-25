package cn.weilin.termlens.probe;

import org.json.JSONArray;
import org.json.JSONObject;

/** Explicitly saved, completed Ask round. Only learning content is serialized, never connection settings. */
final class AskFavorite {
    static JSONObject create(String question, String answer, JSONObject state) {
        if(question == null || question.trim().isEmpty() || answer == null || answer.trim().isEmpty())
            throw new IllegalArgumentException("只能收藏已完成的问答");
        JSONObject record=new JSONObject();
        AppData.put(record,"id",AppData.id());AppData.put(record,"kind","ask");AppData.put(record,"source","ask-favorite");
        AppData.put(record,"text",LookupInput.clip(question.trim(),AskPrompt.QUESTION_LIMIT,null));
        AppData.put(record,"answer",LookupInput.clip(answer,32000,null));
        AppData.put(record,"source_text","");AppData.put(record,"context",clean(state).toString());
        AppData.put(record,"model","");AppData.put(record,"prompt",AskPrompt.SYSTEM);AppData.put(record,"created",AppData.now());
        AppData.put(record,"status","complete");AppData.put(record,"error","");AppData.put(record,"term_key",AppData.key(record.optString("text")));
        return record;
    }
    static JSONObject snapshot(JSONObject record) { return clean(AppData.context(record.optString("context"))); }
    private static JSONObject child(JSONObject value,String key){JSONObject result=value.optJSONObject(key);return result==null?AppData.context(value.optString(key)):result;}
    static JSONObject clean(JSONObject state) {
        if(state==null)state=new JSONObject();JSONObject out=new JSONObject();AppData.put(out,"version",1);
        AppData.put(out,"context",LookupInput.clip(state.optString("context"),AskPrompt.CONTEXT_LIMIT,null));
        AppData.put(out,"attached",state.optBoolean("attached",false));
        JSONObject root=child(state,"root"),safeRoot=new JSONObject();
        AppData.put(safeRoot,"rootOriginal",LookupInput.clip(root.optString("rootOriginal"),3000,null));
        AppData.put(safeRoot,"rootAnswer",LookupInput.clip(root.optString("rootAnswer"),5000,null));
        AppData.put(safeRoot,"rootSourceId",AppData.safeId(root.optString("rootSourceId"))?root.optString("rootSourceId"):"");AppData.put(out,"root",safeRoot);
        JSONObject profile=child(state,"profile"),safeProfile=new JSONObject();
        for(String key:new String[]{"id","name","templateId"})AppData.put(safeProfile,key,LookupInput.clip(profile.optString(key),200,null));
        for(String key:new String[]{"primaryText","secondaryText"})AppData.put(safeProfile,key,LookupInput.clip(profile.optString(key),12000,null));
        AppData.put(safeProfile,"version",1);AppData.put(safeProfile,"legacyFallback",profile.optBoolean("legacyFallback",false));AppData.put(out,"profile",safeProfile);
        JSONArray history=state.optJSONArray("history");if(history==null)try{history=new JSONArray(state.optString("history","[]"));}catch(Exception ignored){history=new JSONArray();}
        JSONArray kept=new JSONArray();
        // Retain complete user/assistant pairs only, newest three, never system/tool messages.
        for(int i=Math.max(0,history.length()-6);i+1<history.length();i+=2){JSONObject q=history.optJSONObject(i),a=history.optJSONObject(i+1);
            if(q==null||a==null||!"user".equals(q.optString("role"))||!"assistant".equals(a.optString("role")))continue;
            String question=LookupInput.clip(q.optString("content"),2000,null),answer=LookupInput.clip(a.optString("content"),6000,null);
            kept.put(AppData.put(AppData.put(new JSONObject(),"role","user"),"content",question));kept.put(AppData.put(AppData.put(new JSONObject(),"role","assistant"),"content",answer));
        }
        while(kept.toString().length()>8000){JSONArray rest=new JSONArray();for(int i=2;i<kept.length();i++)rest.put(kept.optJSONObject(i));kept=rest;}
        AppData.put(out,"history",kept);return out;
    }
}
