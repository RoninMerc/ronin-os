package au.com.roningroup.patrollink;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Speech settings overlay: it never detaches the live monitor browser. */
public final class SpeechSettingsUi implements PatrolEngine.Listener {
    private static final int BG=0xff080d12,PANEL=0xff121d27,LINE=0xff263746,WHITE=0xffeef4f9,MUTED=0xff9daebb,ACCENT=0xff70d7c4;
    private final Activity activity; private final PatrolEngine engine; private final SpeechPreferences prefs;
    private final List<Item> items=new ArrayList<>(); private AlertDialog dialog,editor;
    private EditText search; private TextView note; private Button add,alerts,guards; private ListView list; private int page,revision=-1;
    private static final class Item {
        final SpeechRules.Rule rule; final SpeechPreferences.Seen seen; final String guard;
        Item(SpeechRules.Rule r,SpeechPreferences.Seen s,String g){rule=r;seen=s;guard=g;}
        String original(){return rule!=null?rule.original:seen!=null?seen.issue:guard;}
    }
    public static SpeechSettingsUi show(Activity a,PatrolEngine e){SpeechSettingsUi ui=new SpeechSettingsUi(a,e);ui.open();return ui;}
    private SpeechSettingsUi(Activity a,PatrolEngine e){activity=a;engine=e;prefs=e.voices.wording;}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String s,int size,int color){TextView t=new TextView(activity);t.setText(s);t.setTextColor(color);t.setTextSize(size);t.setPadding(0,dp(4),0,dp(4));return t;}
    private GradientDrawable bg(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));d.setStroke(dp(1),LINE);return d;}
    private Button button(String title,Runnable click){Button b=new Button(activity);b.setAllCaps(false);b.setText(title);b.setTextSize(14);b.setTextColor(WHITE);b.setMinHeight(dp(48));b.setBackground(bg(PANEL));b.setOnClickListener(v->click.run());return b;}
    private void row(LinearLayout parent,Button a,Button b){LinearLayout r=new LinearLayout(activity);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(3),dp(4),dp(3),dp(4));r.addView(a,lp);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,-2,1);rp.setMargins(dp(3),dp(4),dp(3),dp(4));r.addView(b,rp);parent.addView(r);}
    private EditText field(LinearLayout l,String label,String value,boolean multi){l.addView(text(label,12,ACCENT));EditText e=new EditText(activity);e.setTextColor(WHITE);e.setHintTextColor(MUTED);e.setTextSize(16);e.setText(value);e.setSingleLine(!multi);if(multi){e.setMinLines(2);e.setMaxLines(5);e.setGravity(Gravity.TOP);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);}l.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
    private CheckBox check(LinearLayout l,String title,boolean selected){CheckBox c=new CheckBox(activity);c.setText(title);c.setTextSize(14);c.setTextColor(WHITE);c.setChecked(selected);l.addView(c);return c;}
    private void toast(String s){Toast.makeText(activity,s,Toast.LENGTH_LONG).show();}
    private void open(){
        prefs.remember(engine.recent());
        LinearLayout root=column();root.setBackgroundColor(BG);root.setPadding(dp(14),dp(8),dp(14),dp(8));
        alerts=button("Alert wording",()->{page=0;search.setText("");render();});guards=button("Guard nicknames",()->{page=1;search.setText("");render();});row(root,alerts,guards);
        note=text("",13,MUTED);root.addView(note);
        search=new EditText(activity);search.setSingleLine(true);search.setHint("Search original or spoken wording");search.setTextColor(WHITE);search.setHintTextColor(MUTED);search.setTextSize(15);root.addView(search);
        list=new ListView(activity);list.setDividerHeight(dp(8));root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((p,v,position,id)->{Item item=items.get(position);if(page==0)editAlert(item);else editNickname(item.guard);});
        add=button("Add alert wording",()->{if(page==0)editAlert(new Item(null,null,null));else editNickname(null);});
        row(root,add,button("Refresh list",()->{prefs.remember(engine.recent());render();}));
        root.setFocusableInTouchMode(true);root.requestFocus();
        dialog=new AlertDialog.Builder(activity).setTitle("Spoken wording").setView(root).setPositiveButton("Close",null).create();
        dialog.setOnDismissListener(d->{engine.remove(this);if(editor!=null)editor.dismiss();});
        dialog.show();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.getWindow().setLayout(-1,(int)(activity.getResources().getDisplayMetrics().heightPixels*.87));
        search.addTextChangedListener(watcher(this::render));engine.add(this);render();
    }
    public void close(){if(editor!=null)editor.dismiss();if(dialog!=null)dialog.dismiss();engine.remove(this);}
    @Override public void changed(){if(dialog!=null&&dialog.isShowing()&&revision!=prefs.revision())render();}
    private void render(){
        if(list==null)return;revision=prefs.revision();items.clear();
        String query=SpeechRules.key(search.getText().toString());
        alerts.setTextColor(page==0?ACCENT:WHITE);guards.setTextColor(page==1?ACCENT:WHITE);search.setVisibility(page==0?View.VISIBLE:View.GONE);
        add.setText(page==0?"Add alert wording":"Add guard nickname");
        if(page==0){
            Set<String> listed=new HashSet<>();
            for(SpeechRules.Rule r:prefs.rules()){
                SpeechPreferences.Seen sample=null;for(SpeechPreferences.Seen s:prefs.seen())if(SpeechRules.key(s.issue).equals(SpeechRules.key(r.original))){sample=s;break;}
                if(query.isEmpty()||SpeechRules.key(r.original+" "+r.spoken).contains(query))items.add(new Item(r,sample,null));listed.add(SpeechRules.key(r.original));
            }
            for(SpeechPreferences.Seen s:prefs.seen())if(!listed.contains(SpeechRules.key(s.issue))&&(query.isEmpty()||SpeechRules.key(s.issue+" "+prefs.issue(s.issue)).contains(query)))items.add(new Item(null,s,null));
            items.sort(Comparator.comparing(Item::original,String.CASE_INSENSITIVE_ORDER));
            note.setText("Tap an alert to change only how it is spoken. New wording is learned from received entries; this is not Silvertracker’s complete catalogue. "+prefs.rules().size()+" saved rules · "+prefs.seen().size()+" learned alerts."+(prefs.seen().size()>=2000?" The most recent 2,000 wordings are kept; saved rules are never pruned.":"")+(items.isEmpty()?" No matching alerts yet. Add wording manually or let the feed populate this list.":""));
        }else{
            LinkedHashSet<String> names=new LinkedHashSet<>();for(String g:engine.guards())names.add(FeedReducer.key(g));names.addAll(prefs.nicknames().keySet());
            for(String g:names)items.add(new Item(null,null,g));
            note.setText("The nickname is spoken instead of the username. Matching still uses the original Silvertracker ID. Leave a nickname blank to return to the username.");
        }
        if(!prefs.warning().isEmpty())note.append("\n"+prefs.warning());
        list.setAdapter(new BaseAdapter(){
            public int getCount(){return items.size();}public Object getItem(int p){return items.get(p);}public long getItemId(int p){return p;}
            public View getView(int p,View recycled,ViewGroup parent){
                Item item=items.get(p);LinearLayout c=column();c.setPadding(dp(12),dp(8),dp(12),dp(8));c.setBackground(bg(PANEL));
                TextView from=text(item.original(),16,WHITE);from.setTypeface(null,Typeface.BOLD);from.setMaxLines(4);from.setEllipsize(TextUtils.TruncateAt.END);c.addView(from);
                String say=page==0?prefs.issue(item.original()):prefs.nickname(item.guard);if(say.isEmpty())say=VoiceReadout.spokenGuard(item.guard);
                TextView to=text("Says: "+say,14,ACCENT);to.setMaxLines(4);to.setEllipsize(TextUtils.TruncateAt.END);c.addView(to);
                if(page==0)c.addView(text(item.rule==null?"Received alert · tap to edit":(item.rule.enabled?"Enabled":"Disabled")+(item.rule.phrase?" · phrase replacement":" · whole-alert replacement"),12,MUTED));
                return c;
            }
        });
    }
    private Observation example(Item item,String original){
        if(item.seen!=null)return item.seen.observation();
        return new Observation("preview",engine.guards().get(0),"",original,"",0);
    }
    private void editAlert(Item item){
        final String id=item.rule==null?null:item.rule.id;
        String original=item.rule!=null?item.rule.original:item.seen!=null?item.seen.issue:"";
        LinearLayout form=column();form.setPadding(dp(18),dp(8),dp(18),dp(8));form.setBackgroundColor(BG);
        form.addView(text("Your changes affect speech only. Keep the intended warning level and meaning; the original feed is not edited.",13,MUTED));
        EditText from=field(form,"ORIGINAL SILVERTRACKER WORDING",original,true);
        EditText say=field(form,"SAY THIS INSTEAD",item.rule!=null?item.rule.spoken:original,true);
        CheckBox phrase=check(form,"Replace this phrase inside longer alerts",item.rule!=null&&item.rule.phrase);
        CheckBox enabled=check(form,"Use this replacement",item.rule==null||item.rule.enabled);
        form.addView(text("Whole-alert matching ignores case and extra spaces. Phrase matching preserves the remaining words; the longest overlapping phrase wins. The property/location is still included.",12,MUTED));
        TextView preview=text("",15,WHITE);preview.setTextIsSelectable(true);form.addView(text("PREVIEW — EXAMPLE ONLY",12,ACCENT));form.addView(preview);
        Runnable update=()->{
            String f=from.getText().toString(),s=say.getText().toString();
            preview.setText(prefs.preview(id,f,s,phrase.isChecked(),enabled.isChecked(),example(item,f)));
        };
        from.addTextChangedListener(watcher(update));say.addTextChangedListener(watcher(update));phrase.setOnCheckedChangeListener((b,v)->update.run());enabled.setOnCheckedChangeListener((b,v)->update.run());update.run();
        row(form,button("Hear preview",()->{update.run();engine.voices.preview(preview.getText().toString());toast("Preview queued in "+engine.voices.activeName()+".");}),button("Stop speech",engine.voices::stop));
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        AlertDialog d=new AlertDialog.Builder(activity).setTitle("Edit alert wording").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).setNeutralButton("Restore original",null).create();editor=d;
        d.setOnShowListener(v->{
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{try{prefs.saveRule(id,from.getText().toString(),say.getText().toString(),phrase.isChecked(),enabled.isChecked());d.dismiss();render();toast("Saved for following announcements. Silvertracker is unchanged.");}catch(IllegalArgumentException e){say.setError(e.getMessage());}});
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(id!=null);
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x->new AlertDialog.Builder(activity).setTitle("Restore original wording?").setMessage("Remove this speech replacement? The received alert remains in the list.").setPositiveButton("Restore",(a,b)->{prefs.removeRule(id);d.dismiss();render();}).setNegativeButton("Cancel",null).show());
        });d.show();d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private void editNickname(String selected){
        LinearLayout form=column();form.setPadding(dp(18),dp(8),dp(18),dp(8));form.setBackgroundColor(BG);
        EditText id=field(form,"EXACT SILVERTRACKER USERNAME",selected==null?"":selected,false);if(selected!=null)id.setEnabled(false);
        EditText name=field(form,"SPOKEN NICKNAME",prefs.nickname(selected),false);
        form.addView(text("Only the spoken name changes. Clear the nickname and save to speak the username again.",13,MUTED));
        TextView preview=text("",15,WHITE);form.addView(preview);
        Runnable update=()->{
            Map<String,String> names=prefs.nicknames();String key=FeedReducer.key(id.getText().toString());names.put(key,name.getText().toString());
            Observation o=engine.latest.get(key);if(o==null)o=new Observation("preview",key,"","Test activity","",0);
            preview.setText(new SpeechRules(prefs.rules()).readout(o,names));
        };
        id.addTextChangedListener(watcher(update));name.addTextChangedListener(watcher(update));update.run();
        row(form,button("Hear preview",()->{update.run();engine.voices.preview(preview.getText().toString());}),button("Stop speech",engine.voices::stop));
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        AlertDialog d=new AlertDialog.Builder(activity).setTitle("Guard nickname").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();editor=d;
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{try{prefs.nickname(id.getText().toString(),name.getText().toString());d.dismiss();render();toast("Spoken nickname saved; guard matching is unchanged.");}catch(IllegalArgumentException e){name.setError(e.getMessage());}}));
        d.show();d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private static TextWatcher watcher(Runnable r){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){r.run();}public void afterTextChanged(Editable e){}};}
}
