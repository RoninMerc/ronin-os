package au.com.roningroup.patrollink;

import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Controller for reviewed original recordings only. The monitor continues independently. */
public final class VoiceManager {
    public static final String DEFAULT_ID="felicity",DEFAULT_NAME="Felicity";
    private static final String PROFILES="voice_profiles_v1",ACTIVE="voice_profile_active";
    public static final class Profile {
        public final String id,name,path;public final long durationMs;public final boolean builtIn;
        Profile(String id,String name,String path,long duration,boolean builtin){this.id=id;this.name=name;this.path=path;durationMs=duration;builtIn=builtin;}
    }
    private static final class Entry {
        final String id=UUID.randomUUID().toString(),voice;final Observation observation;final File audition;String text;
        Entry(String t,Profile p,Observation o,File f){text=t;voice=p.id;observation=o;audition=f;}
    }
    public final SpeechPreferences wording;public final RecordedVoices recordings;
    private final Context context;private final SharedPreferences prefs;private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayDeque<Entry> queue=new ArrayDeque<>();private Messenger remote;private boolean bound,playerReady;private Entry current;
    private Runnable listener;private int disconnects,completed,failed,missing;private float lastSpeed=1f;
    private String stage="Starting original-recording player",lastError="",lastText="";
    private final Messenger replies=new Messenger(new Handler(Looper.getMainLooper(),m->{
        if(m.what!=LocalVoiceService.EVENT)return false;Bundle b=m.getData();String id=b.getString("id",""),state=b.getString("state","");
        if(id.equals("init")){playerReady=state.equals("READY");stage=baseStatus();changed();pump();return true;}
        if(current==null||!current.id.equals(id))return true;
        if(state.equals("DONE")||state.equals("ERROR")){
            handler.removeCallbacks(this.watchdog);lastSpeed=b.getFloat("speed",1f);
            if(state.equals("DONE")){completed++;lastText=current.text;lastError="";}else{failed++;lastError=b.getString("detail","Recording playback failed");}
            current=null;stage=baseStatus();changed();handler.post(this::pump);
        }else if(state.equals("SPEAKING")){stage="Playing original "+activeName()+" recording";lastSpeed=b.getFloat("speed",1f);changed();}return true;
    }));
    private final Runnable watchdog=()->{if(current==null)return;failed++;lastError="Recording playback timed out; feed monitoring continues.";current=null;sendStop();stage=baseStatus();changed();pump();};
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder b){remote=new Messenger(b);playerReady=false;send(LocalVoiceService.INIT,"init","","",1f);}
        public void onServiceDisconnected(ComponentName n){remote=null;playerReady=false;bound=false;handler.removeCallbacks(watchdog);try{context.unbindService(this);}catch(Exception ignored){}current=null;failed++;stage="Recording player restarted";lastError="Player disconnected; monitoring continues.";changed();if(++disconnects<=3)handler.postDelayed(VoiceManager.this::ensureService,3000);}
        public void onBindingDied(ComponentName n){onServiceDisconnected(n);}public void onNullBinding(ComponentName n){onServiceDisconnected(n);}
    };
    public VoiceManager(Context c,SharedPreferences p){context=c.getApplicationContext();prefs=p;wording=new SpeechPreferences(context);recordings=new RecordedVoices(context);migrate();handler.post(this::ensureService);}
    private void migrate(){
        try{
            JSONArray arr=new JSONArray(prefs.getString(PROFILES,"[]"));Set<String> ids=new HashSet<>();for(int i=0;i<arr.length();i++){JSONObject o=arr.getJSONObject(i);ids.add(o.optString("id"));o.remove("cloudVoiceId");}
            for(String key:new String[]{"voice_profiles_v2","voice_profiles_v3"}){JSONArray a=new JSONArray(prefs.getString(key,"[]"));for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&ids.add(o.optString("id"))){o.remove("cloudVoiceId");arr.put(o);}}}
            SharedPreferences.Editor edit=prefs.edit().putString(PROFILES,arr.toString()).remove("voice_profiles_v2").remove("felicity_cloud_voice_id").remove("voice_api_key_cipher_v1");if(!prefs.contains(ACTIVE))edit.putString(ACTIVE,DEFAULT_ID);if(!prefs.contains("voice"))edit.putBoolean("voice",true);edit.apply();
        }catch(Exception ignored){}
    }
    public void setListener(Runnable l){listener=l;}private void changed(){if(listener!=null)listener.run();}
    public String activeName(){return activeProfile().name;}public boolean ready(){return playerReady&&recordings.count(activeProfile().id)>0;}
    private String baseStatus(){return recordings.count(activeProfile().id)==0?"Import "+activeName()+" alert recordings":"Ready · original recordings only";}
    public String status(){return stage+(queue.isEmpty()?"":" · "+queue.size()+" queued");}
    public String diagnostics(){return "Voice mode: original recordings only\nVoice state: "+status()+"\nRecorded alerts: "+recordings.count(activeProfile().id)+"\nCompleted/failed/missing recording: "+completed+"/"+failed+"/"+missing+"\nPlayback speed: "+wording.speedPercent()+"%\nVoice error: "+(lastError.isEmpty()?"none":lastError);}
    public String lastText(){return lastText;}public float lastSpeed(){return lastSpeed;}public int completedCount(){return completed;}
    public List<Profile> profiles(){ArrayList<Profile> out=new ArrayList<>();out.add(new Profile(DEFAULT_ID,DEFAULT_NAME,null,0,true));try{JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&!o.optString("id").isEmpty())out.add(new Profile(o.optString("id"),o.optString("name","Imported voice"),o.optString("path"),o.optLong("duration"),false));}}catch(Exception ignored){}return out;}
    public Profile activeProfile(){String id=prefs.getString(ACTIVE,DEFAULT_ID);for(Profile p:profiles())if(p.id.equals(id))return p;return new Profile(id,"Missing selected voice",null,0,false);}
    public synchronized Profile ensureNamedProfile(String name)throws Exception{
        String chosen=name.trim();if(chosen.isEmpty()||chosen.length()>40)throw new IOException("Voice name must be 1–40 characters.");
        for(Profile p:profiles())if(p.name.equalsIgnoreCase(chosen))return p;
        String id="voice-"+UUID.randomUUID();JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));JSONObject o=new JSONObject();o.put("id",id);o.put("name",chosen);o.put("path","");a.put(o);if(!prefs.edit().putString(PROFILES,a.toString()).commit())throw new IOException("Could not save voice profile.");return new Profile(id,chosen,"",0,false);
    }
    public void setActive(String id){for(Profile p:profiles())if(p.id.equals(id)){stop();prefs.edit().putString(ACTIVE,id).apply();lastError="";stage=baseStatus();ensureService();changed();return;}}
    public void remember(Collection<Observation> entries){try{wording.remember(entries);}catch(RuntimeException ignored){}}
    public void speak(Observation o){if(o!=null)enqueue(null,o,null);}
    public void preview(String text){enqueue(text,null,null);}
    public void audition(File file,String text){stop();enqueue(text,null,file);}
    public void test(){List<RecordedVoices.Clip> all=recordings.clips(activeProfile().id);if(all.isEmpty()){stage=baseStatus();lastError="No original alert recordings imported. Open Original recordings.";changed();return;}preview(all.get(0).text);}
    private void enqueue(String text,Observation row,File audition){if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(()->enqueue(text,row,audition));return;}if(queue.size()>=128){failed++;lastError="Recording queue is full.";changed();return;}queue.addLast(new Entry(text,activeProfile(),row,audition));ensureService();pump();changed();}
    private void ensureService(){if(bound)return;try{bound=context.bindService(new Intent(context,LocalVoiceService.class),connection,Context.BIND_AUTO_CREATE);if(!bound){lastError="Original recording player could not start.";stage=lastError;}}catch(Exception e){lastError="Original recording player unavailable.";stage=lastError;}changed();}
    private void pump(){
        if(current!=null||remote==null||!playerReady)return;
        while(!queue.isEmpty()){
            Entry item=queue.pollFirst();if(!item.voice.equals(activeProfile().id))continue;if(item.observation!=null)item.text=wording.readout(item.observation);
            File file=item.audition!=null?item.audition:recordings.find(item.voice,item.text);
            if(file==null||!file.isFile()||!recordings.safe(file)){missing++;if(item.audition==null)recordings.need(item.voice,item.text);lastError="Matching original recording needed: "+item.text;stage="Recording needed · "+activeName();changed();continue;}
            current=item;stage="Playing original recording";send(LocalVoiceService.SPEAK,item.id,item.text,file.getAbsolutePath(),wording.speedPercent()/100f);handler.removeCallbacks(watchdog);handler.postDelayed(watchdog,250000);changed();break;
        }
    }
    private void send(int what,String id,String text,String path,float speed){if(remote==null)return;Message m=Message.obtain(null,what);Bundle b=new Bundle();b.putString("id",id);b.putString("text",text);b.putString("reference",path);b.putFloat("speed",speed);m.setData(b);m.replyTo=replies;try{remote.send(m);}catch(RemoteException e){playerReady=false;lastError="Recording player disconnected.";current=null;changed();}}
    private void sendStop(){if(remote!=null)try{remote.send(Message.obtain(null,LocalVoiceService.STOP));}catch(RemoteException ignored){}}
    public void stop(){handler.removeCallbacks(watchdog);queue.clear();current=null;sendStop();stage=baseStatus();changed();}
    public synchronized boolean delete(String id){if(DEFAULT_ID.equals(id))return false;stop();try{JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]")),out=new JSONArray();boolean found=false;for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);if(id.equals(o.optString("id")))found=true;else out.put(o);}if(found){recordings.delete(id);prefs.edit().putString(PROFILES,out.toString()).apply();if(id.equals(prefs.getString(ACTIVE,"")))setActive(DEFAULT_ID);}return found;}catch(Exception e){return false;}}
}
