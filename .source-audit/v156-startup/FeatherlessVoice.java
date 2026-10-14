package au.com.roningroup.patrollink;

import android.content.*;
import android.media.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Optional Featherless/OpenAI-compatible cloud speech path. Never silently substitutes another voice. */
public final class FeatherlessVoice {
    public interface Callback { void done(boolean ok,String message); }
    public static final String PROVIDER_EXACT="exact", PROVIDER_FEATHERLESS="featherless";
    public static final String DEFAULT_ENDPOINT="https://api.featherless.ai/v1/audio/speech";
    public static final String DEFAULT_MODEL="hexgrad/Kokoro-82M";
    public static final String DEFAULT_VOICE="bf_isabella";
    private final Context context; private final SharedPreferences prefs; private final FeatherlessSecretStore secrets;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(); private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> queue=new ArrayDeque<>(); private MediaPlayer player; private AudioFocusRequest focus; private boolean busy;
    private volatile String status="Cloud voice idle", error=""; private volatile int generated,failed,dropped;
    public FeatherlessVoice(Context context,SharedPreferences prefs){this.context=context.getApplicationContext();this.prefs=prefs;this.secrets=new FeatherlessSecretStore(context,prefs);}
    public boolean selected(){return PROVIDER_FEATHERLESS.equals(prefs.getString("voice_provider_v132",PROVIDER_EXACT));}
    public void setSelected(boolean cloud){prefs.edit().putString("voice_provider_v132",cloud?PROVIDER_FEATHERLESS:PROVIDER_EXACT).apply();stop();}
    public String endpoint(){return prefs.getString("featherless_tts_endpoint",DEFAULT_ENDPOINT);}
    public String model(){return prefs.getString("featherless_tts_model",DEFAULT_MODEL);}
    public String voice(){return prefs.getString("featherless_tts_voice",DEFAULT_VOICE);}
    public float speed(){return Math.max(.5f,Math.min(2f,prefs.getFloat("featherless_tts_speed",1f)));}
    public String status(){return status;} public String error(){return error;} public boolean hasKey(){return secrets.has();}
    public void save(String endpoint,String model,String voice,float speed,String key,boolean useCloud) throws Exception {
        String e=endpoint==null?"":endpoint.trim();String m=model==null?"":model.trim();String v=voice==null?"":voice.trim();
        if(!e.startsWith("https://"))throw new IllegalArgumentException("Use an HTTPS Featherless speech endpoint.");
        if(m.isEmpty())throw new IllegalArgumentException("Enter the exact Featherless speech model ID.");
        if(v.isEmpty())throw new IllegalArgumentException("Enter the exact Featherless voice ID.");
        if(speed<.5f||speed>2f)throw new IllegalArgumentException("Voice speed must be between 0.5 and 2.0.");
        if(key!=null&&!key.trim().isEmpty()&&!"••••••••".equals(key.trim()))secrets.put(key.trim());
        prefs.edit().putString("featherless_tts_endpoint",e).putString("featherless_tts_model",m).putString("featherless_tts_voice",v)
                .putFloat("featherless_tts_speed",speed).putString("voice_provider_v132",useCloud?PROVIDER_FEATHERLESS:PROVIDER_EXACT).apply();
        error="";status=useCloud?"Featherless cloud voice selected":"Exact recorded voice selected";
    }
    public void clearKey(){secrets.clear();status="Featherless API key cleared";}
    public void speak(String text){
        String clean=SpeechRules.clean(text);if(clean.isEmpty())return;
        main.post(()->{
            if(!selected())return;
            if(!hasKey()){error="Featherless API key required";status="Cloud voice not configured";return;}
            // FIFO: never interrupt or supersede an announcement that is generating or playing.
            // Keep a generous bounded backlog only as a runaway-feed safeguard.
            if(queue.size()>=100){queue.removeFirst();dropped++;}
            queue.addLast(clean);
            dispatch();
        });
    }
    public void test(Callback cb){
        main.post(()->{
            if(busy||player!=null||!queue.isEmpty()){
                deliver(cb,false,"Finish the queued patrol announcements before testing the voice.");
                return;
            }
            busy=true;
            synthesize("Silvertracker update. Featherless cloud voice is working.",true,(ok,msg)->{
                busy=false;deliver(cb,ok,msg);dispatch();
            });
        });
    }
    public void discoverVoices(Callback cb){discoverVoicesFor(model(),cb);}
    public void discoverVoicesFor(String modelId,Callback cb){
        String key=secrets.get();if(key.isEmpty()){deliver(cb,false,"Enter and save your Featherless API key first.");return;}
        worker.execute(()->{HttpURLConnection c=null;try{
            String base=endpoint();int i=base.indexOf("/audio/speech");if(i>0)base=base.substring(0,i);
            String u=base+"/audio/voices?model="+URLEncoder.encode(modelId,"UTF-8");c=(HttpURLConnection)new URL(u).openConnection();
            c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Accept","application/json");
            int code=c.getResponseCode();String body=readText(code>=200&&code<300?c.getInputStream():c.getErrorStream());
            if(code<200||code>=300)throw new IOException("HTTP "+code+": "+shorten(body));
            deliver(cb,true,body);
        }catch(Exception e){deliver(cb,false,safe(e));}finally{if(c!=null)c.disconnect();}});
    }
    private void dispatch(){
        if(busy||player!=null)return;
        String next=queue.pollFirst();if(next==null){status=selected()?"Featherless voice ready":"Exact recorded voice selected";return;}
        busy=true;
        synthesize(next,false,(ok,msg)->{
            // This callback fires only after synthesis/playback has fully ended.
            busy=false;
            if(!ok){failed++;error=msg;status="Featherless voice failed";}
            dispatch();
        });
    }
    private void synthesize(String text,boolean test,Callback cb){
        String key=secrets.get();if(key.isEmpty()){deliver(cb,false,"Featherless API key required.");return;}
        status="Generating Featherless speech";error="";
        worker.execute(()->{HttpURLConnection c=null;File out=null;try{
            URL url=new URL(endpoint());if(!"https".equalsIgnoreCase(url.getProtocol()))throw new IOException("Speech endpoint must use HTTPS.");
            c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(15000);c.setReadTimeout(45000);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","audio/wav,audio/x-wav,audio/mpeg,application/octet-stream");
            JSONObject body=new JSONObject();body.put("model",model());body.put("voice",voice());body.put("input",text);body.put("speed",speed());body.put("response_format","wav");
            byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);try(OutputStream os=c.getOutputStream()){os.write(payload);}int code=c.getResponseCode();
            if(code<200||code>=300){String err=readText(c.getErrorStream());throw new IOException("HTTP "+code+": "+shorten(err));}
            out=File.createTempFile("featherless-", ".wav", context.getCacheDir());try(InputStream in=c.getInputStream();OutputStream os=new FileOutputStream(out)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)os.write(b,0,n);}if(out.length()<100)throw new IOException("Featherless returned an empty audio response.");
            File ready=out;main.post(()->play(ready,text,test,cb));out=null;
        }catch(Exception e){String msg=safe(e);File f=out;if(f!=null)f.delete();main.post(()->{error=msg;status="Featherless voice failed";deliver(cb,false,msg);});}finally{if(c!=null)c.disconnect();}});
    }
    private void play(File file,String text,boolean test,Callback cb){
        try{release();MediaPlayer mp=new MediaPlayer();player=mp;mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());mp.setDataSource(file.getAbsolutePath());
            mp.setOnPreparedListener(p->{requestFocus();p.start();status="Playing Featherless · "+voice();});
            mp.setOnCompletionListener(p->{generated++;status="Featherless voice ready";release();file.delete();deliver(cb,true,"Featherless voice test passed · "+model()+" · "+voice());});
            mp.setOnErrorListener((p,w,e)->{failed++;error="Audio playback error "+w+"/"+e;status="Featherless playback failed";release();file.delete();deliver(cb,false,error);return true;});mp.prepareAsync();
        }catch(Exception e){file.delete();error=safe(e);status="Featherless playback failed";release();deliver(cb,false,error);}
    }
    public void stop(){main.post(()->{queue.clear();busy=false;release();status=selected()?"Cloud voice stopped":"Exact recorded voice selected";});}
    private void requestFocus(){try{AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);if(Build.VERSION.SDK_INT>=26){focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build();am.requestAudioFocus(focus);} }catch(Exception ignored){}}
    private void release(){if(player!=null){try{player.stop();}catch(Exception ignored){}try{player.release();}catch(Exception ignored){}player=null;}try{AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);if(Build.VERSION.SDK_INT>=26&&focus!=null)am.abandonAudioFocusRequest(focus);}catch(Exception ignored){}focus=null;}
    public String diagnostics(){return "Cloud voice provider: "+(selected()?"Featherless":"Exact WAV/Tiff")+"\nFeatherless key saved: "+hasKey()+"\nFeatherless endpoint: "+endpoint()+"\nFeatherless model: "+model()+"\nFeatherless voice: "+voice()+"\nFeatherless speed: "+speed()+"x\nCloud generated/failed/dropped: "+generated+"/"+failed+"/"+dropped+"\nCloud status: "+status+"\nCloud error: "+(error.isEmpty()?"none":error);}
    private static String readText(InputStream in)throws IOException{if(in==null)return "";ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1&&o.size()<8192)o.write(b,0,n);return new String(o.toByteArray(),StandardCharsets.UTF_8);}
    private static String shorten(String s){if(s==null)return "";s=s.replaceAll("\\s+"," ").trim();return s.length()>500?s.substring(0,500)+"…":s;}
    private static String safe(Exception e){String s=e.getMessage();return s==null?e.getClass().getSimpleName():s;}
    private void deliver(Callback cb,boolean ok,String m){if(cb!=null)main.post(()->cb.done(ok,m));}
}
