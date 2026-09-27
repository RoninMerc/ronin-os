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
 * Evelyn-only exact-recording engine.
 *
 * Voice packs may be imported as multiple WAV parts. Every byte played still comes
 * from the imported website recordings; there is no synthesis or fallback voice.
 */
public final class VoiceManager {
    public static final String DEFAULT_ID="evelyn", DEFAULT_NAME="Evelyn";
    private static final String ACTIVE="voice_profile_active";
    private static final String LEGACY_PROFILES="voice_profiles_v3";
    private static final String PART_MANIFEST="evelyn_multipart_script_v1";
    private static final int PART_SIZE=22;
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
        final String text; final float speed;
        Job(String text,float speed){this.text=text;this.speed=speed;}
    }

    private final Context context; private final SharedPreferences prefs;
    public final SpeechPreferences speechSettings;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private final ArrayDeque<Job> queue=new ArrayDeque<>();
    private final ArrayDeque<File> clipQueue=new ArrayDeque<>();
    private Job current; private MediaPlayer player; private AudioFocusRequest focus;
    private volatile String status="Evelyn voice pack needs recordings",lastError="",lastSpokenText="";
    private volatile int played,failed,dropped; private volatile float lastPlaybackRate=1f;

    public VoiceManager(Context context,SharedPreferences prefs){
        this.context=context.getApplicationContext();this.prefs=prefs;this.speechSettings=new SpeechPreferences(this.context);
        migrateLegacy();
        prefs.edit().putString(ACTIVE,DEFAULT_ID).apply();
        refreshStatus();
    }

    private void migrateLegacy(){
        if(MultipartPhrasePack.ready(context))return;
        ArrayList<String> candidates=new ArrayList<>();
        String active=prefs.getString(ACTIVE,"");if(!active.isEmpty()&&!DEFAULT_ID.equals(active))candidates.add(active);
        try{
            JSONArray a=new JSONArray(prefs.getString(LEGACY_PROFILES,"[]"));
            for(int i=0;i<a.length();i++){
                JSONObject o=a.optJSONObject(i);if(o==null)continue;
                String id=o.optString("id"),name=o.optString("name").toLowerCase(Locale.ROOT);
                if((name.contains("eve")||name.contains("evelyn"))&&!candidates.contains(id))candidates.add(id);
            }
        }catch(Exception ignored){}
        if(!candidates.contains("felicity"))candidates.add("felicity");
        for(String id:candidates){
            try{
                if(ExactPhrasePack.installed(context,id)){MultipartPhrasePack.migrateLegacy(context,id);break;}
            }catch(Exception ignored){}
        }
    }

    public List<Profile> profiles(){return Collections.singletonList(new Profile(DEFAULT_ID,DEFAULT_NAME,"",true));}
    public Profile activeProfile(){return profiles().get(0);}
    public String activeName(){return DEFAULT_NAME;}
    public boolean ready(){return MultipartPhrasePack.ready(context);}
    public String status(){return status;}
    public String error(){return lastError;}
    public String lastSpoken(){return lastSpokenText;}
    public int completedCount(){return played;}
    public int generatedCount(){return 0;}
    public int workerPid(){return android.os.Process.myPid();}
    public long generationMillis(){return 0;}
    public float lastPlaybackRate(){return lastPlaybackRate;}

    public String diagnostics(){
        int clips=MultipartPhrasePack.clipCount(context),total=MultipartPhrasePack.totalParts(context);
        Set<Integer> imported=MultipartPhrasePack.importedParts(context);
        return "Voice mode: exact website recordings only"+
                "\nActive voice: Evelyn"+
                "\nMultipart exact pack ready: "+ready()+
                "\nImported voice parts: "+imported.size()+"/"+total+
                "\nImported part numbers: "+imported+
                "\nExact recorded phrases: "+clips+
                "\nSpeech speed: "+speechSettings.speed()+"x"+
                "\nLast playback speed: "+lastPlaybackRate+"x"+
                "\nPlayed/failed/dropped: "+played+"/"+failed+"/"+dropped+
                "\nVoice status: "+status+
                "\nVoice error: "+(lastError.isEmpty()?"none":lastError);
    }

    public void setActive(String ignored){prefs.edit().putString(ACTIVE,DEFAULT_ID).apply();refreshStatus();}
    public void importTrainingAudio(Uri uri,String name,ImportCallback cb){deliver(cb,false,"Patrol Link now has one voice profile: Evelyn. Use the multi-part Evelyn pack controls.");}
    public boolean delete(String id){return false;}

    public synchronized List<Part> prepareParts(Collection<Observation> recent,List<String> selectedGuards){
        LinkedHashMap<String,String> unique=new LinkedHashMap<>();
        addBasePhrases(unique);
        add(unique,speechSettings.prefix());
        // All seeded/user-edited nicknames are included, not only today's three guards.
        for(Map.Entry<String,String> e:speechSettings.nicknames().entrySet())add(unique,e.getValue());
        if(selectedGuards!=null)for(String guard:selectedGuards){
            add(unique,SpeechRules.guard(guard,speechSettings.nicknames()));
            add(unique,AnnouncementText.spokenGuard(guard));
        }
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
        String revision=revision(phrases);
        JSONArray all=new JSONArray();for(String phrase:phrases)all.put(phrase);
        JSONObject m=new JSONObject();
        try{m.put("revision",revision);m.put("part_size",PART_SIZE);m.put("phrases",all);}catch(JSONException e){throw new IllegalStateException(e);}
        prefs.edit().putString(PART_MANIFEST,m.toString()).apply();
        refreshStatus();
        return partsFromManifest(m);
    }

    public synchronized List<Part> parts(){
        JSONObject m=storedManifest();
        if(m==null)return Collections.emptyList();
        return partsFromManifest(m);
    }

    private List<Part> partsFromManifest(JSONObject m){
        ArrayList<Part> out=new ArrayList<>();
        JSONArray all=m.optJSONArray("phrases");if(all==null)return out;
        String rev=m.optString("revision","");
        int total=(all.length()+PART_SIZE-1)/PART_SIZE;
        Set<Integer> imported=rev.equals(MultipartPhrasePack.revision(context))?MultipartPhrasePack.importedParts(context):Collections.emptySet();
        for(int part=1;part<=total;part++){
            int from=(part-1)*PART_SIZE,to=Math.min(all.length(),from+PART_SIZE);
            StringBuilder script=new StringBuilder();
            for(int i=from;i<to;i++){if(i>from)script.append("[pause 3]\n\n");script.append(all.optString(i)).append('\n');}
            out.add(new Part(part,total,to-from,imported.contains(part),script.toString()));
        }
        return out;
    }

    public synchronized Part part(int number){
        List<Part> parts=parts();if(number<1||number>parts.size())return null;return parts.get(number-1);
    }

    public synchronized String exactPackScript(Collection<Observation> recent,List<String> guards){
        List<Part> ps=prepareParts(recent,guards);StringBuilder b=new StringBuilder();
        for(int i=0;i<ps.size();i++){if(i>0)b.append("\n\n");b.append(ps.get(i).script);}
        return b.toString();
    }

    public void importPart(Uri uri,int partNumber,ImportCallback callback){
        JSONObject m=storedManifest();
        if(m==null){deliver(callback,false,"Prepare the Evelyn script parts first.");return;}
        JSONArray all=m.optJSONArray("phrases");if(all==null){deliver(callback,false,"The Evelyn script manifest is empty.");return;}
        int total=(all.length()+PART_SIZE-1)/PART_SIZE;
        if(partNumber<1||partNumber>total){deliver(callback,false,"Invalid Evelyn part number.");return;}
        int from=(partNumber-1)*PART_SIZE,to=Math.min(all.length(),from+PART_SIZE);
        ArrayList<String> phrases=new ArrayList<>();for(int i=from;i<to;i++)phrases.add(all.optString(i));
        String rev=m.optString("revision","");
        status="Importing Evelyn Part "+partNumber+" of "+total;lastError="";
        files.execute(()->{
            try{
                MultipartPhrasePack.Result result=MultipartPhrasePack.importPart(context,uri,partNumber,total,rev,phrases);
                main.post(()->{refreshStatus();deliver(callback,true,"Evelyn Part "+result.part+" imported: "+result.phrases+" recordings. Library now has "+result.totalClips+" exact phrases.");});
            }catch(Exception e){
                String msg=safe(e);main.post(()->{status="Evelyn Part "+partNumber+" import failed";lastError=msg;deliver(callback,false,msg);});
            }
        });
    }

    public void importExactPack(Uri uri,ImportCallback cb){deliver(cb,false,"Use the Evelyn multi-part importer and choose which part this WAV belongs to.");}

    public void speak(Observation o){
        if(o==null)return;
        main.post(()->{if(prefs.getBoolean("voice",true)){speechSettings.remember(Collections.singletonList(o));enqueue(speechSettings.format(o),speechSettings.speed());}});
    }
    public void preview(String text,float rate){main.post(()->enqueue(text,Math.max(.75f,Math.min(1.5f,rate))));}
    public boolean welcome(){
        String message="Good evening, Tristan. Let's have a good shift.";
        if(!ready())return false;
        try{if(resolve(message).isEmpty())return false;}catch(Exception e){return false;}
        preview(message,speechSettings.speed());return true;
    }
    public void test(){
        main.post(()->{
            if(!ready()){status="Import at least one Evelyn part first";lastError="No Evelyn recordings are installed yet.";return;}
            try{
                LinkedHashMap<String,File> map=MultipartPhrasePack.clips(context);
                String preferred=ExactPhrasePack.normalize("Silvertracker update");
                String phrase=map.containsKey(preferred)?"Silvertracker update":map.keySet().iterator().next();
                enqueue(phrase,speechSettings.speed());
            }catch(Exception e){lastError=safe(e);status="Evelyn voice test failed";}
        });
    }
    public void readLatest(Collection<Observation> rows){if(rows!=null)for(Observation o:rows)speak(o);}
    public void speechSettingsChanged(){stop();status="Speech wording changed — refresh the Evelyn script parts if you added new wording";lastError="";}

    private void enqueue(String text,float speed){
        String clean=SpeechRules.clean(text);if(clean.isEmpty())return;
        if(!ready()){status="Evelyn recordings required";lastError="Import the Evelyn voice-pack parts. No substitute voice will be used.";return;}
        if(queue.size()>=60){queue.removeFirst();dropped++;}
        queue.addLast(new Job(clean,speed));dispatch();
    }
    private void dispatch(){
        if(current!=null)return;Job next=queue.pollFirst();if(next==null){refreshStatus();return;}
        try{
            List<File> clips=resolve(next.text);if(clips.isEmpty())throw new IOException("No exact Evelyn recording matches this update.");
            current=next;clipQueue.clear();clipQueue.addAll(clips);status="Playing exact Evelyn website recording";playNextClip();
        }catch(Exception e){failed++;lastError=safe(e);status="Evelyn recording needed";current=null;dispatch();}
    }
    private List<File> resolve(String text)throws Exception{
        LinkedHashMap<String,File> map=MultipartPhrasePack.clips(context);
        File direct=map.get(ExactPhrasePack.normalize(text));if(direct!=null)return Collections.singletonList(direct);
        ArrayList<File> result=new ArrayList<>();
        for(String raw:text.split("(?<=[.!?])\\s+")){
            String part=raw.replaceAll("[.!?]+$","").trim();if(part.isEmpty())continue;
            File clip=map.get(ExactPhrasePack.normalize(part));
            if(clip==null)throw new IOException("No exact Evelyn recording for: \""+part+"\". Refresh the script parts and record/import the part containing that phrase.");
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
            mp.setOnErrorListener((p,w,e)->{failCurrent("Could not play an Evelyn recording.");return true;});mp.prepareAsync();
        }catch(Exception e){failCurrent(safe(e));}
    }
    private void failCurrent(String message){failed++;lastError=message;status="Evelyn playback failed";current=null;clipQueue.clear();releasePlayer();main.post(this::dispatch);}
    private void releasePlayer(){
        if(player!=null){try{player.stop();}catch(Exception ignored){}try{player.release();}catch(Exception ignored){}player=null;}
        if(focus!=null){try{((AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocusRequest(focus);}catch(Exception ignored){}focus=null;}
    }
    public void stop(){if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::stop);return;}queue.clear();clipQueue.clear();current=null;releasePlayer();}
    private void refreshStatus(){
        int total=MultipartPhrasePack.totalParts(context),done=MultipartPhrasePack.importedParts(context).size();
        if(ready())status=total>0?"Evelyn exact recordings ready · "+done+"/"+total+" parts imported":"Evelyn exact recordings ready";
        else status="Evelyn voice pack needs recordings";
    }

    private JSONObject storedManifest(){try{String raw=prefs.getString(PART_MANIFEST,"");return raw.isEmpty()?null:new JSONObject(raw);}catch(Exception e){return null;}}
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
}
