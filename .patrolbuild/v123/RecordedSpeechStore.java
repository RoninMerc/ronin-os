package au.com.roningroup.patrollink;

import android.content.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Each recording belongs to one voice and one COMPLETE final announcement. */
public final class RecordedSpeechStore {
    private final Context context;private final SharedPreferences prefs;
    public RecordedSpeechStore(Context c){context=c.getApplicationContext();prefs=context.getSharedPreferences("patrol_recorded_speech_v123",Context.MODE_PRIVATE);}
    public File directory()throws IOException{File d=new File(context.getFilesDir(),"recorded_speech_v123");if(!d.exists()&&!d.mkdirs())throw new IOException("Cannot open recording storage.");return d;}
    private static String key(String voice,String text){try{byte[] d=MessageDigest.getInstance("SHA-256").digest((voice+"\n"+SpeechRules.key(text)).getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:d)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    public File recording(String voice,String text){String path=prefs.getString("phrase_"+key(voice,text),"");try{File f=new File(path).getCanonicalFile();if(!path.isEmpty()&&f.getPath().startsWith(directory().getCanonicalPath()+File.separator)&&f.isFile())return f;}catch(IOException ignored){}return null;}
    public synchronized File save(String voice,String text,File source,double from,double to)throws Exception{
        if(voice==null||voice.isEmpty()||SpeechRules.clean(text).isEmpty())throw new IOException("A voice and complete spoken phrase are required.");
        File dest=new File(directory(),UUID.randomUUID()+".wav"),old=recording(voice,text);boolean saved=false;
        try{ExactWav.open(source).cut(dest,from,to);if(!prefs.edit().putString("phrase_"+key(voice,text),dest.getPath()).commit())throw new IOException("Could not save the recording assignment.");saved=true;if(old!=null)old.delete();remember(text);return dest;}finally{if(!saved)dest.delete();}
    }
    public File importSource(Uri uri)throws Exception{
        File dest=new File(directory(),"source-"+UUID.randomUUID()+".wav");boolean saved=false;
        try{try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(dest)){if(in==null)throw new IOException("Cannot open the selected WAV.");byte[] b=new byte[65536];long total=0;int n;while((n=in.read(b))!=-1){total+=n;if(total>250L*1024*1024)throw new IOException("The recording exceeds 250 MB.");out.write(b,0,n);}}
            ExactWav wav=ExactWav.open(dest);if(wav.seconds()>3600)throw new IOException("Import a recording no longer than one hour.");saved=true;return dest;
        }finally{if(!saved)dest.delete();}
    }
    public void setSource(String voice,File f){prefs.edit().putString("source_"+key(voice,"source"),f.getPath()).apply();}
    public File source(String voice){String path=prefs.getString("source_"+key(voice,"source"),"");try{File f=new File(path).getCanonicalFile();if(!path.isEmpty()&&f.getPath().startsWith(context.getFilesDir().getCanonicalPath()+File.separator)&&f.isFile())return f;}catch(IOException ignored){}return null;}
    public synchronized void remember(String text){String clean=SpeechRules.clean(text);if(clean.isEmpty())return;JSONArray old;try{old=new JSONArray(prefs.getString("needed","[]"));}catch(Exception e){old=new JSONArray();}String k=SpeechRules.key(clean);for(int i=0;i<old.length();i++)if(SpeechRules.key(old.optString(i)).equals(k))return;JSONArray a=new JSONArray();int start=Math.max(0,old.length()-1999);for(int i=start;i<old.length();i++)a.put(old.optString(i));a.put(clean);prefs.edit().putString("needed",a.toString()).apply();}
    public List<String> required(){ArrayList<String> out=new ArrayList<>();try{JSONArray a=new JSONArray(prefs.getString("needed","[]"));for(int i=0;i<a.length();i++)out.add(a.getString(i));}catch(Exception ignored){}return out;}
    public List<String> script(SpeechPreferences wording,Collection<Observation> current,List<String> guards){
        LinkedHashMap<String,String> desired=new LinkedHashMap<>();
        for(SpeechRules.Rule r:wording.rules())if(r.enabled)put(desired,r.spoken);
        for(SpeechPreferences.Seen s:wording.seen()){
            // Same received issue/property, rendered for each guard currently selected.
            for(String guard:guards)put(desired,wording.readout(new Observation("script",guard,s.property,s.issue,"",0)));
        }
        for(Observation o:current)put(desired,wording.readout(o));
        // Keep newly required live phrases, but not stale phrases replaced by a saved rule.
        if(desired.isEmpty())for(String text:required())put(desired,text);
        return new ArrayList<>(desired.values());
    }
    private static void put(Map<String,String> map,String text){String clean=SpeechRules.clean(text);if(!clean.isEmpty())map.putIfAbsent(SpeechRules.key(clean),clean);}
    public int recordedCount(String voice,List<String> phrases){int count=0;for(String s:phrases)if(recording(voice,s)!=null)count++;return count;}
    public void remove(String voice,String text){File f=recording(voice,text);prefs.edit().remove("phrase_"+key(voice,text)).apply();if(f!=null)f.delete();}
}
