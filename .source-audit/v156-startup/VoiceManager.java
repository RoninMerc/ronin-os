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
    private static final String COMPLETE_TEMPLATE="voice_complete_library_template_v1";
    private static final String COMPLETE_TEMPLATE_SOURCE="voice_complete_library_template_source_v1";
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
    private final boolean bundledTiffAvailable;
    private volatile String tiffStatus="Tiff is not included in this build";
    private boolean tiffBusy;
    private final List<ImportCallback> tiffCallbacks=new ArrayList<>();

    public VoiceManager(Context context,SharedPreferences prefs){
        this.context=context.getApplicationContext();this.prefs=prefs;this.speechSettings=new SpeechPreferences(this.context);
        migrateProfiles();
        refreshStatus();
        bundledTiffAvailable=BundledTiff.available(this.context);
        if(bundledTiffAvailable){
            tiffStatus=tiffReady()?"Tiff ready — included recording":"Tiff recording included — preparing setup";
            if(!prefs.getBoolean(BundledTiff.DONE_PREF,false))main.post(()->prepareBundledTiff(null));
        }
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
                "\nBase-script phrases: "+canonicalPhrases().size()+
                "\nExact recorded phrases installed: "+clips+
                "\nPending add-on phrases: "+addOnDraft().size()+
                "\nSpeech speed: "+speechSettings.speed()+"x"+
                "\nLast playback speed: "+lastPlaybackRate+"x"+
                "\nPlayed/failed/dropped: "+played+"/"+failed+"/"+dropped+
                "\nBundled Tiff: "+tiffStatus+
                "\nVoice status: "+status+
                "\nVoice error: "+(lastError.isEmpty()?"none":lastError);
    }

    public boolean hasBundledTiff(){return bundledTiffAvailable;}
    public String tiffStatus(){return tiffStatus;}
    public boolean tiffBusy(){return tiffBusy;}
    public boolean tiffReady(){return !BundledTiff.readyId(context,prefs).isEmpty();}
    public void selectTiff(){String id=BundledTiff.readyId(context,prefs);if(!id.isEmpty())setActive(id);}
    private interface TiffSource { InputStream open() throws Exception; }

    public void whenTiffFinished(ImportCallback callback){
        if(callback==null)return;
        if(tiffBusy)tiffCallbacks.add(callback);
        else deliver(callback,tiffReady(),tiffStatus);
    }
    public void prepareBundledTiff(ImportCallback callback){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->prepareBundledTiff(callback));return;}
        if(!bundledTiffAvailable){deliver(callback,false,"This APK does not contain the Tiff audio bundle.");return;}
        runTiffSetup(()->context.getAssets().open(BundledTiff.ASSET),callback);
    }
    /** Test seam uses the same installer and registration path with a local fixture. */
    void prepareTiffFixture(File archive,ImportCallback callback){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->prepareTiffFixture(archive,callback));return;}
        runTiffSetup(()->new FileInputStream(archive),callback);
    }
    private void runTiffSetup(TiffSource source,ImportCallback callback){
        if(callback!=null)tiffCallbacks.add(callback);
        if(tiffBusy)return;
        tiffBusy=true;tiffStatus="Preparing Tiff — existing voices remain unchanged";
        files.execute(()->{
            try(InputStream in=source.open()){
                BundledTiff.Result result=BundledTiff.install(context,prefs,in,text->tiffStatus=text);
                main.post(()->{
                    try{
                        BundledTiff.register(prefs,result);
                        refreshStatus();finishTiff(true,"Tiff ready — "+result.count+" recordings included. Select Tiff to use her.");
                    }catch(Exception e){finishTiff(false,safe(e));}
                });
            }catch(Exception e){String error=safe(e);main.post(()->finishTiff(false,error));}
        });
    }
    private void finishTiff(boolean ok,String message){
        tiffBusy=false;tiffStatus=message;
        List<ImportCallback> callbacks=new ArrayList<>(tiffCallbacks);tiffCallbacks.clear();
        for(ImportCallback callback:callbacks)deliver(callback,ok,message);
    }

    public void setActive(String id){
        for(Profile p:profiles())if(p.id.equals(id)){
            stop();prefs.edit().putString(ACTIVE,id).apply();lastError="";refreshStatus();return;
        }
    }

    public void importTrainingAudio(Uri ignored,String requestedName,ImportCallback callback){
        String name=requestedName==null?"":requestedName.trim();
        if(name.isEmpty()||name.length()>40){deliver(callback,false,"Voice name must be 1 to 40 characters.");return;}
        try{
            Profile source=activeProfile();
            if(savedCompleteTemplate().isEmpty() && ExactPhrasePack.installed(context,source.id))
                saveCompleteTemplate(ExactPhrasePack.phrases(context,source.id),source.name);
        }catch(Exception captureError){}
        String id="voice-"+UUID.randomUUID();
        try{
            JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));
            JSONObject e=new JSONObject();e.put("id",id);e.put("name",name);a.put(e);
            prefs.edit().putString(PROFILES,a.toString()).putString(ACTIVE,id).apply();
            stop();refreshStatus();deliver(callback,true,name+" created. You can import the full base library or start adding exact phrases in small WAVs.");
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
                main.post(()->{refreshStatus();deliver(callback,true,p.name+" base library replaced: "+result.phrases+" exact recordings.");});
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
    private String addOnDraftKey(String profile){return "voice_addon_draft:"+profile;}

    public List<String> parseAddOnPhrases(String raw){
        LinkedHashMap<String,String> unique=new LinkedHashMap<>();
        if(raw!=null)for(String line:raw.split("\\r?\\n")){
            String phrase=SpeechRules.clean(line);
            if(phrase.isEmpty()||phrase.equalsIgnoreCase("[pause 3]"))continue;
            if(phrase.length()>300)throw new IllegalArgumentException("Keep each phrase under 300 characters.");
            String key=ExactPhrasePack.normalize(phrase);
            if(!unique.containsKey(key))unique.put(key,phrase);
        }
        if(unique.isEmpty())throw new IllegalArgumentException("Enter at least one phrase, one per line.");
        if(unique.size()>500)throw new IllegalArgumentException("Use up to 500 phrases in one add-on. You can import unlimited add-on WAVs over time.");
        return new ArrayList<>(unique.values());
    }

    public void saveAddOnDraft(List<String> phrases){
        JSONArray a=new JSONArray();
        if(phrases!=null)for(String phrase:phrases)a.put(phrase);
        prefs.edit().putString(addOnDraftKey(activeProfile().id),a.toString()).apply();
    }

    public List<String> addOnDraft(){
        ArrayList<String> out=new ArrayList<>();
        try{
            JSONArray a=new JSONArray(prefs.getString(addOnDraftKey(activeProfile().id),"[]"));
            for(int i=0;i<a.length();i++){
                String phrase=SpeechRules.clean(a.optString(i));
                if(!phrase.isEmpty())out.add(phrase);
            }
        }catch(Exception ignored){}
        return out;
    }

    public String addOnScript(List<String> phrases){
        if(phrases==null || phrases.isEmpty())throw new IllegalArgumentException("Enter at least one phrase first.");
        return VoiceScriptText.format(phrases);
    }

    public int installedPhraseCount(){
        try{return ExactPhrasePack.clips(context,activeProfile().id).size();}
        catch(Exception ignored){return 0;}
    }

    private void saveCompleteTemplate(List<String> phrases,String source){
        JSONArray a=new JSONArray();
        if(phrases!=null)for(String phrase:phrases){
            String clean=SpeechRules.clean(phrase);
            if(!clean.isEmpty())a.put(clean);
        }
        prefs.edit()
            .putString(COMPLETE_TEMPLATE,a.toString())
            .putString(COMPLETE_TEMPLATE_SOURCE,source==null||source.trim().isEmpty()?"Current library":source.trim())
            .apply();
    }

    private List<String> savedCompleteTemplate(){
        ArrayList<String> out=new ArrayList<>();
        try{
            JSONArray a=new JSONArray(prefs.getString(COMPLETE_TEMPLATE,"[]"));
            LinkedHashSet<String> seen=new LinkedHashSet<>();
            for(int i=0;i<a.length();i++){
                String phrase=SpeechRules.clean(a.optString(i));
                String key=ExactPhrasePack.normalize(phrase);
                if(!phrase.isEmpty()&&seen.add(key))out.add(phrase);
            }
        }catch(Exception ignored){}
        return out;
    }

    public List<String> completeTemplatePhrases(){
        List<String> saved=savedCompleteTemplate();
        return saved.isEmpty()?canonicalPhrases():saved;
    }

    /** Loading a text manifest changes no audio, speech controls, or checkpoint data. */
    public int setCompleteScript(String raw) throws IOException {
        List<String> phrases=VoiceScriptText.parse(raw);
        saveCompleteTemplate(phrases,"Loaded text script");
        return phrases.size();
    }

    public void importScriptTemplate(Uri uri,ImportCallback callback) {
        if(uri==null){deliver(callback,false,"Choose the script text file.");return;}
        files.execute(()->{
            try(InputStream in=context.getContentResolver().openInputStream(uri)) {
                if(in==null)throw new IOException("Could not open this text file.");
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1) {
                    if(out.size()+n>524288)throw new IOException("Choose a script text file smaller than 512 KB.");
                    out.write(buf,0,n);
                }
                int count=setCompleteScript(new String(out.toByteArray(),StandardCharsets.UTF_8));
                deliver(callback,true,"Loaded "+count+" phrases in order. Generate this exact script, then use Import complete-library WAV.");
            }catch(Exception e){deliver(callback,false,safe(e));}
        });
    }

    public String loadedScriptText(){return VoiceScriptText.format(completeTemplatePhrases());}

    public int completeTemplateCount(){return completeTemplatePhrases().size();}

    public String completeTemplateSource(){
        List<String> saved=savedCompleteTemplate();
        if(saved.isEmpty())return "Built-in base";
        String source=prefs.getString(COMPLETE_TEMPLATE_SOURCE,"Current library");
        return source==null||source.trim().isEmpty()?"Current library":source.trim();
    }

    /**
     * Copies exactly what the selected voice can currently say: original base
     * phrases plus every add-on/replacement installed up to this point.
     *
     * The same phrase list is persisted as the import manifest for another
     * voice, so the generated WAV can be mapped exactly after a new profile is created.
     */
    public static final class ScriptExport {
        public final String text,source;
        public final int phrases;
        private final List<String> manifest;
        ScriptExport(List<String> list,String source) {
            manifest=Collections.unmodifiableList(new ArrayList<>(list));
            text=VoiceScriptText.format(manifest);phrases=manifest.size();this.source=source;
        }
    }
    public interface ScriptExportCallback { void done(ScriptExport export,String error); }

    private ScriptExport snapshotEntireScript(Profile profile) throws Exception {
        File dir=ExactPhrasePack.profileDir(context,profile.id);
        List<String> phrases;String source;
        if(new File(dir,"manifest.json").exists()) {
            // An incomplete installed library is an error, not permission to copy
            // a stale/base-only script and misleadingly call it the whole library.
            phrases=ExactPhrasePack.phrases(context,profile.id);
            if(phrases.isEmpty())throw new IOException("The installed voice manifest is empty.");
            source=profile.name;
        }else{
            File[] clips=dir.listFiles((d,n)->n.startsWith("clip-")&&n.endsWith(".wav"));
            if(clips!=null&&clips.length>0)throw new IOException("The voice manifest is missing; no partial script was copied.");
            phrases=savedCompleteTemplate();
            source=phrases.isEmpty()?"Built-in base":completeTemplateSource();
            if(phrases.isEmpty())phrases=canonicalPhrases();
        }
        // Validate exact order and uniqueness against the same parser used at import.
        List<String> validated=VoiceScriptText.parse(VoiceScriptText.format(phrases));
        return new ScriptExport(validated,source);
    }

    public String completeLibraryScript(){
        try{
            ScriptExport export=snapshotEntireScript(activeProfile());
            saveCompleteTemplate(export.manifest,export.source);
            return export.text;
        }catch(Exception e){throw new IllegalStateException(safe(e),e);}
    }

    /** Capture the selected source and queue behind pending WAV imports. */
    public void exportEntireScript(ScriptExportCallback callback){
        if(callback==null)return;
        final Profile selected=activeProfile();
        files.execute(()->{
            try{
                ScriptExport export=snapshotEntireScript(selected);
                saveCompleteTemplate(export.manifest,export.source);
                main.post(()->callback.done(export,""));
            }catch(Exception e){
                String error=safe(e);
                main.post(()->callback.done(null,error));
            }
        });
    }

    /**
     * Import a full WAV generated from the last complete-library script copied.
     * This intentionally replaces only the selected voice profile's library.
     */
    public void importCompleteLibrary(Uri uri,ImportCallback callback){
        final Profile p=activeProfile();
        final List<String> phrases=completeTemplatePhrases();
        final String source=completeTemplateSource();
        status="Importing complete "+p.name+" library";
        lastError="";
        files.execute(()->{
            try{
                ExactPhrasePack.ImportResult result=ExactPhrasePack.importPack(context,uri,p.id,phrases);
                main.post(()->{
                    refreshStatus();
                    deliver(callback,true,p.name+" complete library imported: "+result.total+
                            " exact recordings from the "+source+" template.");
                });
            }catch(Exception e){
                String msg=safe(e);
                main.post(()->{
                    status=p.name+" complete-library import failed";
                    lastError=msg;
                    deliver(callback,false,msg);
                });
            }
        });
    }

    public void importAddOn(Uri uri,ImportCallback callback){
        final Profile p=activeProfile();
        final List<String> phrases=addOnDraft();
        if(phrases.isEmpty()){deliver(callback,false,"Enter and save the add-on phrase list first.");return;}
        status="Adding "+phrases.size()+" exact "+p.name+" phrase"+(phrases.size()==1?"":"s");
        lastError="";
        files.execute(()->{
            try{
                ExactPhrasePack.ImportResult result=ExactPhrasePack.importAdditions(context,uri,p.id,phrases);
                prefs.edit().remove(addOnDraftKey(p.id)).apply();
                main.post(()->{
                    refreshStatus();
                    String message=p.name+" library updated: "+result.added+" added, "+result.replaced+" replaced, "+result.total+" total exact recordings.";
                    deliver(callback,true,message);
                });
            }catch(Exception e){
                String msg=safe(e);
                main.post(()->{
                    status=p.name+" add-on import failed";
                    lastError=msg;
                    deliver(callback,false,msg);
                });
            }
        });
    }

    public String recordingIssue(String text){
        Profile p=activeProfile();
        if(!ExactPhrasePack.installed(context,p.id))
            return "Import the single "+p.name+" WAV first.";
        try{
            List<File> resolved=resolve(p.id,SpeechRules.clean(text));
            return resolved.isEmpty()?"No exact recording matches this wording.":"";
        }catch(Exception e){return safe(e);}
    }

    public void speechSettingsChanged(){
        stop();
        status="Speech wording changed";
        lastError="If the wording is not already recorded, add only the missing words or phrase from Voice library → Add phrases. Existing recordings do not need to be rebuilt.";
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
        return ExactPhraseResolver.resolve(ExactPhrasePack.clips(context,profile),text);
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
        Profile p=activeProfile();
        if(!ExactPhrasePack.installed(context,p.id)){status=p.name+" exact library empty · import base or add phrases";return;}
        try{
            int have=ExactPhrasePack.clips(context,p.id).size();
            status=p.name+" expandable exact library · "+have+" recordings";
        }catch(Exception e){status=p.name+" exact library needs checking";}
    }

    private void deliver(ImportCallback cb,boolean ok,String message){if(cb!=null)main.post(()->cb.done(ok,message));}
    private static String safe(Exception e){String m=e.getMessage();return m==null||m.trim().isEmpty()?e.getClass().getSimpleName():m.trim();}
    private static void deleteTree(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] c=f.listFiles();if(c!=null)for(File x:c)deleteTree(x);}f.delete();}
}
