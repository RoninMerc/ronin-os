package au.com.roningroup.patrollink;

import android.content.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.util.*;

/** User-reviewed text-to-original-recording mapping, scoped to exactly one voice profile. */
public final class RecordedVoices {
    public static final class Clip {
        public final String text;public final File file;
        Clip(String t,File f){text=t;file=f;}
    }
    public static final class Draft {
        public final File directory;public final List<Clip> clips;
        Draft(File d,List<Clip> c){directory=d;clips=Collections.unmodifiableList(c);}
    }
    private final Context context;private final SharedPreferences prefs;private final File root;
    public RecordedVoices(Context c){context=c.getApplicationContext();prefs=context.getSharedPreferences("patrol_original_recordings_v1",Context.MODE_PRIVATE);root=new File(context.getFilesDir(),"recorded_voices");}
    private void ensure()throws IOException{if(!root.exists()&&!root.mkdirs())throw new IOException("Cannot create original-recording storage.");}
    private JSONObject index(String id){try{return new JSONObject(prefs.getString("voice:"+id,"{}"));}catch(JSONException e){return new JSONObject();}}
    public synchronized List<Clip> clips(String id){JSONObject a=index(id);ArrayList<Clip> out=new ArrayList<>();for(Iterator<String> it=a.keys();it.hasNext();){JSONObject o=a.optJSONObject(it.next());if(o!=null){File f=new File(o.optString("path"));if(safe(f)&&f.isFile())out.add(new Clip(o.optString("text"),f));}}out.sort(Comparator.comparing(c->c.text,String.CASE_INSENSITIVE_ORDER));return out;}
    public synchronized File find(String id,String text){JSONObject o=index(id).optJSONObject(RecordedWav.key(text));if(o==null)return null;File f=new File(o.optString("path"));return safe(f)&&f.isFile()?f:null;}
    public synchronized int count(String id){return clips(id).size();}
    public synchronized void need(String id,String text){
        LinkedHashSet<String> lines=new LinkedHashSet<>(missing(id));lines.add(text);while(lines.size()>1000)lines.remove(lines.iterator().next());prefs.edit().putString("missing:"+id,new JSONArray(lines).toString()).apply();
    }
    public synchronized List<String> missing(String id){ArrayList<String> out=new ArrayList<>();try{JSONArray a=new JSONArray(prefs.getString("missing:"+id,"[]"));for(int i=0;i<a.length();i++){String s=a.optString(i);if(!s.isEmpty()&&find(id,s)==null)out.add(s);}}catch(Exception ignored){}return out;}
    public String pendingScript(){return prefs.getString("pending_script","");}
    public void pendingScript(String s){prefs.edit().putString("pending_script",s).apply();}
    public Draft prepare(Uri uri,String script,int gapMs,boolean single)throws Exception{
        List<String> lines=RecordedWav.scriptLines(script);if(single&&lines.size()!=1)throw new IOException("Single recording mode needs exactly one line: the complete words spoken in that WAV.");
        ensure();File dir=new File(root,"batch-"+UUID.randomUUID());if(!dir.mkdir())throw new IOException("Cannot stage this recording.");File source=new File(dir,"source.wav");
        boolean complete=false;
        try{
            try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(source)){if(in==null)throw new IOException("Cannot open this WAV.");byte[] b=new byte[65536];int n;long total=0;while((n=in.read(b))!=-1){total+=n;if(total>250L*1024*1024)throw new IOException("Use a WAV smaller than 250 MB.");out.write(b,0,n);}}
            RecordedWav.Format fmt=RecordedWav.inspect(source);ArrayList<Clip> clips=new ArrayList<>();
            if(single){if(fmt.seconds()>180)throw new IOException("This looks like a long training recording. Single mode is for one complete announcement, up to 3 minutes.");clips.add(new Clip(lines.get(0),source));}
            else{
                List<RecordedWav.Span> spans=RecordedWav.split(source,gapMs);
                if(spans.size()!=lines.size())throw new IOException("Found "+spans.size()+" audio passages for "+lines.size()+" script lines. Nothing was installed. Adjust the pause threshold, or generate each line separately and use Single recording. The app will not guess the missing boundaries.");
                for(int i=0;i<spans.size();i++){File clip=new File(dir,String.format(Locale.ROOT,"clip-%03d.wav",i+1));RecordedWav.slice(source,clip,spans.get(i));clips.add(new Clip(lines.get(i),clip));}
                source.delete();
            }
            complete=true;return new Draft(dir,clips);
        }finally{if(!complete)remove(dir);}
    }
    public synchronized void save(String id,Draft draft)throws Exception{
        if(!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IOException("Invalid voice profile.");
        JSONObject current=index(id);
        for(Clip clip:draft.clips){if(!safe(clip.file)||!clip.file.isFile())throw new IOException("A staged recording is missing.");JSONObject o=new JSONObject();o.put("text",clip.text);o.put("path",clip.file.getCanonicalPath());current.put(RecordedWav.key(clip.text),o);}
        if(!prefs.edit().putString("voice:"+id,current.toString()).commit())throw new IOException("Could not save the recording library.");
    }
    public boolean safe(File file){try{return file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator);}catch(IOException e){return false;}}
    public void discard(Draft d){if(d!=null&&safe(d.directory))remove(d.directory);}
    public synchronized void delete(String id){for(Clip c:clips(id))c.file.delete();prefs.edit().remove("voice:"+id).remove("missing:"+id).apply();}
    private static void remove(File d){File[] children=d.listFiles();if(children!=null)for(File f:children)remove(f);d.delete();}
}
