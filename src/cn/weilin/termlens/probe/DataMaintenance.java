package cn.weilin.termlens.probe;
import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only expired, owned, hash-verified leaf audio directories are eligible for cache eviction. */
final class DataMaintenance {
    static int cleanAudio(Context context) throws Exception {
        int removed=0;long cutoff=System.currentTimeMillis()-15L*86400000L;AppData store=AppData.get(context);
        for(String group:new String[]{"history-audio","favorites"}) {
            File parent=new File(context.getFilesDir(),group);File[] directories=parent.listFiles();if(directories==null)continue;
            for(File dir:directories) {
                if(!dir.isDirectory()||!AppData.safeId(dir.getName())||store.protectAudio(group,dir.getName()))continue;
                try {
                    File primary=new File(dir,"manifest.json");JSONObject m=AppData.object(new String(SpeechOutput.read(primary),StandardCharsets.UTF_8));long created=AppData.parseDate(m.optString("Created"));
                    if(created<0||created>=cutoff)continue;Map<String,JSONObject> assets=DataArchive.assets(m,dir.getName(),group.equals("favorites"));
                    File backup=new File(dir,"manifest.json.bak");if(backup.isFile()){
                        JSONObject old=AppData.object(new String(SpeechOutput.read(backup),StandardCharsets.UTF_8));Map<String,JSONObject> extra=DataArchive.assets(old,dir.getName(),group.equals("favorites"));
                        for(Map.Entry<String,JSONObject> item:extra.entrySet()){JSONObject known=assets.get(item.getKey());if(known!=null&&!known.optString("Sha256").equals(item.getValue().optString("Sha256")))throw new Exception("清单冲突");assets.put(item.getKey(),item.getValue());}}
                    File[] files=dir.listFiles();if(files==null)continue;
                    for(File file:files){String name=file.getName();if(!file.isFile()||!file.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile()))throw new Exception("未知缓存路径");if(name.equals("manifest.json")||name.equals("manifest.json.bak"))continue;
                        JSONObject asset=assets.get(name);if(asset==null||file.length()!=asset.optLong("Bytes")||!SpeechOutput.hash(SpeechOutput.read(file)).equalsIgnoreCase(asset.optString("Sha256")))throw new Exception("缓存文件未经校验");}
                    for(File file:files)if(!file.getName().equals("manifest.json")&&!file.delete())throw new Exception("缓存正在使用");
                    if(primary.delete()&&dir.delete())removed++;
                }catch(Exception ignored){/* Unknown or modified data is preserved for review. */}
            }
        }
        return removed;
    }
}
