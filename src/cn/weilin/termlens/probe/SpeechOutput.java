package cn.weilin.termlens.probe;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Pluggable speech API, desktop-shaped audio manifests, and private cached playback. */
final class SpeechOutput {
    static final Object ASSET_LOCK=new Object();
    private static long maintenanceAt;
    static final String ENGINE="edge";
    private static SpeechOutput instance;
    static synchronized SpeechOutput get(Context context) { if(instance==null) instance=new SpeechOutput(context.getApplicationContext()); return instance; }
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newFixedThreadPool(3);
    private final ExecutorService playbackWorker=Executors.newCachedThreadPool();
    private final Semaphore networkSlots=new Semaphore(3,true);
    private final SpeechProvider provider;
    private final ConcurrentHashMap<String,FutureTask<List<File>>> preparing=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,FutureTask<byte[]>> downloading=new ConcurrentHashMap<>();
    private final AtomicInteger synthesized=new AtomicInteger(),cacheHits=new AtomicInteger();
    private final Map<String,CacheRef> diskIndex=new HashMap<>();
    private long indexBuilt;
    private volatile boolean ready=true;
    private volatile String lastState="尚未朗读";
    private final java.util.LinkedHashMap<String,byte[]> memory=new java.util.LinkedHashMap<>(16,0.75f,true);
    private long memoryBytes;
    private volatile int playbackGeneration;
    private volatile long clickAt,readyMs=-1,playMs=-1;
    private volatile String lastRole="",lastCache="";
    private MediaPlayer player;
    private SpeechOutput(Context context) {this(context,new EdgeSpeechProvider());}
    SpeechOutput(Context context,SpeechProvider provider) {this.context=context;this.provider=provider;}
    private static final class Profile {
        final String provider,voice,extension;final int rate;
        Profile(Context c,SpeechProvider p){provider=p.id();extension=p.extension();voice=TermLensPrefs.speechVoice(c);rate=TermLensPrefs.speechRate(c);}
        String fingerprint(String text){return hash((provider+"\n"+voice+"\n"+rate+"\n"+extension+"\n"+plain(text)).getBytes(StandardCharsets.UTF_8));}
        boolean matches(JSONObject m){return provider.equals(m.optString("Provider"))&&voice.equals(m.optString("Voice"))&&rate==m.optInt("Rate");}
    }
    private static final class CacheRef {final File dir;final String role;CacheRef(File d,String r){dir=d;role=r;}}
    static String plain(String text) {
        return text.replaceAll("(?m)^\\s*---\\s*$", " ").replace("```", "").replace("**", "").replace("`", "").replaceAll("(?m)^\\s*[-*#]+\\s+", "").trim();
    }
    static String hash(byte[] bytes) {
        try { byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder value=new StringBuilder();for(byte b:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString(); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    static byte[] read(File path) throws Exception {
        if(path.length()>32L*1024*1024) throw new Exception("缓存文件过大");
        try(FileInputStream input=new FileInputStream(path);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) { byte[] bytes=new byte[8192];int n;while((n=input.read(bytes))!=-1)out.write(bytes,0,n);return out.toByteArray(); }
    }
    static void atomic(File path,byte[] bytes) throws Exception {
        File parent=path.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new Exception("无法创建缓存目录");
        AtomicFile file=new AtomicFile(path);FileOutputStream output=null;
        try { output=file.startWrite();output.write(bytes);file.finishWrite(output); }
        catch(Exception e) { if(output!=null)file.failWrite(output);throw e; }
    }
    private File directory(String group,String id) throws Exception {
        if(!AppData.safeId(id))throw new Exception("音频记录标识无效");return new File(new File(context.getFilesDir(),group),id);
    }
    private JSONObject manifest(File directory,String id,boolean favorite) {
        try { JSONObject m=AppData.object(new String(read(new File(directory,"manifest.json")),StandardCharsets.UTF_8));
            if(m.optInt("Version")==1 && id.equals(m.optString(favorite?"FavoriteId":"QueryId")))return m;
        } catch(Exception ignored) { }
        JSONObject m=new JSONObject();AppData.put(m,"Version",1);AppData.put(m,favorite?"FavoriteId":"QueryId",id);AppData.put(m,"Created",AppData.now());
        AppData.put(m,"Word",new JSONArray());AppData.put(m,"Explanation",new JSONArray());AppData.put(m,"Retired",new JSONArray());return m;
    }
    private void commit(File directory,JSONObject manifest) throws Exception {atomic(new File(directory,"manifest.json"),manifest.toString().getBytes(StandardCharsets.UTF_8));index(directory,manifest);}
    private void index(File dir,JSONObject m){for(String role:new String[]{"Word","Explanation"}){
        String key=m.optString(role.equals("Word")?"OriginalFingerprint":"ExplanationFingerprint");
        if(key.matches("[a-fA-F0-9]{64}")&&(m.has("FavoriteId")||m.optBoolean(role+"Complete")))diskIndex.put(key,new CacheRef(dir,role));}}
    private List<File> cached(File dir,JSONObject m,String role,String text,Profile profile) throws Exception {
        if(!profile.matches(m)||!profile.fingerprint(text).equals(m.optString(role.equals("Word")?"OriginalFingerprint":"ExplanationFingerprint")) || !m.has("FavoriteId")&&!m.optBoolean(role+"Complete"))return null;
        JSONArray assets=m.optJSONArray(role);if(assets==null||assets.length()==0)return null;List<File> result=new ArrayList<>();
        for(int i=0;i<assets.length();i++) { JSONObject asset=assets.getJSONObject(i);String name=asset.optString("File");
            if(!name.matches("[A-Za-z0-9._-]+\\.(wav|mp3)"))return null;File path=new File(dir,name);
            if(!path.isFile()||path.length()!=asset.optLong("Bytes")||!hash(read(path)).equals(asset.optString("Sha256")))return null;result.add(path);
        }
        return result;
    }
    private List<File> reuse(String text,Profile profile) throws Exception {
        if(indexBuilt==0||System.currentTimeMillis()-indexBuilt>30000){
            diskIndex.clear();long cutoff=System.currentTimeMillis()-15L*86400000L;
            for(String group:new String[]{"history-audio","favorites"}){File[] dirs=new File(context.getFilesDir(),group).listFiles();if(dirs==null)continue;
                for(File dir:dirs){if(!dir.isDirectory()||!AppData.safeId(dir.getName()))continue;try{
                    JSONObject m=manifest(dir,dir.getName(),group.equals("favorites"));long created=AppData.parseDate(m.optString("Created"));
                    if(created>=cutoff||AppData.get(context).protectAudio(group,dir.getName()))index(dir,m);
                }catch(Exception ignored){}}}
            indexBuilt=System.currentTimeMillis();
        }
        CacheRef found=diskIndex.get(profile.fingerprint(text));if(found==null)return null;
        JSONObject m=manifest(found.dir,found.dir.getName(),found.dir.getParentFile().getName().equals("favorites"));
        return cached(found.dir,m,found.role,text,profile);
    }
    private List<File> prepare(JSONObject record,String text,String role) throws Exception {return prepare(record,text,role,new Profile(context,provider));}
    private List<File> prepare(JSONObject record,String text,String role,Profile profile) throws Exception {
        final String value=plain(text),key=record.optString("id")+":"+role+":"+profile.fingerprint(value);
        FutureTask<List<File>> task=new FutureTask<>(()->prepareAudio(record,value,role,profile));
        FutureTask<List<File>> shared=preparing.putIfAbsent(key,task);boolean owner=shared==null;if(owner)shared=task;
        try{shared.run();return shared.get();}catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new Exception(cause);}
        finally{if(owner)preparing.remove(key,task);}
    }
    private byte[] segment(String text,Profile profile) throws Exception {
        String key=profile.fingerprint(text);synchronized(memory){byte[] hit=memory.get(key);if(hit!=null){cacheHits.incrementAndGet();return hit;}}
        FutureTask<byte[]> task=new FutureTask<>(()->{
            synchronized(memory){byte[] hit=memory.get(key);if(hit!=null){cacheHits.incrementAndGet();return hit;}}
            long began=android.os.SystemClock.elapsedRealtime();android.util.Log.i("TLSpeech","fetch_start fingerprint="+key.substring(0,12));
            byte[] bytes;networkSlots.acquire();try{bytes=provider.synthesize(text,profile.voice,profile.rate);}finally{networkSlots.release();}
            if(bytes.length<44)throw new Exception("Edge 返回了空音频");synthesized.incrementAndGet();
            synchronized(memory){memory.put(key,bytes);memoryBytes+=bytes.length;while(memory.size()>160||memoryBytes>32L*1024*1024){Map.Entry<String,byte[]> oldest=memory.entrySet().iterator().next();memoryBytes-=oldest.getValue().length;memory.remove(oldest.getKey());}}
            android.util.Log.i("TLSpeech","fetch_done ms="+(android.os.SystemClock.elapsedRealtime()-began)+" fingerprint="+key.substring(0,12));return bytes;
        });
        FutureTask<byte[]> shared=downloading.putIfAbsent(key,task);boolean owner=shared==null;if(owner)shared=task;else cacheHits.incrementAndGet();
        try{shared.run();return shared.get();}catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new Exception(cause);}
        finally{if(owner)downloading.remove(key,task);}
    }
    private List<File> prepareAudio(JSONObject record,String text,String role,Profile profile) throws Exception {
        text=plain(text);if(text.isEmpty())throw new Exception("没有可朗读的文字");
        String id=record.optString("id");File dir=directory("history-audio",id);
        synchronized(ASSET_LOCK){JSONObject m=manifest(dir,id,false);
        List<File> hit=cached(dir,m,role,text,profile);if(hit!=null){cacheHits.incrementAndGet();return hit;}
        JSONObject favorite=AppData.get(context).favorite(record.optString("kind"),record.optString("text"));
        if(favorite!=null&&favorite.optBoolean("Saved")){
            File saved=directory("favorites",favorite.optString("Id"));List<File> permanent=cached(saved,manifest(saved,favorite.optString("Id"),true),role,text,profile);
            if(permanent!=null){cacheHits.incrementAndGet();return permanent;}
        }
        List<File> reused=reuse(text,profile);if(reused!=null){List<byte[]> bytes=new ArrayList<>();for(File file:reused)bytes.add(read(file));cacheHits.incrementAndGet();return storeAudio(record,text,role,profile,bytes);}}
        String whole=text;int limit=1000;List<byte[]> result=new ArrayList<>();
        while(!text.isEmpty()) {
            int count=Math.min(text.length(),limit);
            if(count<text.length()) { int space=text.lastIndexOf(' ',count);if(space>count/2)count=space; if(Character.isHighSurrogate(text.charAt(count-1)))count--; }
            String part=text.substring(0,count);text=text.substring(count).trim();
            result.add(segment(part,profile));
        }
        synchronized(ASSET_LOCK){return storeAudio(record,whole,role,profile,result);}
    }
    private List<File> storeAudio(JSONObject record,String text,String role,Profile profile,List<byte[]> chunks) throws Exception {
        String id=record.optString("id");File dir=directory("history-audio",id);JSONObject m=manifest(dir,id,false);JSONArray retired=m.optJSONArray("Retired");if(retired==null)retired=new JSONArray();
        for(String oldRole:new String[]{"Word","Explanation"})if(oldRole.equals(role)||!profile.matches(m)){JSONArray old=m.optJSONArray(oldRole);if(old!=null)for(int i=0;i<old.length();i++)retired.put(old.get(i));AppData.put(m,oldRole,new JSONArray());AppData.put(m,oldRole+"Complete",false);}
        AppData.put(m,"Retired",retired);JSONArray assets=new JSONArray();AppData.put(m,role,assets);List<File> result=new ArrayList<>();
        for(byte[] bytes:chunks){String digest=hash(bytes),name=(role.equals("Word")?"word":"explanation")+"-"+digest.substring(0,24)+profile.extension;File destination=new File(dir,name);
            if(destination.exists()&&!hash(read(destination)).equals(digest))throw new Exception("音频缓存内容冲突");
            if(!destination.exists())atomic(destination,bytes);
            JSONObject asset=new JSONObject();AppData.put(asset,"File",name);AppData.put(asset,"Sha256",digest);AppData.put(asset,"Bytes",bytes.length);assets.put(asset);
            result.add(destination);
        }
        AppData.put(m,"Provider",profile.provider);AppData.put(m,"Voice",profile.voice);AppData.put(m,"Rate",profile.rate);AppData.put(m,"Created",record.optString("created",AppData.now()));
        AppData.put(m,role.equals("Word")?"OriginalFingerprint":"ExplanationFingerprint",profile.fingerprint(text));AppData.put(m,role+"Complete",true);commit(dir,m);
        android.util.Log.i("TLSpeech","cache_ready role="+role+" assets="+result.size());return result;
    }
    // On-demand Ask playback uses audio-only metadata, never a dictionary-history entry.
    void speakTemporary(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String value = plain(text); JSONObject record = new JSONObject();
        AppData.put(record,"id","ask-" + hash(value.getBytes(StandardCharsets.UTF_8)).substring(0,32));
        AppData.put(record,"kind","ask"); AppData.put(record,"text",value); AppData.put(record,"answer",""); AppData.put(record,"created",AppData.now());
        speak(record,value,"Word");
    }
    void speak(JSONObject record,String text,String role) {
        final int token=++playbackGeneration;stopPlayer();clickAt=android.os.SystemClock.elapsedRealtime();readyMs=-1;playMs=-1;lastRole=role;lastCache="pending";lastState="准备朗读";
        final Profile profile=new Profile(context,provider);final JSONObject snapshot=AppData.object(record.toString());final int before=synthesized.get();
        main.postDelayed(()->{if(token==playbackGeneration&&readyMs<0)toast("首次音频正在生成，准备好后自动播放");},700);
        playbackWorker.execute(()->{
            if(token!=playbackGeneration)return;
            try { List<File> files=prepare(snapshot,text,role,profile);main.post(()->{if(token==playbackGeneration){readyMs=android.os.SystemClock.elapsedRealtime()-clickAt;lastCache=synthesized.get()==before?"disk_or_shared":"generated";play(files,0,token);}}); }
            catch(Exception error){lastState=error.getMessage();main.post(()->{if(token==playbackGeneration)toast(lastState);});android.util.Log.i("TLSpeech","synthesis_failed");}
        });
    }
    void saveFavorite(JSONObject record) {
        final Profile profile=new Profile(context,provider);final JSONObject snapshot=AppData.object(record.toString());
        worker.execute(()->{
            try {
                List<File> words=prepare(snapshot,snapshot.optString("text"),"Word",profile),explanations=prepare(snapshot,snapshot.optString("answer"),"Explanation",profile);
                synchronized(ASSET_LOCK){
                AppData store=AppData.get(context);JSONObject f=store.favorite(record.optString("kind"),record.optString("text"));
                if(f==null||!f.optBoolean("Saved")||!f.optString("QueryId").equals(record.optString("id")))return;
                File target=directory("favorites",f.optString("Id"));JSONObject m=new JSONObject();AppData.put(m,"Version",1);AppData.put(m,"FavoriteId",f.optString("Id"));AppData.put(m,"Provider",profile.provider);AppData.put(m,"Voice",profile.voice);AppData.put(m,"Rate",profile.rate);AppData.put(m,"Created",AppData.now());
                AppData.put(m,"OriginalFingerprint",profile.fingerprint(snapshot.optString("text")));AppData.put(m,"ExplanationFingerprint",profile.fingerprint(snapshot.optString("answer")));
                for(String role:new String[]{"Word","Explanation"}){JSONArray assets=new JSONArray();for(File file:role.equals("Word")?words:explanations){JSONObject asset=new JSONObject();AppData.put(asset,"File",file.getName());AppData.put(asset,"Sha256",hash(read(file)));AppData.put(asset,"Bytes",file.length());assets.put(asset);}AppData.put(m,role,assets);}
                JSONObject previous=manifest(target,f.optString("Id"),true);JSONArray retired=new JSONArray();java.util.Set<String> activeNames=new java.util.HashSet<>();
                for(String role:new String[]{"Word","Explanation"}){JSONArray active=m.getJSONArray(role);for(int i=0;i<active.length();i++)activeNames.add(active.getJSONObject(i).getString("File"));}
                for(String role:new String[]{"Word","Explanation","Retired"}){JSONArray old=previous.optJSONArray(role);if(old!=null)for(int i=0;i<old.length();i++){JSONObject asset=old.getJSONObject(i);if(!activeNames.contains(asset.optString("File")))retired.put(asset);}}
                AppData.put(m,"Retired",retired);
                for(String role:new String[]{"Word","Explanation"}) {
                    JSONArray paths=new JSONArray(),assets=m.getJSONArray(role);
                    List<File> prepared=role.equals("Word")?words:explanations;
                    for(int i=0;i<assets.length();i++){String file=assets.getJSONObject(i).getString("File");File destination=new File(target,file);byte[] content=read(prepared.get(i));
                        if(destination.exists()&&!hash(read(destination)).equals(hash(content)))throw new Exception("收藏音频存在不同内容，已保留原文件");if(!destination.exists())atomic(destination,content);paths.put("favorites/"+f.optString("Id")+"/"+file);}
                    AppData.put(f,role.equals("Word")?"WordAudio":"ExplanationAudio",paths);
                }
                commit(target,m);AppData.put(f,"Voice",m.optString("Voice"));AppData.put(f,"AudioState","ready");AppData.put(f,"AudioError","");
                // Recheck after synthesis; a user may have removed or replaced this favorite meanwhile.
                synchronized(store){JSONObject current=store.favorite(record.optString("kind"),record.optString("text"));
                    if(current!=null&&current.optBoolean("Saved")&&current.optString("QueryId").equals(record.optString("id")))store.storeFavorite(f);}
                }
            } catch(Exception error) {
                AppData store=AppData.get(context);synchronized(store){JSONObject f=store.favorite(record.optString("kind"),record.optString("text"));
                    if(f!=null&&f.optBoolean("Saved")&&f.optString("QueryId").equals(record.optString("id"))){AppData.put(f,"AudioState","failed");AppData.put(f,"AudioError",error.getMessage());store.storeFavorite(f);}}
            }
        });
    }
    Future<?> preloadOriginal(JSONObject record){return enqueue(record,record.optString("text"),"Word");}
    void preload(JSONObject record){preloadOriginal(record);if(!plain(record.optString("answer")).isEmpty())enqueue(record,record.optString("answer"),"Explanation");}
    private Future<?> enqueue(JSONObject record,String text,String role){
        final JSONObject snapshot=AppData.object(record.toString());final Profile profile=new Profile(context,provider);
        android.util.Log.i("TLSpeech","preload_queued role="+role);
        return worker.submit(()->{try{prepare(snapshot,text,role,profile);android.util.Log.i("TLSpeech","preload_ready role="+role);}catch(Exception e){lastState=e.getMessage();android.util.Log.i("TLSpeech","preload_failed role="+role);}});
    }
    static void invalidateIndex(){SpeechOutput value=instance;if(value!=null)synchronized(ASSET_LOCK){value.indexBuilt=0;}}
    void maintain(){synchronized(SpeechOutput.class){if(System.currentTimeMillis()-maintenanceAt<3600000)return;maintenanceAt=System.currentTimeMillis();}
        worker.execute(()->{synchronized(ASSET_LOCK){try{AppData.get(context).retain();DataMaintenance.cleanAudio(context);}catch(Exception e){android.util.Log.i("TLSpeech","retention_skipped");}}});}
    private void play(List<File> files,int index,int token) {
        if(token!=playbackGeneration)return;if(index>=files.size()){stopPlayer();lastState="播放完成";return;}
        stopPlayer();MediaPlayer next=new MediaPlayer();player=next;
        try { next.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            next.setDataSource(files.get(index).getAbsolutePath());next.setOnPreparedListener(p->{if(token==playbackGeneration){p.start();lastState="正在朗读";if(index==0)playMs=android.os.SystemClock.elapsedRealtime()-clickAt;android.util.Log.i("TLSpeech","playback_started click_to_ready_ms="+readyMs+" click_to_play_ms="+playMs+" role="+lastRole+" cache_hits="+cacheHits.get());}});
            next.setOnCompletionListener(p->play(files,index+1,token));next.setOnErrorListener((p,a,b)->{stopPlayer();toast("音频播放失败");return true;});next.prepareAsync();
        } catch(Exception e) {stopPlayer();toast("无法播放音频缓存");}
    }
    void stop() {playbackGeneration++;stopPlayer();}
    private void stopPlayer(){if(player!=null){player.release();player=null;}}
    private void toast(String message){Toast.makeText(context,message,Toast.LENGTH_SHORT).show();}
    void shutdown(){worker.shutdownNow();playbackWorker.shutdownNow();}
    String status(){return "engine="+ENGINE+" ready="+ready+" synthesized="+synthesized.get()+" cache_hits="+cacheHits.get()+" preparing="+preparing.size()+" click_to_ready_ms="+readyMs+" click_to_play_ms="+playMs+" role="+lastRole+" cache="+lastCache+" state="+lastState;}
}
