package au.com.roningroup.patrollink;

import android.content.*;
import android.content.res.AssetFileDescriptor;
import android.media.*;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;

/**
 * Lightweight Patrol Link voice-pack player.
 *
 * Felicity is bundled as clean pre-recorded alert clips. Imported profiles use the exact
 * Patrol Link training-script recording and remain completely local. Patrol Link never uses
 * Android TextToSpeech, ElevenLabs, another API, or any fallback voice. If a requested phrase
 * is not present in the selected local voice pack, the app does not substitute another voice.
 */
public final class VoiceManager {
    public static final String DEFAULT_ID = "felicity";
    public static final String DEFAULT_NAME = "Felicity";
    private static final String PREF_PROFILES = "voice_profiles_v1";
    private static final String PREF_ACTIVE = "voice_profile_active";
    private static final long BASE_DURATION_MS = 308_290L;
    private static final long MAX_IMPORT_BYTES = 120L * 1024L * 1024L;

    // Positions in the exact training script supplied for Patrol Link.
    private static final long SCAN_START = 44_100L, SCAN_END = 45_320L;
    private static final long BREACH_START = 45_530L, BREACH_END = 46_880L;
    private static final long INCIDENT_START = 47_070L, INCIDENT_END = 48_590L;
    private static final long GENERIC_START = 48_640L, GENERIC_END = 50_520L;

    public static final class Profile {
        public final String id, name, path;
        public final long durationMs;
        public final boolean builtIn;
        Profile(String id, String name, String path, long durationMs, boolean builtIn) {
            this.id=id; this.name=name; this.path=path; this.durationMs=durationMs; this.builtIn=builtIn;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private MediaPlayer player;
    private Runnable stopRunnable;

    public VoiceManager(Context context, SharedPreferences prefs) {
        this.context=context.getApplicationContext(); this.prefs=prefs;
        SharedPreferences.Editor init=prefs.edit();
        if (!prefs.contains(PREF_ACTIVE)) init.putString(PREF_ACTIVE, DEFAULT_ID);
        if (!prefs.getBoolean("voice_v117_migrated",false)) init.putBoolean("voice",true).putBoolean("voice_v117_migrated",true);
        init.apply();
    }

    public String activeName() { return activeProfile().name; }
    public boolean ready() { return activeProfile()!=null; }

    public List<Profile> profiles() {
        ArrayList<Profile> out=new ArrayList<>();
        out.add(new Profile(DEFAULT_ID,DEFAULT_NAME,null,BASE_DURATION_MS,true));
        try {
            JSONArray arr=new JSONArray(prefs.getString(PREF_PROFILES,"[]"));
            for(int i=0;i<arr.length();i++) {
                JSONObject o=arr.optJSONObject(i); if(o==null) continue;
                String id=o.optString("id"), name=o.optString("name"), path=o.optString("path"); long d=o.optLong("duration",0);
                if(id.isEmpty()||name.isEmpty()||path.isEmpty()||d<=0) continue;
                File f=new File(path); if(f.isFile()) out.add(new Profile(id,name,path,d,false));
            }
        } catch(Exception ignored) {}
        return out;
    }

    public Profile activeProfile() {
        String wanted=prefs.getString(PREF_ACTIVE,DEFAULT_ID);
        for(Profile p:profiles()) if(p.id.equals(wanted)) return p;
        prefs.edit().putString(PREF_ACTIVE,DEFAULT_ID).apply();
        return new Profile(DEFAULT_ID,DEFAULT_NAME,null,BASE_DURATION_MS,true);
    }

    public void setActive(String id) {
        for(Profile p:profiles()) if(p.id.equals(id)) { prefs.edit().putString(PREF_ACTIVE,id).apply(); return; }
    }

    public Profile importTrainingAudio(Uri uri, String requestedName) throws Exception {
        if(uri==null) throw new IllegalArgumentException("Choose an audio file.");
        String name=requestedName==null?"":requestedName.trim();
        if(name.isEmpty()||name.length()>40) throw new IllegalArgumentException("Voice name must be 1 to 40 characters.");
        File dir=new File(context.getFilesDir(),"voice_profiles"); if(!dir.exists()&&!dir.mkdirs()) throw new IOException("Could not create the voice library.");
        String id="voice-"+UUID.randomUUID(); File dest=new File(dir,id+".audio");
        long total=0;
        try(InputStream in=context.getContentResolver().openInputStream(uri); OutputStream out=new FileOutputStream(dest)) {
            if(in==null) throw new IOException("The selected audio file could not be opened.");
            byte[] buf=new byte[64*1024]; int n;
            while((n=in.read(buf))!=-1) { total+=n; if(total>MAX_IMPORT_BYTES) throw new IOException("Voice audio is larger than 120 MB."); out.write(buf,0,n); }
        } catch(Exception e) { dest.delete(); throw e; }
        long duration=duration(dest.getAbsolutePath());
        if(duration<120_000L||duration>600_000L) { dest.delete(); throw new IllegalArgumentException("Use the complete Patrol Link training-script recording (about 2 to 10 minutes)."); }
        JSONArray arr=loadJson();
        JSONObject o=new JSONObject(); o.put("id",id);o.put("name",name);o.put("path",dest.getAbsolutePath());o.put("duration",duration);arr.put(o);
        prefs.edit().putString(PREF_PROFILES,arr.toString()).putString(PREF_ACTIVE,id).apply();
        return new Profile(id,name,dest.getAbsolutePath(),duration,false);
    }

    public boolean delete(String id) {
        if(id==null||DEFAULT_ID.equals(id)) return false;
        JSONArray old=loadJson(), next=new JSONArray(); boolean removed=false;
        for(int i=0;i<old.length();i++) {
            JSONObject o=old.optJSONObject(i); if(o==null) continue;
            if(id.equals(o.optString("id"))) { new File(o.optString("path","")).delete(); removed=true; }
            else next.put(o);
        }
        if(removed) {
            SharedPreferences.Editor e=prefs.edit().putString(PREF_PROFILES,next.toString());
            if(id.equals(prefs.getString(PREF_ACTIVE,DEFAULT_ID))) e.putString(PREF_ACTIVE,DEFAULT_ID); e.apply();
        }
        return removed;
    }

    private JSONArray loadJson() { try { return new JSONArray(prefs.getString(PREF_PROFILES,"[]")); } catch(Exception e) { return new JSONArray(); } }
    private static long duration(String path) {
        MediaMetadataRetriever r=new MediaMetadataRetriever();
        try { r.setDataSource(path); String d=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION); return d==null?0:Long.parseLong(d); }
        catch(Exception e) { return 0; } finally { try { r.release(); } catch(Exception ignored) {} }
    }

    public void speak(Observation o) {
        if(o==null) return; String a=o.activity()==null?"":o.activity().toLowerCase(Locale.ROOT);
        if(a.contains("breach")||a.contains("parking")) enqueue("breach");
        else if(a.contains("scan")) enqueue("scan");
        else if(a.contains("incident")||a.contains("report")||a.contains("suspicious")||a.contains("alarm")) enqueue("incident");
        else enqueue("generic");
    }
    public void test() { enqueue("generic"); }

    private synchronized void enqueue(String kind) {
        if(queue.size()>8) queue.pollFirst(); queue.addLast(kind); if(player==null) playNext();
    }
    private synchronized void playNext() {
        String kind=queue.pollFirst(); if(kind==null) return;
        Profile p=activeProfile();
        try {
            MediaPlayer mp=new MediaPlayer(); player=mp;
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            if(p.builtIn) {
                int res=kind.equals("scan")?R.raw.felicity_scan:kind.equals("breach")?R.raw.felicity_breach:kind.equals("incident")?R.raw.felicity_incident:R.raw.felicity_generic;
                AssetFileDescriptor afd=context.getResources().openRawResourceFd(res);
                mp.setDataSource(afd.getFileDescriptor(),afd.getStartOffset(),afd.getLength()); afd.close();
                mp.setOnCompletionListener(x->finishCurrent()); mp.prepare(); mp.start();
            } else {
                long[] seg=segment(kind,p.durationMs); int start=(int)Math.max(0,Math.min(Integer.MAX_VALUE,seg[0])); long length=Math.max(300,seg[1]-seg[0]);
                mp.setDataSource(p.path); mp.setOnErrorListener((x,what,extra)->{finishCurrent();return true;});
                mp.setOnPreparedListener(x->{ try { x.seekTo(start,MediaPlayer.SEEK_CLOSEST); x.start(); scheduleStop(length); } catch(Exception e){finishCurrent();} });
                mp.prepareAsync();
            }
        } catch(Exception e) { finishCurrent(); }
    }
    private long[] segment(String kind,long duration) {
        long s=GENERIC_START,e=GENERIC_END;
        if(kind.equals("scan")){s=SCAN_START;e=SCAN_END;} else if(kind.equals("breach")){s=BREACH_START;e=BREACH_END;} else if(kind.equals("incident")){s=INCIDENT_START;e=INCIDENT_END;}
        double scale=(double)duration/(double)BASE_DURATION_MS;
        return new long[]{Math.round(s*scale),Math.round(e*scale)};
    }
    private synchronized void scheduleStop(long ms) {
        if(stopRunnable!=null) handler.removeCallbacks(stopRunnable);
        stopRunnable=()->finishCurrent(); handler.postDelayed(stopRunnable,ms);
    }
    private synchronized void finishCurrent() {
        if(stopRunnable!=null){handler.removeCallbacks(stopRunnable);stopRunnable=null;}
        if(player!=null){try{player.stop();}catch(Exception ignored){} try{player.release();}catch(Exception ignored){} player=null;}
        handler.post(this::playNext);
    }
    public synchronized void stop() { queue.clear(); if(stopRunnable!=null){handler.removeCallbacks(stopRunnable);stopRunnable=null;} if(player!=null){try{player.stop();}catch(Exception ignored){} try{player.release();}catch(Exception ignored){} player=null;} }
}
