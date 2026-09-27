package au.com.roningroup.patrollink;

import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Portable exact-recorded voice profiles.
 *
 * Every phone uses the same fixed canonical phrase manifest, so the same WAV file
 * maps to the same phrase count/order on every device. No live feed, local history,
 * selected guards or edited speech catalogs are allowed to change the import manifest.
 */
public final class VoiceManager {
    public static final String DEFAULT_ID="evelyn", DEFAULT_NAME="Evelyn";
    private static final String PROFILES="voice_profiles_v5", ACTIVE="voice_profile_active";
    private static final String MIGRATED="voice_profiles_single_wav_v128";
    public interface ImportCallback { void done(boolean ok,String message); }

    public static final class Profile {
        public final String id,name,path; public final boolean builtIn;
        Profile(String id,String name,String path,boolean builtIn){this.id=id;this.name=name;this.path=path;this.builtIn=builtIn;}
    }

    private static final class Job {
        final String profile,text; final float speed;
        Job(String profile,String text,float speed){this.profile=profile;this.text=text;this.speed=speed;}
    }

    private final Context context; private final SharedPreferences prefs;
    public final SpeechPreferences speechSettings;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private final ArrayDeque<Job> queue=new ArrayDeque<>();
    private final ArrayDeque<File> clipQueue=new ArrayDeque<>();
    private Job current; private MediaPlayer player; private AudioFocusRequest focus;
    private volatile String status="Exact WAV required",lastError="",lastSpokenText="";
    private volatile int played,failed,dropped; private volatile float lastPlaybackRate=1f;
    private List<String> canonicalCache;

    public VoiceManager(Context context,SharedPreferences prefs){
        this.context=context.getApplicationContext();this.prefs=prefs;this.speechSettings=new SpeechPreferences(this.context);
        migrateProfiles();
        refreshStatus();
    }

    private void migrateProfiles(){
        if(prefs.getBoolean(MIGRATED,false))return;
        JSONArray seed=new JSONArray();
        String chosen=DEFAULT_ID;
        try{
            // Keep user-created v1.1.17 profiles if present, but Felicity is never carried forward.
            JSONArray old=new JSONArray(prefs.getString("voice_profiles_v4","[]"));
            for(int i=0;i<old.length();i++){
                JSONObject o=old.optJSONObject(i);if(o==null)continue;
                String id=o.optString("id"),name=o.optString("name").trim();
                if(id.isEmpty()||name.isEmpty()||name.equalsIgnoreCase("Felicity"))continue;
                JSONObject n=new JSONObject();n.put("id",id);n.put("name",name);seed.put(n);
                if(id.equals(prefs.getString(ACTIVE,"")))chosen=id;
            }
        }catch(Exception ignored){}
        if(seed.length()==0){
            try{JSONObject e=new JSONObject();e.put("id",DEFAULT_ID);e.put("name",DEFAULT_NAME);seed.put(e);}catch(JSONException ignored){}
            chosen=DEFAULT_ID;
        }
        prefs.edit().putString(PROFILES,seed.toString()).putString(ACTIVE,chosen)
                .remove("voice_profiles_v4").putBoolean(MIGRATED,true).apply();
        // Felicity remains removed. Do not touch monitor/session state.
        deleteTree(new File(context.getFilesDir(),"exact_voice_packs/felicity"));
        deleteTree(new File(context.getFilesDir(),"exact_voice_packs_v3/felicity"));
    }

    public List<Profile> profiles(){
        ArrayList<Profile> out=new ArrayList<>();
        try{
            JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));
            for(int i=0;i<a.length();i++){
                JSONObject o=a.optJSONObject(i);if(o==null)continue;
                String id=o.optString("id"),name=o.optString("name").trim();
                if(id.matches("[A-Za-z0-9_-]{1,90}")&&!name.isEmpty()&&!name.equalsIgnoreCase("Felicity"))
                    out.add(new Profile(id,name,"",false));
            }
        }catch(Exception ignored){}
        if(out.isEmpty()){
            try{
                JSONArray a=new JSONArray();JSONObject e=new JSONObject();e.put("id",DEFAULT_ID);e.put("name",DEFAULT_NAME);a.put(e);
                prefs.edit().putString(PROFILES,a.toString()).putString(ACTIVE,DEFAULT_ID).apply();
            }catch(Exception ignored){}
            out.add(new Profile(DEFAULT_ID,DEFAULT_NAME,"",false));
        }
        return out;
    }

    public Profile activeProfile(){
        String wanted=prefs.getString(ACTIVE,DEFAULT_ID);
        for(Profile p:profiles())if(p.id.equals(wanted))return p;
        Profile first=profiles().get(0);prefs.edit().putString(ACTIVE,first.id).apply();return first;
    }
    public String activeName(){return activeProfile().name;}
    public boolean ready(){return ExactPhrasePack.installed(context,activeProfile().id);}
    public String status(){return status;}
    public String error(){return lastError;}
    public String lastSpoken(){return lastSpokenText;}
    public int completedCount(){return played;}
    public int generatedCount(){return 0;}
    public int workerPid(){return android.os.Process.myPid();}
    public long generationMillis(){return 0;}
    public float lastPlaybackRate(){return lastPlaybackRate;}

    public String diagnostics(){
        int clips=0;try{clips=ExactPhrasePack.clips(context,activeProfile().id).size();}catch(Exception ignored){}
        return "Voice mode: exact website recordings only"+
                "\nActive voice: "+activeName()+
                "\nVoice profiles: "+profiles().size()+
                "\nExact pack ready: "+ready()+
                "\nCanonical manifest phrases: "+canonicalPhrases().size()+
                "\nExact recorded phrases: "+clips+
                "\nSpeech speed: "+speechSettings.speed()+"x"+
                "\nLast playback speed: "+lastPlaybackRate+"x"+
                "\nPlayed/failed/dropped: "+played+"/"+failed+"/"+dropped+
                "\nVoice status: "+status+
                "\nVoice error: "+(lastError.isEmpty()?"none":lastError);
    }

    public void setActive(String id){
        for(Profile p:profiles())if(p.id.equals(id)){
            stop();prefs.edit().putString(ACTIVE,id).apply();lastError="";refreshStatus();return;
        }
    }

    public void importTrainingAudio(Uri ignored,String requestedName,ImportCallback callback){
        String name=requestedName==null?"":requestedName.trim();
        if(name.isEmpty()||name.length()>40){deliver(callback,false,"Voice name must be 1 to 40 characters.");return;}
        String id="voice-"+UUID.randomUUID();
        try{
            JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));
            JSONObject e=new JSONObject();e.put("id",id);e.put("name",name);a.put(e);
            prefs.edit().putString(PROFILES,a.toString()).putString(ACTIVE,id).apply();
            stop();refreshStatus();deliver(callback,true,name+" created. Copy the single canonical script, generate one WAV, then import that WAV.");
        }catch(Exception e){deliver(callback,false,safe(e));}
    }

    public boolean delete(String id){
        List<Profile> list=profiles();if(list.size()<=1)return false;
        JSONArray out=new JSONArray();boolean removed=false;String next=null;
        for(Profile p:list){
            if(p.id.equals(id)){removed=true;continue;}
            if(next==null)next=p.id;
            try{JSONObject e=new JSONObject();e.put("id",p.id);e.put("name",p.name);out.put(e);}catch(JSONException ignored){}
        }
        if(!removed)return false;
        try{deleteTree(ExactPhrasePack.profileDir(context,id));}catch(Exception ignored){}
        prefs.edit().putString(PROFILES,out.toString()).apply();
        if(id.equals(activeProfile().id)&&next!=null)prefs.edit().putString(ACTIVE,next).apply();
        stop();refreshStatus();return true;
    }

    public synchronized List<String> canonicalPhrases(){
        if(canonicalCache!=null)return new ArrayList<>(canonicalCache);
        ArrayList<String> list=new ArrayList<>();
        try(InputStream in=context.getAssets().open("exact/canonical_phrases.txt")){
            BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));
            LinkedHashSet<String> seen=new LinkedHashSet<>();String line;
            while((line=r.readLine())!=null){
                String clean=SpeechRules.clean(line);if(clean.isEmpty())continue;
                String key=ExactPhrasePack.normalize(clean);
                if(seen.add(key))list.add(clean);
            }
        }catch(IOException e){throw new IllegalStateException("Canonical voice script is missing.",e);}
        canonicalCache=Collections.unmodifiableList(list);
        return new ArrayList<>(canonicalCache);
    }

    public String exactPackScript(Collection<Observation> ignored,List<String> ignoredGuards){
        StringBuilder b=new StringBuilder();List<String> phrases=canonicalPhrases();
        for(int i=0;i<phrases.size();i++){if(i>0)b.append("[pause 3]\n\n");b.append(phrases.get(i)).append('\n');}
        return b.toString();
    }

    public void importExactPack(Uri uri,ImportCallback callback){
        final Profile p=activeProfile();final List<String> phrases=canonicalPhrases();
        status="Importing single "+p.name+" WAV";lastError="";
        files.execute(()->{
            try{
                ExactPhrasePack.ImportResult result=ExactPhrasePack.importPack(context,uri,p.id,phrases);
                main.post(()->{refreshStatus();deliver(callback,true,p.name+" imported: "+result.phrases+" exact recordings from one WAV.");});
            }catch(Exception e){
                String msg=safe(e);main.post(()->{status=p.name+" WAV import failed";lastError=msg;deliver(callback,false,msg);});
            }
        });
    }

    // Compatibility: v1.1.18 is intentionally single-WAV only.
    public void importPart(Uri uri,int part,ImportCallback callback){importExactPack(uri,callback);}

    public void speak(Observation o){
        if(o!=null)main.post(()->{if(prefs.getBoolean("voice",true)){speechSettings.remember(Collections.singletonList(o));enqueue(activeProfile().id,speechSettings.format(o),speechSettings.speed());}});
    }
    public void preview(String text,float rate){main.post(()->enqueue(activeProfile().id,text,Math.max(.75f,Math.min(1.5f,rate))));}
    public boolean welcome(){
        String message="Good evening, Tristan. Let's have a good shift.";if(!ready())return false;
        try{if(resolve(activeProfile().id,message).isEmpty())return false;}catch(Exception e){return false;}
        preview(message,speechSettings.speed());return true;
    }
    public void test(){
        main.post(()->{
            Profile p=activeProfile();if(!ready()){status="Import the single "+p.name+" WAV first";lastError="No exact recordings are installed for "+p.name+" yet.";return;}
            try{
                LinkedHashMap<String,File> map=ExactPhrasePack.clips(context,p.id);
                String preferred=ExactPhrasePack.normalize("Silvertracker update");
                String phrase=map.containsKey(preferred)?"Silvertracker update":map.keySet().iterator().next();
                enqueue(p.id,phrase,speechSettings.speed());
            }catch(Exception e){lastError=safe(e);status=p.name+" voice test failed";}
        });
    }
    public void readLatest(Collection<Observation> rows){if(rows!=null)for(Observation o:rows)speak(o);}
    public void speechSettingsChanged(){
        stop();status="Speech wording changed";lastError="The single canonical WAV is portable across phones. Custom wording can only be spoken when that exact phrase is already in the canonical pack.";
    }

    private void enqueue(String profile,String text,float speed){
        String clean=SpeechRules.clean(text);if(clean.isEmpty())return;
        if(!ExactPhrasePack.installed(context,profile)){status="Voice recordings required";lastError="Import the single WAV for "+activeName()+". No substitute voice will be used.";return;}
        if(queue.size()>=60){queue.removeFirst();dropped++;}queue.addLast(new Job(profile,clean,speed));dispatch();
    }
    private void dispatch(){
        if(current!=null)return;Job next=queue.pollFirst();if(next==null){refreshStatus();return;}
        if(!next.profile.equals(activeProfile().id)){dropped++;dispatch();return;}
        try{
            List<File> clips=resolve(next.profile,next.text);if(clips.isEmpty())throw new IOException("No exact recording matches this update.");
            current=next;clipQueue.clear();clipQueue.addAll(clips);status="Playing exact "+activeName()+" website recording";playNextClip();
        }catch(Exception e){failed++;lastError=safe(e);status=activeName()+" recording needed";current=null;dispatch();}
    }
    private List<File> resolve(String profile,String text)throws Exception{
        LinkedHashMap<String,File> map=ExactPhrasePack.clips(context,profile);
        File direct=map.get(ExactPhrasePack.normalize(text));if(direct!=null)return Collections.singletonList(direct);
        ArrayList<File> result=new ArrayList<>();
        for(String raw:text.split("(?<=[.!?])\\s+")){
            String part=raw.replaceAll("[.!?]+$","").trim();if(part.isEmpty())continue;
            File clip=map.get(ExactPhrasePack.normalize(part));
            if(clip==null)throw new IOException("No exact "+activeName()+" recording for: \""+part+"\".");
            result.add(clip);
        }
        return result;
    }
    private void playNextClip(){
        if(current==null)return;File file=clipQueue.pollFirst();
        if(file==null){played++;lastSpokenText=current.text;current=null;releasePlayer();main.postDelayed(this::dispatch,80);return;}
        try{
            releasePlayer();MediaPlayer mp=new MediaPlayer();player=mp;
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            mp.setAudioAttributes(attrs);mp.setDataSource(file.getAbsolutePath());
            mp.setOnPreparedListener(p->{
                if(current==null){releasePlayer();return;}
                try{
                    AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
                    focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).setOnAudioFocusChangeListener(change->{},main).build();am.requestAudioFocus(focus);
                    p.setPlaybackParams(new PlaybackParams().allowDefaults().setPitch(1f).setSpeed(current.speed));lastPlaybackRate=p.getPlaybackParams().getSpeed();p.start();
                }catch(Exception e){failCurrent(safe(e));}
            });
            mp.setOnCompletionListener(p->{releasePlayer();main.postDelayed(this::playNextClip,70);});
            mp.setOnErrorListener((p,w,e)->{failCurrent("Could not play the selected voice recording.");return true;});mp.prepareAsync();
        }catch(Exception e){failCurrent(safe(e));}
    }
    private void failCurrent(String message){failed++;lastError=message;status="Voice playback failed";current=null;clipQueue.clear();releasePlayer();main.post(this::dispatch);}
    private void releasePlayer(){
        if(player!=null){try{player.stop();}catch(Exception ignored){}try{player.release();}catch(Exception ignored){}player=null;}
        if(focus!=null){try{((AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocusRequest(focus);}catch(Exception ignored){}focus=null;}
    }
    public void stop(){if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::stop);return;}queue.clear();clipQueue.clear();current=null;releasePlayer();}
    private void refreshStatus(){
        Profile p=activeProfile();status=ready()?p.name+" exact single-WAV pack ready":p.name+" single WAV required";
    }

    private void deliver(ImportCallback cb,boolean ok,String message){if(cb!=null)main.post(()->cb.done(ok,message));}
    private static String safe(Exception e){String m=e.getMessage();return m==null||m.trim().isEmpty()?e.getClass().getSimpleName():m.trim();}
    private static void deleteTree(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] c=f.listFiles();if(c!=null)for(File x:c)deleteTree(x);}f.delete();}
}
