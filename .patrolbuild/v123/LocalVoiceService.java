package au.com.roningroup.patrollink;

import android.app.Service;
import android.content.*;
import android.media.*;
import android.os.*;
import java.io.*;

/** Plays the reviewed original WAV. No neural model, TTS, networking or substitute voice. */
public final class LocalVoiceService extends Service {
    public static final int INIT=1,SPEAK=2,STOP=3,EVENT=100;
    private final Handler main=new Handler(Looper.getMainLooper());private MediaPlayer player;private AudioFocusRequest focus;
    private Messenger reply;private String id="";private long generation;private float speed=1f;
    private final Messenger input=new Messenger(new Handler(Looper.getMainLooper(),m->{
        if(m.what==STOP){generation++;release();return true;}
        if(m.what==INIT){send(m.replyTo,"init","READY","Original-recording player ready",1f);return true;}
        if(m.what==SPEAK){generation++;release();reply=m.replyTo;Bundle b=m.getData();id=b.getString("id","");speed=Math.max(.75f,Math.min(1.5f,b.getFloat("speed",1f)));play(b.getString("reference",""),generation);return true;}
        return false;
    }));
    @Override public IBinder onBind(Intent i){return input.getBinder();}
    private void play(String path,long token){
        try{
            File f=new File(path).getCanonicalFile();String allowed=new File(getFilesDir(),"recorded_voices").getCanonicalPath()+File.separator;
            if(!f.getPath().startsWith(allowed)||!f.isFile())throw new IOException("Original recording missing. No substitute voice was used.");
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            AudioManager am=(AudioManager)getSystemService(AUDIO_SERVICE);
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).setOnAudioFocusChangeListener(change->{
                if(player==null)return;
                try{if(change==AudioManager.AUDIOFOCUS_LOSS)finish(false,"Audio output taken by another app");else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)player.pause();else if(change==AudioManager.AUDIOFOCUS_GAIN)player.start();}catch(Exception e){finish(false,"Audio output interrupted");}
            },main).build();
            if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){finish(false,"Audio output is busy");return;}
            MediaPlayer mp=new MediaPlayer();player=mp;mp.setAudioAttributes(attrs);mp.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);mp.setDataSource(f.getPath());
            mp.setOnPreparedListener(p->{if(player!=p||token!=generation)return;try{
                // Normal speed uses untouched playback; no pitch, gain, resampling or model processing is applied by this app.
                if(Math.abs(speed-1f)>.001f)p.setPlaybackParams(new PlaybackParams().allowDefaults().setPitch(1f).setSpeed(speed));
                p.start();send(reply,id,"SPEAKING","Playing original website recording",speed);
            }catch(Exception e){finish(false,"The selected playback speed was not supported");}});
            mp.setOnCompletionListener(p->{if(player==p&&token==generation)finish(true,"Original recording completed");});
            mp.setOnErrorListener((p,w,e)->{if(player==p&&token==generation)finish(false,"Original audio could not play ("+w+"/"+e+")");return true;});mp.prepareAsync();
        }catch(Exception e){finish(false,e.getMessage()==null?"Original recording could not play":e.getMessage());}
    }
    private void finish(boolean ok,String detail){Messenger r=reply;String job=id;float rate=speed;release();send(r,job,ok?"DONE":"ERROR",detail,rate);}
    private void release(){MediaPlayer old=player;player=null;if(old!=null){try{old.stop();}catch(Exception ignored){}old.release();}if(focus!=null){((AudioManager)getSystemService(AUDIO_SERVICE)).abandonAudioFocusRequest(focus);focus=null;}reply=null;}
    private static void send(Messenger r,String id,String state,String detail,float speed){if(r==null)return;Message m=Message.obtain(null,EVENT);Bundle b=new Bundle();b.putString("id",id);b.putString("state",state);b.putString("detail",detail);b.putFloat("speed",speed);m.setData(b);try{r.send(m);}catch(RemoteException ignored){}}
    @Override public void onDestroy(){generation++;release();super.onDestroy();}
}
