package cn.weilin.termlens.probe;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Portable data-root layout; managed manifests validate every audio asset before transfer. */
final class DataArchive {
    static void write(Context context,OutputStream output) throws Exception {
        synchronized(SpeechOutput.ASSET_LOCK){writeLocked(context,output);}
    }
    private static void writeLocked(Context context,OutputStream output) throws Exception {
        try(ZipOutputStream zip=new ZipOutputStream(output)) {
            entry(zip,"history.json",AppData.get(context).export(false).toString().getBytes(StandardCharsets.UTF_8));
            entry(zip,"prompt-library.json",SceneProfiles.library(context).toString().getBytes(StandardCharsets.UTF_8));
            for(String group:new String[]{"history-audio","favorites"}) {
                File parent=new File(context.getFilesDir(),group);File[] directories=parent.listFiles();if(directories==null)continue;
                for(File dir:directories){if(!dir.isDirectory()||!AppData.safeId(dir.getName()))continue;File manifest=new File(dir,"manifest.json");if(!manifest.isFile())continue;
                    JSONObject m=AppData.object(new String(SpeechOutput.read(manifest),StandardCharsets.UTF_8));Map<String,JSONObject> assets=assets(m,dir.getName(),group.equals("favorites"));
                    // Serialize an internally consistent revision, not partially written synthesis files.
                    boolean valid=true;Map<String,byte[]> bytes=new LinkedHashMap<>();
                    for(Map.Entry<String,JSONObject> asset:assets.entrySet()){File file=new File(dir,asset.getKey());if(!file.isFile()){valid=false;break;}byte[] content=SpeechOutput.read(file);validate(content,asset.getValue());bytes.put(asset.getKey(),content);}
                    if(!valid)continue;
                    entry(zip,group+"/"+dir.getName()+"/manifest.json",m.toString().getBytes(StandardCharsets.UTF_8));
                    for(Map.Entry<String,byte[]> asset:bytes.entrySet())entry(zip,group+"/"+dir.getName()+"/"+asset.getKey(),asset.getValue());
                }
            }
        }
    }
    private static void entry(ZipOutputStream zip,String name,byte[] bytes) throws Exception {zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}
    static Map<String,JSONObject> assets(JSONObject m,String id,boolean favorite) throws Exception {
        if(m.optInt("Version")!=1||!id.equals(m.optString(favorite?"FavoriteId":"QueryId")))throw new Exception("音频清单标识无效");
        Map<String,JSONObject> assets=new LinkedHashMap<>();
        for(String role:new String[]{"Word","Explanation","Retired"}) {JSONArray list=m.optJSONArray(role);if(list==null){if(role.equals("Retired"))continue;throw new Exception("音频清单缺少字段");}
            for(int i=0;i<list.length();i++){JSONObject a=list.getJSONObject(i);String name=a.getString("File");if(!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,180}\\.(wav|mp3)")||!a.getString("Sha256").matches("[0-9a-fA-F]{64}"))throw new Exception("音频清单文件名无效");
                JSONObject old=assets.put(name,a);if(old!=null&&(!old.getString("Sha256").equalsIgnoreCase(a.getString("Sha256"))||old.getLong("Bytes")!=a.getLong("Bytes")))throw new Exception("音频清单冲突");}}
        return assets;
    }
    private static void validate(byte[] bytes,JSONObject asset) throws Exception {if(bytes.length!=asset.getLong("Bytes")||!SpeechOutput.hash(bytes).equalsIgnoreCase(asset.getString("Sha256")))throw new Exception("音频校验失败，导入已取消");}
    static String read(Context context,InputStream input) throws Exception {
        synchronized(SpeechOutput.ASSET_LOCK){return readLocked(context,input);}
    }
    private static String readLocked(Context context,InputStream input) throws Exception {
        File stage=new File(context.getCacheDir(),"import-"+AppData.id());if(!stage.mkdirs())throw new Exception("无法创建导入暂存目录");
        Map<String,File> extracted=new LinkedHashMap<>();long total=0;
        try(ZipInputStream zip=new ZipInputStream(input)) {
            ZipEntry e;while((e=zip.getNextEntry())!=null){String name=e.getName();if(e.isDirectory())continue;
                if(!(name.equals("history.json")||name.equals("prompt-library.json")||name.matches("(history-audio|favorites)/[A-Za-z0-9_-]{1,128}/(manifest\\.json|[A-Za-z0-9][A-Za-z0-9._-]{0,180}\\.(wav|mp3))")))throw new Exception("备份包含不支持的路径");
                if(extracted.containsKey(name)||extracted.size()>10000)throw new Exception("备份包含重复路径或文件过多");
                File file=new File(stage,name);file.getParentFile().mkdirs();long size=0;
                try(FileOutputStream out=new FileOutputStream(file)){byte[] buffer=new byte[8192];int n;while((n=zip.read(buffer))!=-1){size+=n;total+=n;if(size>64L*1024*1024||total>256L*1024*1024)throw new Exception("备份解压后过大");out.write(buffer,0,n);}}
                extracted.put(name,file);
            }
            if(!extracted.containsKey("history.json"))throw new Exception("缺少 history.json");
            if(extracted.containsKey("prompt-library.json"))SceneProfiles.validate(AppData.object(new String(SpeechOutput.read(extracted.get("prompt-library.json")),StandardCharsets.UTF_8)));
            JSONObject history=AppData.object(new String(SpeechOutput.read(extracted.get("history.json")),StandardCharsets.UTF_8));
            Set<String> verified=new HashSet<>();verified.add("history.json");verified.add("prompt-library.json");
            for(Map.Entry<String,File> file:extracted.entrySet())if(file.getKey().endsWith("/manifest.json")){
                String[] path=file.getKey().split("/");JSONObject m=AppData.object(new String(SpeechOutput.read(file.getValue()),StandardCharsets.UTF_8));Map<String,JSONObject> assets=assets(m,path[1],path[0].equals("favorites"));
                verified.add(file.getKey());String parent=path[0]+"/"+path[1]+"/";
                for(Map.Entry<String,JSONObject> asset:assets.entrySet()){String name=parent+asset.getKey();File bytes=extracted.get(name);if(bytes==null)throw new Exception("音频文件缺失");validate(SpeechOutput.read(bytes),asset.getValue());verified.add(name);}
            }
            if(!verified.containsAll(extracted.keySet()))throw new Exception("备份包含未登记的音频文件");
            for(Map.Entry<String,File> file:extracted.entrySet())if(file.getKey().contains("/")){
                File target=new File(context.getFilesDir(),file.getKey());if(target.exists()&&!SpeechOutput.hash(SpeechOutput.read(target)).equals(SpeechOutput.hash(SpeechOutput.read(file.getValue()))))throw new Exception("本机已有不同内容的同名音频，保留本机数据");}
            String result=AppData.get(context).importData(history);
            for(Map.Entry<String,File> file:extracted.entrySet())if(file.getKey().contains("/")){File target=new File(context.getFilesDir(),file.getKey());if(!target.exists())SpeechOutput.atomic(target,SpeechOutput.read(file.getValue()));}
            if(extracted.containsKey("prompt-library.json"))SceneProfiles.merge(context,AppData.object(new String(SpeechOutput.read(extracted.get("prompt-library.json")),StandardCharsets.UTF_8)));
            // Restore available favorite assets without pretending the current engine synthesized them.
            JSONArray favorites=history.optJSONArray("favorites");
            for(int i=0;i<favorites.length();i++){JSONObject imported=favorites.getJSONObject(i),current=AppData.get(context).favorite(imported.optString("Kind"),imported.optString("Term"));
                if(current!=null&&current.optString("Id").equals(imported.optString("Id"))&&current.optString("QueryId").equals(imported.optString("QueryId"))&&current.optString("Explanation").equals(imported.optString("Explanation"))&&current.optString("Term").equals(imported.optString("Term"))&&extracted.containsKey("favorites/"+current.optString("Id")+"/manifest.json")){
                    AppData.put(current,"WordAudio",imported.optJSONArray("WordAudio"));AppData.put(current,"ExplanationAudio",imported.optJSONArray("ExplanationAudio"));AppData.put(current,"AudioState",imported.optString("AudioState"));AppData.put(current,"Voice",imported.optString("Voice"));AppData.get(context).storeFavorite(current);}}
            SpeechOutput.invalidateIndex();return result+"；音频备份已校验导入";
        }finally{removeStage(stage);}
    }
    private static void removeStage(File path){File[] files=path.listFiles();if(files!=null)for(File file:files)removeStage(file);path.delete();}
}
