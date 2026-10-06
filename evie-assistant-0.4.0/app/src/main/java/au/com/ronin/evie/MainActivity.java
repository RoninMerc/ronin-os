package au.com.ronin.evie;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.speech.SpeechRecognizer;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {
    private LinearLayout form;
    private EditText name, assistantName, wake, brainBase, apiKey, brainModel, persona, chatTitle, ttsBase, ttsModel, ttsVoice, ttsSpeed;
    private CheckBox autoRead, overlay, speakReplies;
    private Spinner ttsMode;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        load();
        requestCorePermissions();
    }

    private TextView label(String s) {
        TextView v=new TextView(this); v.setText(s); v.setTextSize(14); v.setPadding(0,18,0,4); return v;
    }
    private EditText field(String hint, boolean secret) {
        EditText e=new EditText(this); e.setHint(hint); e.setSingleLine(true);
        if(secret) e.setInputType(0x00000081);
        return e;
    }
    private Button button(String text, View.OnClickListener l) {
        Button b=new Button(this); b.setText(text); b.setOnClickListener(l); return b;
    }
    private void add(String title, View v){ form.addView(label(title)); form.addView(v,new LinearLayout.LayoutParams(-1,-2)); }

    private void buildUi() {
        ScrollView sv=new ScrollView(this);
        form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(32,24,32,40);
        sv.addView(form);
        TextView h=new TextView(this); h.setText("EVIE ASSISTANT 0.4.0"); h.setTextSize(24); h.setGravity(Gravity.CENTER_HORIZONTAL); h.setPadding(0,12,0,10); form.addView(h);
        TextView sub=new TextView(this); sub.setText("Hands-free Android assistant • ChatGPT control • Featherless brain + TTS • overlay • notifications"); sub.setGravity(Gravity.CENTER_HORIZONTAL); form.addView(sub);

        name=field("Tristan",false); add("Your name",name);
        assistantName=field("Evie",false); add("Assistant name",assistantName);
        wake=field("Hey Evie",false); add("Wake phrase",wake);
        chatTitle=field("Optional ChatGPT conversation title",false); add("Preferred ChatGPT conversation",chatTitle);

        brainBase=field("https://api.featherless.ai/v1",false); add("Brain API base URL",brainBase);
        apiKey=field("Featherless API key",true); add("API key",apiKey);
        brainModel=field("dongfangshuo/Dongfangshuo-Qwen3.8-27B",false); add("Brain model",brainModel);
        persona=field("Evie persona / system instruction",false); persona.setSingleLine(false); persona.setMinLines(4); add("Persona",persona);

        ttsMode=new Spinner(this); ttsMode.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Featherless / OpenAI-compatible","Android TTS"})); add("Speech provider",ttsMode);
        ttsBase=field("https://api.featherless.ai/v1/audio/speech",false); add("TTS endpoint",ttsBase);
        ttsModel=field("hexgrad/Kokoro-82M",false); add("TTS model",ttsModel);
        ttsVoice=field("bf_isabella",false); add("TTS voice (bf_isabella, bf_emma, bf_alice, bf_lily)",ttsVoice);
        ttsSpeed=field("1.0",false); add("Voice speed",ttsSpeed);

        speakReplies=new CheckBox(this); speakReplies.setText("Speak Evie’s replies aloud"); form.addView(speakReplies);
        autoRead=new CheckBox(this); autoRead.setText("Automatically read ChatGPT replies"); form.addView(autoRead);
        overlay=new CheckBox(this); overlay.setText("Show floating Evie control"); form.addView(overlay);

        form.addView(button("SAVE EVIE SETTINGS",v->{save(); toast("Saved"); startEvie();}));
        form.addView(button("START / RESTART EVIE",v->{save(); startEvie();}));
        form.addView(button("TEST FEATHERLESS BRAIN",v->{save(); BrainClient.ask(this,"Reply with one short sentence confirming Evie brain is online.", new BrainClient.Callback(){ public void ok(String s){runOnUiThread(()->new AlertDialog.Builder(MainActivity.this).setTitle("Brain test").setMessage(s).setPositiveButton("OK",null).show());} public void fail(String e){runOnUiThread(()->show("Brain test failed",e));}});}));
        form.addView(button("TEST EVIE VOICE",v->{save(); TtsEngine.get(this).speak("Evie voice test. Everything is connected and ready.",null);}));
        form.addView(button("RUN EVIE DIAGNOSTICS",v->diagnostics()));
        form.addView(button("ENABLE ACCESSIBILITY CONTROL",v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        form.addView(button("ENABLE NOTIFICATION ACCESS",v->startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))));
        form.addView(button("ALLOW FLOATING OVERLAY",v->{if(Build.VERSION.SDK_INT>=23) startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName())));}));
        form.addView(button("REMOVE BATTERY OPTIMISATION",v->{try{Intent i=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName())); startActivity(i);}catch(Exception e){startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}}));
        setContentView(sv);
    }

    private void requestCorePermissions(){
        if(Build.VERSION.SDK_INT>=23){
            java.util.ArrayList<String> p=new java.util.ArrayList<>();
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.RECORD_AUDIO);
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS);
            if(!p.isEmpty())requestPermissions(p.toArray(new String[0]),41);
        }
    }

    private void load(){
        SettingsStore s=new SettingsStore(this);
        name.setText(s.userName()); assistantName.setText(s.assistantName()); wake.setText(s.wakePhrase());
        chatTitle.setText(s.chatTitle()); brainBase.setText(s.brainBase()); apiKey.setText(s.apiKey()); brainModel.setText(s.brainModel()); persona.setText(s.persona());
        ttsBase.setText(s.ttsEndpoint()); ttsModel.setText(s.ttsModel()); ttsVoice.setText(s.ttsVoice()); ttsSpeed.setText(String.valueOf(s.ttsSpeed()));
        ttsMode.setSelection(s.useAndroidTts()?1:0); speakReplies.setChecked(s.speakReplies()); autoRead.setChecked(s.autoReadChatGpt()); overlay.setChecked(s.overlay());
    }
    private void save(){
        SettingsStore s=new SettingsStore(this);
        s.put("user_name",name.getText().toString().trim());
        s.put("assistant_name",assistantName.getText().toString().trim());
        s.put("wake_phrase",wake.getText().toString().trim());
        s.put("chat_title",chatTitle.getText().toString().trim());
        s.put("brain_base",brainBase.getText().toString().trim());
        s.put("api_key",apiKey.getText().toString().trim());
        s.put("brain_model",brainModel.getText().toString().trim());
        s.put("persona",persona.getText().toString());
        s.put("tts_endpoint",ttsBase.getText().toString().trim());
        s.put("tts_model",ttsModel.getText().toString().trim());
        s.put("tts_voice",ttsVoice.getText().toString().trim());
        try{s.put("tts_speed",Float.parseFloat(ttsSpeed.getText().toString()));}catch(Exception e){s.put("tts_speed",1f);}
        s.put("android_tts",ttsMode.getSelectedItemPosition()==1);
        s.put("speak_replies",speakReplies.isChecked()); s.put("auto_read_chatgpt",autoRead.isChecked()); s.put("overlay",overlay.isChecked());
    }
    private void startEvie(){
        Intent i=new Intent(this,AssistantService.class).setAction(AssistantService.ACTION_START);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
        toast("Evie starting");
    }
    private void diagnostics(){
        SettingsStore s=new SettingsStore(this);
        boolean mic=Build.VERSION.SDK_INT<23||checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
        boolean ov=Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(this);
        boolean acc=EvieAccessibilityService.isEnabled(this);
        boolean notif=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners")!=null && Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners").contains(getPackageName());
        boolean offline=Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        String msg="Version: 0.4.0\nMicrophone permission: "+mic+"\nAccessibility: "+acc+"\nNotification access: "+notif+"\nOverlay permission: "+ov+"\nOn-device speech recognition: "+offline+"\nBattery optimisation ignored: "+pm.isIgnoringBatteryOptimizations(getPackageName())+"\nBrain model: "+s.brainModel()+"\nTTS model: "+s.ttsModel()+"\nVoice: "+s.ttsVoice();
        show("Evie diagnostics",msg);
    }
    private void show(String t,String m){new AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK",null).show();}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}
