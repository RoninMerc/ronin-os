package au.com.ronin.evie;

import android.content.*;
import android.media.MediaPlayer;
import android.os.*;
import android.speech.tts.TextToSpeech;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class TtsEngine {
    public interface Done { void run(); }
    private static TtsEngine INSTANCE;
    public static synchronized TtsEngine get(Context c){if(INSTANCE==null)INSTANCE=new TtsEngine(c.getApplicationContext());return INSTANCE;}

    private final Context c;
    private final ExecutorService net=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextToSpeech androidTts;
    private MediaPlayer mp;
    private final ArrayList<String> chunks=new ArrayList<>();
    private int index=0;
    private String lastText="";
    private boolean paused=false,stopped=true;
    private Done done;

    private TtsEngine(Context c){
        this.c=c;
        androidTts=new TextToSpeech(c,status->{});
    }

    public synchronized void speak(String text,Done d){
        stop();
        if(text==null||text.trim().isEmpty()){if(d!=null)d.run();return;}
        lastText=text.trim();done=d;chunks.clear();chunks.addAll(split(lastText,120));index=0;stopped=false;paused=false;playCurrent();
    }
    public synchronized void repeatLast(){ if(!lastText.isEmpty())speak(lastText,null); }
    public synchronized void pause(){paused=true;if(mp!=null&&mp.isPlaying())mp.pause();else if(androidTts!=null)androidTts.stop();}
    public synchronized void resume(){if(!paused)return;paused=false;if(mp!=null){try{mp.start();return;}catch(Exception ignored){}}playCurrent();}
    public synchronized void skip(){ if(stopped)return; stopPlayerOnly(); index++; playCurrent(); }
    public synchronized void stop(){stopped=true;paused=false;stopPlayerOnly();if(androidTts!=null)androidTts.stop();chunks.clear();index=0;}
    private synchronized void stopPlayerOnly(){if(mp!=null){try{mp.stop();}catch(Exception ignored){}try{mp.release();}catch(Exception ignored){}mp=null;}}

    private synchronized void playCurrent(){
        if(stopped||paused)return;
        if(index>=chunks.size()){stopped=true;Done d=done;done=null;if(d!=null)d.run();return;}
        String chunk=chunks.get(index);
        SettingsStore s=new SettingsStore(c);
        if(s.useAndroidTts()||s.apiKey().trim().isEmpty()){speakAndroid(chunk);return;}
        int thisIndex=index;
        net.execute(()->{
            File f=null;
            try{
                f=requestAudio(s,chunk,thisIndex);
                File ff=f;
                main.post(()->playFile(ff));
            }catch(Exception e){
                if(f!=null)f.delete();
                main.post(()->speakAndroid(chunk));
            }
        });
    }

    private void speakAndroid(String chunk){
        SettingsStore s=new SettingsStore(c);
        Bundle b=new Bundle();b.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,1f);
        androidTts.setSpeechRate(Math.max(0.5f,Math.min(2f,s.ttsSpeed())));
        final String id="evie_"+System.nanoTime();
        androidTts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener(){
            public void onStart(String u){}
            public void onError(String u){advance();}
            public void onDone(String u){advance();}
        });
        androidTts.speak(chunk,TextToSpeech.QUEUE_FLUSH,b,id);
    }
    private synchronized void playFile(File f){
        if(stopped){if(f!=null)f.delete();return;}
        try{
            mp=new MediaPlayer();mp.setDataSource(f.getAbsolutePath());mp.setOnCompletionListener(x->{try{x.release();}catch(Exception ignored){}f.delete();mp=null;advance();});mp.setOnErrorListener((x,w,e)->{try{x.release();}catch(Exception ignored){}f.delete();mp=null;advance();return true;});mp.prepare();mp.start();
        }catch(Exception e){if(f!=null)f.delete();advance();}
    }
    private synchronized void advance(){if(stopped||paused)return;index++;playCurrent();}

    private File requestAudio(SettingsStore s,String text,int i)throws Exception{
        URL u=new URL(s.ttsEndpoint());
        HttpURLConnection h=(HttpURLConnection)u.openConnection();
        h.setConnectTimeout(20000);h.setReadTimeout(90000);h.setRequestMethod("POST");h.setDoOutput(true);
        h.setRequestProperty("Authorization","Bearer "+s.apiKey());h.setRequestProperty("Content-Type","application/json");
        JSONObject j=new JSONObject().put("model",s.ttsModel()).put("voice",s.ttsVoice()).put("input",text).put("response_format","mp3").put("speed",s.ttsSpeed());
        try(OutputStream o=h.getOutputStream()){o.write(j.toString().getBytes(StandardCharsets.UTF_8));}
        int code=h.getResponseCode();
        if(code<200||code>=300)throw new IOException("TTS HTTP "+code);
        File f=new File(c.getCacheDir(),"evie_tts_"+System.nanoTime()+"_"+i+".mp3");
        try(InputStream in=h.getInputStream();OutputStream out=new FileOutputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        return f;
    }
    private static List<String> split(String s,int words){
        ArrayList<String> out=new ArrayList<>();String[] a=s.split("\\s+");StringBuilder b=new StringBuilder();
        for(String w:a){if(b.length()>0)b.append(' ');b.append(w);if(b.toString().split("\\s+").length>=words||w.matches(".*[.!?]$")&&b.length()>350){out.add(b.toString());b.setLength(0);}}
        if(b.length()>0)out.add(b.toString());return out;
    }
}
