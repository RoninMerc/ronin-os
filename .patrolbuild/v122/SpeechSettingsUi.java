package au.com.roningroup.patrollink;

import android.app.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Speech controls are independent of the raw dashboard and the monitor's network state. */
public final class SpeechSettingsUi {
    private final Activity activity;private final PatrolEngine engine;private final SpeechPreferences settings;
    private AlertDialog currentDialog;
    private static final int WHITE=0xffeff5fa,MUTED=0xffa7b7c3,ACCENT=0xff80d5c5;
    public SpeechSettingsUi(Activity activity,PatrolEngine engine){this.activity=activity;this.engine=engine;this.settings=engine.voices.speechSettings;}
    AlertDialog currentDialog(){return currentDialog;}
    private int dp(int n){return (int)(n*activity.getResources().getDisplayMetrics().density+.5f);}
    private LinearLayout form(){LinearLayout f=new LinearLayout(activity);f.setOrientation(LinearLayout.VERTICAL);f.setPadding(dp(20),dp(12),dp(20),dp(12));return f;}
    private TextView text(String s,int size,int color){TextView v=new TextView(activity);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(6),0,dp(6));return v;}
    private EditText field(String tag,String value,String hint,boolean single){EditText e=new EditText(activity);e.setTag(tag);e.setTextColor(WHITE);e.setHintTextColor(MUTED);e.setTextSize(16);e.setSingleLine(single);e.setText(value);e.setHint(hint);if(!single){e.setMinLines(2);e.setGravity(Gravity.TOP);}return e;}
    private Button button(String title,String tag,Runnable action){Button b=new Button(activity);b.setAllCaps(false);b.setText(title);b.setTextColor(WHITE);b.setTextSize(14);b.setMinHeight(dp(50));b.setTag(tag);b.setOnClickListener(v->action.run());return b;}
    private void dismiss(){if(currentDialog!=null){currentDialog.dismiss();currentDialog=null;}}
    private AlertDialog dialog(String title,LinearLayout f,String positive,android.content.DialogInterface.OnClickListener save,String negative,android.content.DialogInterface.OnClickListener back){
        dismiss();ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.addView(f);
        AlertDialog.Builder b=new AlertDialog.Builder(activity).setTitle(title).setView(scroll);
        if(positive!=null)b.setPositiveButton(positive,save);if(negative!=null)b.setNegativeButton(negative,back);
        currentDialog=b.create();currentDialog.show();return currentDialog;
    }
    private void error(Exception e){Toast.makeText(activity,e.getMessage()==null?"Check the speech setting.":e.getMessage(),Toast.LENGTH_LONG).show();}
    private void saved(){engine.voices.stop();Toast.makeText(activity,"Speech settings saved. New updates use the new wording.",Toast.LENGTH_SHORT).show();}
    private Observation sample(){for(Observation row:engine.latest.values())return row;return new Observation("preview","T.MURD","Impeccable BC","General Patrol - Impeccable BC","",System.currentTimeMillis());}
    public void show(){
        settings.remember(engine.recent());LinearLayout f=form();
        f.addView(text("EDIT WHAT FELICITY SAYS",12,ACCENT));
        f.addView(text("These settings change spoken announcements only. Silvertracker records, usernames used for matching, and the dashboard stay unchanged. They also work with any other selected local voice.",13,MUTED));
        f.addView(button("Alert wording  ·  "+settings.entries(SpeechPreferences.ALERT).size()+" seen/saved","speech_alerts",()->showCatalog(SpeechPreferences.ALERT)));
        f.addView(button("Place names","speech_places",()->showCatalog(SpeechPreferences.PLACE)));
        f.addView(button("Words and abbreviations","speech_phrases",()->showCatalog(SpeechPreferences.PHRASE)));
        f.addView(button("Guard nicknames","speech_nicknames",this::showNicknames));
        f.addView(text("Opening phrase (optional)",13,MUTED));EditText prefix=field("speech_prefix",settings.prefix(),"For example: Silvertracker update",true);f.addView(prefix);
        f.addView(text("Speech speed",15,WHITE));TextView rate=text("",18,ACCENT);rate.setTag("speech_rate_label");f.addView(rate);
        SeekBar speed=new SeekBar(activity);speed.setTag("speech_rate_slider");speed.setMax(15);speed.setProgress((settings.speedPercent()-75)/5);f.addView(speed);
        Runnable rateLabel=()->rate.setText(String.format(Locale.ROOT,"%.2f×  ·  %s",(75+speed.getProgress()*5)/100f,speed.getProgress()<5?"Slower":speed.getProgress()>5?"Faster":"Normal"));rateLabel.run();
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int p,boolean fromUser){rateLabel.run();}});
        f.addView(text("0.75× slower  —  1.00× normal  —  1.50× faster. The selected voice and pitch stay the same. Save applies the speed to subsequent speech, including cached announcements.",12,MUTED));
        f.addView(button("Reset speed to normal","speech_reset_speed",()->speed.setProgress(5)));
        f.addView(button("Preview wording and speed","speech_test_delivery",()->{
            String spoken=SpeechRules.format(sample(),prefix.getText().toString(),settings.nicknames(),settings.overrides(SpeechPreferences.ALERT),settings.overrides(SpeechPreferences.PLACE),settings.overrides(SpeechPreferences.PHRASE));
            engine.voices.preview("Voice preview. "+spoken,(75+speed.getProgress()*5)/100f);
        }));
        AlertDialog d=dialog("Speech controls",f,"Save",null,"Close",null);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setTag("speech_save_delivery");
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{settings.saveDelivery(prefix.getText().toString(),75+speed.getProgress()*5);saved();d.dismiss();}catch(Exception ex){error(ex);}});
    }
    private String title(String type){return type.equals(SpeechPreferences.ALERT)?"Alert wording":type.equals(SpeechPreferences.PLACE)?"Place names":"Words and abbreviations";}
    private String compact(String value){return value.length()<=180?value:value.substring(0,177)+"…";}
    public void showCatalog(String type){
        settings.remember(engine.recent());LinearLayout f=form();
        String note=type.equals(SpeechPreferences.ALERT)?"Tap an alert to change how its complete title is spoken. This list contains actual alert text seen for the selected guards, plus wording you add. Newly seen alert titles are added automatically; unseen Silvertracker titles are not yet in this list.":type.equals(SpeechPreferences.PLACE)?"Rename spoken locations without changing the displayed property. A saved place name is also replaced when that exact name appears inside an alert.":"Use literal whole words or phrases, for example BC → body corporate. Matching ignores letter case. Longer matches win; replacements do not repeatedly replace themselves.";
        f.addView(text(note,13,MUTED));
        EditText search=field("speech_search","","Search original or spoken wording",true);f.addView(search);
        f.addView(button("Add wording","speech_add_rule",()->showEditor(type,null)));
        f.addView(button("Refresh list from recent activity","speech_reload_list",()->showCatalog(type)));
        TextView count=text("",12,ACCENT);f.addView(count);LinearLayout list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);f.addView(list);int[] limit={40};
        Runnable[] render=new Runnable[1];render[0]=()->{
            list.removeAllViews();String q=SpeechRules.key(search.getText().toString());ArrayList<SpeechPreferences.Entry> found=new ArrayList<>();
            for(SpeechPreferences.Entry e:settings.entries(type))if(SpeechRules.key(e.original+" "+e.replacement).contains(q))found.add(e);
            count.setText(found.size()+" matching entries"+(found.size()>limit[0]?" · first "+limit[0]+" shown":""));
            if(found.isEmpty())list.addView(text("No entries yet. Let the next monitor read complete, or add the exact Silvertracker wording manually.",14,MUTED));
            for(int i=0;i<Math.min(limit[0],found.size());i++){
                SpeechPreferences.Entry e=found.get(i);String label="Original: "+compact(e.original)+"\nRead as: "+compact(e.edited()?e.replacement:e.original);
                Button b=button(label,"entry:"+type+":"+SpeechRules.key(e.original),()->showEditor(type,e));b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);list.addView(b);
            }
            if(found.size()>limit[0])list.addView(button("Show 40 more","speech_more_rules",()->{limit[0]+=40;render[0].run();}));
        };
        search.addTextChangedListener(watcher(()->{limit[0]=40;render[0].run();}));render[0].run();
        dialog(title(type),f,null,null,"Back",(d,w)->show());
    }
    public void showEditor(String type,SpeechPreferences.Entry entry){
        LinearLayout f=form();f.addView(text("Original Silvertracker text",13,MUTED));
        EditText original=field("speech_original",entry==null?"":entry.original,"Text to match",false);original.setEnabled(entry==null);f.addView(original);
        f.addView(text("Read aloud as",13,ACCENT));EditText spoken=field("speech_replacement",entry==null?"":entry.replacement,"Blank = original wording",false);f.addView(spoken);
        f.addView(text(type.equals(SpeechPreferences.ALERT)?"Applies to this complete alert title, ignoring letter case and repeated spaces. Other alerts remain unchanged.":"Saved locally for speech only. Blank removes this override.",12,MUTED));
        TextView preview=text("",15,WHITE);preview.setTag("speech_preview_text");f.addView(preview);
        Runnable update=()->{
            String source=original.getText().toString();Observation row=editorSample(type,source,entry);
            preview.setText("Will say:\n"+settings.preview(row,type,source,spoken.getText().toString()));
        };
        original.addTextChangedListener(watcher(update));spoken.addTextChangedListener(watcher(update));update.run();
        f.addView(button("Hear preview in "+engine.voices.activeName(),"speech_test_rule",()->engine.voices.preview("Voice preview. "+settings.preview(editorSample(type,original.getText().toString(),entry),type,original.getText().toString(),spoken.getText().toString()),settings.speed())));
        f.addView(button("Use original wording","speech_reset_rule",()->spoken.setText("")));
        AlertDialog d=dialog("Edit "+title(type).toLowerCase(Locale.ROOT),f,"Save",null,"Cancel",(x,w)->showCatalog(type));
        d.getButton(AlertDialog.BUTTON_POSITIVE).setTag("speech_save_rule");
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{settings.save(type,original.getText().toString(),spoken.getText().toString());saved();showCatalog(type);}catch(Exception ex){error(ex);}});
    }
    private Observation editorSample(String type,String original,SpeechPreferences.Entry e){
        Observation fallback=sample();String guard=e!=null&&!e.guard.isEmpty()?e.guard:fallback.guard;
        String issue=type.equals(SpeechPreferences.ALERT)?original:e!=null&&!e.issue.isEmpty()?e.issue:"General patrol";
        String place=type.equals(SpeechPreferences.PLACE)||type.equals(SpeechPreferences.PHRASE)?original:e!=null&&!e.place.isEmpty()?e.place:fallback.property;
        return new Observation("preview",guard,place,issue,"",System.currentTimeMillis());
    }
    public void showNicknames(){
        LinearLayout f=form();f.addView(text("A nickname replaces only the spoken guard name. Leave it blank to use that guard's Silvertracker username. Nicknames belong to an ID, not a position in the shift list.",13,MUTED));
        LinkedHashSet<String> ids=new LinkedHashSet<>();for(String id:engine.guards())ids.add(SpeechRules.guardKey(id));ids.addAll(settings.nicknames().keySet());
        LinkedHashMap<String,EditText> fields=new LinkedHashMap<>();
        for(String id:ids){f.addView(text("Silvertracker username: "+id,13,ACCENT));EditText name=field("nickname:"+id,settings.nickname(id),"Blank = username",true);fields.put(id,name);f.addView(name);}
        AlertDialog d=dialog("Guard nicknames",f,"Save",null,"Cancel",(x,w)->show());
        d.getButton(AlertDialog.BUTTON_POSITIVE).setTag("speech_save_nicknames");
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{Map<String,String> names=new LinkedHashMap<>();for(Map.Entry<String,EditText> e:fields.entrySet())names.put(e.getKey(),e.getValue().getText().toString());settings.saveNicknames(names);saved();show();}catch(Exception ex){error(ex);}});
    }
    private static TextWatcher watcher(Runnable action){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){action.run();}public void afterTextChanged(Editable e){}};}
}
