package au.com.roningroup.patrollink;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Reviewable phrase-recording workflow. No label is inferred from recording length. */
public final class RecordingSettingsUi {
    public static final int PICK_SOURCE=7304;
    private final Activity activity;private final PatrolEngine engine;private final VoiceManager voice;
    private final Handler main=new Handler(Looper.getMainLooper());private final ExecutorService io=Executors.newSingleThreadExecutor();
    private AlertDialog dialog,editor;private List<String> phrases=new ArrayList<>(),script=new ArrayList<>();private List<double[]> ranges=Collections.emptyList();
    private ListView list;private TextView status;private String importedVoice="";private File source;private boolean closed;
    private static final int WHITE=0xffeef4f9,MUTED=0xff9daebb,ACCENT=0xff70d7c4,BG=0xff080d12;
    public static RecordingSettingsUi show(Activity a,PatrolEngine e){RecordingSettingsUi ui=new RecordingSettingsUi(a,e);ui.open();return ui;}
    private RecordingSettingsUi(Activity a,PatrolEngine e){activity=a;engine=e;voice=e.voices;source=voice.recordings.source(voice.activeProfile().id);loadScript();}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(1);l.setPadding(dp(16),dp(8),dp(16),dp(8));l.setBackgroundColor(BG);return l;}
    private TextView label(String t,int size,int color){TextView v=new TextView(activity);v.setText(t);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(5),0,dp(5));return v;}
    private Button button(String t,Runnable r){Button b=new Button(activity);b.setAllCaps(false);b.setText(t);b.setTextColor(WHITE);b.setOnClickListener(v->r.run());return b;}
    private void toast(String s){Toast.makeText(activity,s,Toast.LENGTH_LONG).show();}
    private boolean alive(){return !closed&&!activity.isFinishing()&&!activity.isDestroyed();}
    private void open(){
        LinearLayout root=column();status=label("",14,ACCENT);root.addView(status);
        root.addView(label("1. Save your final wording. 2. Copy the recording script to your voice website. 3. Import that website's WAV. 4. Listen to each suggested section and assign it to its phrase. Only assigned recordings are spoken. Missing phrases stay visible; no replacement voice or generic alert is played.",13,MUTED));
        TextView speed=label("Speed: "+String.format(Locale.ROOT,"%.2fx",voice.speed())+" (1.00x = original recording)",13,WHITE);root.addView(speed);
        SeekBar rate=new SeekBar(activity);rate.setMax(15);rate.setProgress(Math.round((voice.speed()-.75f)/.05f));root.addView(rate);
        rate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int p,boolean user){if(user){voice.speed(.75f+p*.05f);speed.setText("Speed: "+String.format(Locale.ROOT,"%.2fx",voice.speed())+" (1.00x = original recording)");}}});
        root.addView(button("Copy recording script",this::copyScript));
        root.addView(button("Import source WAV for "+voice.activeName(),()->{
            importedVoice=voice.activeProfile().id;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/*");
            try{activity.startActivityForResult(i,PICK_SOURCE);}catch(ActivityNotFoundException e){toast("No file picker is available.");}
        }));
        root.addView(button("Review imported script sections",()->{
            if(source==null){toast("Import the WAV generated from your script first.");return;}if(script.isEmpty()){toast("Copy a recording script first so the order is known.");return;}analyse(()->edit(script.get(0),0));
        }));
        list=new ListView(activity);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));list.setOnItemClickListener((p,v,pos,id)->edit(phrases.get(pos),-1));
        root.addView(button("Refresh phrases",this::render));
        dialog=new AlertDialog.Builder(activity).setTitle("Exact recorded announcements").setView(root).setPositiveButton("Close",null).create();
        dialog.setOnDismissListener(d->{closed=true;safeDismiss(editor);editor=null;voice.stop();io.shutdown();});dialog.show();dialog.getWindow().setLayout(-1,(int)(activity.getResources().getDisplayMetrics().heightPixels*.9));render();
    }
    private void render(){if(!alive())return;voice.remember(engine.recent());phrases=voice.recordings.script(voice.wording,engine.recent(),engine.guards());int count=voice.recordings.recordedCount(voice.activeProfile().id,phrases);
        status.setText(voice.activeName()+": "+count+" / "+phrases.size()+" phrases recorded. Tap a phrase to attach or review its audio.");
        list.setAdapter(new BaseAdapter(){public int getCount(){return phrases.size();}public Object getItem(int p){return phrases.get(p);}public long getItemId(int p){return p;}public View getView(int p,View v,ViewGroup parent){String text=phrases.get(p);boolean recorded=voice.recordings.recording(voice.activeProfile().id,text)!=null;return label((recorded?"RECORDED\n":"NEEDS RECORDING\n")+text,15,recorded?ACCENT:WHITE);}});
    }
    private SharedPreferences prefs(){return activity.getSharedPreferences("patrol_recording_script_v123",Context.MODE_PRIVATE);}
    private void loadScript(){try{JSONArray a=new JSONArray(prefs().getString("script_"+voice.activeProfile().id,"[]"));for(int i=0;i<a.length();i++)script.add(a.getString(i));}catch(Exception ignored){}}
    private void copyScript(){render();script=new ArrayList<>(phrases);if(script.isEmpty()){toast("Let the feed populate the alert list, or add wording first.");return;}
        prefs().edit().putString("script_"+voice.activeProfile().id,new JSONArray(script).toString()).apply();
        String text=String.join("\n\n",script);
        new AlertDialog.Builder(activity).setTitle("Record "+script.size()+" phrases in "+voice.activeName())
            .setMessage("The copied text contains only the actual final announcements, one per paragraph. Use the same voice for all paragraphs, with a clear pause between them. Do not add headings, numbering, music or spoken instructions. The app will ask you to listen and confirm the sections; it will not guess which words were recorded. New or edited wording needs a matching new recording.")
            .setPositiveButton("Copy script",(d,w)->{((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Patrol Link recording script",text));toast("Recording script copied. This exact order is saved on this phone.");}).setNegativeButton("Cancel",null).show();
    }
    public void onResult(int resultCode,Intent data){if(resultCode!=Activity.RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();String selected=importedVoice;
        status.setText("Importing original WAV without changing its voice…");io.execute(()->{try{File f=voice.recordings.importSource(uri);voice.recordings.setSource(selected,f);main.post(()->{if(!alive())return;if(!selected.equals(voice.activeProfile().id)){toast("Source saved for the previously selected voice.");return;}source=f;status.setText("WAV imported unchanged. Review and assign phrases before live playback.");analyse(()->{if(!script.isEmpty())edit(script.get(0),0);});});}catch(Exception e){main.post(()->{if(alive()){status.setText("Import failed");toast(e.getMessage());}});}});
    }
    private void analyse(Runnable after){File f=source;if(f==null){after.run();return;}status.setText("Finding pauses locally — no automatic wording assignment…");io.execute(()->{try{List<double[]> found=ExactWav.open(f).suggestRanges(.65);main.post(()->{if(alive()){ranges=found;status.setText(found.size()+" sections suggested from silence. Every assignment needs listening confirmation.");after.run();}});}catch(Exception e){main.post(()->{if(alive())toast(e.getMessage());});}});}
    private EditText timeField(LinearLayout root,String title,String value){root.addView(label(title,12,ACCENT));EditText e=new EditText(activity);e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);e.setTextColor(WHITE);e.setText(value);root.addView(e);return e;}
    private void edit(String phrase,int index){
        safeDismiss(editor);final String selected=voice.activeProfile().id;File recording=voice.recordings.recording(selected,phrase);
        LinearLayout form=column();form.addView(label("ONLY THESE WORDS",12,ACCENT));form.addView(label(phrase,18,WHITE));
        form.addView(label("No guard name, 'recorded', location, introduction or other wording will be added to an enabled replacement. Attach audio that already says this complete phrase.",13,MUTED));
        if(recording!=null)form.addView(button("Play assigned original recording",()->voice.preview(phrase)));
        if(source==null){form.addView(label("Import a source WAV above, then select this phrase again.",14,WHITE));editor=new AlertDialog.Builder(activity).setTitle("Phrase recording").setView(form).setPositiveButton("Close",null).create();editor.show();return;}
        boolean matched=index>=0&&ranges.size()==script.size();double[] suggested=matched?ranges.get(index):null;
        String start=suggested==null?"":String.format(Locale.ROOT,"%.3f",suggested[0]),end=suggested==null?"":String.format(Locale.ROOT,"%.3f",suggested[1]);
        form.addView(label(matched?"Suggested section based on pauses. Listen before saving; silence detection does not identify words.":"No reliable one-to-one section map. Enter the start and end in seconds for this phrase in the source WAV. Nothing will be assigned automatically.",13,MUTED));
        EditText from=timeField(form,"START (SECONDS)",start),to=timeField(form,"END (SECONDS)",end);
        CheckBox confirmed=new CheckBox(activity);confirmed.setText("I have listened: this section says exactly the phrase above, in the selected voice.");confirmed.setTextColor(WHITE);form.addView(confirmed);
        TextWatcher reset=new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int n){}public void onTextChanged(CharSequence s,int a,int before,int count){confirmed.setChecked(false);}public void afterTextChanged(Editable e){}};from.addTextChangedListener(reset);to.addTextChangedListener(reset);
        form.addView(button("Hear selected section",()->{
            try{double a=Double.parseDouble(from.getText().toString()),b=Double.parseDouble(to.getText().toString());File f=source;io.execute(()->{try{File preview=new File(voice.recordings.directory(),"preview-"+UUID.randomUUID()+".wav");ExactWav.open(f).cut(preview,a,b);main.post(()->{if(alive()&&selected.equals(voice.activeProfile().id))voice.previewRecording(preview,phrase);});}catch(Exception e){main.post(()->{if(alive())toast(e.getMessage());});}});}catch(Exception e){toast("Enter valid start and end times.");}
        }));form.addView(button("Stop speech",voice::stop));
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        AlertDialog d=new AlertDialog.Builder(activity).setTitle(index>=0?"Recording "+(index+1)+" / "+script.size():"Assign original recording").setView(scroll).setPositiveButton(index>=0?"Save & next":"Save",null).setNegativeButton("Cancel",null).create();editor=d;
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            if(!confirmed.isChecked()){toast("Listen to the section and confirm its words first.");return;}
            if(!selected.equals(voice.activeProfile().id)){toast("The selected voice changed. Reopen this phrase.");return;}
            try{double a=Double.parseDouble(from.getText().toString()),b=Double.parseDouble(to.getText().toString());File f=source;d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                io.execute(()->{try{voice.recordings.save(selected,phrase,f,a,b);main.post(()->{if(!alive())return;d.dismiss();render();if(index>=0&&index+1<script.size())edit(script.get(index+1),index+1);else toast("Recording assigned. Only this exact selected-voice audio will be played.");});}catch(Exception e){main.post(()->{if(alive()){d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);toast(e.getMessage());}});}});
            }catch(Exception e){toast("Enter valid start and end times.");}
        }));d.show();d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private static void safeDismiss(AlertDialog d){if(d!=null)try{if(d.isShowing())d.dismiss();}catch(IllegalArgumentException ignored){}}
    public void close(){closed=true;safeDismiss(editor);safeDismiss(dialog);voice.stop();io.shutdown();}
}
