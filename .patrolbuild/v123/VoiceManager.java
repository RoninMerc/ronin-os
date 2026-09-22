package au.com.roningroup.patrollink;

import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Recorded speech only. At 1x the stored PCM is played with no voice reconstruction. */
public final class VoiceManager {
    public static final String DEFAULT_ID="felicity",DEFAULT_NAME="Felicity";
    private static final String PROFILES="voice_profiles_v1",ACTIVE="voice_profile_active";
    public interface ImportCallback{void done(Profile profile,String error);}
    public static final class Profile {
        public final String id,name,path;public final long durationMs;public final boolean builtIn;
        Profile(String i,String n,String p,long d,boolean b){id=i;name=n;path=p;durationMs=d;builtIn=b;}
    }
    private static final class Entry {
        final String id=UUID.randomUUID().toString(),voice;final Observation observation;final File preview;String text;
        Entry(String t,Profile p,Observation o,File f){text=t;voice=p.id;observation=o;preview=f;}
    }
    public final SpeechPreferences wording;public final RecordedSpeechStore recordings;
    private final Context context;private final SharedPreferences prefs;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ArrayDeque<Entry> queue=new ArrayDeque<>();
    private Entry current;private MediaPlayer player;private AudioFocusRequest focus;
    private Runnable listener,deadline;private int completed,failed,missing;
    private String stage="Recordings only — open Recorded announcements to set up",lastError="",lastText="";
    public VoiceManager(Context c,SharedPreferences p){context=c.getApplicationContext();prefs=p;wording=new SpeechPreferences(context);recordings=new RecordedSpeechStore(context);migrate();}
    private void migrate(){
        SharedPreferences.Editor e=prefs.edit().remove("voice_api_key_cipher_v1").remove("felicity_cloud_voice_id");if(!prefs.contains(ACTIVE))e.putString(ACTIVE,DEFAULT_ID);if(!prefs.contains("voice"))e.putBoolean("voice",true);e.apply();
        try{JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);String source=o.optString("source",o.optString("path",""));File f=new File(source);if(recordings.source(o.optString("id"))==null&&f.isFile()&&f.getCanonicalPath().startsWith(context.getFilesDir().getCanonicalPath()+File.separator))recordings.setSource(o.optString("id"),f);}}catch(Exception ignored){}
    }
    public void setListener(Runnable r){listener=r;}
    private void changed(){if(listener!=null)listener.run();}
    public String activeName(){return activeProfile().name;}
    public String status(){return stage+(queue.isEmpty()?"":" · "+queue.size()+" queued");}
    public String lastText(){return lastText;}
    public boolean ready(){return recordings.recordedCount(activeProfile().id,recordings.required())>0&&lastError.isEmpty();}
    public int completedCount(){return completed;}
    public String diagnostics(){return "Voice mode: exact recorded audio only\nActive voice: "+activeName()+"\nVoice state: "+status()+"\nSpeech speed: "+speed()+"x\nCompleted/failed/missing recording: "+completed+"/"+failed+"/"+missing+"\nVoice error: "+(lastError.isEmpty()?"none":lastError);}
    public float speed(){return prefs.getFloat("recording_speed_v123",1f);}
    public void speed(float value){if(!Float.isFinite(value)||value<.75f||value>1.5f)throw new IllegalArgumentException("Choose 0.75x to 1.50x.");prefs.edit().putFloat("recording_speed_v123",value).apply();changed();}
    public List<Profile> profiles(){
        List<Profile> out=new ArrayList<>();out.add(new Profile(DEFAULT_ID,DEFAULT_NAME,null,0,true));try{JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&!o.optString("id").isEmpty())out.add(new Profile(o.optString("id"),o.optString("name","Imported voice"),o.optString("path"),o.optLong("duration",0),false));}}catch(Exception ignored){}return out;
    }
    public Profile activeProfile(){String id=prefs.getString(ACTIVE,DEFAULT_ID);for(Profile p:profiles())if(p.id.equals(id))return p;return new Profile(id,"Missing selected voice",null,0,false);}
    public void setActive(String id){for(Profile p:profiles())if(p.id.equals(id)){stop();prefs.edit().putString(ACTIVE,id).apply();lastError="";stage="Selected "+p.name+" · recorded phrases only";changed();return;}}
    public void remember(Collection<Observation> rows){try{wording.remember(rows);for(Observation o:rows)recordings.remember(wording.readout(o));}catch(RuntimeException ignored){}}
    public void speak(Observation o){if(o!=null)enqueue(null,o,null);}
    public void preview(String text){enqueue(text,null,null);}
    public void previewRecording(File wav,String label){enqueue(label,null,wav);}
    public void test(){for(String text:recordings.required())if(recordings.recording(activeProfile().id,text)!=null){preview(text);return;}lastError="No phrase has been assigned a recording for "+activeName()+". Open Recorded announcements.";stage="Recording setup required";changed();}
    private void enqueue(String text,Observation o,File preview){
        if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(()->enqueue(text,o,preview));return;}
        if(queue.size()>=128){failed++;lastError="Speech queue is full; the dashboard remains current.";changed();return;}
        queue.addLast(new Entry(text,activeProfile(),o,preview));pump();
    }
    private void pump(){
        if(current!=null)return;
        while(!queue.isEmpty()){
            Entry e=queue.pollFirst();if(!e.voice.equals(activeProfile().id))continue;
            if(e.observation!=null)e.text=wording.readout(e.observation);
            File f=e.preview==null?recordings.recording(e.voice,e.text):e.preview;
            if(e.preview==null)recordings.remember(e.text);
            if(f==null||!f.isFile()){missing++;stage="Recording needed";lastError="Not recorded in "+activeName()+": "+e.text;changed();continue;}
            current=e;stage="Preparing original recording";lastError="";play(e,f);break;
        }
        changed();
    }
    private void play(Entry e,File file){
        try{
            String path=file.getCanonicalPath(),allowed=context.getFilesDir().getCanonicalPath()+File.separator;
            if(!path.startsWith(allowed))throw new IOException("Recording is not in private voice storage.");
            MediaPlayer mp=new MediaPlayer();player=mp;
            AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            mp.setAudioAttributes(attributes);mp.setDataSource(path);
            mp.setOnPreparedListener(p->{
                if(current!=e||player!=p)return;
                try{
                    AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
                    focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).setOnAudioFocusChangeListener(change->{if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)stop();},handler).build();
                    if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IOException("Audio is busy. The alert remains on the dashboard.");
                    float rate=speed();if(rate!=1f)p.setPlaybackParams(new PlaybackParams().allowDefaults().setPitch(1f).setSpeed(rate));
                    stage="Playing "+activeName()+" recording";deadline=()->finish(e,false,"Recording playback timed out");handler.postDelayed(deadline,(long)(p.getDuration()/rate)+10000);p.start();changed();
                }catch(Exception ex){finish(e,false,ex.getMessage());}
            });
            mp.setOnCompletionListener(p->finish(e,true,""));mp.setOnErrorListener((p,w,x)->{finish(e,false,"Recording playback failed");return true;});
            deadline=()->finish(e,false,"Recording preparation timed out");handler.postDelayed(deadline,15000);mp.prepareAsync();
        }catch(Exception ex){finish(e,false,ex.getMessage());}
    }
    private void finish(Entry e,boolean ok,String error){if(current!=e)return;if(deadline!=null)handler.removeCallbacks(deadline);deadline=null;release();current=null;if(ok){completed++;lastText=e.text;lastError="";stage="Ready · exact recordings";}else{failed++;lastError=error==null?"Playback failed":error;stage="Recording error";}changed();handler.post(this::pump);}
    private void release(){if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}if(focus!=null){((AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocusRequest(focus);focus=null;}}
    public void stop(){if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(this::stop);return;}queue.clear();current=null;if(deadline!=null)handler.removeCallbacks(deadline);deadline=null;release();stage="Stopped · recordings retained";changed();}
    public void importAudio(Uri uri,String name,ImportCallback cb){io.execute(()->{Profile p=null;String error=null;try{p=importTrainingAudio(uri,name);}catch(Exception ex){error=ex.getMessage();}Profile out=p;String problem=error;handler.post(()->{if(out!=null)setActive(out.id);cb.done(out,problem);});});}
    public synchronized Profile importTrainingAudio(Uri uri,String requestedName)throws Exception{
        String name=requestedName==null?"":requestedName.trim();if(name.isEmpty()||name.length()>40)throw new IOException("Enter a voice name of 1–40 characters.");File source=recordings.importSource(uri);String id="voice-"+UUID.randomUUID();
        boolean saved=false;try{ExactWav wav=ExactWav.open(source);JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));JSONObject o=new JSONObject();o.put("id",id);o.put("name",name);o.put("path",source.getPath());o.put("source",source.getPath());o.put("duration",Math.round(wav.seconds()*1000));a.put(o);if(!prefs.edit().putString(PROFILES,a.toString()).commit())throw new IOException("Could not save voice profile.");recordings.setSource(id,source);saved=true;return new Profile(id,name,source.getPath(),Math.round(wav.seconds()*1000),false);}finally{if(!saved)source.delete();}
    }
    public synchronized boolean delete(String id){if(DEFAULT_ID.equals(id))return false;try{JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]")),out=new JSONArray();boolean removed=false;for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);if(id.equals(o.optString("id")))removed=true;else out.put(o);}if(!removed)return false;boolean selected=id.equals(activeProfile().id);prefs.edit().putString(PROFILES,out.toString()).apply();if(selected)setActive(DEFAULT_ID);return true;}catch(Exception e){return false;}}
}
