from pathlib import Path
import sys, shutil, hashlib, json
root=Path(sys.argv[1]); here=Path(__file__).parent
java=root/'app/src/main/java/au/com/roningroup/patrollink'
protected=[java/n for n in ['CheckpointTracker.java','HistoryPageAudit.java','HistoryScanGate.java','TimeParser.java','ExactPhraseResolver.java','BundledTiff.java','SpeechRules.java','SpeechPreferences.java','VoiceScriptText.java']]
protected += [root/'app/src/main/assets/extract.js']+list((root/'app/src/main/assets/exact').glob('*'))
before={p:p.read_bytes() for p in protected}
def replace(path,old,new,count=1):
    s=path.read_text();assert s.count(old)>=count,(str(path),old[:100]);path.write_text(s.replace(old,new,count))
replace(root/'app/build.gradle','versionCode 138','versionCode 139')
replace(root/'app/build.gradle',"versionName '1.1.28'","versionName '1.1.29'")
replace(root/'app/build.gradle',"testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner'","testInstrumentationRunner 'au.com.roningroup.patrollink.PatrolTestRunner'")
replace(java/'VoiceManager.java','unique.size()>250','unique.size()>500')
replace(java/'VoiceManager.java','Use up to 250 phrases in one add-on.','Use up to 500 phrases in one add-on.')
replace(java/'ExactPhrasePack.java','addition?250:2000','addition?500:2000')
replace(java/'ExactPhrasePack.java','Use at most 250 phrases per add-on WAV.','Use at most 500 phrases per add-on WAV.')
for n in ['LocalGpsService.java','LocalGpsUi.java']:
    shutil.copyfile(here/n,java/n)
(java/'GpsFixPolicy.java').write_text('''package au.com.roningroup.patrollink;
/** Platform-independent validation for real, recent position samples. */
public final class GpsFixPolicy {
    private GpsFixPolicy(){}
    public static boolean valid(double latitude,double longitude,float accuracy,long fixElapsed,long nowElapsed){
        return Double.isFinite(latitude)&&Double.isFinite(longitude)&&Float.isFinite(accuracy)
            &&latitude>=-90&&latitude<=90&&longitude>=-180&&longitude<=180&&accuracy>=0
            &&fixElapsed>0&&nowElapsed>0&&fixElapsed<=nowElapsed+2000&&nowElapsed-fixElapsed<=120000;
    }
}
''')
(java/'GuardDirectory.java').write_text(r'''package au.com.roningroup.patrollink;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;
/** Only learns creator IDs from rows already accepted by the existing Silvertracker reader. */
public final class GuardDirectory {
    private static final String KEY="observed_guard_ids_v139";
    private GuardDirectory(){}
    public static boolean inactive(String id){return id==null||id.trim().isEmpty()||"N/A".equalsIgnoreCase(id.trim());}
    private static boolean valid(String id){return id!=null&&id.matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,39}")&&!inactive(id);}
    public static void remember(SharedPreferences prefs,JSONArray rows){
        if(rows==null)return;
        TreeSet<String> ids=new TreeSet<>();
        try{JSONArray old=new JSONArray(prefs.getString(KEY,"[]"));for(int i=0;i<old.length();i++){String id=FeedReducer.key(old.optString(i));if(valid(id))ids.add(id);}}catch(Exception ignored){}
        int before=ids.size();
        for(int i=0;i<rows.length()&&ids.size()<5000;i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;String id=FeedReducer.key(row.optString("guard"));if(valid(id))ids.add(id);}
        if(ids.size()!=before)prefs.edit().putString(KEY,new JSONArray(ids).toString()).apply();
    }
    public static List<String> options(SharedPreferences prefs,List<String> selected){
        TreeSet<String> ids=new TreeSet<>();
        if(selected!=null)for(String id:selected){String k=FeedReducer.key(id);if(valid(k))ids.add(k);}
        try{JSONArray saved=new JSONArray(prefs.getString(KEY,"[]"));for(int i=0;i<saved.length();i++){String id=FeedReducer.key(saved.optString(i));if(valid(id))ids.add(id);}}catch(Exception ignored){}
        ArrayList<String> out=new ArrayList<>();out.add("N/A");out.addAll(ids);return out;
    }
    public static void validate(List<String> selected){
        if(selected==null||selected.size()!=3)throw new IllegalArgumentException("Choose three guard slots; unused slots can be N/A.");
        Set<String> unique=new HashSet<>();
        for(String id:selected){if(inactive(id))continue;String key=FeedReducer.key(id);if(!valid(key)||!unique.add(key))throw new IllegalArgumentException("Choose different guard IDs. Any unused slots may be N/A.");}
    }
}
''')
replace(java/'PatrolEngine.java','                List<Observation> observations = new ArrayList<>();','                GuardDirectory.remember(prefs, data.optJSONArray("allRows"));\n                List<Observation> observations = new ArrayList<>();')
# Exclude disabled slots without changing timestamp selection or checkpoint identity.
replace(java/'FeedReducer.java','for (String guard : guards) allowed.add(key(guard));','for (String guard : guards) if (!GuardDirectory.inactive(guard)) allowed.add(key(guard));',2)
replace(java/'PatrolCarService.java','List<Observation> recent=engine.recent();','List<Observation> recent=FeedReducer.selected(engine.recent(),engine.guards());')
replace(java/'PatrolCarService.java','for(String guard:engine.guards()) rows.addItem(new Row.Builder().setTitle(guard).addText("Connecting to Silvertracker…").build());','for(String guard:engine.guards()) rows.addItem(new Row.Builder().setTitle(guard).addText(GuardDirectory.inactive(guard)?"Unused guard slot":"Connecting to Silvertracker…").build());')
main=java/'MainActivity.java'
replace(main,'    private PatrolEngine engine;','    private PatrolEngine engine;\n    private LocalGpsUi gpsUi;\n    private TextView gpsSummary;')
replace(main,'        engine = ((PatrolApp)getApplication()).engine();','        engine = ((PatrolApp)getApplication()).engine();\n        gpsUi=new LocalGpsUi(this);')
replace(main,'            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},72);','            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},72);\n        else timer.postDelayed(()->{if(!isFinishing())gpsUi.offerFirstRun();},350);\n        if(getIntent().getBooleanExtra("open_patrol_gps",false))timer.post(()->gpsUi.show());')
replace(main,'        buttonRow(dashboard,button("Silvertracker",false,this::showBrowser),button("Guard settings",false,this::settings));','''        LinearLayout gps=card();gpsSummary=text(gpsUi.summary(),13,ACCENT,true);gps.addView(gpsSummary);gap(gps,8);
        gps.addView(text("Last GPS position of this phone · separate from Silvertracker activity",12,MUTED,false));gap(gps,8);
        gps.addView(button("Patrol GPS",false,()->gpsUi.show()));addCard(dashboard,gps);
        buttonRow(dashboard,button("Silvertracker",false,this::showBrowser),button("Guard settings",false,this::settings));''')
replace(main,'        long now=System.currentTimeMillis();','        long now=System.currentTimeMillis();\n        if(gpsSummary!=null)gpsSummary.setText(gpsUi.summary());')
replace(main,'            if(o==null) {','''            guardCards[i].setEnabled(!GuardDirectory.inactive(guard));
            if(GuardDirectory.inactive(guard)){
                activities[i].setText("Unused guard slot");activityMeta[i].setText("Select a guard in Guard settings to enable this slot.");flags[i].setText("N/A");flags[i].setTextColor(MUTED);continue;
            }
            if(o==null) {''')
s=main.read_text();start=s.index('    private void settings() {');end=s.index('    private void voiceLibrary()',start)
s=s[:start]+r'''    private void settings() {
        LinearLayout form=vertical();form.setPadding(dp(22),dp(10),dp(22),dp(10));
        Spinner[] fields=new Spinner[3];List<String> options=GuardDirectory.options(engine.prefs,engine.guards());
        for(int i=0;i<3;i++){
            form.addView(text("GUARD "+(i+1)+" · SILVERTRACKER CREATED BY",12,MUTED,true));
            fields[i]=new Spinner(this);
            ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,options);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);fields[i].setAdapter(adapter);
            int selected=options.indexOf(FeedReducer.key(engine.guards().get(i)));fields[i].setSelection(Math.max(0,selected));
            form.addView(fields[i],new LinearLayout.LayoutParams(-1,dp(52)));gap(form,8);
        }
        form.addView(text("IDs are learned from Created By on Silvertracker pages already read, including other guards. Current selections are retained. Read more pages to discover more IDs. N/A disables an unused slot; it does not erase its previous tallies.",12,MUTED,false));gap(form,10);
        EditText zone=input(form,"SITE TIME ZONE",engine.zone().getId(),InputType.TYPE_CLASS_TEXT);
        EditText old=input(form,"FLAG ACTIVITY OLDER THAN (MINUTES)",String.valueOf(engine.oldMinutes()),InputType.TYPE_CLASS_NUMBER);
        CheckBox voice=new CheckBox(this);voice.setText("Speak full guard, activity and location in the selected voice");voice.setTextColor(WHITE);voice.setChecked(engine.prefs.getBoolean("voice",true));form.addView(voice);
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Guard settings").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try{
                List<String> chosen=new ArrayList<>();for(Spinner field:fields)chosen.add(String.valueOf(field.getSelectedItem()));GuardDirectory.validate(chosen);
                String zoneText=zone.getText().toString().trim();ZoneId.of(zoneText);int minutes=Integer.parseInt(old.getText().toString());
                if(minutes<1||minutes>240)throw new IllegalArgumentException("Use an age threshold between 1 and 240 minutes.");
                SharedPreferences.Editor edit=engine.prefs.edit();for(int i=0;i<3;i++)edit.putString("guard"+i,chosen.get(i));
                edit.putString("zone",zoneText).putInt("oldMinutes",minutes).putBoolean("patrolOnly",false).putBoolean("voice",voice.isChecked()).apply();
                engine.latest.clear();engine.present.clear();engine.lastRead=engine.lastReadElapsed=0;
                engine.status("CHECKING","Guard selection updated. Refreshing Silvertracker…");engine.refreshMonitor();dialog.dismiss();refresh();
            }catch(Exception ex){toast(ex.getMessage()==null?"Check the settings.":ex.getMessage());}
        }));dialog.show();
    }

'''+s[end:];main.write_text(s)
replace(main,'There are no Part 1 / Part 2 / Part 3 limits.','Each add-on supports up to 500 phrases. There are no Part 1 / Part 2 / Part 3 limits.')
replace(main,'                    stopService(new Intent(this,MonitorService.class));','                    LocalGpsService.stop(this);\n                    stopService(new Intent(this,MonitorService.class));')
replace(main,'It stops monitoring, clears the app\'s Silvertracker cookies/session and closes the app.','It stops monitoring and local GPS, clears the app\'s Silvertracker cookies/session and closes the app.')
replace(main,'    @Override public void onResume() { super.onResume();','    @Override public void onResume() { super.onResume();if(gpsUi!=null)gpsUi.resume();')
replace(main,'    @Override public void onPause() { engine.appVisible=false;','    @Override public void onPause() { if(gpsUi!=null)gpsUi.pause();engine.appVisible=false;')
replace(main,'    @Override public void onDestroy() { engine.remove(this);','    @Override public void onDestroy() { if(gpsUi!=null)gpsUi.close();engine.remove(this);')
replace(main,'    @Override public void onBackPressed() {','''    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(gpsUi==null)return;
        if(request==72)gpsUi.offerFirstRun();else gpsUi.permissions(request);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("open_patrol_gps",false)&&gpsUi!=null)gpsUi.show();}
    @Override public void onBackPressed() {''')
manifest=root/'app/src/main/AndroidManifest.xml'
replace(manifest,'    <application ','''    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
    <application ''')
replace(manifest,'        <service android:name=".MonitorService"','        <service android:name=".LocalGpsService" android:exported="false" android:stopWithTask="false" android:foregroundServiceType="location" />\n        <service android:name=".MonitorService"')
# Suppress onboarding only for automated instrumented tests, not in the shipped application.
test=root/'app/src/androidTest/java/au/com/roningroup/patrollink'
(test/'PatrolTestRunner.java').write_text('''package au.com.roningroup.patrollink;
public final class PatrolTestRunner extends androidx.test.runner.AndroidJUnitRunner {
    @Override public void onStart(){LocalGpsService.prefs(getTargetContext()).edit().putBoolean("intro_seen",true).putBoolean("background_explained",true).apply();super.onStart();}
}
''')
for p,data in before.items():assert p.read_bytes()==data,'Protected file changed: '+str(p)
Path('/tmp/deliver139').mkdir(parents=True,exist_ok=True)
Path('/tmp/deliver139/UNCHANGED-CORE.json').write_text(json.dumps({str(p.relative_to(root)):hashlib.sha256(data).hexdigest() for p,data in before.items()},indent=2))
assert not (root/'app/src/main/assets/bundled_voice/tiff.zip').exists(),'Personal audio must remain local, never uploaded to CI'
print('Applied v1.1.29. Core checkpoint parser, history logic, speech rules and Tiff installer preserved.')
