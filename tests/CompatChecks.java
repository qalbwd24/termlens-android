package cn.weilin.termlens.probe;
import android.app.Instrumentation;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs only in an isolated database/files/prefs namespace; never edits personal app data. */
public final class CompatChecks extends Instrumentation {
    private final StringBuilder report=new StringBuilder();
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);report.append("PASS: ").append(message).append('\n');}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle result=new Bundle();try{runChecks();runAudioChecks();runSpeechDefaultsChecks();result.putString("stream",report.toString());finish(-1,result);}catch(Throwable error){result.putString("stream",report+"FAIL: "+android.util.Log.getStackTraceString(error));finish(0,result);}}
    private void reset() throws Exception {java.lang.reflect.Field field=AppData.class.getDeclaredField("instance");field.setAccessible(true);AppData current=(AppData)field.get(null);if(current!=null)current.close();field.set(null,null);}
    private static final class Fixture extends ContextWrapper {
        final File root;
        Fixture(Context base,String name){super(base);root=new File(base.getCacheDir(),name);root.mkdirs();}
        public Context getApplicationContext(){return this;}
        public File getFilesDir(){File file=new File(root,"files");file.mkdirs();return file;}
        public File getCacheDir(){File file=new File(root,"cache");file.mkdirs();return file;}
        public File getDatabasePath(String name){File dir=new File(root,"databases");dir.mkdirs();return new File(dir,name);}
        public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
        public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler error){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,error);}
        public SharedPreferences getSharedPreferences(String name,int mode){return getBaseContext().getSharedPreferences(root.getName()+"-"+name,mode);}
    }
    private JSONObject envelope(JSONArray records){JSONObject value=new JSONObject();AppData.put(value,"format","termlens-history");AppData.put(value,"version",2);AppData.put(value,"records",records);AppData.put(value,"favorites",new JSONArray());AppData.put(value,"selection_events",new JSONArray());return value;}
    private void runAudioChecks() throws Exception {
        reset();Fixture fixture=new Fixture(getTargetContext(),"audio-prefetch-"+AppData.id());AppData store=AppData.get(fixture);
        final AtomicInteger calls=new AtomicInteger();final CountDownLatch began=new CountDownLatch(1),release=new CountDownLatch(1);
        SpeechProvider provider=new SpeechProvider(){public String id(){return "edge";}public String extension(){return ".mp3";}
            public byte[] synthesize(String text,String voice,int rate)throws Exception{calls.incrementAndGet();if(text.equals("slow explanation")){began.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new Exception("fixture timeout");}byte[] bytes=new byte[128];Arrays.fill(bytes,(byte)text.hashCode());return bytes;}};
        SpeechOutput speech=new SpeechOutput(fixture,provider);ExecutorService checks=Executors.newFixedThreadPool(3);
        java.lang.reflect.Method prepare=SpeechOutput.class.getDeclaredMethod("prepare",JSONObject.class,String.class,String.class);prepare.setAccessible(true);
        JSONObject first=record(store,"prefetched original");
        try {
            speech.preloadOriginal(first).get(2,TimeUnit.SECONDS);
            check(calls.get()==1&&new File(fixture.getFilesDir(),"history-audio/"+first.getString("id")+"/manifest.json").isFile()&&first.optString("answer").isEmpty(),"Original audio is fetched and persisted while the LLM answer is still empty");
            store.finish(first,"slow explanation","complete","");speech.preload(first);check(began.await(2,TimeUnit.SECONDS),"Explanation synthesis starts automatically without a play tap");
            Future<List<?>> immediate=checks.submit(()->(List<?>)prepare.invoke(speech,first,"prefetched original","Word"));
            long start=android.os.SystemClock.elapsedRealtime();List<?> files=immediate.get(400,TimeUnit.MILLISECONDS);
            check(files.size()==1&&release.getCount()==1&&android.os.SystemClock.elapsedRealtime()-start<400,"Cached original resolves immediately while explanation network synthesis is blocked");
            java.lang.reflect.Field laneField=SpeechOutput.class.getDeclaredField("playbackWorker");laneField.setAccessible(true);ExecutorService lane=(ExecutorService)laneField.get(speech);
            final CountDownLatch oldStarted=new CountDownLatch(1),oldDone=new CountDownLatch(1);
            lane.submit(()->{oldStarted.countDown();try{oldDone.await(3,TimeUnit.SECONDS);}catch(InterruptedException ignored){}});check(oldStarted.await(1,TimeUnit.SECONDS),"An older waiting playback request is active during the regression");
            try{runOnMainSync(()->speech.speak(first,"prefetched original","Word"));java.lang.reflect.Field readyField=SpeechOutput.class.getDeclaredField("readyMs");readyField.setAccessible(true);
                long until=android.os.SystemClock.elapsedRealtime()+600;while(readyField.getLong(speech)<0&&android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(10);
                check(readyField.getLong(speech)>=0&&oldDone.getCount()==1,"A new cached play tap bypasses an older waiting playback request");
            }finally{oldDone.countDown();runOnMainSync(()->speech.stop());}
            release.countDown();
            speech.preloadOriginal(first).get(2,TimeUnit.SECONDS);
            SpeechProvider offline=new SpeechProvider(){public String id(){return "edge";}public String extension(){return ".mp3";}public byte[] synthesize(String t,String v,int r)throws Exception{throw new Exception("network forbidden");}};
            SpeechOutput restarted=new SpeechOutput(fixture,offline);JSONObject second=record(store,"prefetched original");
            try{List<?> reused=(List<?>)prepare.invoke(restarted,second,"prefetched original","Word");check(reused.size()==1&&new File(fixture.getFilesDir(),"history-audio/"+second.getString("id")+"/manifest.json").isFile(),"A fresh speech instance and a new query reuse old history audio with the network disabled");}finally{restarted.shutdown();}
        }finally{release.countDown();speech.shutdown();checks.shutdownNow();}
        final AtomicInteger sharedCalls=new AtomicInteger();final CountDownLatch fetching=new CountDownLatch(1),finish=new CountDownLatch(1);
        SpeechProvider slow=new SpeechProvider(){public String id(){return "edge";}public String extension(){return ".mp3";}public byte[] synthesize(String t,String v,int r)throws Exception{sharedCalls.incrementAndGet();fetching.countDown();if(!finish.await(5,TimeUnit.SECONDS))throw new Exception("fixture timeout");return new byte[128];}};
        SpeechOutput shared=new SpeechOutput(fixture,slow);ExecutorService parallel=Executors.newFixedThreadPool(2);
        JSONObject one=record(store,"shared in-flight word"),two=record(store,"shared in-flight word");
        try {
            Future<?> a=parallel.submit(()->{try{return prepare.invoke(shared,one,one.optString("text"),"Word");}catch(Exception e){throw new RuntimeException(e);}});
            check(fetching.await(2,TimeUnit.SECONDS),"Query audio synthesis begins before any playback request");
            Future<?> b=parallel.submit(()->{try{return prepare.invoke(shared,two,two.optString("text"),"Word");}catch(Exception e){throw new RuntimeException(e);}});
            finish.countDown();a.get(2,TimeUnit.SECONDS);b.get(2,TimeUnit.SECONDS);
            check(sharedCalls.get()==1,"Concurrent prefetch and playback requests share a single synthesis");
        }finally{finish.countDown();shared.shutdown();parallel.shutdownNow();}
        final AtomicInteger attempts=new AtomicInteger();SpeechProvider retry=new SpeechProvider(){public String id(){return "edge";}public String extension(){return ".mp3";}public byte[] synthesize(String t,String v,int r)throws Exception{if(attempts.incrementAndGet()==1)throw new Exception("temporary failure");return new byte[128];}};
        SpeechOutput retrying=new SpeechOutput(fixture,retry);JSONObject failed=record(store,"retry after prefetch failure");
        try{boolean rejected=false;try{prepare.invoke(retrying,failed,failed.optString("text"),"Word");}catch(Exception e){rejected=true;}List<?> recovered=(List<?>)prepare.invoke(retrying,failed,failed.optString("text"),"Word");check(rejected&&attempts.get()==2&&recovered.size()==1,"A failed prefetch can be retried instead of retaining a failed shared task");}finally{retrying.shutdown();}
        reset();
    }
    private void runSpeechDefaultsChecks() throws Exception {
        reset(); Fixture fixture=new Fixture(getTargetContext(),"us-voice-"+AppData.id());
        SharedPreferences prefs=TermLensPrefs.open(fixture);
        check(TermLensPrefs.speechVoice(fixture).equals("en-US-AndrewNeural") && TermLensPrefs.speechRate(fixture)==10,"New install uses US Andrew at +10 percent");
        prefs.edit().clear().putString("speech_voice","en-GB-SoniaNeural").putString("speech_rate","-5").commit();
        check(TermLensPrefs.speechVoice(fixture).equals("en-GB-SoniaNeural") && TermLensPrefs.speechRate(fixture)==-5,"Existing custom voice and rate survive migration");
        prefs.edit().clear().putBoolean("speech_us_andrew_v1",true).putString("speech_voice",SpeechDefaults.LEGACY_VOICE).putString("speech_rate","0").commit();
        final AtomicInteger calls=new AtomicInteger();
        SpeechProvider provider=new SpeechProvider(){public String id(){return "edge";}public String extension(){return ".mp3";}
            public byte[] synthesize(String text,String voice,int rate){calls.incrementAndGet();byte[] bytes=new byte[128];Arrays.fill(bytes,(byte)(voice.hashCode()+rate));return bytes;}};
        AppData store=AppData.get(fixture);JSONObject record=record(store,"voice migration fixture");store.finish(record,"answer","complete","");store.setSaved(record,true);
        SpeechOutput speech=new SpeechOutput(fixture,provider);
        java.lang.reflect.Method prepare=SpeechOutput.class.getDeclaredMethod("prepare",JSONObject.class,String.class,String.class);prepare.setAccessible(true);
        try {
            List<?> old=(List<?>)prepare.invoke(speech,record,record.optString("text"),"Word");
            prefs.edit().remove("speech_us_andrew_v1").commit();
            check(TermLensPrefs.speechVoice(fixture).equals("en-US-AndrewNeural") && TermLensPrefs.speechRate(fixture)==10,"Existing legacy default pair migrates");
            List<?> fresh=(List<?>)prepare.invoke(speech,record,record.optString("text"),"Word");
            check(calls.get()==2 && !old.get(0).equals(fresh.get(0)),"Voice/rate migration cannot replay old memory or disk audio");
            check(((File)old.get(0)).isFile() && store.saved(record),"Old audio files and favorite records are not destructively deleted");
            prepare.invoke(speech,record,record.optString("text"),"Word");check(calls.get()==2,"New matching voice cache reuses audio");
            prefs.edit().putString("speech_rate","0").commit();
            check(TermLensPrefs.speechRate(fixture)==0,"Migration marker preserves subsequent user changes");
        } finally { speech.shutdown(); reset(); }
    }
    private JSONObject record(AppData store,String word){return store.begin(word,false,word,"{}","fixture-model","fixture-prompt");}
    private File audio(Fixture context,String id,boolean favorite,boolean unknown) throws Exception {
        File dir=new File(context.getFilesDir(),(favorite?"favorites/":"history-audio/")+id);dir.mkdirs();byte[] bytes="ID3 compatibility fixture audio metadata only".getBytes(StandardCharsets.UTF_8);SpeechOutput.atomic(new File(dir,"word-fixture.mp3"),bytes);
        JSONObject m=new JSONObject(),asset=new JSONObject();AppData.put(asset,"File","word-fixture.mp3");AppData.put(asset,"Bytes",bytes.length);AppData.put(asset,"Sha256",SpeechOutput.hash(bytes));
        AppData.put(m,"Version",1);AppData.put(m,favorite?"FavoriteId":"QueryId",id);AppData.put(m,"Created","2000-01-01T00:00:00Z");AppData.put(m,"Word",new JSONArray().put(asset));AppData.put(m,"Explanation",new JSONArray());AppData.put(m,"Retired",new JSONArray());
        SpeechOutput.atomic(new File(dir,"manifest.json"),m.toString().getBytes(StandardCharsets.UTF_8));if(unknown)SpeechOutput.atomic(new File(dir,"personal-note.txt"),new byte[]{1});return dir;
    }
    private void runChecks() throws Exception {
        StreamAnswer stream=new StreamAnswer();StringBuilder text=new StringBuilder();String[] lines={": ping","","data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}","","data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"private thought\",\"content\":\"词😀\"},\"finish_reason\":null}]}","","data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}","","data: [DONE]",""};
        for(String line:lines)text.append(stream.line(line));stream.requireComplete();check(text.toString().equals("词😀"),"SSE comments, empty deltas and Unicode work; reasoning is not displayed");
        boolean rejected=false;try{new StreamAnswer().requireComplete();}catch(Exception e){rejected=true;}check(rejected,"Interrupted SSE is not accepted as a completed answer");
        stream=new StreamAnswer();stream.line("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}");stream.line("");stream.line("data: [DONE]");stream.line("");rejected=false;try{stream.requireComplete();}catch(Exception e){rejected=true;}check(rejected,"Output-limit replies remain incomplete");
        ByteArrayOutputStream frame=new ByteArrayOutputStream();EdgeSpeechProvider.send(frame,1,"hello 😀".getBytes(StandardCharsets.UTF_8));byte[] sent=frame.toByteArray();int size=sent[1]&127;byte[] decoded=new byte[size];for(int i=0;i<size;i++)decoded[i]=(byte)(sent[6+i]^sent[2+i%4]);check(new String(decoded,StandardCharsets.UTF_8).equals("hello 😀")&&(sent[1]&128)!=0,"WebSocket client messages are masked without damaging UTF-8");
        check(EdgeSpeechProvider.frame(new ByteArrayInputStream(new byte[]{(byte)0x89,0})).opcode==9,"WebSocket ping frames are recognized");
        rejected=false;try{EdgeSpeechProvider.frame(new ByteArrayInputStream(new byte[]{(byte)0x89,126,0,126}));}catch(Exception e){rejected=true;}check(rejected,"Invalid oversized control frames are rejected");
        String ssml=EdgeSpeechProvider.ssml("<x> & '词'","en-US-AndrewMultilingualNeural",0);check(ssml.contains("&lt;x&gt; &amp; &apos;词&apos;")&&!ssml.contains("<x>"),"SSML quotes and markup are escaped");
        reset();Fixture a=new Fixture(getTargetContext(),"compat-a-"+AppData.id());AppData store=AppData.get(a);
        check(AppData.key("  A\tB ").equals("a b"),"Term normalization matches the desktop rule");
        JSONObject first=record(store,"cached original"),favorite=record(store,"favorite original"),failed=record(store,"failed original");
        AppData.put(first,"created","2000-01-01T00:00:00.0000000Z");store.finish(first,"old answer","complete","");
        AppData.put(favorite,"created","2000-01-01T00:00:00Z");store.finish(favorite,"favorite explanation","complete","");JSONObject f=store.setSaved(favorite,true);
        store.finish(failed,"partial response","failed","connection interrupted");
        File expiredAudio=audio(a,first.optString("id"),false,false),favoriteAudio=audio(a,f.optString("Id"),true,false),unknownAudio=audio(a,"unknown-owned",false,true);
        check(store.retain().length()==1&&store.find(first.optString("id"))==null&&store.find(favorite.optString("id"))!=null,"Fifteen-day retention expires ordinary explanations and protects saved snapshots");
        check(store.export(false).getJSONArray("selection_events").length()==3,"Selection ledger survives answer-cache expiry");
        check(DataMaintenance.cleanAudio(a)==1&&!expiredAudio.exists()&&favoriteAudio.exists()&&unknownAudio.exists(),"Expired verified audio is removed; favorite and unknown files are preserved");
        JSONObject audioManifest=AppData.object(new String(SpeechOutput.read(new File(favoriteAudio,"manifest.json")),StandardCharsets.UTF_8));
        String fingerprint=SpeechOutput.hash(("edge\n"+TermLensPrefs.speechVoice(a)+"\n0\n.mp3\nfavorite original").getBytes(StandardCharsets.UTF_8));AppData.put(audioManifest,"OriginalFingerprint",fingerprint);AppData.put(audioManifest,"Provider","edge");AppData.put(audioManifest,"Voice",TermLensPrefs.speechVoice(a));AppData.put(audioManifest,"Rate",0);
        SpeechOutput.atomic(new File(favoriteAudio,"manifest.json"),audioManifest.toString().getBytes(StandardCharsets.UTF_8));
        java.lang.reflect.Constructor<SpeechOutput> speechConstructor=SpeechOutput.class.getDeclaredConstructor(Context.class);speechConstructor.setAccessible(true);SpeechOutput speech=speechConstructor.newInstance(a);
        java.lang.reflect.Method prepare=SpeechOutput.class.getDeclaredMethod("prepare",JSONObject.class,String.class,String.class);prepare.setAccessible(true);
        List<?> cached=(List<?>)prepare.invoke(speech,favorite,"favorite original","Word");check(cached.size()==1&&speech.status().contains("synthesized=0")&&speech.status().contains("cache_hits=1"),"Permanent favorite audio replays from disk without a network synthesis");
        JSONObject exported=store.export(false);check(exported.getString("format").equals("termlens-history")&&exported.getInt("version")==2&&exported.getJSONArray("favorites").getJSONObject(0).getString("Id").equals(f.getString("Id")),"Portable history v2 preserves desktop field names and stable favorite IDs");
        store.importData(new JSONObject(exported.toString()));check(store.export(false).getJSONArray("records").length()==2,"Repeated JSON import is idempotent");
        JSONObject conflict=new JSONObject(favorite.toString());AppData.put(conflict,"text","must not overwrite");store.importData(envelope(new JSONArray().put(conflict)));check(store.find(favorite.getString("id")).getString("text").equals("favorite original"),"Conflicting query IDs preserve local content");
        JSONObject newRecord=new JSONObject(failed.toString());String newId=AppData.id();AppData.put(newRecord,"id",newId);rejected=false;
        try{store.importData(envelope(new JSONArray().put(newRecord).put(new JSONObject().put("id","broken"))));}catch(Exception e){rejected=true;}
        check(rejected&&store.find(newId)==null,"A malformed later record rolls back the entire import");
        check(AppData.parseDate("2000-01-01T00:00:00.1234567Z")==946684800123L&&AppData.parseDate("unknown")==-1,"Desktop seven-digit ISO dates parse; unknown dates stay protected");
        String active=SceneProfiles.active(a).getString("Id");JSONArray profiles=SceneProfiles.library(a).getJSONArray("PromptPresets");check(profiles.length()>=2,"Desktop scene library is available independently of connection settings");
        JSONObject originalProfile=SceneProfiles.snapshot(a);SceneProfiles.select(a,profiles.getJSONObject(1).getString("Id"));check(originalProfile.getString("id").equals(active),"A captured query profile remains immutable after changing scenes");SceneProfiles.select(a,active);
        ByteArrayOutputStream backup=new ByteArrayOutputStream();DataArchive.write(a,backup);byte[] archive=backup.toByteArray();
        check(!new String(archive,StandardCharsets.ISO_8859_1).contains("api-key"),"Complete backup contains no credential files");
        File fixtureOutput=new File(getTargetContext().getExternalFilesDir(null),"compat-checks");fixtureOutput.mkdirs();SpeechOutput.atomic(new File(fixtureOutput,"android-history.json"),exported.toString().getBytes(StandardCharsets.UTF_8));SpeechOutput.atomic(new File(fixtureOutput,"android-data.zip"),archive);
        store.close();java.lang.reflect.Field field=AppData.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,null);
        SpeechOutput.atomic(new File(fixtureOutput,"android-history.sqlite3"),SpeechOutput.read(a.getDatabasePath("history.sqlite3")));
        Fixture b=new Fixture(getTargetContext(),"compat-b-"+AppData.id());DataArchive.read(b,new ByteArrayInputStream(archive));AppData restored=AppData.get(b);
        check(restored.export(false).getJSONArray("records").length()==2&&new File(b.getFilesDir(),"favorites/"+f.getString("Id")+"/word-fixture.mp3").isFile(),"ZIP restore preserves records and verified audio assets");
        check(restored.export(false).getJSONArray("selection_events").length()==3,"ZIP restore preserves the long-term selection ledger");
        ByteArrayOutputStream corrupted=new ByteArrayOutputStream();try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(archive));ZipOutputStream out=new ZipOutputStream(corrupted)){
            ZipEntry e;boolean changed=false;while((e=in.getNextEntry())!=null){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);byte[] content=bytes.toByteArray();if(!changed&&e.getName().endsWith(".mp3")){content[0]^=1;changed=true;}out.putNextEntry(new ZipEntry(e.getName()));out.write(content);out.closeEntry();}}
        rejected=false;try{DataArchive.read(b,new ByteArrayInputStream(corrupted.toByteArray()));}catch(Exception e){rejected=true;}check(rejected&&restored.export(false).getJSONArray("records").length()==2,"Corrupted audio is rejected by SHA-256 before importing records");
        ByteArrayOutputStream malicious=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(malicious)){zip.putNextEntry(new ZipEntry("../outside.txt"));zip.write(1);zip.closeEntry();}
        rejected=false;try{DataArchive.read(b,new ByteArrayInputStream(malicious.toByteArray()));}catch(Exception e){rejected=true;}check(rejected&&!new File(b.root,"outside.txt").exists(),"ZIP traversal is rejected before data mutation");
        File existing=new File(b.getFilesDir(),"favorites/"+f.getString("Id")+"/word-fixture.mp3");byte[] personal="different local content".getBytes(StandardCharsets.UTF_8);SpeechOutput.atomic(existing,personal);
        rejected=false;try{DataArchive.read(b,new ByteArrayInputStream(archive));}catch(Exception e){rejected=true;}check(rejected&&Arrays.equals(SpeechOutput.read(existing),personal),"Restoring a same-name different audio file preserves the existing local file");
        SpeechOutput.atomic(new File(fixtureOutput,"results.txt"),report.toString().getBytes(StandardCharsets.UTF_8));reset();
    }
}
