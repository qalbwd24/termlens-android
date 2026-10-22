package cn.weilin.termlens.probe;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/** Desktop-compatible tables and termlens-history v2 exchange. Never exports connection secrets. */
final class AppData extends SQLiteOpenHelper {
    private static AppData instance;
    private static final String[] RECORD = {"id","kind","source","text","answer","source_text","context","model","prompt","created","status","error","term_key"};
    private static final String[] EVENT = {"id","text","normalized_key","created","kind","source","scene_id","scene_name"};
    static synchronized AppData get(Context c) { if (instance == null) instance = new AppData(c.getApplicationContext()); return instance; }
    private AppData(Context c) { super(c, "history.sqlite3", null, 1); setWriteAheadLoggingEnabled(true); }
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE queries(id TEXT PRIMARY KEY,kind TEXT NOT NULL,source TEXT NOT NULL,text TEXT NOT NULL,answer TEXT NOT NULL,source_text TEXT NOT NULL,context TEXT NOT NULL,model TEXT NOT NULL,prompt TEXT NOT NULL,created TEXT NOT NULL,status TEXT NOT NULL,error TEXT NOT NULL,term_key TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX queries_kind_created ON queries(kind,created DESC)");
        db.execSQL("CREATE TABLE favorites(id TEXT PRIMARY KEY,favorite_key TEXT UNIQUE NOT NULL,kind TEXT NOT NULL,term TEXT NOT NULL,saved INTEGER NOT NULL,record TEXT NOT NULL)");
        db.execSQL("CREATE TABLE selection_events(id TEXT PRIMARY KEY,text TEXT NOT NULL,normalized_key TEXT NOT NULL,created TEXT NOT NULL,kind TEXT NOT NULL,source TEXT NOT NULL,scene_id TEXT NOT NULL,scene_name TEXT NOT NULL)");
        db.execSQL("CREATE INDEX selection_events_created ON selection_events(created,id)");
        db.execSQL("CREATE INDEX selection_events_key ON selection_events(kind,normalized_key)");
        db.execSQL("CREATE TABLE selection_ledger_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        db.execSQL("INSERT INTO selection_ledger_meta VALUES('backfill-version','1')");
        db.execSQL("CREATE TABLE terms(term_key TEXT PRIMARY KEY,term TEXT NOT NULL,count INTEGER NOT NULL,last_seen TEXT NOT NULL,saved INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE lookups(id TEXT PRIMARY KEY,term_key TEXT NOT NULL,term TEXT NOT NULL,answer TEXT NOT NULL,created TEXT NOT NULL,model TEXT NOT NULL,prompt TEXT NOT NULL,context TEXT NOT NULL)");
        db.execSQL("CREATE TABLE query_links(legacy_id TEXT PRIMARY KEY,query_id TEXT NOT NULL)");
        db.execSQL("CREATE TABLE teacher_answers(id TEXT PRIMARY KEY,question TEXT NOT NULL,answer TEXT NOT NULL,context TEXT NOT NULL,prompt TEXT NOT NULL,model TEXT NOT NULL,created TEXT NOT NULL)");
        db.execSQL("CREATE TABLE refinements(id TEXT PRIMARY KEY,question TEXT NOT NULL,answer TEXT NOT NULL,context TEXT NOT NULL,prompt TEXT NOT NULL,model TEXT NOT NULL,created TEXT NOT NULL)");
    }
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }
    static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    static String now() { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.format(new Date()); }
    static String key(String value) { return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    static boolean safeId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,128}"); }
    static JSONObject object(String text) { try { return new JSONObject(text); } catch (Exception e) { throw new IllegalArgumentException("数据格式无效", e); } }
    static JSONObject context(String text){try{return new JSONObject(text);}catch(Exception ignored){return new JSONObject();}}
    static JSONObject put(JSONObject value, String key, Object item) { try { value.put(key, item); return value; } catch (Exception e) { throw new IllegalArgumentException(e); } }
    private static ContentValues values(JSONObject json, String[] keys) {
        ContentValues values = new ContentValues(); for (String key : keys) values.put(key, json.optString(key, "")); return values;
    }
    private static JSONObject row(Cursor c) {
        JSONObject result = new JSONObject(); for (int i = 0; i < c.getColumnCount(); i++) put(result, c.getColumnName(i), c.getString(i)); return result;
    }
    synchronized JSONObject find(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM queries WHERE id=?", new String[]{id})) { return c.moveToFirst() ? row(c) : null; }
    }
    synchronized JSONObject favorite(String kind, String text) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT record FROM favorites WHERE favorite_key=?", new String[]{kind + ":" + key(text)})) { return c.moveToFirst() ? object(c.getString(0)) : null; }
    }
    synchronized boolean saved(JSONObject record) {
        JSONObject f = favorite(record.optString("kind"), record.optString("text")); return f != null && f.optBoolean("Saved");
    }
    synchronized JSONObject begin(String text, boolean secondary, String sourceText, String context, String model, String prompt) {
        JSONObject r = new JSONObject();
        String[] data = {id(), secondary ? "quick" : "explain", secondary ? "release-context" : "release-selection", text, "", sourceText, context, model, prompt, now(), "pending", "", key(text)};
        for (int i = 0; i < RECORD.length; i++) put(r, RECORD[i], data[i]);
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            db.insertOrThrow("queries", null, values(r, RECORD));
            JSONObject profile = object(context).optJSONObject("promptProfile");
            JSONObject e = new JSONObject(); String[] event = {data[0],text,key(text),data[9],data[1],data[2],profile == null ? "" : profile.optString("id"),profile == null ? "" : profile.optString("name")};
            for (int i = 0; i < EVENT.length; i++) put(e, EVENT[i], event[i]);
            db.insertOrThrow("selection_events", null, values(e, EVENT));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return r;
    }
    synchronized void finish(JSONObject record, String answer, String status, String error) {
        put(record,"answer",answer); put(record,"status",status); put(record,"error",error == null ? "" : error);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            db.update("queries", values(record, RECORD), "id=?", new String[]{record.optString("id")});
            if(status.equals("complete"))legacy(record);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private void legacy(JSONObject r){
        if(!r.optString("kind").equals("explain")||!r.optString("status").equals("complete"))return;
        SQLiteDatabase db=getWritableDatabase();String key=key(r.optString("text"));
        db.execSQL("INSERT OR REPLACE INTO lookups VALUES(?,?,?,?,?,?,?,?)",new Object[]{r.optString("id"),key,r.optString("text"),r.optString("answer"),r.optString("created"),r.optString("model"),r.optString("prompt"),r.optString("context")});
        db.execSQL("INSERT OR IGNORE INTO terms VALUES(?,?,0,?,0)",new Object[]{key,r.optString("text"),r.optString("created")});
        db.execSQL("UPDATE terms SET count=(SELECT COUNT(*) FROM lookups WHERE term_key=?),last_seen=(SELECT MAX(created) FROM lookups WHERE term_key=?) WHERE term_key=?",new Object[]{key,key,key});
    }
    synchronized JSONObject setSaved(JSONObject r, boolean saved) {
        JSONObject f = favorite(r.optString("kind"), r.optString("text"));
        if (f == null) {
            f = new JSONObject(); put(f,"Id",id()); put(f,"Created",now()); put(f,"Kind",r.optString("kind")); put(f,"Term",r.optString("text"));
            put(f,"WordAudio",new JSONArray()); put(f,"ExplanationAudio",new JSONArray()); put(f,"Voice",""); put(f,"AudioState","pending"); put(f,"AudioError","");
        }
        if (saved) {
            boolean changed = !f.optString("Explanation").equals(r.optString("answer")) || !f.optString("QueryId").equals(r.optString("id"));
            put(f,"QueryId",r.optString("id")); put(f,"Term",r.optString("text")); put(f,"Explanation",r.optString("answer"));
            put(f,"SourceText",r.optString("source_text")); put(f,"Context",r.optString("context"));
            if (changed) { put(f,"WordAudio",new JSONArray()); put(f,"ExplanationAudio",new JSONArray()); put(f,"AudioState","pending"); }
        }
        put(f,"Saved",saved); put(f,"Updated",now()); storeFavorite(f);
        if(r.optString("kind").equals("explain"))getWritableDatabase().execSQL("UPDATE terms SET saved=? WHERE term_key=?",new Object[]{saved?1:0,key(r.optString("text"))});return f;
    }
    synchronized void storeFavorite(JSONObject f) {
        ContentValues v = new ContentValues(); v.put("id",f.optString("Id")); v.put("favorite_key",f.optString("Kind")+":"+key(f.optString("Term")));
        v.put("kind",f.optString("Kind")); v.put("term",f.optString("Term")); v.put("saved",f.optBoolean("Saved") ? 1 : 0); v.put("record",f.toString());
        getWritableDatabase().insertWithOnConflict("favorites",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized JSONArray list(boolean savedOnly, String search) {
        String like = "%" + search.replace("!","!!").replace("%","!%").replace("_","!_") + "%";
        String sql = "SELECT q.*,COALESCE(f.saved,0) AS saved FROM queries q LEFT JOIN favorites f ON f.favorite_key=q.kind||':'||q.term_key WHERE (q.text LIKE ? ESCAPE '!' OR q.answer LIKE ? ESCAPE '!')" + (savedOnly ? " AND f.saved=1" : "") + " ORDER BY q.created DESC LIMIT 500";
        JSONArray result = new JSONArray(); try (Cursor c = getReadableDatabase().rawQuery(sql,new String[]{like,like})) { while(c.moveToNext()) result.put(row(c)); } return result;
    }
    synchronized JSONObject export(boolean savedOnly) {
        JSONObject result = new JSONObject(); JSONArray records = new JSONArray(), favorites = new JSONArray(), events = new JSONArray(); SQLiteDatabase db = getReadableDatabase();
        db.beginTransaction();
        try {
            String filter = savedOnly ? " WHERE EXISTS(SELECT 1 FROM favorites f WHERE f.saved=1 AND f.favorite_key=q.kind||':'||q.term_key)" : "";
            try (Cursor c = db.rawQuery("SELECT * FROM queries q"+filter+" ORDER BY created DESC",null)) {
                while (c.moveToNext()) { JSONObject r = row(c); r.remove("term_key"); put(r,"saved",saved(r)); records.put(r); }
            }
            try (Cursor c = db.rawQuery("SELECT record FROM favorites WHERE saved=1 ORDER BY rowid",null)) { while(c.moveToNext()) favorites.put(object(c.getString(0))); }
            filter = savedOnly ? " WHERE EXISTS(SELECT 1 FROM favorites f WHERE f.saved=1 AND f.favorite_key=s.kind||':'||s.normalized_key)" : "";
            try (Cursor c = db.rawQuery("SELECT * FROM selection_events s"+filter+" ORDER BY created,id",null)) { while(c.moveToNext()) events.put(row(c)); }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        put(result,"format","termlens-history"); put(result,"version",2); put(result,"exported_at",now()); put(result,"scope",savedOnly?"favorites":"all");
        put(result,"records",records); put(result,"favorites",favorites); put(result,"selection_events",events); return result;
    }
    synchronized String importData(JSONObject data) throws Exception {
        if (!"termlens-history".equals(data.optString("format")) || data.optInt("version") != 2) throw new Exception("需要电脑版 termlens-history 第 2 版 JSON");
        JSONArray records = data.getJSONArray("records"), favorites = data.getJSONArray("favorites"), events = data.getJSONArray("selection_events");
        if (records.length()+favorites.length()+events.length()>100000) throw new Exception("记录过多，请拆分文件后导入");
        int added=0, unchanged=0, conflict=0; SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            for (int i=0;i<records.length();i++) {
                JSONObject r=records.getJSONObject(i); validate(r,"id","text");
                for(String field:RECORD) if(!field.equals("term_key") && !(r.opt(field) instanceof String)) throw new Exception("记录缺少字段："+field);
                put(r,"term_key",key(r.getString("text"))); JSONObject old=find(r.getString("id"));
                if(old!=null) { boolean same=true; for(String field:RECORD) if(!old.optString(field).equals(r.optString(field))) same=false; if(same) unchanged++; else conflict++; continue; }
                if(r.optString("answer").length()>1024*1024||r.optString("context").length()>200000||r.optString("prompt").length()>200000)throw new Exception("记录内容过长，导入已取消");
                db.insertOrThrow("queries",null,values(r,RECORD));legacy(r); added++;
            }
            for(int i=0;i<events.length();i++) {
                JSONObject e=events.getJSONObject(i); validate(e,"id","text");
                for(String field:EVENT) if(!(e.opt(field) instanceof String)) throw new Exception("选词记录缺少字段："+field);
                db.insertWithOnConflict("selection_events",null,values(e,EVENT),SQLiteDatabase.CONFLICT_IGNORE);
            }
            for(int i=0;i<favorites.length();i++) {
                JSONObject f=favorites.getJSONObject(i); validate(f,"Id","Term");
                if(!f.optBoolean("Saved") || !(f.opt("Kind") instanceof String) || !(f.opt("Explanation") instanceof String)) throw new Exception("收藏字段不完整");
                JSONObject old=favorite(f.optString("Kind"),f.optString("Term"));
                if(old!=null && (!old.optString("Id").equals(f.optString("Id")) || !old.toString().equals(f.toString()))) { conflict++; continue; }
                // JSON transfers text and metadata, not the desktop's local audio files.
                put(f,"WordAudio",new JSONArray()); put(f,"ExplanationAudio",new JSONArray()); put(f,"AudioState","pending"); put(f,"AudioError","");
                try(Cursor c=db.rawQuery("SELECT favorite_key FROM favorites WHERE id=?",new String[]{f.optString("Id")})) {
                    if(c.moveToFirst() && !c.getString(0).equals(f.optString("Kind")+":"+key(f.optString("Term")))) { conflict++; continue; }
                }
                storeFavorite(f);
                if(f.optString("Kind").equals("explain"))db.execSQL("UPDATE terms SET saved=1 WHERE term_key=?",new Object[]{key(f.optString("Term"))});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return "新增 "+added+" 条，重复 "+unchanged+" 条，保留本机冲突 "+conflict+" 条";
    }
    private static void validate(JSONObject item,String id,String text) throws Exception {
        if(!safeId(item.optString(id)) || !(item.opt(text) instanceof String) || item.optString(text).length()>200000) throw new Exception("记录标识或文字无效，导入已取消");
    }
    synchronized JSONArray retain() {
        SQLiteDatabase db=getWritableDatabase(); JSONArray expired=new JSONArray(); long cutoff=System.currentTimeMillis()-15L*86400000L;
        db.beginTransaction();
        try (Cursor c=db.rawQuery("SELECT * FROM queries WHERE source IN ('release-selection','release-context','legacy')",null)) {
            while(c.moveToNext()) {
                JSONObject r=row(c); long created=parseDate(r.optString("created"));
                if(created<0 || created>=cutoff) continue;
                boolean protectedRecord=false;
                try(Cursor f=db.rawQuery("SELECT record FROM favorites WHERE saved=1",null)) {
                    while(f.moveToNext()) { JSONObject favorite=object(f.getString(0)); String query=favorite.optString("QueryId");
                        if(query.equals(r.optString("id")) || query.isEmpty() && favorite.optString("Kind").equals(r.optString("kind")) && key(favorite.optString("Term")).equals(r.optString("term_key"))) { protectedRecord=true;break; }
                    }
                }
                if(!protectedRecord) { expired.put(r.optString("id")); db.delete("queries","id=?",new String[]{r.optString("id")});db.delete("lookups","id=?",new String[]{r.optString("id")});
                    db.execSQL("UPDATE terms SET count=(SELECT COUNT(*) FROM lookups WHERE term_key=?) WHERE term_key=?",new Object[]{r.optString("term_key"),r.optString("term_key")});}
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return expired;
    }
    static long parseDate(String value) {
        // ISO 8601 dates from .NET may carry seven fractional digits or a +00:00 offset.
        try {
            String normalized=value.replaceFirst("\\.(\\d{3})\\d+", ".$1").replace("+00:00","Z");
            SimpleDateFormat f=new SimpleDateFormat(normalized.contains(".")?"yyyy-MM-dd'T'HH:mm:ss.SSSX":"yyyy-MM-dd'T'HH:mm:ssX",Locale.ROOT); f.setLenient(false); return f.parse(normalized).getTime();
        } catch(Exception ignored) { return -1; }
    }
    synchronized boolean protectAudio(String group,String id){
        if(group.equals("history-audio")&&find(id)!=null)return true;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT record FROM favorites WHERE saved=1",null)){
            while(c.moveToNext()){JSONObject f=object(c.getString(0));if(id.equals(f.optString(group.equals("favorites")?"Id":"QueryId")))return true;}}
        return false;
    }
}
