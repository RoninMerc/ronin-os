package au.com.roningroup.patrollink;

import android.content.*;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Main-process controller. All speech generation and playback happen in :voice. */
public final class VoiceManager {
    public static final String DEFAULT_ID="felicity",DEFAULT_NAME="Felicity";
    private static final String PROFILES="voice_profiles_v1",ACTIVE="voice_profile_active";
    public interface ImportCallback{void done(Profile profile,String error);}
    public static final class Profile {
        public final String id,name,path;public final long durationMs;public final boolean builtIn;
        Profile(String id,String name,String path,long duration,boolean builtin){this.id=id;this.name=name;this.path=path;this.durationMs=duration;this.builtIn=builtin;}
    }
    private static final class Entry {
        final String id,text,voice,reference;
        Entry(String text,Profile p){id=UUID.randomUUID().toString();this.text=text;voice=p.id;reference=p.builtIn?"":p.path;}
    }
    private final Context context;private final SharedPreferences prefs;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService imports=Executors.newSingleThreadExecutor();
    private final ArrayDeque<Entry> queue=new ArrayDeque<>();
    private Messenger remote;private boolean bound,modelReady;private Entry current;
    private Runnable listener;private int disconnects,completed,failed;private String stage="Starting local speech",lastError="",lastText="";
    private final Messenger replies=new Messenger(new Handler(Looper.getMainLooper(),m->{
        if(m.what!=LocalVoiceService.EVENT)return false;
        Bundle b=m.getData();String id=b.getString("id",""),state=b.getString("state",""),detail=b.getString("detail","");
        if(id.equals("init")){
            if(state.equals("READY")){modelReady=true;stage="Ready";lastError="";pump();}
            else if(state.equals("ERROR")){modelReady=false;stage="Speech unavailable";lastError=detail;}
            else stage="Loading local speech model";
            changed();return true;
        }
        if(current==null||!current.id.equals(id))return true;
        if(state.equals("DONE")||state.equals("ERROR")){
            handler.removeCallbacks(watchdog);
            if(state.equals("DONE")){completed++;lastText=current.text;stage="Ready";lastError="";}else{failed++;stage="Speech error";lastError=detail;}
            current=null;changed();handler.post(this::pump);
        } else if(state.equals("SPEAKING")){stage="Speaking";changed();}
        else if(state.equals("GENERATING")){stage="Generating full readout";changed();}
        return true;
    }));
    private final Runnable watchdog=()->{
        if(current==null)return;failed++;lastError="Local speech took too long; monitoring has not been interrupted.";stage="Speech timeout";current=null;sendStop();changed();pump();
    };
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){remote=new Messenger(binder);modelReady=false;send(LocalVoiceService.INIT,"init","","");}
        public void onServiceDisconnected(ComponentName name){remote=null;modelReady=false;bound=false;try{context.unbindService(this);}catch(Exception ignored){}current=null;failed++;stage="Speech process restarted";lastError="Speech process disconnected; the feed continues independently.";changed();if(++disconnects<=3)handler.postDelayed(VoiceManager.this::ensureService,3000);}
        public void onBindingDied(ComponentName name){onServiceDisconnected(name);}
        public void onNullBinding(ComponentName name){onServiceDisconnected(name);}
    };
    public VoiceManager(Context c,SharedPreferences p){context=c.getApplicationContext();prefs=p;migrate();handler.post(this::ensureService);}
    public void setListener(Runnable listener){this.listener=listener;}
    private void changed(){if(listener!=null)listener.run();}
    public String activeName(){return activeProfile().name;}
    public boolean ready(){Profile p=activeProfile();return modelReady&&(p.builtIn||(p.path!=null&&new File(p.path).isFile()))&&lastError.isEmpty();}
    public String status(){return stage+(queue.isEmpty()?"":" · "+queue.size()+" queued");}
    public String diagnostics(){return "Voice mode: on-device reference synthesis\nVoice state: "+status()+"\nCompleted/failed speech: "+completed+"/"+failed+"\nVoice error: "+(lastError.isEmpty()?"none":lastError);}
    public String lastText(){return lastText;}
    private void migrate(){
        try{
            JSONArray arr=new JSONArray(prefs.getString(PROFILES,"[]")),other=new JSONArray(prefs.getString("voice_profiles_v2","[]"));Set<String> ids=new HashSet<>();
            for(int i=0;i<arr.length();i++){JSONObject o=arr.optJSONObject(i);if(o!=null){ids.add(o.optString("id"));o.remove("cloudVoiceId");}}
            for(int i=0;i<other.length();i++){JSONObject o=other.optJSONObject(i);if(o!=null&&!ids.contains(o.optString("id"))){o.remove("cloudVoiceId");arr.put(o);}}
            SharedPreferences.Editor e=prefs.edit().putString(PROFILES,arr.toString()).remove("voice_profiles_v2").remove("felicity_cloud_voice_id").remove("voice_api_key_cipher_v1");
            if(!prefs.contains(ACTIVE))e.putString(ACTIVE,DEFAULT_ID);if(!prefs.contains("voice"))e.putBoolean("voice",true);e.apply();
        }catch(Exception ignored){}
    }
    public List<Profile> profiles(){
        List<Profile> out=new ArrayList<>();out.add(new Profile(DEFAULT_ID,DEFAULT_NAME,null,12000,true));
        try{JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&!o.optString("id").isEmpty())out.add(new Profile(o.optString("id"),o.optString("name","Imported voice"),o.optString("path"),o.optLong("duration",12000),false));}}catch(Exception ignored){}return out;
    }
    public Profile activeProfile(){String id=prefs.getString(ACTIVE,DEFAULT_ID);for(Profile p:profiles())if(p.id.equals(id))return p;return new Profile(id,"Missing selected voice",null,0,false);}
    public void setActive(String id){for(Profile p:profiles())if(p.id.equals(id)){stop();prefs.edit().putString(ACTIVE,id).apply();lastError="";stage=modelReady?"Ready":"Loading local speech model";ensureService();changed();return;}}
    public void speak(Observation o){if(o!=null)enqueue(VoiceReadout.of(o));}
    public void test(){lastError="";disconnects=0;ensureService();if(remote!=null&&!modelReady)send(LocalVoiceService.INIT,"init","","");enqueue("T Murd. Third warning parking breach. Impeccable.");}
    private void enqueue(String text){
        if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(()->enqueue(text));return;}
        if(queue.size()>=128){failed++;lastError="Speech queue is full; a new announcement could not be queued.";changed();return;}
        queue.addLast(new Entry(text,activeProfile()));ensureService();pump();changed();
    }
    private void ensureService(){
        if(bound)return;stage="Loading local speech model";
        try{bound=context.bindService(new Intent(context,LocalVoiceService.class),connection,Context.BIND_AUTO_CREATE);if(!bound){stage="Speech unavailable";lastError="Android could not start the local speech process.";}}catch(Exception e){stage="Speech unavailable";lastError="Android could not start local speech.";}
        changed();
    }
    private void pump(){
        if(current!=null||remote==null||!modelReady)return;
        while(!queue.isEmpty()){
            Entry e=queue.pollFirst();if(!e.voice.equals(activeProfile().id))continue;current=e;
            if(e.reference==null){failed++;lastError="Selected voice is missing. Reimport it; no substitute voice was used.";current=null;changed();continue;}
            stage="Generating full readout";send(LocalVoiceService.SPEAK,e.id,e.text,e.reference);handler.removeCallbacks(watchdog);handler.postDelayed(watchdog,120000);changed();break;
        }
    }
    private void send(int what,String id,String text,String reference){
        if(remote==null)return;Message m=Message.obtain(null,what);Bundle b=new Bundle();b.putString("id",id);b.putString("text",text);b.putString("reference",reference);m.setData(b);m.replyTo=replies;
        try{remote.send(m);}catch(RemoteException e){modelReady=false;lastError="Speech worker disconnected.";stage="Speech unavailable";current=null;changed();}
    }
    private void sendStop(){if(remote!=null)try{remote.send(Message.obtain(null,LocalVoiceService.STOP));}catch(RemoteException ignored){}}
    public void stop(){handler.removeCallbacks(watchdog);queue.clear();current=null;sendStop();stage=modelReady?"Ready":"Starting local speech";changed();}
    public void importAudio(Uri uri,String name,ImportCallback callback){
        imports.execute(()->{Profile p=null;String problem=null;try{p=importTrainingAudio(uri,name);}catch(Exception e){problem=e.getMessage()==null?"Could not import recording":e.getMessage();}final Profile result=p;final String error=problem;handler.post(()->{if(result!=null)setActive(result.id);callback.done(result,error);});});
    }
    public synchronized Profile importTrainingAudio(Uri uri,String requestedName)throws Exception {
        String name=requestedName==null?"":requestedName.trim();if(uri==null||name.isEmpty()||name.length()>40)throw new IOException("Choose a WAV recording and a name of 1–40 characters.");
        File dir=new File(context.getFilesDir(),"voice_profiles");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create voice folder.");
        String id="voice-"+UUID.randomUUID();File original=new File(dir,id+".source.wav"),reference=new File(dir,id+".wav");boolean success=false;
        try{
            try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(original)){
                if(in==null)throw new IOException("Could not open this file.");byte[] b=new byte[65536];int n;long total=0;
                while((n=in.read(b))!=-1){total+=n;if(total>120L*1024*1024)throw new IOException("Use a WAV file smaller than 120 MB.");out.write(b,0,n);}
            }
            float[] samples=VoiceAudio.reference(original);VoiceAudio.write(reference,samples,VoiceAudio.RATE);
            JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));JSONObject o=new JSONObject();o.put("id",id);o.put("name",name);o.put("path",reference.getPath());o.put("source",original.getPath());o.put("duration",samples.length*1000L/VoiceAudio.RATE);a.put(o);
            if(!prefs.edit().putString(PROFILES,a.toString()).commit())throw new IOException("Could not save the voice library.");success=true;
            return new Profile(id,name,reference.getPath(),samples.length*1000L/VoiceAudio.RATE,false);
        }finally{if(!success){original.delete();reference.delete();}}
    }
    public synchronized boolean delete(String id){
        if(DEFAULT_ID.equals(id))return false;
        try{JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]")),out=new JSONArray();boolean removed=false;
            for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);if(id.equals(o.optString("id"))){safeDelete(o.optString("path"));safeDelete(o.optString("source"));removed=true;}else out.put(o);}
            if(removed){prefs.edit().putString(PROFILES,out.toString()).apply();if(id.equals(activeProfile().id)||id.equals(prefs.getString(ACTIVE,"")))setActive(DEFAULT_ID);}return removed;
        }catch(Exception e){return false;}
    }
    private void safeDelete(String path)throws IOException{if(path.isEmpty())return;File f=new File(path).getCanonicalFile();if(f.getPath().startsWith(context.getFilesDir().getCanonicalPath()+File.separator))f.delete();}
}
