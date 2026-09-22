package au.com.roningroup.patrollink;

import android.app.Service;
import android.content.*;
import android.media.*;
import android.os.*;
import com.k2fsa.sherpa.onnx.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bound, private, separate-process speech worker. No network requests or system speech engines. */
public final class LocalVoiceService extends Service {
    public static final int INIT=1, SPEAK=2, STOP=3, EVENT=100;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"patrol-local-speech");t.setPriority(Thread.NORM_PRIORITY-1);return t;});
    private final AtomicLong epoch=new AtomicLong();
    private OfflineTts model;
    private MediaPlayer player;
    private AudioFocusRequest focus;
    private Messenger playingReply;
    private String playingId="", playingPath="", playingHash="";
    private long playingEpoch;
    private final Messenger input=new Messenger(new Handler(Looper.getMainLooper(),message->{
        if(message.what==STOP){epoch.incrementAndGet();releasePlayer();return true;}
        final Messenger reply=message.replyTo;
        final Bundle args=new Bundle(message.getData());
        final String id=args.getString("id","");
        if(message.what==INIT){
            worker.execute(()->{try{send(reply,id,"LOADING","Loading the bundled local speech model.","","");ensureModel();defaultReference();send(reply,id,"READY","Local speech model loaded.","","");}catch(Throwable e){send(reply,id,"ERROR",error(e),"","");}});
            return true;
        }
        if(message.what==SPEAK){
            final long job=epoch.incrementAndGet();releasePlayer();
            worker.execute(()->generate(reply,args,id,job));return true;
        }
        return false;
    }));
    @Override public IBinder onBind(Intent intent){return input.getBinder();}

    private void ensureModel()throws Exception {
        if(model!=null)return;
        File dir=new File(getNoBackupFilesDir(),"speech-model-20260126");
        if(!dir.exists()&&!dir.mkdirs())throw new IOException("Not enough storage for the local speech model.");
        JSONObject manifest;
        try(InputStream in=getAssets().open("speech-model/manifest.json")){manifest=new JSONObject(new String(readAll(in),"UTF-8"));}
        for(Iterator<String> it=manifest.keys();it.hasNext();){
            String n=it.next();if(n.contains("/")||n.contains(".."))throw new IOException("Invalid model manifest.");
            JSONObject spec=manifest.getJSONObject(n);File dest=new File(dir,n);
            if(dest.isFile()&&dest.length()==spec.getLong("bytes"))continue;
            File tmp=new File(dir,n+".part");
            try(InputStream in=getAssets().open("speech-model/"+n);OutputStream out=new FileOutputStream(tmp)){pipe(in,out);}
            if(tmp.length()!=spec.getLong("bytes")||!VoiceAudio.hash(tmp).equals(spec.getString("sha256"))){tmp.delete();throw new IOException("Speech model integrity check failed.");}
            if(dest.exists()&&!dest.delete())throw new IOException("Cannot replace local model file.");
            if(!tmp.renameTo(dest))throw new IOException("Cannot install local model file.");
        }
        OfflineTtsPocketModelConfig p=new OfflineTtsPocketModelConfig();
        p.setLmFlow(new File(dir,"lm_flow.int8.onnx").getPath());p.setLmMain(new File(dir,"lm_main.int8.onnx").getPath());
        p.setEncoder(new File(dir,"encoder.onnx").getPath());p.setDecoder(new File(dir,"decoder.int8.onnx").getPath());
        p.setTextConditioner(new File(dir,"text_conditioner.onnx").getPath());p.setVocabJson(new File(dir,"vocab.json").getPath());p.setTokenScoresJson(new File(dir,"token_scores.json").getPath());
        p.setVoiceEmbeddingCacheCapacity(4);
        OfflineTtsModelConfig m=new OfflineTtsModelConfig();m.setPocket(p);m.setNumThreads(2);m.setDebug(false);m.setProvider("cpu");
        OfflineTtsConfig c=new OfflineTtsConfig();c.setModel(m);c.setMaxNumSentences(1);
        model=new OfflineTts(null,c);
    }
    private File defaultReference()throws Exception {
        File f=new File(getNoBackupFilesDir(),"felicity-reference-v121.wav");
        if(!f.isFile()){
            File tmp=new File(f.getPath()+".part");try(InputStream in=getAssets().open("voices/felicity.wav");OutputStream out=new FileOutputStream(tmp)){pipe(in,out);}
            VoiceAudio.reference(tmp);if(!tmp.renameTo(f))throw new IOException("Cannot install Felicity reference.");
        }
        return f;
    }
    private File checkedReference(String path)throws Exception {
        if(path==null||path.isEmpty())return defaultReference();
        File f=new File(path).getCanonicalFile();String root=getFilesDir().getCanonicalPath()+File.separator;
        if(!f.getPath().startsWith(root)||!f.isFile())throw new IOException("The selected voice recording is missing. Reimport it; no other voice was substituted.");
        return f;
    }
    private void generate(Messenger reply,Bundle args,String id,long job){
        PowerManager.WakeLock wake=null;
        try{
            if(epoch.get()!=job)return;
            String text=args.getString("text","").trim();if(text.isEmpty())throw new IOException("No activity text was supplied.");
            send(reply,id,"GENERATING","Generating complete announcement locally.","","");
            wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PatrolLink:voice");wake.acquire(120000);
            ensureModel();File reference=checkedReference(args.getString("reference",""));String fingerprint=VoiceAudio.hash(reference);
            File folder=new File(getCacheDir(),"local-voice");if(!folder.exists())folder.mkdirs();
            File audio=new File(folder,VoiceAudio.hash("pocket-20260126-steps5\n"+fingerprint+"\n"+text)+".wav");
            long start=SystemClock.elapsedRealtime();
            if(!audio.isFile()||audio.length()<1000){
                GenerationConfig c=new GenerationConfig();c.setReferenceAudio(VoiceAudio.reference(reference));c.setReferenceSampleRate(VoiceAudio.RATE);c.setNumSteps(5);
                Map<String,String> extra=new HashMap<>();extra.put("temperature","0.7");extra.put("chunk_size","15");c.setExtra(extra);
                GeneratedAudio a=model.generateWithConfigAndCallback(text,c,samples->epoch.get()==job?1:0);
                if(epoch.get()!=job)return;
                float[] samples=a.getSamples();if(samples==null||samples.length<a.getSampleRate()/3)throw new IOException("The selected voice produced no usable speech.");
                double sum=0;for(float v:samples)sum+=v*v;if(!Double.isFinite(sum)||Math.sqrt(sum/samples.length)<0.002)throw new IOException("The generated voice was silent.");
                File tmp=new File(audio.getPath()+".part");VoiceAudio.write(tmp,samples,a.getSampleRate());if(!tmp.renameTo(audio))throw new IOException("Could not store generated speech.");
            }
            if(epoch.get()!=job)return;
            send(reply,id,"AUDIO_READY","Generated in "+(SystemClock.elapsedRealtime()-start)+" ms.",audio.getPath(),fingerprint);
            if(BuildConfig.DEBUG&&args.getBoolean("renderOnly",false)){
                File export=new File(getCacheDir(),"voice-test.wav");try(InputStream in=new FileInputStream(audio);OutputStream out=new FileOutputStream(export)){pipe(in,out);}
                send(reply,id,"DONE","Offline synthesis test completed.",export.getPath(),fingerprint);
            } else main.post(()->play(reply,id,audio,fingerprint,job));
            trimCache(folder,audio);
        }catch(Throwable e){if(epoch.get()==job)send(reply,id,"ERROR",error(e),"","");}
        finally{if(wake!=null&&wake.isHeld())wake.release();}
    }
    private void play(Messenger reply,String id,File file,String fingerprint,long job){
        if(epoch.get()!=job)return;
        try{
            releasePlayer();playingReply=reply;playingId=id;playingPath=file.getPath();playingHash=fingerprint;playingEpoch=job;
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            AudioManager am=(AudioManager)getSystemService(AUDIO_SERVICE);
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(change->{
                        if(player==null)return;
                        try{if(change==AudioManager.AUDIOFOCUS_LOSS){complete(false,"Audio focus was taken by another app.");}
                        else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)player.pause();
                        else if(change==AudioManager.AUDIOFOCUS_GAIN)player.start();}catch(Exception ignored){}
                    },main).build();
            if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){complete(false,"Audio output is unavailable while another app holds focus.");return;}
            MediaPlayer mp=new MediaPlayer();player=mp;mp.setAudioAttributes(attrs);mp.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);mp.setDataSource(file.getPath());
            mp.setOnPreparedListener(p->{if(player!=p||epoch.get()!=job)return;p.start();send(reply,id,"SPEAKING","Reading the complete activity.",file.getPath(),fingerprint);});
            mp.setOnCompletionListener(p->{if(player==p)complete(true,"Announcement completed.");});
            mp.setOnErrorListener((p,w,e)->{if(player==p)complete(false,"Audio playback failed ("+w+"/"+e+").");return true;});
            mp.prepareAsync();
        }catch(Throwable e){complete(false,error(e));}
    }
    private void complete(boolean ok,String detail){
        Messenger r=playingReply;String id=playingId,path=playingPath,hash=playingHash;long job=playingEpoch;releasePlayer();
        if(job==epoch.get())send(r,id,ok?"DONE":"ERROR",detail,path,hash);
    }
    private void releasePlayer(){
        MediaPlayer p=player;player=null;if(p!=null){try{p.stop();}catch(Exception ignored){}p.release();}
        if(focus!=null){((AudioManager)getSystemService(AUDIO_SERVICE)).abandonAudioFocusRequest(focus);focus=null;}
        playingReply=null;
    }
    private void send(Messenger reply,String id,String state,String detail,String file,String fingerprint){
        if(reply==null)return;Bundle b=new Bundle();b.putString("id",id);b.putString("state",state);b.putString("detail",detail);b.putString("file",file);b.putString("referenceHash",fingerprint);
        Message m=Message.obtain(null,EVENT);m.setData(b);try{reply.send(m);}catch(RemoteException ignored){}
    }
    private static void trimCache(File folder,File keep){
        File[] all=folder.listFiles();if(all==null)return;Arrays.sort(all,Comparator.comparingLong(File::lastModified));long size=0;for(File f:all)size+=f.length();
        for(File f:all){if(size<=96L*1024*1024)break;if(!f.equals(keep)){long n=f.length();if(f.delete())size-=n;}}
    }
    private static String error(Throwable e){String s=e.getMessage();return s==null?"Local speech failed: "+e.getClass().getSimpleName():s;}
    private static byte[] readAll(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();pipe(in,out);return out.toByteArray();}
    private static void pipe(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
    @Override public void onDestroy(){epoch.incrementAndGet();releasePlayer();worker.execute(()->{if(model!=null){model.release();model=null;}});worker.shutdown();super.onDestroy();}
}
