package au.com.roningroup.patrollink;

import android.content.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Offline, validated first-run installation. Audio is attached at final local packaging,
 * not committed to the repository. Never invent an ordered transcript from clip count. */
public final class BundledTiff {
    public static final String ASSET="bundled_voice/tiff.zip";
    public static final String ID_PREF="bundled_tiff_profile_v138";
    public static final String DONE_PREF="bundled_tiff_installed_v138";
    private static final long MAX_TOTAL=512L*1024L*1024L;
    public interface Progress { void update(String text); }
    public static final class Result {
        public final String id,source;public final int count;
        Result(String id,String source,int count){this.id=id;this.source=source;this.count=count;}
    }
    private BundledTiff(){}
    public static boolean available(Context c){
        try(InputStream in=c.getAssets().open(ASSET)){return in.read()>=0;}catch(IOException e){return false;}
    }
    public static String readyId(Context c,SharedPreferences prefs){
        String id=prefs.getString(ID_PREF,"");
        return id!=null&&!id.isEmpty()&&ExactPhrasePack.installed(c,id)?id:"";
    }
    private static List<String> manifest(JSONArray array)throws Exception{
        if(array==null)return Collections.emptyList();
        ArrayList<String> out=new ArrayList<>();
        for(int i=0;i<array.length();i++)out.add(array.getString(i));
        return VoiceScriptText.parse(VoiceScriptText.format(out));
    }
    /** The app saved this exact ordered text when the user copied the whole script.
     * An installed Evelyn library is used only when no matching saved copy remains. */
    private static List<String> sourcePhrases(Context c,SharedPreferences prefs,int count)throws Exception{
        List<String> saved=Collections.emptyList();
        String raw=prefs.getString("voice_complete_library_template_v1","[]");
        try{JSONArray a=new JSONArray(raw);if(a.length()>0)saved=manifest(a);}catch(Exception ignored){}
        if(saved.size()==count)return saved;
        JSONArray profiles=new JSONArray(prefs.getString("voice_profiles_v5","[]"));
        List<String> selected=null;
        for(int i=0;i<profiles.length();i++){
            JSONObject p=profiles.getJSONObject(i);
            if(!p.optString("name").equalsIgnoreCase("Evelyn"))continue;
            String id=p.optString("id");
            if(!ExactPhrasePack.installed(c,id))continue;
            List<String> current=ExactPhrasePack.phrases(c,id);
            if(current.size()!=count)continue;
            current=VoiceScriptText.parse(VoiceScriptText.format(current));
            if(selected!=null&&!selected.equals(current))throw new IOException("More than one Evelyn script matches this recording. Select the source voice and tap Copy entire voice script, then Finish Tiff setup.");
            selected=current;
        }
        if(selected!=null)return selected;
        throw new IOException("Tiff's recording is included ("+count+" phrases), but the saved script has "+saved.size()+". Select the voice whose script you recorded, tap Copy entire voice script, then Finish Tiff setup. No need to regenerate or import the WAV.");
    }
    private static String targetId(Context c,SharedPreferences prefs,String source)throws Exception{
        String prior=prefs.getString(ID_PREF,"");
        if(prior!=null&&prior.matches("[A-Za-z0-9_-]{1,90}"))return prior;
        JSONArray profiles=new JSONArray(prefs.getString("voice_profiles_v5","[]"));
        File root=new File(c.getFilesDir(),"exact_voice_packs");
        for(int i=0;i<profiles.length();i++){
            JSONObject p=profiles.getJSONObject(i);String id=p.optString("id");
            if(!p.optString("name").equalsIgnoreCase("Tiff")||!id.matches("[A-Za-z0-9_-]{1,90}"))continue;
            File dir=new File(root,id);String[] names=dir.list();
            if(!dir.exists()||(names!=null&&names.length==0))return id;
        }
        return "tiff-"+source.substring(0,12);
    }
    private static byte[] readBounded(InputStream in,int limit)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Bundled metadata is too large.");out.write(b,0,n);}
        return out.toByteArray();
    }
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private static void removeStage(File f){if(f.isDirectory()){File[] all=f.listFiles();if(all!=null)for(File child:all)removeStage(child);}f.delete();}

    public static Result install(Context c,SharedPreferences prefs,InputStream archive,Progress progress)throws Exception{
        File staging=null;
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(archive))){
            ZipEntry entry=zip.getNextEntry();
            if(entry==null||!entry.getName().equals("bundle.json"))throw new IOException("Tiff bundle metadata is missing.");
            JSONObject info=new JSONObject(new String(readBounded(zip,1024*1024),StandardCharsets.UTF_8));
            String source=info.getString("source_sha256");int count=info.getInt("count");
            if(info.optInt("version")!=1||!info.optString("name").equals("Tiff")||!source.matches("[0-9a-f]{64}")||count<1||count>2000)throw new IOException("Invalid Tiff bundle metadata.");
            String ready=readyId(c,prefs);
            if(!ready.isEmpty())return new Result(ready,source,ExactPhrasePack.phrases(c,ready).size());
            List<String> phrases=sourcePhrases(c,prefs,count);
            JSONArray checks=info.getJSONArray("clips");if(checks.length()!=count)throw new IOException("Tiff clip index is incomplete.");
            File root=new File(c.getFilesDir(),"exact_voice_packs");
            if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Voice storage is unavailable.");
            String id=targetId(c,prefs,source);File target=new File(root,id);
            if(target.exists()){
                File prior=new File(target,"manifest.json");
                if(prior.isFile()){
                    JSONObject old=new JSONObject(new String(java.nio.file.Files.readAllBytes(prior.toPath()),StandardCharsets.UTF_8));
                    if(source.equals(old.optString("source_sha256"))&&ExactPhrasePack.installed(c,id))return new Result(id,source,ExactPhrasePack.phrases(c,id).size());
                }
                String[] old=target.list();if(old==null||old.length>0)throw new IOException("Existing Tiff data was preserved. Its folder is not empty; no recordings were overwritten.");
            }
            staging=new File(root,"tiff-stage-"+UUID.randomUUID());
            if(!staging.mkdirs())throw new IOException("Not enough storage to prepare Tiff.");
            long total=0;byte[] buffer=new byte[65536];
            for(int i=0;i<count;i++){
                zip.closeEntry();entry=zip.getNextEntry();
                String expected=String.format(Locale.ROOT,"clip-%04d.wav",i);
                JSONObject check=checks.getJSONObject(i);long size=check.getLong("bytes");
                String digest=check.getString("sha256");
                if(size<44||size>32*1024*1024||!digest.matches("[0-9a-f]{64}")||entry==null||entry.isDirectory()||!expected.equals(entry.getName()))throw new IOException("Tiff clip "+(i+1)+" is missing or invalid.");
                MessageDigest sha=MessageDigest.getInstance("SHA-256");long written=0;
                File file=new File(staging,expected);
                try(FileOutputStream out=new FileOutputStream(file)){
                    int n;while((n=zip.read(buffer))!=-1){written+=n;total+=n;if(written>size||total>MAX_TOTAL)throw new IOException("Tiff archive size check failed.");sha.update(buffer,0,n);out.write(buffer,0,n);}
                    out.getFD().sync();
                }
                if(written!=size||!digest.equals(hex(sha.digest())))throw new IOException("Tiff clip "+(i+1)+" did not pass integrity verification.");
                try(RandomAccessFile wav=new RandomAccessFile(file,"r")){
                    byte[] tag=new byte[12];wav.readFully(tag);
                    if(!new String(tag,0,4,StandardCharsets.US_ASCII).equals("RIFF")||!new String(tag,8,4,StandardCharsets.US_ASCII).equals("WAVE"))throw new IOException("Invalid bundled WAV clip.");
                }
                if(progress!=null&&(i%25==0||i==count-1))progress.update("Preparing Tiff: "+(i+1)+" / "+count+" recordings");
            }
            zip.closeEntry();if(zip.getNextEntry()!=null)throw new IOException("Unexpected extra data in the Tiff archive.");
            JSONObject manifest=new JSONObject();manifest.put("version",2);manifest.put("source_sha256",source);
            manifest.put("phrases",new JSONArray(phrases));manifest.put("bundled_name","Tiff");
            try(FileOutputStream out=new FileOutputStream(new File(staging,"manifest.json"))){out.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            if(target.exists()&&!target.delete())throw new IOException("Could not prepare Tiff's empty folder.");
            if(!staging.renameTo(target))throw new IOException("Could not finish installing Tiff. Existing voices are unchanged.");
            staging=null;
            return new Result(id,source,count);
        }finally{if(staging!=null)removeStage(staging);}
    }

    /** Called on the UI thread: add only Tiff, using the latest profile list. */
    public static void register(SharedPreferences prefs,Result r)throws Exception{
        JSONArray list=new JSONArray(prefs.getString("voice_profiles_v5","[]"));boolean found=false;
        for(int i=0;i<list.length();i++)if(r.id.equals(list.getJSONObject(i).optString("id"))){found=true;break;}
        if(!found){JSONObject p=new JSONObject();p.put("id",r.id);p.put("name","Tiff");list.put(p);}
        if(!prefs.edit().putString("voice_profiles_v5",list.toString()).putString(ID_PREF,r.id).putBoolean(DONE_PREF,true).commit())throw new IOException("Tiff was prepared but could not be saved in the profile list. Retry setup.");
    }
}
