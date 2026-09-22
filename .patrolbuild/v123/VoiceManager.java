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
 * Exact-recorded voice engine.
 *
 * It never synthesises, clones, reconstructs or substitutes a voice. Every byte
 * played comes from the AnyVoiceLab WAV imported for the currently selected voice.
 */
public final class VoiceManager {
    public static final String DEFAULT_ID="felicity", DEFAULT_NAME="Felicity";
    private static final String PROFILES="voice_profiles_v3", ACTIVE="voice_profile_active";
    private static final String PENDING_PREFIX="exact_pack_manifest:";
    public interface ImportCallback { void done(boolean ok,String message); }

    public static final class Profile {
        public final String id,name,path; public final boolean builtIn;
        Profile(String id,String name,String path,boolean builtIn){this.id=id;this.name=name;this.path=path;this.builtIn=builtIn;}
    }

    private static final class Job {
        final String token=UUID.randomUUID().toString(),profile,text;
        final float speed;
        Job(String profile,String text,float speed){this.profile=profile;this.text=text;this.speed=speed;}
    }

    private final Context context; private final SharedPreferences prefs;
    public final SpeechPreferences speechSettings;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private final ArrayDeque<Job> queue=new ArrayDeque<>();
    private final ArrayDeque<File> clipQueue=new ArrayDeque<>();
    private Job current; private MediaPlayer player; private AudioFocusRequest focus;
    private volatile String status="Exact recording pack required", lastError="", lastSpokenText="";
    private volatile int played,failed,dropped; private volatile float lastPlaybackRate=1f;

    public VoiceManager(Context context,SharedPreferences prefs){
        this.context=context.getApplicationContext();this.prefs=prefs;
        this.speechSettings=new SpeechPreferences(this.context);
        if(!prefs.contains(ACTIVE))prefs.edit().putString(ACTIVE,DEFAULT_ID).apply();
        refreshStatus();
    }

    public List<Profile> profiles(){
        ArrayList<Profile> result=new ArrayList<>();
        result.add(new Profile(DEFAULT_ID,DEFAULT_NAME,"",true));
        try{
            JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));
            for(int i=0;i<a.length();i++){
                JSONObject o=a.optJSONObject(i);if(o==null)continue;
                String id=o.optString("id"),name=o.optString("name"),path=o.optString("path");
                if(id.matches("[A-Za-z0-9_-]{1,90}")&&!name.trim().isEmpty())result.add(new Profile(id,name,path,false));
            }
        }catch(Exception ignored){}
        return result;
    }

    public Profile activeProfile(){
        String wanted=prefs.getString(ACTIVE,DEFAULT_ID);
        for(Profile p:profiles())if(p.id.equals(wanted))return p;
        prefs.edit().putString(ACTIVE,DEFAULT_ID).apply();
        return profiles().get(0);
    }

    public String activeName(){return activeProfile().name;}
    public boolean ready(){return ExactPhrasePack.installed(context,activeProfile().id);}
    public String status(){return status;}
    public String error(){return lastError;}
    public String lastSpoken(){return lastSpokenText;}
    public int completedCount(){return played;}
    public int generatedCount(){return 0;} // exact mode deliberately generates nothing
    public int workerPid(){return android.os.Process.myPid();}
    public long generationMillis(){return 0;}
    public float lastPlaybackRate(){return lastPlaybackRate;}

    public String diagnostics(){
        int phraseCount=0;
        try{phraseCount=ExactPhrasePack.clips(context,activeProfile().id).size();}catch(Exception ignored){}
        return "Voice mode: exact website recordings only"+
                "\nActive voice: "+activeName()+
                "\nExact pack ready: "+ready()+
                "\nExact recorded phrases: "+phraseCount+
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

    /** Legacy UI entry point: creates a profile but does not pretend a generic WAV is an exact pack. */
    public void importTrainingAudio(Uri uri,String requestedName,ImportCallback callback){
        String name=requestedName==null?"":requestedName.trim();
        if(name.isEmpty()||name.length()>40){deliver(callback,false,"Voice name must be 1 to 40 characters.");return;}
        String id="voice-"+UUID.randomUUID();
        try{
            JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));
            JSONObject o=new JSONObject();o.put("id",id);o.put("name",name);o.put("path","");a.put(o);
            prefs.edit().putString(PROFILES,a.toString()).putString(ACTIVE,id).apply();
            stop();refreshStatus();
            deliver(callback,true,name+" created. Copy its exact phrase-pack script, generate it on AnyVoiceLab, then import that WAV.");
        }catch(Exception e){deliver(callback,false,safe(e));}
    }

    public boolean delete(String id){
        if(id==null||DEFAULT_ID.equals(id))return false;
        stop();JSONArray out=new JSONArray();boolean removed=false;
        try{
            JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));
            for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);if(id.equals(o.optString("id")))removed=true;else out.put(o);}
            if(removed){
                prefs.edit().putString(PROFILES,out.toString()).remove(PENDING_PREFIX+id).apply();
                deleteTree(ExactPhrasePack.profileDir(context,id));
                if(id.equals(prefs.getString(ACTIVE,DEFAULT_ID)))prefs.edit().putString(ACTIVE,DEFAULT_ID).apply();
            }
        }catch(Exception ignored){}
        refreshStatus();return removed;
    }

    public String exactPackScript(Collection<Observation> recent,List<String> selectedGuards){
        LinkedHashMap<String,String> unique=new LinkedHashMap<>();
        addBasePhrases(unique);
        String prefix=speechSettings.prefix();add(unique,prefix);
        if(selectedGuards!=null)for(String guard:selectedGuards){
            add(unique,SpeechRules.guard(guard,speechSettings.nicknames()));
            add(unique,AnnouncementText.spokenGuard(guard));
        }
        for(SpeechPreferences.Entry e:speechSettings.entries(SpeechPreferences.ALERT)){
            add(unique,e.original);if(e.edited())add(unique,e.replacement);
        }
        for(SpeechPreferences.Entry e:speechSettings.entries(SpeechPreferences.PLACE)){
            add(unique,e.original);if(e.edited())add(unique,e.replacement);
        }
        for(SpeechPreferences.Entry e:speechSettings.entries(SpeechPreferences.PHRASE)){
            add(unique,e.original);if(e.edited())add(unique,e.replacement);
        }
        if(recent!=null)for(Observation o:recent){
            String formatted=speechSettings.format(o);
            add(unique,formatted);
            addAnnouncementParts(unique,formatted);
            add(unique,SpeechRules.guard(o.guard,speechSettings.nicknames()));
            add(unique,SpeechRules.exact(o.issue,speechSettings.overrides(SpeechPreferences.ALERT)));
            add(unique,SpeechRules.exact(o.property,speechSettings.overrides(SpeechPreferences.PLACE)));
        }
        ArrayList<String> phrases=new ArrayList<>(unique.values());
        savePendingManifest(activeProfile().id,phrases);
        StringBuilder script=new StringBuilder();
        for(int i=0;i<phrases.size();i++){
            script.append(phrases.get(i)).append('\n');
            if(i<phrases.size()-1)script.append("[pause 3]\n\n");
        }
        return script.toString();
    }

    private void addBasePhrases(LinkedHashMap<String,String> out){
        try(InputStream in=context.getAssets().open("exact/base_phrases.txt")){
            BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));String line;
            while((line=r.readLine())!=null)add(out,line);
        }catch(IOException ignored){}
    }

    private static void add(LinkedHashMap<String,String> out,String value){
        String clean=SpeechRules.clean(value);
        if(clean.isEmpty())return;
        out.putIfAbsent(ExactPhrasePack.normalize(clean),clean);
    }

    private static void addAnnouncementParts(LinkedHashMap<String,String> out,String announcement){
        if(announcement==null)return;
        String[] parts=announcement.split("(?<=[.!?])\\s+");
        for(String raw:parts){
            String part=raw.replaceAll("[.!?]+$","").trim();
            add(out,part);
        }
    }

    private void savePendingManifest(String profile,List<String> phrases){
        JSONArray a=new JSONArray();for(String p:phrases)a.put(p);
        prefs.edit().putString(PENDING_PREFIX+profile,a.toString()).apply();
    }

    private List<String> pendingManifest(String profile)throws Exception{
        String raw=prefs.getString(PENDING_PREFIX+profile,"");
        if(raw.isEmpty())throw new IOException("Copy the exact phrase-pack script first, then generate that exact script on AnyVoiceLab.");
        JSONArray a=new JSONArray(raw);ArrayList<String> result=new ArrayList<>();
        for(int i=0;i<a.length();i++)result.add(a.getString(i));
        return result;
    }

    public void importExactPack(Uri uri,ImportCallback callback){
        final Profile profile=activeProfile();status="Importing exact "+profile.name+" recordings";lastError="";
        files.execute(()->{
            try{
                List<String> phrases=pendingManifest(profile.id);
                ExactPhrasePack.ImportResult result=ExactPhrasePack.importPack(context,uri,profile.id,phrases);
                main.post(()->{status="Ready: exact "+profile.name+" website recordings";lastError="";deliver(callback,true,"Imported "+result.phrases+" exact "+profile.name+" recordings. Patrol Link will not synthesise this voice.");});
            }catch(Exception e){
                String m=safe(e);main.post(()->{status="Exact pack import failed";lastError=m;deliver(callback,false,m);});
            }
        });
    }

    public void speak(Observation o){
        if(o==null)return;
        main.post(()->{
            if(!prefs.getBoolean("voice",true))return;
            speechSettings.remember(Collections.singletonList(o));
            enqueue(speechSettings.format(o),speechSettings.speed());
        });
    }

    public void preview(String text,float rate){main.post(()->enqueue(text,Math.max(.75f,Math.min(1.5f,rate))));}
    public void test(){
        main.post(()->{
            if(!ready()){
                status="Exact "+activeName()+" phrase pack required";
                lastError="Import the exact AnyVoiceLab phrase-pack WAV first.";
                return;
            }
            try{
                LinkedHashMap<String,File> map=ExactPhrasePack.clips(context,activeProfile().id);
                String preferred=ExactPhrasePack.normalize("Silvertracker update");
                String phrase=map.containsKey(preferred)?"Silvertracker update":
                        map.isEmpty()?null:map.keySet().iterator().next();
                if(phrase==null){status="Exact phrase pack is empty";lastError=status;return;}
                enqueue(phrase,speechSettings.speed());
            }catch(Exception e){lastError=safe(e);status="Exact voice test failed";}
        });
    }
    public void readLatest(Collection<Observation> rows){if(rows!=null)for(Observation o:rows)speak(o);}
    public void speechSettingsChanged(){
        stop();
        if(ready()){
            status="Speech wording changed — copy a fresh exact phrase-pack script if you added new wording";
            lastError="";
        }else refreshStatus();
    }

    private void enqueue(String text,float speed){
        String clean=SpeechRules.clean(text);
        if(clean.isEmpty())return;
        if(!ready()){status="Exact "+activeName()+" phrase pack required";lastError="Copy the exact phrase-pack script and import the AnyVoiceLab WAV. No substitute voice will be used.";return;}
        if(queue.size()>=60){queue.removeFirst();dropped++;}
        queue.addLast(new Job(activeProfile().id,clean,speed));
        dispatch();
    }

    private void dispatch(){
        if(current!=null)return;
        Job next=queue.pollFirst();if(next==null){refreshStatus();return;}
        if(!next.profile.equals(activeProfile().id)){dropped++;dispatch();return;}
        try{
            List<File> clips=resolve(next.text,next.profile);
            if(clips.isEmpty())throw new IOException("No exact recording matches this update.");
            current=next;clipQueue.clear();clipQueue.addAll(clips);
            status="Playing exact "+activeName()+" website recording";
            playNextClip();
        }catch(Exception e){
            failed++;lastError=safe(e);status="Exact recording needed";current=null;dispatch();
        }
    }

    private List<File> resolve(String text,String profile)throws Exception{
        LinkedHashMap<String,File> map=ExactPhrasePack.clips(context,profile);
        String full=ExactPhrasePack.normalize(text);
        File direct=map.get(full);
        if(direct!=null)return Collections.singletonList(direct);

        ArrayList<File> result=new ArrayList<>();
        // SpeechPreferences deliberately formats composable parts as sentences.
        String[] parts=text.split("(?<=[.!?])\\s+");
        for(String raw:parts){
            String part=raw.replaceAll("[.!?]+$","").trim();
            if(part.isEmpty())continue;
            File clip=map.get(ExactPhrasePack.normalize(part));
            if(clip==null)throw new IOException("No exact "+activeName()+" recording for: \""+part+"\". Copy a fresh phrase-pack script and import its AnyVoiceLab WAV.");
            result.add(clip);
        }
        return result;
    }

    private void playNextClip(){
        if(current==null)return;
        File file=clipQueue.pollFirst();
        if(file==null){played++;lastSpokenText=current.text;current=null;releasePlayer();main.postDelayed(this::dispatch,80);return;}
        try{
            releasePlayer();
            MediaPlayer mp=new MediaPlayer();player=mp;
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            mp.setAudioAttributes(attrs);mp.setDataSource(file.getAbsolutePath());
            mp.setOnPreparedListener(p->{
                if(current==null){releasePlayer();return;}
                try{
                    AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
                    focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).setOnAudioFocusChangeListener(change->{},main).build();
                    am.requestAudioFocus(focus);
                    p.setPlaybackParams(new PlaybackParams().allowDefaults().setPitch(1f).setSpeed(current.speed));
                    lastPlaybackRate=p.getPlaybackParams().getSpeed();p.start();
                }catch(Exception ex){failCurrent(safe(ex));}
            });
            mp.setOnCompletionListener(p->{releasePlayer();main.postDelayed(this::playNextClip,70);});
            mp.setOnErrorListener((p,w,e)->{failCurrent("Could not play the exact website recording.");return true;});
            mp.prepareAsync();
        }catch(Exception e){failCurrent(safe(e));}
    }

    private void failCurrent(String message){
        failed++;lastError=message;status="Exact recording playback failed";current=null;clipQueue.clear();releasePlayer();main.post(this::dispatch);
    }

    private void releasePlayer(){
        if(player!=null){try{player.stop();}catch(Exception ignored){}try{player.release();}catch(Exception ignored){}player=null;}
        if(focus!=null){try{((AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocusRequest(focus);}catch(Exception ignored){}focus=null;}
    }

    public void stop(){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::stop);return;}
        queue.clear();clipQueue.clear();current=null;releasePlayer();
    }

    private void refreshStatus(){
        status=ready()?"Ready: exact "+activeName()+" website recordings":"Exact "+activeName()+" phrase pack required";
    }

    private void deliver(ImportCallback cb,boolean ok,String message){if(cb!=null)main.post(()->cb.done(ok,message));}
    private static String safe(Exception e){String m=e.getMessage();return m==null||m.trim().isEmpty()?e.getClass().getSimpleName():m.trim();}
    private static void deleteTree(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] c=f.listFiles();if(c!=null)for(File x:c)deleteTree(x);}f.delete();}
}
