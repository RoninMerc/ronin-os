package au.com.roningroup.patrollink;

import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Selectable exact-recorded voice profiles.
 * Every profile uses exactly two script/WAV parts that merge into that profile's library.
 */
public final class VoiceManager {
    public static final String DEFAULT_ID="evelyn", DEFAULT_NAME="Evelyn";
    private static final String PROFILES="voice_profiles_v4", ACTIVE="voice_profile_active";
    private static final String MIGRATED="voice_profiles_reset_v127";
    private static final String MANIFEST_PREFIX="two_part_manifest:";
    public interface ImportCallback { void done(boolean ok,String message); }

    public static final class Profile {
        public final String id,name,path; public final boolean builtIn;
        Profile(String id,String name,String path,boolean builtIn){this.id=id;this.name=name;this.path=path;this.builtIn=builtIn;}
    }
    public static final class Part {
        public final int number,total,phraseCount; public final boolean imported; public final String script;
        Part(int number,int total,int phraseCount,boolean imported,String script){this.number=number;this.total=total;this.phraseCount=phraseCount;this.imported=imported;this.script=script;}
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
    private volatile String status="Voice pack needs recordings",lastError="",lastSpokenText="";
    private volatile int played,failed,dropped; private volatile float lastPlaybackRate=1f;

    public VoiceManager(Context context,SharedPreferences prefs){
        this.context=context.getApplicationContext();this.prefs=prefs;this.speechSettings=new SpeechPreferences(this.context);
        migrateProfiles();
        refreshStatus();
    }

    private void migrateProfiles(){
        if(prefs.getBoolean(MIGRATED,false))return;
        String legacyActive=prefs.getString(ACTIVE,"");
        String legacyEvelynId="";
        try{
            JSONArray old=new JSONArray(prefs.getString("voice_profiles_v3","[]"));
            for(int i=0;i<old.length();i++){
                JSONObject o=old.optJSONObject(i);if(o==null)continue;
                String id=o.optString("id"),name=o.optString("name").toLowerCase(Locale.ROOT);
                if(name.contains("evelyn")||name.equals("eve")){legacyEvelynId=id;break;}
            }
        }catch(Exception ignored){}
        try{ProfilePhrasePack.migrateV2Evelyn(context);}catch(Exception ignored){}
        try{
            if(!ProfilePhrasePack.ready(context,DEFAULT_ID)){
                if(!legacyEvelynId.isEmpty())ProfilePhrasePack.migrateLegacyProfile(context,legacyEvelynId,DEFAULT_ID);
                else if(!legacyActive.isEmpty()&&!legacyActive.equals("felicity"))ProfilePhrasePack.migrateLegacyProfile(context,legacyActive,DEFAULT_ID);
            }
        }catch(Exception ignored){}

        // Felicity was the undeletable legacy built-in. Remove its local exact-pack storage and metadata.
        deleteTree(new File(context.getFilesDir(),"exact_voice_packs/felicity"));

        JSONArray seed=new JSONArray();
        try{JSONObject e=new JSONObject();e.put("id",DEFAULT_ID);e.put("name",DEFAULT_NAME);seed.put(e);}catch(JSONException ignored){}
        prefs.edit().putString(PROFILES,seed.toString()).putString(ACTIVE,DEFAULT_ID)
                .remove("voice_profiles_v3").putBoolean(MIGRATED,true).apply();
    }

    public List<Profile> profiles(){
        ArrayList<Profile> out=new ArrayList<>();
        try{
            JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));
            for(int i=0;i<a.length();i++){
                JSONObject o=a.optJSONObject(i);if(o==null)continue;
                String id=o.optString("id"),name=o.optString("name");
                if(id.matches("[A-Za-z0-9_-]{1,90}")&&!name.trim().isEmpty())out.add(new Profile(id,name.trim(),"",false));
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
    public boolean ready(){return ProfilePhrasePack.ready(context,activeProfile().id);}
    public String status(){return status;}
    public String error(){return lastError;}
    public String lastSpoken(){return lastSpokenText;}
    public int completedCount(){return played;}
    public int generatedCount(){return 0;}
    public int workerPid(){return android.os.Process.myPid();}
    public long generationMillis(){return 0;}
    public float lastPlaybackRate(){return lastPlaybackRate;}

    public String diagnostics(){
        Profile p=activeProfile();
        Set<Integer> imported=ProfilePhrasePack.importedParts(context,p.id);
        return "Voice mode: exact website recordings only"+
                "\nActive voice: "+p.name+
                "\nVoice profiles: "+profiles().size()+
                "\nExact pack ready: "+ready()+
                "\nImported voice parts: "+imported.size()+"/2"+
                "\nImported part numbers: "+imported+
                "\nExact recorded phrases: "+ProfilePhrasePack.clipCount(context,p.id)+
                "\nSpeech speed: "+speechSettings.speed()+"x"+
                "\nLast playback speed: "+lastPlaybackRate+"x"+
                "\nPlayed/failed/dropped: "+played+"/"+failed+"/"+dropped+
                "\nVoice status: "+status+
                "\nVoice error: "+(lastError.isEmpty()?"none":lastError);
    }

    public void setActive(String id){
        for(Profile p:profiles())if(p.id.equals(id)){stop();prefs.edit().putString(ACTIVE,id).apply();lastError="";refreshStatus();return;}
    }

    /** Existing UI-compatible profile creator. The WAV itself is imported later as Part 1/Part 2. */
    public void importTrainingAudio(Uri ignored,String requestedName,ImportCallback callback){
        String name=requestedName==null?"":requestedName.trim();
        if(name.isEmpty()||name.length()>40){deliver(callback,false,"Voice name must be 1 to 40 characters.");return;}
        String id="voice-"+UUID.randomUUID();
        try{
            JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));
            JSONObject e=new JSONObject();e.put("id",id);e.put("name",name);a.put(e);
            prefs.edit().putString(PROFILES,a.toString()).putString(ACTIVE,id).apply();
            stop();refreshStatus();deliver(callback,true,name+" created. Copy Part 1 and Part 2, then import both WAV files into this profile.");
        }catch(Exception e){deliver(callback,false,safe(e));}
    }

    public boolean delete(String id){
        List<Profile> list=profiles();
        if(list.size()<=1)return false;
        JSONArray out=new JSONArray();boolean removed=false;
        for(Profile p:list){
            if(p.id.equals(id)){removed=true;continue;}
            try{JSONObject e=new JSONObject();e.put("id",p.id);e.put("name",p.name);out.put(e);}catch(JSONException ignored){}
        }
        if(!removed)return false;
        try{ProfilePhrasePack.deleteProfile(context,id);}catch(Exception ignored){}
        prefs.edit().putString(PROFILES,out.toString()).remove(MANIFEST_PREFIX+id).apply();
        if(id.equals(activeProfile().id)){
            Profile first=profilesAfter(out).get(0);prefs.edit().putString(ACTIVE,first.id).apply();
        }
        stop();refreshStatus();return true;
    }

    private List<Profile> profilesAfter(JSONArray a){
        ArrayList<Profile> out=new ArrayList<>();
        for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)out.add(new Profile(o.optString("id"),o.optString("name"),"",false));}
        return out;
    }

    public synchronized List<Part> prepareParts(Collection<Observation> recent,List<String> selectedGuards){
        Profile profile=activeProfile();
        LinkedHashMap<String,String> unique=new LinkedHashMap<>();
        addBasePhrases(unique);add(unique,speechSettings.prefix());
        for(Map.Entry<String,String> e:speechSettings.nicknames().entrySet())add(unique,e.getValue());
        if(selectedGuards!=null)for(String guard:selectedGuards){add(unique,SpeechRules.guard(guard,speechSettings.nicknames()));add(unique,AnnouncementText.spokenGuard(guard));}
        for(SpeechPreferences.Entry e:speechSettings.entries(SpeechPreferences.ALERT)){add(unique,e.original);if(e.edited())add(unique,e.replacement);}
        for(SpeechPreferences.Entry e:speechSettings.entries(SpeechPreferences.PLACE)){add(unique,e.original);if(e.edited())add(unique,e.replacement);}
        for(SpeechPreferences.Entry e:speechSettings.entries(SpeechPreferences.PHRASE)){add(unique,e.original);if(e.edited())add(unique,e.replacement);}
        if(recent!=null)for(Observation o:recent){
            String formatted=speechSettings.format(o);add(unique,formatted);addAnnouncementParts(unique,formatted);
            add(unique,SpeechRules.guard(o.guard,speechSettings.nicknames()));
            add(unique,SpeechRules.exact(o.issue,speechSettings.overrides(SpeechPreferences.ALERT)));
            add(unique,SpeechRules.exact(o.property,speechSettings.overrides(SpeechPreferences.PLACE)));
        }
        ArrayList<String> phrases=new ArrayList<>(unique.values());
        JSONObject m=new JSONObject();JSONArray a=new JSONArray();for(String x:phrases)a.put(x);
        try{m.put("revision",revision(phrases));m.put("phrases",a);}catch(JSONException e){throw new IllegalStateException(e);}
        prefs.edit().putString(MANIFEST_PREFIX+profile.id,m.toString()).apply();
        refreshStatus();return partsFromManifest(profile.id,m);
    }

    public synchronized List<Part> parts(){
        Profile p=activeProfile();JSONObject m=storedManifest(p.id);if(m==null)return Collections.emptyList();return partsFromManifest(p.id,m);
    }

    private List<Part> partsFromManifest(String profile,JSONObject m){
        JSONArray all=m.optJSONArray("phrases");if(all==null)return Collections.emptyList();
        int n=all.length(),mid=(n+1)/2;String rev=m.optString("revision","");
        Set<Integer> imported=rev.equals(ProfilePhrasePack.revision(context,profile))?ProfilePhrasePack.importedParts(context,profile):Collections.emptySet();
        ArrayList<Part> out=new ArrayList<>();
        for(int part=1;part<=2;part++){
            int from=part==1?0:mid,to=part==1?mid:n;
            StringBuilder script=new StringBuilder();
            for(int i=from;i<to;i++){if(i>from)script.append("[pause 3]\n\n");script.append(all.optString(i)).append('\n');}
            out.add(new Part(part,2,to-from,imported.contains(part),script.toString()));
        }
        return out;
    }

    public synchronized String exactPackScript(Collection<Observation> recent,List<String> guards){
        List<Part> ps=prepareParts(recent,guards);return ps.get(0).script+"\n[pause 3]\n\n"+ps.get(1).script;
    }

    public void importPart(Uri uri,int partNumber,ImportCallback cb){
        Profile p=activeProfile();JSONObject m=storedManifest(p.id);
        if(m==null){deliver(cb,false,"Refresh the two script parts first.");return;}
        JSONArray all=m.optJSONArray("phrases");if(all==null){deliver(cb,false,"The script is empty.");return;}
        int n=all.length(),mid=(n+1)/2,from=partNumber==1?0:mid,to=partNumber==1?mid:n;
        if(partNumber<1||partNumber>2){deliver(cb,false,"Choose Part 1 or Part 2.");return;}
        ArrayList<String> phrases=new ArrayList<>();for(int i=from;i<to;i++)phrases.add(all.optString(i));
        String rev=m.optString("revision","");status="Importing "+p.name+" Part "+partNumber+" of 2";lastError="";
        files.execute(()->{
            try{
                ProfilePhrasePack.Result result=ProfilePhrasePack.importPart(context,uri,p.id,partNumber,rev,phrases);
                main.post(()->{refreshStatus();deliver(cb,true,p.name+" Part "+result.part+" imported: "+result.phrases+" recordings. This profile now has "+result.totalClips+" exact phrases.");});
            }catch(Exception e){String msg=safe(e);main.post(()->{status=p.name+" Part "+partNumber+" import failed";lastError=msg;deliver(cb,false,msg);});}
        });
    }

    public void importExactPack(Uri uri,ImportCallback cb){deliver(cb,false,"Use Part 1 or Part 2 import for the selected voice profile.");}

    public void speak(Observation o){if(o!=null)main.post(()->{if(prefs.getBoolean("voice",true)){speechSettings.remember(Collections.singletonList(o));enqueue(activeProfile().id,speechSettings.format(o),speechSettings.speed());}});}
    public void preview(String text,float rate){main.post(()->enqueue(activeProfile().id,text,Math.max(.75f,Math.min(1.5f,rate))));}
    public boolean welcome(){
        String message="Good evening, Tristan. Let's have a good shift.";if(!ready())return false;
        try{if(resolve(activeProfile().id,message).isEmpty())return false;}catch(Exception e){return false;}
        preview(message,speechSettings.speed());return true;
    }
    public void test(){
        main.post(()->{
            Profile p=activeProfile();if(!ready()){status="Import "+p.name+" Part 1 or Part 2 first";lastError="No exact recordings are installed for "+p.name+" yet.";return;}
            try{
                LinkedHashMap<String,File> map=ProfilePhrasePack.clips(context,p.id);
                String preferred=ExactPhrasePack.normalize("Silvertracker update");
                String phrase=map.containsKey(preferred)?"Silvertracker update":map.keySet().iterator().next();
                enqueue(p.id,phrase,speechSettings.speed());
            }catch(Exception e){lastError=safe(e);status=p.name+" voice test failed";}
        });
    }
    public void readLatest(Collection<Observation> rows){if(rows!=null)for(Observation o:rows)speak(o);}
    public void speechSettingsChanged(){stop();status="Speech wording changed — refresh Part 1 and Part 2 if you added new wording";lastError="";}

    private void enqueue(String profile,String text,float speed){
        String clean=SpeechRules.clean(text);if(clean.isEmpty())return;
        if(!ProfilePhrasePack.ready(context,profile)){status="Voice recordings required";lastError="Import Part 1 and/or Part 2 for "+activeName()+". No substitute voice will be used.";return;}
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
        LinkedHashMap<String,File> map=ProfilePhrasePack.clips(context,profile);
        File direct=map.get(ExactPhrasePack.normalize(text));if(direct!=null)return Collections.singletonList(direct);
        ArrayList<File> result=new ArrayList<>();
        for(String raw:text.split("(?<=[.!?])\\s+")){
            String part=raw.replaceAll("[.!?]+$","").trim();if(part.isEmpty())continue;
            File clip=map.get(ExactPhrasePack.normalize(part));
            if(clip==null)throw new IOException("No exact "+activeName()+" recording for: \""+part+"\". Refresh the two scripts and re-record the half containing that phrase.");
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
        Profile p=activeProfile();Set<Integer> parts=ProfilePhrasePack.importedParts(context,p.id);
        status=ProfilePhrasePack.ready(context,p.id) ? p.name+" exact recordings ready · "+parts.size()+"/2 parts imported "+parts : p.name+" voice pack needs recordings";
    }

    private JSONObject storedManifest(String id){try{String raw=prefs.getString(MANIFEST_PREFIX+id,"");return raw.isEmpty()?null:new JSONObject(raw);}catch(Exception e){return null;}}
    private void addBasePhrases(LinkedHashMap<String,String> out){
        try(InputStream in=context.getAssets().open("exact/base_phrases.txt")){BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));String line;while((line=r.readLine())!=null)add(out,line);}catch(IOException ignored){}
    }
    private static void add(LinkedHashMap<String,String> out,String value){String clean=SpeechRules.clean(value);if(!clean.isEmpty())out.putIfAbsent(ExactPhrasePack.normalize(clean),clean);}
    private static void addAnnouncementParts(LinkedHashMap<String,String> out,String announcement){if(announcement==null)return;for(String raw:announcement.split("(?<=[.!?])\\s+"))add(out,raw.replaceAll("[.!?]+$","").trim());}
    private static String revision(List<String> phrases){
        try{MessageDigest md=MessageDigest.getInstance("SHA-256");for(String p:phrases){md.update(ExactPhrasePack.normalize(p).getBytes(StandardCharsets.UTF_8));md.update((byte)0);}StringBuilder b=new StringBuilder();for(byte x:md.digest())b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString();}catch(Exception e){return String.valueOf(phrases.hashCode());}
    }
    private void deliver(ImportCallback cb,boolean ok,String message){if(cb!=null)main.post(()->cb.done(ok,message));}
    private static String safe(Exception e){String m=e.getMessage();return m==null||m.trim().isEmpty()?e.getClass().getSimpleName():m.trim();}
    private static void deleteTree(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] c=f.listFiles();if(c!=null)for(File x:c)deleteTree(x);}f.delete();}
}
