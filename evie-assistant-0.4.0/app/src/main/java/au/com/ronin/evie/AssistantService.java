package au.com.ronin.evie;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.speech.*;
import android.view.*;
import android.widget.Button;
import java.util.*;
import java.util.regex.*;

public class AssistantService extends Service {
    public static final String ACTION_START="au.com.ronin.evie.START";
    public static final String ACTION_CHATGPT_REPLY="au.com.ronin.evie.CHATGPT_REPLY";
    public static final String ACTION_SPEAK_REMINDER="au.com.ronin.evie.SPEAK_REMINDER";
    public static final String ACTION_LISTEN_NOW="au.com.ronin.evie.LISTEN_NOW";
    private final Handler h=new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean commandMode=false;
    private boolean listening=false;
    private SettingsStore settings;
    private WindowManager wm;
    private View bubble;

    @Override public void onCreate(){
        super.onCreate();
        settings=new SettingsStore(this);
        ensureChannel();
        Notification n=new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"evie_service":null)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Evie is ready")
            .setContentText("Say “"+settings.wakePhrase()+"”")
            .setOngoing(true).build();
        try{startForeground(9040,n);}catch(Exception ignored){}
        if(settings.overlay())showBubble();
        h.postDelayed(this::startWakeListening,700);
    }

    @Override public int onStartCommand(Intent i,int flags,int id){
        String a=i==null?null:i.getAction();
        if(ACTION_CHATGPT_REPLY.equals(a)){
            String sn=i.getStringExtra("snippet");
            handleChatGptReply(sn);
        }else if(ACTION_SPEAK_REMINDER.equals(a)){
            String t=i.getStringExtra("text");if(t!=null)TtsEngine.get(this).speak("Reminder. "+t,null);
        }else if(ACTION_LISTEN_NOW.equals(a)){
            promptForCommand();
        }else if(ACTION_START.equals(a)){
            if(settings.overlay())showBubble();
            startWakeListening();
        }
        return START_STICKY;
    }

    @Override public android.os.IBinder onBind(Intent i){return null;}

    private void ensureChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            n.createNotificationChannel(new NotificationChannel("evie_service","Evie background assistant",NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void createRecognizer(){
        if(recognizer!=null)return;
        if(Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        else recognizer=SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener(){
            public void onReadyForSpeech(Bundle b){listening=true;}
            public void onBeginningOfSpeech(){}
            public void onRmsChanged(float v){}
            public void onBufferReceived(byte[] b){}
            public void onEndOfSpeech(){}
            public void onError(int e){listening=false;h.postDelayed(()->{if(commandMode)listenCommand();else startWakeListening();}, e==SpeechRecognizer.ERROR_RECOGNIZER_BUSY?1400:700);}
            public void onResults(Bundle b){listening=false;process(b,false);}
            public void onPartialResults(Bundle b){process(b,true);}
            public void onEvent(int e,Bundle b){}
        });
    }

    private Intent recognizerIntent(boolean command){
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5);
        if(command)i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,900);
        return i;
    }

    private void startWakeListening(){
        commandMode=false;
        if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return;
        if(!SpeechRecognizer.isRecognitionAvailable(this))return;
        try{
            createRecognizer();
            if(listening)recognizer.cancel();
            recognizer.startListening(recognizerIntent(false));
            listening=true;
        }catch(Exception e){h.postDelayed(this::startWakeListening,1500);}
    }

    private void listenCommand(){
        commandMode=true;
        try{
            createRecognizer();
            if(listening)recognizer.cancel();
            recognizer.startListening(recognizerIntent(true));
            listening=true;
        }catch(Exception e){h.postDelayed(this::listenCommand,1200);}
    }

    private void process(Bundle b,boolean partial){
        ArrayList<String> r=b==null?null:b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if(r==null||r.isEmpty())return;
        String best=r.get(0).trim();
        if(!commandMode){
            String w=settings.wakePhrase().toLowerCase(Locale.ROOT);
            if(best.toLowerCase(Locale.ROOT).contains(w)){
                try{recognizer.cancel();}catch(Exception ignored){}
                listening=false;promptForCommand();
            }else if(!partial)h.postDelayed(this::startWakeListening,250);
        }else if(!partial && !best.isEmpty()){
            commandMode=false;handleCommand(best);h.postDelayed(this::startWakeListening,800);
        }
    }

    private void promptForCommand(){
        commandMode=true;
        try{if(recognizer!=null)recognizer.cancel();}catch(Exception ignored){}
        listening=false;
        String prompt="How can I help, "+settings.userName()+"?";
        TtsEngine.get(this).speak(prompt,this::listenCommand);
    }

    private void handleCommand(String raw){
        String q=raw.trim(), low=q.toLowerCase(Locale.ROOT);
        if(low.equals("stop")||low.equals("cancel")||low.equals("be quiet")||low.equals("quiet")){
            TtsEngine.get(this).stop();return;
        }
        if(low.equals("pause")){TtsEngine.get(this).pause();return;}
        if(low.equals("continue")||low.equals("resume")){TtsEngine.get(this).resume();return;}
        if(low.equals("repeat")||low.equals("say that again")){TtsEngine.get(this).repeatLast();return;}
        if(low.equals("skip")){TtsEngine.get(this).skip();return;}

        if(low.contains("read clipboard")){
            android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(cm.hasPrimaryClip()&&cm.getPrimaryClip().getItemCount()>0){
                CharSequence t=cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                TtsEngine.get(this).speak(t==null?"Clipboard is empty.":t.toString(),null);
            }else TtsEngine.get(this).speak("Clipboard is empty.",null);
            return;
        }

        if(low.contains("read screen")||low.contains("what's on screen")||low.contains("what is on screen")){
            String t=EvieAccessibilityService.visibleText();
            TtsEngine.get(this).speak(t.isEmpty()?"I cannot read the current screen. Check Evie's accessibility access.":t,null);return;
        }

        if(low.equals("open chatgpt")||low.equals("open chat gpt")){
            launchPackage("com.openai.chatgpt");return;
        }
        if(low.contains("chatgpt microphone")||low.contains("chat gpt microphone")||low.contains("open chatgpt and listen")){
            if(!EvieAccessibilityService.isEnabled(this)){speak("Turn on Evie accessibility control first.");return;}
            EvieAccessibilityService.activateChatGptMic(this,settings.chatTitle());return;
        }

        String askPrefix=null;
        for(String p:new String[]{"ask chatgpt ","ask chat gpt ","send to chatgpt ","send to chat gpt "}){
            if(low.startsWith(p)){askPrefix=p;break;}
        }
        if(askPrefix!=null){
            String msg=q.substring(askPrefix.length()).trim();
            if(msg.isEmpty()){promptForCommand();return;}
            if(!EvieAccessibilityService.isEnabled(this)){speak("Turn on Evie accessibility control first.");return;}
            EvieAccessibilityService.sendToChatGPT(this,settings.chatTitle(),msg);
            speak("Sent to ChatGPT.");return;
        }

        if(low.contains("read latest chatgpt")||low.contains("read latest chat gpt")||low.contains("read the latest reply")){
            readLatestChatGpt(null);return;
        }

        if(low.startsWith("open ")){
            String app=q.substring(5).trim();
            if(openAppByLabel(app))return;
        }

        if(scheduleReminder(q))return;

        BrainClient.ask(this,q,new BrainClient.Callback(){
            public void ok(String s){if(settings.speakReplies())TtsEngine.get(AssistantService.this).speak(s,null);}
            public void fail(String e){speak("I couldn't reach my brain. "+e);}
        });
    }

    private boolean scheduleReminder(String q){
        Pattern p=Pattern.compile("(?i)remind me in\\s+(\\d+)\\s*(minute|minutes|hour|hours)\\s+to\\s+(.+)");
        Matcher m=p.matcher(q);if(!m.find())return false;
        long n=Long.parseLong(m.group(1));boolean hours=m.group(2).toLowerCase().startsWith("hour");
        long when=System.currentTimeMillis()+n*(hours?3600000L:60000L);String text=m.group(3).trim();
        Intent ri=new Intent(this,ReminderReceiver.class).putExtra("text",text);
        PendingIntent pi=PendingIntent.getBroadcast(this,(int)(System.currentTimeMillis()%1000000),ri,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE);
        if(Build.VERSION.SDK_INT>=23)am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);else am.set(AlarmManager.RTC_WAKEUP,when,pi);
        speak("Done. I'll remind you "+(hours?"in "+n+" hour"+(n==1?"":"s"):"in "+n+" minute"+(n==1?"":"s"))+".");return true;
    }

    private void handleChatGptReply(String snippet){
        if(!settings.autoReadChatGpt())return;
        readLatestChatGpt(snippet);
    }

    private void readLatestChatGpt(String fallback){
        launchPackage("com.openai.chatgpt");
        h.postDelayed(()->{
            String t=EvieAccessibilityService.visibleText();
            if(t==null||t.trim().isEmpty())t=fallback;
            if(t==null||t.trim().isEmpty())t="ChatGPT replied, but I couldn't extract the response. Check accessibility access.";
            TtsEngine.get(this).speak(t,null);
        },1300);
    }

    private void speak(String s){if(settings.speakReplies())TtsEngine.get(this).speak(s,null);}

    private void launchPackage(String pkg){
        Intent i=getPackageManager().getLaunchIntentForPackage(pkg);
        if(i!=null){i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);}else speak("That app isn't installed.");
    }

    private boolean openAppByLabel(String query){
        String q=query.toLowerCase(Locale.ROOT);
        try{
            PackageManager pm=getPackageManager();
            List<ApplicationInfo> apps=pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0));
            ApplicationInfo best=null;
            for(ApplicationInfo a:apps){
                String label=pm.getApplicationLabel(a).toString().toLowerCase(Locale.ROOT);
                if(label.equals(q)){best=a;break;}
                if(best==null&&label.contains(q))best=a;
            }
            if(best!=null){Intent i=pm.getLaunchIntentForPackage(best.packageName);if(i!=null){i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);return true;}}
        }catch(Exception ignored){}
        speak("I couldn't find "+query+".");return false;
    }

    private void showBubble(){
        if(Build.VERSION.SDK_INT<23||!Settings.canDrawOverlays(this)||bubble!=null)return;
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        Button b=new Button(this);b.setText("E");b.setTextSize(18);b.setAllCaps(false);
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(150,150,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.END|Gravity.CENTER_VERTICAL;lp.x=10;lp.y=0;
        b.setOnClickListener(v->promptForCommand());
        b.setOnLongClickListener(v->{TtsEngine.get(this).stop();return true;});
        try{wm.addView(b,lp);bubble=b;}catch(Exception ignored){}
    }

    @Override public void onDestroy(){
        try{if(recognizer!=null){recognizer.cancel();recognizer.destroy();}}catch(Exception ignored){}
        if(wm!=null&&bubble!=null)try{wm.removeView(bubble);}catch(Exception ignored){}
        TtsEngine.get(this).stop();super.onDestroy();
    }
}
