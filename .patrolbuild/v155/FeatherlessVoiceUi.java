package au.com.roningroup.patrollink;

import android.app.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Canonical Featherless model + voice picker. */
public final class FeatherlessVoiceUi {
 private static final int WHITE=0xffeef4f9,MUTED=0xff9daebb,ACCENT=0xff70d7c4,AMBER=0xffffc266;
 private final Activity a; private final FeatherlessVoice voice;
 public FeatherlessVoiceUi(Activity a,FeatherlessVoice voice){this.a=a;this.voice=voice;}
 private int dp(float v){return Math.round(v*a.getResources().getDisplayMetrics().density);}
 private TextView text(String value,int size,int color){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(6));return t;}
 private Spinner spinner(List<String> values){Spinner s=new Spinner(a);ArrayAdapter<String> ad=new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,values);s.setAdapter(ad);return s;}
 private EditText input(String value,String hint,int type){EditText e=new EditText(a);e.setText(value);e.setHint(hint);e.setTextColor(WHITE);e.setHintTextColor(MUTED);e.setSingleLine(true);e.setInputType(type);return e;}

 public void show(){
   final String savedModel=voice.model(),savedVoice=voice.voice();
   LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(18),dp(8),dp(18),dp(8));

   body.addView(text("Voice source",13,MUTED));
   Spinner source=spinner(Arrays.asList("Exact WAV / Tiff (offline)","Featherless AI (cloud)"));
   source.setSelection(voice.selected()?1:0);body.addView(source);

   body.addView(text("Featherless API key",13,MUTED));
   EditText key=input(voice.hasKey()?"••••••••":"","Bearer API key",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
   key.setHint(voice.hasKey()?"Saved securely · leave unchanged to retain":"Paste Featherless API key");
   body.addView(key);

   body.addView(text("Speech model",13,MUTED));
   ArrayList<String> modelIds=new ArrayList<>(Arrays.asList(FeatherlessCatalog.MODEL_IDS));
   ArrayList<String> modelLabels=new ArrayList<>(Arrays.asList(FeatherlessCatalog.MODEL_LABELS));
   if(!modelIds.contains(savedModel)){modelIds.add(savedModel);modelLabels.add("Saved custom model · "+savedModel);}
   Spinner models=spinner(modelLabels);models.setSelection(Math.max(0,modelIds.indexOf(savedModel)));body.addView(models);

   body.addView(text("Voice",13,MUTED));
   Spinner voices=spinner(Collections.singletonList("Loading voices…"));body.addView(voices);

   TextView description=text("",13,WHITE);body.addView(description);
   TextView availability=text("",12,ACCENT);body.addView(availability);

   body.addView(text("Voice speed",13,MUTED));
   EditText speed=input(Float.toString(voice.speed()),"1.0",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);body.addView(speed);

   LinearLayout row=new LinearLayout(a);
   Button refresh=new Button(a);refresh.setText("Refresh voices");refresh.setAllCaps(false);
   Button test=new Button(a);test.setText("Test voice");test.setAllCaps(false);
   row.addView(refresh,new LinearLayout.LayoutParams(0,dp(52),1));row.addView(test,new LinearLayout.LayoutParams(0,dp(52),1));body.addView(row);

   TextView status=text("Four model choices are always available. Voice availability is checked live where Featherless exposes discovery.",12,MUTED);body.addView(status);

   ScrollView scroll=new ScrollView(a);scroll.addView(body);
   AlertDialog d=new AlertDialog.Builder(a).setTitle("Featherless AI · Models & voices").setView(scroll)
      .setPositiveButton("Save",null).setNeutralButton("Clear API key",null).setNegativeButton("Close",null).create();

   ArrayList<String> currentVoices=new ArrayList<>();
   int[] seq={0};

   Runnable populate=()->{
      int mi=models.getSelectedItemPosition();if(mi<0||mi>=modelIds.size())return;
      String model=modelIds.get(mi);int request=++seq[0];
      ArrayList<String> fallback=new ArrayList<>(FeatherlessCatalog.fallback(model));
      if(model.equals(savedModel)&&savedVoice!=null&&!savedVoice.isEmpty()&&!fallback.contains(savedVoice))fallback.add(0,savedVoice);
      currentVoices.clear();currentVoices.addAll(fallback);
      if(currentVoices.isEmpty())currentVoices.add("Refresh to discover voices");
      voices.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,currentVoices));
      int pre=model.equals(savedModel)?currentVoices.indexOf(savedVoice):-1;
      voices.setSelection(Math.max(0,pre));
      description.setText(FeatherlessCatalog.description(model,currentVoices.get(Math.max(0,voices.getSelectedItemPosition()))));
      availability.setText(fallback.isEmpty()?"Checking Featherless live catalogue…":"Preset list loaded · checking Featherless live catalogue…");

      if(!voice.hasKey()){availability.setText("API key is not saved. Preset/fallback list shown; live availability not verified.");return;}
      voice.discoverVoicesFor(model,(ok,raw)->{
        if(a.isFinishing()||!d.isShowing()||seq[0]!=request)return;
        if(!ok){availability.setText("Live discovery unavailable · using saved/preset list. "+raw);return;}
        try{
          List<String> live=FeatherlessCatalog.parseVoices(raw);
          if(live.isEmpty()){availability.setText("Featherless returned no live voices for this model.");return;}
          currentVoices.clear();currentVoices.addAll(live);
          if(model.equals(savedModel)&&savedVoice!=null&&!savedVoice.isEmpty()&&!currentVoices.contains(savedVoice))currentVoices.add(0,savedVoice);
          voices.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,currentVoices));
          int idx=model.equals(savedModel)?currentVoices.indexOf(savedVoice):-1;voices.setSelection(Math.max(0,idx));
          availability.setText(currentVoices.size()+" voices returned live by Featherless.");
          description.setText(FeatherlessCatalog.description(model,currentVoices.get(voices.getSelectedItemPosition())));
        }catch(Exception e){availability.setText("Live catalogue response could not be parsed · using preset list.");}
      });
   };

   models.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      public void onNothingSelected(android.widget.AdapterView<?> p){}
      public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){if(d.isShowing())populate.run();}
   });
   voices.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      public void onNothingSelected(android.widget.AdapterView<?> p){}
      public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
         if(pos>=0&&pos<currentVoices.size())description.setText(FeatherlessCatalog.description(modelIds.get(models.getSelectedItemPosition()),currentVoices.get(pos)));
      }
   });

   d.setOnShowListener(x->{
      refresh.setOnClickListener(v->populate.run());
      d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->apply(models,modelIds,voices,currentVoices,speed,key,source,status,false));
      test.setOnClickListener(v->{
         if(apply(models,modelIds,voices,currentVoices,speed,key,source,status,true)){
            status.setText("Generating sample. Existing patrol announcements remain queued and are not interrupted.");
            voice.test((ok,msg)->{if(d.isShowing())status.setText((ok?"Sample passed · ":"Sample failed · ")+msg);});
         }
      });
      d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{voice.clearKey();key.setText("");status.setText("Featherless API key cleared.");});
      populate.run();
   });
   d.show();
 }

 private boolean apply(Spinner models,List<String> modelIds,Spinner voices,List<String> names,EditText speed,EditText key,Spinner source,TextView status,boolean forTest){
   try{
      int mi=models.getSelectedItemPosition(),vi=voices.getSelectedItemPosition();
      if(mi<0||mi>=modelIds.size())throw new IllegalArgumentException("Choose a model.");
      if(vi<0||vi>=names.size())throw new IllegalArgumentException("Choose a voice.");
      String selectedVoice=names.get(vi);
      if(selectedVoice.startsWith("Refresh to "))throw new IllegalArgumentException("Refresh the voice list first.");
      float sp=Float.parseFloat(speed.getText().toString().trim());
      voice.save(FeatherlessVoice.DEFAULT_ENDPOINT,modelIds.get(mi),selectedVoice,sp,key.getText().toString(),forTest||source.getSelectedItemPosition()==1);
      status.setText("Saved · "+modelIds.get(mi)+" · "+selectedVoice);
      return true;
   }catch(Exception e){status.setText("Unable to save voice: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));return false;}
 }
}