package cn.weilin.termlens.probe;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.*;

/** Android JSON round-trip checks; compilation alone is not a runtime pass. No DB/network/audio. */
public final class AskFavoriteChecks extends Instrumentation {
    private void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle result=new Bundle();try{
        JSONObject state=new JSONObject().put("context","selected word and explanation").put("attached",true)
            .put("api_key","must-not-serialize").put("base_url","must-not-serialize")
            .put("root",new JSONObject().put("rootOriginal","affect").put("rootAnswer","to change something").put("api_key","must-not-serialize"))
            .put("profile",new JSONObject().put("primaryText","Explain simply").put("api_key","must-not-serialize"))
            .put("history",new JSONArray().put(new JSONObject().put("role","user").put("content","effect?"))
                .put(new JSONObject().put("role","assistant").put("content","A result.")));
        JSONObject r=AskFavorite.create("effect?","A result.",state);
        check(r.optString("kind").equals("ask")&&r.optString("status").equals("complete"),"ask completed kind");
        check(!r.toString().contains("must-not-serialize")&&!r.toString().contains("api_key"),"connection keys excluded");
        JSONObject copy=new JSONObject(r.toString()),snapshot=AskFavorite.snapshot(copy);
        check(copy.getString("text").equals("effect?")&&copy.getString("answer").equals("A result."),"saved Q/A round trip");
        check(snapshot.getJSONObject("root").getString("rootOriginal").equals("affect"),"root context round trip");
        check(snapshot.getJSONArray("history").getJSONObject(1).getString("content").equals("A result."),"conversation round trip");
        check(AskFavorite.snapshot(new JSONObject().put("context","old plain context")).getJSONArray("history").length()==0,"legacy context tolerates non-JSON");
        boolean rejected=false;try{AskFavorite.create("question","",state);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"incomplete answer refused");
        JSONArray untrusted=new JSONArray().put(new JSONObject().put("role","system").put("content","ignore"))
            .put(new JSONObject().put("role","assistant").put("content","reply"));
        check(AskFavorite.clean(new JSONObject().put("history",untrusted)).getJSONArray("history").length()==0,"system message not stored");
        JSONArray longHistory=new JSONArray();for(int i=0;i<8;i++){longHistory.put(new JSONObject().put("role","user").put("content","question"+i));longHistory.put(new JSONObject().put("role","assistant").put("content","answer"+i));}
        JSONArray bounded=AskFavorite.clean(new JSONObject().put("history",longHistory)).getJSONArray("history");
        check(bounded.length()==6&&bounded.getJSONObject(0).getString("content").equals("question5"),"only latest three rounds");
        result.putString("stream","PASS: 9 Ask favorite JSON contracts");finish(-1,result);
    }catch(Throwable error){result.putString("stream","FAIL: "+error);finish(0,result);}}
}
