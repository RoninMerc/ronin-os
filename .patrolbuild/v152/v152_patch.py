from pathlib import Path
import sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'
def replace(path,old,new):
 s=path.read_text()
 if s.count(old)!=1: raise RuntimeError(f'{path.name}: expected exactly one anchor: {old[:90]} (found {s.count(old)})')
 path.write_text(s.replace(old,new,1))
b=root/'app/build.gradle'
replace(b,'versionCode 151','versionCode 152')
replace(b,"versionName '1.1.41'","versionName '1.1.42'")
p=java/'FeatherlessVoice.java'
replace(p,'public void discoverVoices(Callback cb){',
  'public void discoverVoices(Callback cb){discoverVoicesFor(model(),cb);}\n    public void discoverVoicesFor(String modelId,Callback cb){')
replace(p,'URLEncoder.encode(model(),"UTF-8")','URLEncoder.encode(modelId,"UTF-8")')
replace(p,'body.length()>3500?body.substring(0,3500)+"…":body','body')
replace(p,'c.setRequestProperty("Accept","audio/mpeg,application/octet-stream");',
'c.setRequestProperty("Accept","audio/wav,audio/x-wav,audio/mpeg,application/octet-stream");')
replace(p,'body.put("response_format","mp3");','body.put("response_format","wav");')
replace(p,'File.createTempFile("featherless-", ".mp3", context.getCacheDir())','File.createTempFile("featherless-", ".wav", context.getCacheDir())')
(java/'FeatherlessCatalog.java').write_text(r'''package au.com.roningroup.patrollink;
import java.util.*;
import org.json.*;

/** Preset-voice catalogue. Only a live Featherless response authorises a selection. */
final class FeatherlessCatalog {
 static final String[] MODEL_IDS={
 "hexgrad/Kokoro-82M","ResembleAI/chatterbox-turbo",
 "Qwen/Qwen3-TTS-12Hz-1.7B-Base","canopylabs/orpheus-3b-0.1-ft"};
 static final String[] MODEL_LABELS={
 "Kokoro 82M · 54 presets, multiple accents",
 "Chatterbox Turbo · 20 English presets",
 "Qwen3-TTS Base · 9 multilingual presets",
 "Orpheus 3B · expressive English presets"};
 static String description(String model,String id){
   if(id==null||id.trim().isEmpty())return "Select a voice to listen to a sample.";
   if(MODEL_IDS[0].equals(model)) {
     String accent="Language/accent not identified";
     if(id.startsWith("af_")||id.startsWith("am_"))accent="American English";
     else if(id.startsWith("bf_")||id.startsWith("bm_"))accent="British English";
     else if(id.startsWith("jf_")||id.startsWith("jm_"))accent="Japanese";
     else if(id.startsWith("zf_")||id.startsWith("zm_"))accent="Mandarin Chinese";
     else if(id.startsWith("ef_")||id.startsWith("em_"))accent="Spanish";
     else if(id.startsWith("ff_"))accent="French";
     else if(id.startsWith("hf_")||id.startsWith("hm_"))accent="Hindi";
     else if(id.startsWith("if_")||id.startsWith("im_"))accent="Italian";
     else if(id.startsWith("pf_")||id.startsWith("pm_"))accent="Brazilian Portuguese";
     String gender=id.length()>1?(id.charAt(1)=='f'?"female":id.charAt(1)=='m'?"male":""):"";
     return accent+(gender.isEmpty()?"":" · "+gender)+" preset. Individual timbre is best assessed using Test voice.";
   }
   if(MODEL_IDS[1].equals(model))return "English preset. Chatterbox Turbo is designed for natural, conversational delivery. Test this speaker to judge timbre.";
   if(MODEL_IDS[2].equals(model)){
     if(id.equals("Aiden")||id.equals("Ryan"))return "English preset from Qwen3-TTS. Test pronunciation and speaking character.";
     if(id.equals("Ono_anna"))return "Japanese preset from Qwen3-TTS.";
     if(id.equals("Sohee"))return "Korean preset from Qwen3-TTS.";
     return "Chinese or regional Chinese preset from Qwen3-TTS. English quality may vary.";
   }
   if(MODEL_IDS[3].equals(model))return "Expressive English voice. Orpheus supports special vocal events; ordinary patrol alerts remain plain text.";
   return "Current saved voice. Test before operational use.";
 }
 static List<String> parseVoices(String raw)throws JSONException{
   JSONObject j=new JSONObject(raw);
   JSONArray a=j.optJSONArray("voices");
   if(a==null)throw new JSONException("Featherless did not return a voices array");
   List<String> result=new ArrayList<>();
   for(int i=0;i<a.length();i++){
      Object v=a.opt(i);String id=v instanceof JSONObject?((JSONObject)v).optString("id"):a.optString(i);
      if(id!=null&&!id.trim().isEmpty()&&!result.contains(id))result.add(id);
   }
   return result;
 }
 static int indexOf(String value,String[] list){for(int i=0;i<list.length;i++)if(list[i].equals(value))return i;return -1;}
}
''')
(java/'FeatherlessVoiceUi.java').write_text(r'''package au.com.roningroup.patrollink;

import android.app.*;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Featherless models and verified voice presets, selected without copying IDs from a website. */
public final class FeatherlessVoiceUi {
 private static final int WHITE=0xffeef4f9,MUTED=0xff9daebb,ACCENT=0xff70d7c4;
 private final Activity a; private final FeatherlessVoice voice;
 public FeatherlessVoiceUi(Activity a,FeatherlessVoice voice){this.a=a;this.voice=voice;}
 private int dp(float v){return Math.round(v*a.getResources().getDisplayMetrics().density);}
 private TextView text(String value,int size,int color){
   TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(6));return t;
 }
 private Spinner spinner(String[] values){
   Spinner s=new Spinner(a);
   ArrayAdapter<String> adapter=new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,values);
   s.setAdapter(adapter);return s;
 }
 private EditText input(String value,String hint,int type){
   EditText e=new EditText(a);e.setText(value);e.setHint(hint);e.setTextColor(WHITE);e.setHintTextColor(MUTED);
   e.setSingleLine(true);e.setInputType(type);return e;
 }
 public void show(){
   final String savedModel=voice.model(),savedVoice=voice.voice();
   LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(18),dp(8),dp(18),dp(8));
   body.addView(text("Voice source",13,MUTED));
   Spinner source=spinner(new String[]{"Exact WAV / Tiff (offline)","Featherless AI (cloud)"});
   source.setSelection(voice.selected()?1:0);body.addView(source);
   body.addView(text("Existing Featherless API key",13,MUTED));
   EditText key=input(voice.hasKey()?"••••••••","","" .isEmpty()?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT);
   key.setHint("Paste a key only if one is not already saved");body.addView(key);
   body.addView(text("Speech model",13,MUTED));
   final ArrayList<String> modelIds=new ArrayList<>(Arrays.asList(FeatherlessCatalog.MODEL_IDS));
   final ArrayList<String> modelLabels=new ArrayList<>(Arrays.asList(FeatherlessCatalog.MODEL_LABELS));
   if(!modelIds.contains(savedModel)){modelIds.add(savedModel);modelLabels.add("Saved custom model · "+savedModel);}
   Spinner models=spinner(modelLabels.toArray(new String[0]));models.setSelection(modelIds.indexOf(savedModel));body.addView(models);
   body.addView(text("Voice preset (from Featherless)",13,MUTED));
   Spinner voices=spinner(new String[]{"Load voices…"});body.addView(voices);
   TextView description=text("Select a model to discover its voices.",13,WHITE);body.addView(description);
   TextView hint=text("Voice IDs and availability are refreshed from Featherless. The description indicates accent/language, not a guaranteed personality. Select Test voice to hear the actual sound.",12,MUTED);body.addView(hint);
   body.addView(text("Voice speed",13,MUTED));
   EditText speed=input(Float.toString(voice.speed()),"1.0",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);body.addView(speed);
   LinearLayout row=new LinearLayout(a);
   Button reload=new Button(a);reload.setAllCaps(false);reload.setText("Refresh voices");
   Button test=new Button(a);test.setAllCaps(false);test.setText("Test voice");
   row.addView(reload,new LinearLayout.LayoutParams(0,dp(52),1));row.addView(test,new LinearLayout.LayoutParams(0,dp(52),1));body.addView(row);
   TextView status=text("Voice catalogue loading requires the saved Featherless API key.",12,ACCENT);body.addView(status);
   ScrollView scroll=new ScrollView(a);scroll.addView(body);
   AlertDialog d=new AlertDialog.Builder(a).setTitle("Patrol Link · Voice picker")
     .setView(scroll).setNegativeButton("Close",null).setNeutralButton("Clear key",null).setPositiveButton("Save",null).create();
   final ArrayList<String> liveNames=new ArrayList<>();
   final int[] requestSeq={0};
   final Runnable populate=()->{
      int idx=models.getSelectedItemPosition();if(idx<0||idx>=modelIds.size())return;
      final String model=modelIds.get(idx);final int seq=++requestSeq[0];
      liveNames.clear();voices.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,new String[]{"Loading verified voices…"}));
      status.setText("Contacting Featherless for "+model+"…");
      voice.discoverVoicesFor(model,(ok,raw)->{
         if(a.isFinishing()||!d.isShowing()||requestSeq[0]!=seq)return;
         if(!ok){status.setText("Voice discovery unavailable: "+raw);
            if(model.equals(savedModel)){liveNames.add(savedVoice);
               voices.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,liveNames));
               description.setText("Previously saved voice. Availability not verified; refresh when connected.");}
            return;}
         try{
           List<String> available=FeatherlessCatalog.parseVoices(raw);
           if(available.isEmpty()){status.setText("No published voices for this model.");return;}
           liveNames.clear();liveNames.addAll(available);
           voices.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,liveNames));
           int pre=model.equals(savedModel)?liveNames.indexOf(savedVoice):-1;
           voices.setSelection(Math.max(0,pre));
           status.setText(liveNames.size()+" currently available voices. Tap a name to see its description.");
           description.setText(FeatherlessCatalog.description(model,liveNames.get(voices.getSelectedItemPosition())));
         }catch(Exception error){status.setText("Invalid voice-catalogue response: "+error.getMessage());}
      });
   };
   models.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      public void onNothingSelected(android.widget.AdapterView<?> v){}
      public void onItemSelected(android.widget.AdapterView<?> p,View view,int position,long id){if(d.isShowing())populate.run();}
   });
   voices.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      public void onNothingSelected(android.widget.AdapterView<?> v){}
      public void onItemSelected(android.widget.AdapterView<?> p,View view,int position,long id){
        if(position>=0&&position<liveNames.size())description.setText(FeatherlessCatalog.description(modelIds.get(models.getSelectedItemPosition()),liveNames.get(position)));
      }
   });
   d.setOnShowListener(x->{
     reload.setOnClickListener(v->populate.run());
     d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->apply(models,modelIds,voices,liveNames,speed,key,source,status,false));
     test.setOnClickListener(v->{if(apply(models,modelIds,voices,liveNames,speed,key,source,status,true)){
       status.setText("Generating voice sample. Normal announcements are not interrupted.");
       voice.test((ok,msg)->{if(d.isShowing())status.setText((ok?"Sample played: ":"Sample failed: ")+msg);});
     }});
     d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{voice.clearKey();key.setText("");status.setText("Featherless key cleared.");});
     populate.run();
   });d.show();
 }
 private boolean apply(Spinner models,List<String> modelIds,Spinner voices,List<String> names,EditText speed,EditText key,Spinner source,TextView status,boolean test){
   try{
      int mid=models.getSelectedItemPosition(),vid=voices.getSelectedItemPosition();
      if(mid<0||mid>=modelIds.size()||vid<0||vid>=names.size())throw new IllegalArgumentException("Refresh and choose an available voice first.");
      String model=modelIds.get(mid),name=names.get(vid);
      voice.save(FeatherlessVoice.DEFAULT_ENDPOINT,model,name,Float.parseFloat(speed.getText().toString().trim()),key.getText().toString(),test||source.getSelectedItemPosition()==1);
      status.setText("Saved "+name+" · "+model+". This voice is used for the next announcement.");
      return true;
   }catch(Exception e){status.setText("Unable to use selected voice: "+e.getMessage());return false;}
 }
}
''')
print("Applied Patrol Link 1.1.42 voice dropdown, live per-model discovery, sample and WAV response path")
