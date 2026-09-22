package au.com.roningroup.patrollink;

import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;

/** Only generated audio conditioned on the selected local reference may reach the speaker. */
public final class VoiceManager {
    public static final String DEFAULT_ID = "felicity", DEFAULT_NAME = "Felicity";
    private static final String PROFILES = "voice_profiles_v3", ACTIVE = "voice_profile_active";
    public interface ImportCallback { void done(boolean ok, String message); }
    public static final class Profile {
        public final String id, name, path; public final boolean builtIn;
        Profile(String id, String name, String path, boolean builtIn) { this.id=id; this.name=name; this.path=path; this.builtIn=builtIn; }
    }
    private static final class Item {
        final String token = UUID.randomUUID().toString(), profile, text; final long queuedAt = SystemClock.elapsedRealtime();
        Item(String profile, String text) { this.profile=profile; this.text=text; }
    }
    private final Context context; private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private Messenger remote; private boolean bound, modelLoaded; private Item current;
    private MediaPlayer player; private AudioFocusRequest focus; private Runnable deadline;
    private volatile String status = "Preparing local voice reference", lastError = "", lastSpokenText = "";
    private volatile long lastGenerationMs; private volatile int generated, played, failed, dropped, voicePid;
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = new Messenger(binder); status = "Loading on-device voice model"; send(LocalVoiceService.WARM, null, null);
        }
        public void onServiceDisconnected(ComponentName name) {
            remote = null; modelLoaded = false; failCurrent("Local voice worker restarted; monitoring is unaffected");
        }
        public void onBindingDied(ComponentName name) {
            if (bound) { try { context.unbindService(this); } catch (Exception ignored) {} }
            bound = false; remote = null; modelLoaded = false; failCurrent("Local voice worker restarted");
            main.postDelayed(VoiceManager.this::ensureBound, 1500);
        }
        public void onNullBinding(ComponentName name) { failCurrent("Local voice worker could not start"); }
    };
    public VoiceManager(Context c, SharedPreferences p) {
        context = c.getApplicationContext(); prefs = p;
        migrate();
        files.execute(() -> {
            try { ensureReference(activeProfile()); main.post(() -> { status = "Local reference prepared"; if (prefs.getBoolean("voice", true)) ensureBound(); }); }
            catch (Exception ex) { lastError = safe(ex); status = "Voice reference needs attention"; }
        });
    }
    private void migrate() {
        if (!prefs.contains(ACTIVE)) prefs.edit().putString(ACTIVE, DEFAULT_ID).apply();
        if (!prefs.contains(PROFILES)) {
            JSONArray combined = new JSONArray(); Set<String> ids = new HashSet<>();
            for (String key : new String[]{"voice_profiles_v1", "voice_profiles_v2"}) {
                try { JSONArray a = new JSONArray(prefs.getString(key, "[]"));
                    for (int i=0;i<a.length();i++) { JSONObject o=a.optJSONObject(i); if(o==null)continue; String id=o.optString("id");
                        if(!id.isEmpty() && ids.add(id)) { JSONObject n=new JSONObject(); n.put("id",id);n.put("name",o.optString("name"));n.put("path",o.optString("path"));combined.put(n); }
                    }
                } catch (Exception ignored) {}
            }
            prefs.edit().putString(PROFILES, combined.toString()).apply();
        }
        prefs.edit().remove("voice_api_key_cipher_v1").remove("felicity_cloud_voice_id").apply();
        try { KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(ks.containsAlias("patrol_link_voice_api_v1"))ks.deleteEntry("patrol_link_voice_api_v1"); }catch(Exception ignored){}
    }
    private File directory() throws IOException { File d=new File(context.getFilesDir(),"local_voice_v121");if(!d.exists()&&!d.mkdirs())throw new IOException("Cannot create local voice storage.");return d; }
    private File referencePath(String id) throws IOException {
        if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw new IOException("Invalid local voice identifier.");
        return new File(directory(),id+".wav");
    }
    private File ensureReference(Profile p) throws Exception {
        File dest=referencePath(p.id);if(dest.isFile()&&dest.length()>1024)return dest;
        File temp=new File(directory(),p.id+".preparing");
        if(p.builtIn) {
            try(InputStream in=context.getAssets().open("voice/felicity.wav");OutputStream out=new FileOutputStream(temp)){copy(in,out,2L*1024*1024);}
        } else { if(p.path.isEmpty())throw new IOException("Re-import the selected voice WAV.");VoiceReference.prepare(new File(p.path),temp); }
        if(!temp.renameTo(dest)){temp.delete();throw new IOException("Could not save the selected voice reference.");}return dest;
    }
    public List<Profile> profiles() {
        ArrayList<Profile> result=new ArrayList<>();result.add(new Profile(DEFAULT_ID,DEFAULT_NAME,"",true));
        try {JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String id=o.optString("id"),name=o.optString("name");if(id.matches("[A-Za-z0-9_-]{1,80}")&&!name.isEmpty())result.add(new Profile(id,name,o.optString("path"),false));}}catch(Exception ignored){}
        return result;
    }
    public Profile activeProfile() {
        String id=prefs.getString(ACTIVE,DEFAULT_ID);for(Profile p:profiles())if(p.id.equals(id))return p;
        return new Profile(id==null?"missing":id,"Selected voice unavailable","",false);
    }
    public String activeName(){return activeProfile().name;}
    public boolean ready(){try{return modelLoaded&&referencePath(activeProfile().id).isFile();}catch(Exception e){return false;}}
    public String status(){return status;}
    public String error(){return lastError;}
    public String lastSpoken(){return lastSpokenText;}
    public int completedCount(){return played;}
    public int generatedCount(){return generated;}
    public int workerPid(){return voicePid;}
    public long generationMillis(){return lastGenerationMs;}
    public String diagnostics(){return "Local voice: "+activeName()+"\nVoice engine: "+status+"\nVoice model loaded: "+modelLoaded+"\nVoice worker PID: "+voicePid+"\nVoice queued/generated/played/failed/dropped: "+queue.size()+"/"+generated+"/"+played+"/"+failed+"/"+dropped+"\nLast generation (ms): "+lastGenerationMs+"\nVoice error: "+(lastError.isEmpty()?"none":lastError);}
    public void setActive(String id) {
        for(Profile p:profiles())if(p.id.equals(id)) {
            stop();prefs.edit().putString(ACTIVE,id).apply();lastError="";status="Preparing "+p.name;
            files.execute(()->{try{ensureReference(p);main.post(()->{status="Selected "+p.name;ensureBound();});}catch(Exception e){lastError=safe(e);status="Voice reference unavailable";}});return;
        }
    }
    public void importTrainingAudio(Uri uri,String requestedName,ImportCallback callback) {
        String name=requestedName==null?"":requestedName.trim();
        if(uri==null||name.isEmpty()||name.length()>40){callback.done(false,"Choose a WAV and a voice name of 1–40 characters.");return;}
        status="Importing "+name+" locally";
        files.execute(()->{
            String id="voice-"+UUID.randomUUID();File source=null,ref=null;
            try {
                source=new File(directory(),id+".source.wav");ref=referencePath(id);
                try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(source)){if(in==null)throw new IOException("Cannot open this WAV.");copy(in,out,120L*1024*1024);}
                VoiceReference.prepare(source,ref);
                JSONObject entry=new JSONObject();entry.put("id",id);entry.put("name",name);entry.put("path",source.getAbsolutePath());
                synchronized(this){JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));a.put(entry);prefs.edit().putString(PROFILES,a.toString()).apply();}
                main.post(()->{setActive(id);prefs.edit().putBoolean("voice",true).apply();callback.done(true,name+" imported. Its recording will be used for all speech.");test();});
            }catch(Exception e){if(source!=null)source.delete();if(ref!=null)ref.delete();String error=safe(e);main.post(()->{lastError=error;status="Import failed";callback.done(false,error);});}
        });
    }
    public boolean delete(String id) {
        if(DEFAULT_ID.equals(id))return false;stop();JSONArray out=new JSONArray();boolean found=false;
        try{JSONArray a=new JSONArray(prefs.getString(PROFILES,"[]"));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);if(id.equals(o.optString("id"))){found=true;File f=new File(o.optString("path"));if(f.getCanonicalPath().startsWith(context.getFilesDir().getCanonicalPath()+File.separator))f.delete();referencePath(id).delete();}else out.put(o);}}catch(Exception ignored){}
        if(found){prefs.edit().putString(PROFILES,out.toString()).apply();if(id.equals(prefs.getString(ACTIVE,DEFAULT_ID)))setActive(DEFAULT_ID);}return found;
    }
    public void speak(Observation o){if(o==null)return;main.post(()->{if(prefs.getBoolean("voice",true))enqueue(AnnouncementText.format(o));});}
    public void test(){main.post(()->enqueue(AnnouncementText.format(new Observation("999999","T.MURD","Impeccable","Third warning parking breach","",System.currentTimeMillis()))));}
    public void readLatest(Collection<Observation> entries){for(Observation o:entries)speak(o);}
    private void enqueue(String text){
        if(queue.size()>=60){queue.removeFirst();dropped++;}
        queue.addLast(new Item(activeProfile().id,text));ensureBound();dispatch();
    }
    private void ensureBound(){
        if(bound)return;
        try{bound=context.bindService(new Intent(context,LocalVoiceService.class),connection,Context.BIND_AUTO_CREATE);if(!bound){status="Local voice worker unavailable";lastError=status;}}
        catch(Exception e){bound=false;lastError=safe(e);status="Local voice worker unavailable";}
    }
    private void dispatch(){
        if(current!=null||remote==null||!modelLoaded)return;
        while(!queue.isEmpty()){
            Item candidate=queue.removeFirst();if(!candidate.profile.equals(activeProfile().id)){dropped++;continue;}
            if(SystemClock.elapsedRealtime()-candidate.queuedAt>300000){dropped++;continue;}
            current=candidate;status="Generating complete update in "+activeName();lastError="";
            final Item sending=current;
            files.execute(()->{try{File reference=ensureReference(activeProfile());main.post(()->{if(current!=sending||!sending.profile.equals(activeProfile().id))return;send(LocalVoiceService.GENERATE,sending,reference);setDeadline(sending.token,180000);});}
                catch(Exception e){main.post(()->failCurrent(safe(e)));}});
            break;
        }
    }
    private void send(int type,Item item,File reference){
        if(remote==null)return;Message m=Message.obtain(null,type);m.replyTo=replies;Bundle b=new Bundle();
        if(item!=null){b.putString("token",item.token);b.putString("profile",item.profile);b.putString("text",item.text);}
        if(reference!=null)b.putString("reference",reference.getAbsolutePath());m.setData(b);
        try{remote.send(m);}catch(RemoteException e){failCurrent("Local voice worker disconnected");}
    }
    private boolean receive(Message m){
        Bundle b=m.getData();voicePid=b.getInt("voicePid",voicePid);
        if(m.what==LocalVoiceService.READY){modelLoaded=true;status="Ready: complete updates use "+activeName();dispatch();return true;}
        String token=b.getString("token","");
        if(m.what==LocalVoiceService.ERROR&&token.isEmpty()){lastError=b.getString("status","Model could not load");status=lastError;modelLoaded=false;return true;}
        if(current==null||!current.token.equals(token)||!current.profile.equals(b.getString("profile")))return true;
        if(m.what==LocalVoiceService.PROGRESS){status=b.getString("status",status);return true;}
        if(m.what==LocalVoiceService.ERROR){failCurrent(b.getString("status","Local voice generation failed"));return true;}
        if(m.what==LocalVoiceService.AUDIO){modelLoaded=true;generated++;lastGenerationMs=b.getLong("generationMs");play(current,b.getString("path",""));return true;}
        return false;
    }
    private void play(Item item,String path){
        try {
            File file=new File(path);String allowed=new File(context.getCacheDir(),"local_speech_v121").getCanonicalPath()+File.separator;
            if(!file.getCanonicalPath().startsWith(allowed)||!file.isFile())throw new IOException("Generated speech file is missing.");
            releasePlayer();MediaPlayer mp=new MediaPlayer();player=mp;
            AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            mp.setAudioAttributes(attributes);mp.setDataSource(path);
            mp.setOnPreparedListener(p->{
                if(current!=item){releasePlayer();return;}
                AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
                focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(change->{if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)failCurrent("Speech paused by another audio application");},main).build();
                if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){failCurrent("Audio is busy; activity remains on the dashboard");return;}
                status="Speaking full update in "+activeName();setDeadline(item.token,Math.max(10000,p.getDuration()+5000L));p.start();
            });
            mp.setOnCompletionListener(p->{if(current==item){played++;lastSpokenText=item.text;status="Ready: complete updates use "+activeName();finish(item.token);}});
            mp.setOnErrorListener((p,w,e)->{if(current==item)failCurrent("Generated speech could not be played");return true;});mp.prepareAsync();
        }catch(Exception e){failCurrent(safe(e));}
    }
    private void setDeadline(String token,long ms){if(deadline!=null)main.removeCallbacks(deadline);deadline=()->{if(current!=null&&current.token.equals(token)){send(LocalVoiceService.CANCEL,null,null);failCurrent("Local voice operation timed out; feed monitoring continues");}};main.postDelayed(deadline,ms);}
    private void failCurrent(String why){lastError=why;status=why;failed++;if(current!=null)finish(current.token);}
    private void finish(String token){if(current==null||!current.token.equals(token))return;if(deadline!=null)main.removeCallbacks(deadline);deadline=null;releasePlayer();current=null;main.post(this::dispatch);}
    private void releasePlayer(){if(player!=null){try{player.stop();}catch(Exception ignored){}player.release();player=null;}if(focus!=null){((AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocusRequest(focus);focus=null;}}
    public void stop(){if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::stop);return;}send(LocalVoiceService.CANCEL,null,null);queue.clear();if(deadline!=null)main.removeCallbacks(deadline);deadline=null;current=null;releasePlayer();}
    private static void copy(InputStream in,OutputStream out,long limit)throws IOException{byte[] b=new byte[65536];long total=0;int n;while((n=in.read(b))!=-1){total+=n;if(total>limit)throw new IOException("The voice recording is too large (maximum 120 MB).");out.write(b,0,n);}}
    private static String safe(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
