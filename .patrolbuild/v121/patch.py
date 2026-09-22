from pathlib import Path
import re, shutil, sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'; payload=Path(__file__).parent

def replace(s, old, new):
    if old not in s: raise RuntimeError('Missing patch anchor: '+old[:140])
    return s.replace(old,new,1)

p=root/'app/build.gradle';s=p.read_text();s=replace(s,'versionCode 117','versionCode 121');s=replace(s,"versionName '1.1.7'","versionName '1.1.11'")
s=replace(s,'dependencies {',"dependencies {\n    implementation files('libs/sherpa-onnx.aar')\n    implementation 'org.jetbrains.kotlin:kotlin-stdlib:2.2.0'")
s=replace(s,"buildFeatures { buildConfig true }","buildFeatures { buildConfig true }\n    aaptOptions { noCompress 'onnx', 'wav' }\n    packagingOptions { jniLibs { useLegacyPackaging true } }")
p.write_text(s)
for f in ['VoiceManager.java','VoiceReference.java','LocalVoiceService.java','AnnouncementText.java','AnnouncementLedger.java']:
    shutil.copyfile(payload/f,java/f)

p=root/'app/src/main/AndroidManifest.xml';s=p.read_text();s=re.sub(r'<queries>.*?</queries>','',s,flags=re.S)
s=replace(s,'<service android:name=".MonitorService"','<service android:name=".LocalVoiceService" android:exported="false" android:process=":voice" />\n        <service android:name=".MonitorService"')
p.write_text(s)

p=java/'PatrolEngine.java';s=p.read_text()
s=replace(s,'private final LinkedHashMap<String, Observation> recentById = new LinkedHashMap<>();','private final LinkedHashMap<String, Observation> recentById = new LinkedHashMap<>();\n    private final AnnouncementLedger speechLedger = new AnnouncementLedger();\n    private String lastRefreshFailure = "none";')
s=replace(s,'List<Observation> announce = new ArrayList<>();','List<Observation> announce = speechLedger.collect(selected, hadBaseline, now);')
start=s.index('                for (Observation o : selected) {');end=s.index('                trimRecent();',start)
s=s[:start]+'                for (Observation o : selected) recentById.put(o.id, o);\n'+s[end:]
s=replace(s,'if (previous == null || FeedReducer.newer(e.getValue(), previous)) latest.put(e.getKey(), e.getValue());','if (previous == null || previous.id.equals(e.getValue().id) || FeedReducer.newer(e.getValue(), previous)) latest.put(e.getKey(), e.getValue());')
s=replace(s,'latest.clear(); present.clear(); recentById.clear(); lastRead = lastReadElapsed = 0;','latest.clear(); present.clear(); recentById.clear(); speechLedger.clear(); lastRead = lastReadElapsed = 0;')
s=replace(s,'status("REFRESH_TIMEOUT", "No usable monitor rows arrived within 25 seconds. Keeping last known activity and retrying.");','lastRefreshFailure = "Row-readiness timeout (25 seconds)";\n            status("REFRESH_TIMEOUT", "No usable monitor rows arrived within 25 seconds. Keeping last known activity and retrying.");')
s=replace(s,'private void fail(String code, String message) {','private void fail(String code, String message) {\n        lastRefreshFailure = code + ": " + message;')
s=replace(s,'"\\nCar host connected: " + carConnected + "\\nActive voice: " + voices.activeName() + "\\nOffline voice ready: " + voices.ready() +','"\\nLast refresh failure: " + lastRefreshFailure + "\\n" + voices.diagnostics() +')
p.write_text(s)

p=java/'MainActivity.java';s=p.read_text()
s=replace(s,'private TextView stateText, detailText, readText, countdownText;','private TextView stateText, detailText, readText, countdownText, voiceStatusText;')
s=replace(s,'countdownText=text("",12,MUTED,false); status.addView(countdownText); addCard(dashboard,status);','countdownText=text("",12,MUTED,false); status.addView(countdownText); gap(status,8); voiceStatusText=text("",12,MUTED,false); status.addView(voiceStatusText); addCard(dashboard,status);')
s=replace(s,'long now=System.currentTimeMillis();','long now=System.currentTimeMillis();\n        if(voiceStatusText!=null) { voiceStatusText.setText("Voice · "+(engine.prefs.getBoolean("voice",true)?engine.voices.status():"Muted")); voiceStatusText.setTextColor(engine.voices.error().isEmpty()?MUTED:AMBER); }')
s=replace(s,'voice.setText("Speak new activity with "+engine.voices.activeName());','voice.setText("Speak full guard, activity and location in the selected voice");')
start=s.index('    private void voiceLibrary() {');end=s.index('    private void diagnostics() {',start)
s=s[:start]+'''    private void voiceLibrary() {
        LinearLayout form=vertical();form.setPadding(dp(20),dp(12),dp(20),dp(12));
        form.addView(text(engine.voices.activeName(),24,WHITE,true));gap(form,8);
        TextView live=text(engine.voices.status(),12,ACCENT,false);form.addView(live);gap(form,12);
        form.addView(text("Complete announcements are generated on this device from the selected voice recording. No account, voice API or system voice is used. The bundled model may take a little time to load on first use.",13,MUTED,false));gap(form,12);
        CheckBox enabled=new CheckBox(this);enabled.setText("Automatically read new activity, guard and location");enabled.setTextColor(WHITE);enabled.setChecked(engine.prefs.getBoolean("voice",true));
        enabled.setOnCheckedChangeListener((v,on)->{engine.prefs.edit().putBoolean("voice",on).apply();if(!on)engine.voices.stop();});form.addView(enabled);gap(form,12);
        final AlertDialog[] holder=new AlertDialog[1];
        for(VoiceManager.Profile p:engine.voices.profiles()) {
            boolean selected=p.id.equals(engine.voices.activeProfile().id);
            addCard(form,button((selected?"●  ":"○  ")+p.name,selected,()->{engine.voices.setActive(p.id);holder[0].dismiss();voiceLibrary();}));
        }
        addCard(form,button("Test full update",true,engine.voices::test));
        addCard(form,button("Import voice WAV",false,()->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("audio/*");
            try{startActivityForResult(pick,REQ_VOICE_AUDIO);}catch(ActivityNotFoundException e){toast("No file picker is installed.");}
        }));
        VoiceManager.Profile active=engine.voices.activeProfile();
        if(!active.builtIn)addCard(form,dangerButton("Delete "+active.name,()->new AlertDialog.Builder(this).setTitle("Delete "+active.name+"?")
            .setMessage("Remove this local voice and select Felicity instead?").setPositiveButton("Delete",(d,w)->{engine.voices.delete(active.id);holder[0].dismiss();voiceLibrary();}).setNegativeButton("Cancel",null).show()));
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        holder[0]=new AlertDialog.Builder(this).setTitle("Local voice library").setView(scroll).setPositiveButton("Close",null).create();
        Runnable update=new Runnable(){public void run(){live.setText(engine.voices.status()+(engine.voices.error().isEmpty()?"":"\\n"+engine.voices.error()));timer.postDelayed(this,1000);}};
        holder[0].setOnDismissListener(d->timer.removeCallbacks(update));holder[0].show();timer.post(update);
    }
    private void nameImportedVoice(android.net.Uri uri) {
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("Voice name");name.setTextColor(WHITE);name.setHintTextColor(MUTED);name.setPadding(dp(16),dp(10),dp(16),dp(10));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Import a local voice")
            .setMessage("Choose the PCM WAV exported from your voice website. The app prepares a short reference from the speech; it does not guess alert timings or upload the recording anywhere.")
            .setView(name).setPositiveButton("Import",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            if(name.getText().toString().trim().isEmpty()){toast("Enter a name for the voice.");return;}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            engine.voices.importTrainingAudio(uri,name.getText().toString(),(ok,message)->{if(!isFinishing()){toast(message);if(ok){dialog.dismiss();}else dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);}});
        }));dialog.show();
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_VOICE_AUDIO&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null)nameImportedVoice(data.getData());
    }

'''+s[end:]
s=replace(s,'Voice settings include the built-in Felicity voice and local import of additional training-script recordings. Voice playback is isolated from the 30-second monitor loop, so an audio failure does not stop feed refreshes.','A bundled on-device speech model generates the complete guard, activity and property announcement using the selected local voice reference. Connection state is shown visually; the app does not repeatedly play connection-warning clips. Speech generation runs in a separate process from the 30-second monitor. The selected recording guides the voice; synthesis is not guaranteed to be identical to the original voice website.')
p.write_text(s)

# Keep existing refresh/TLS tests focused; the new separate test exercises real local speech.
for p in (root/'app/src/androidTest/java/au/com/roningroup/patrollink').glob('*.java'):
    s=p.read_text();s=re.sub(r'(@Test\s+public void \w+\([^)]*\)(?: throws [^{]+)?\s*\{)',r'\1\n        InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences("patrol_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("voice",false).commit();',s)
    p.write_text(s)
for f in ['VoicePipelineTest.java']:
    shutil.copyfile(payload/f,root/'app/src/androidTest/java/au/com/roningroup/patrollink'/f)
for f in ['AnnouncementTextTest.java','VoiceReferenceTest.java']:
    shutil.copyfile(payload/f,root/'app/src/test/java/au/com/roningroup/patrollink'/f)
for p in java.glob('*.java'):
    if 'android.speech.tts' in p.read_text() or 'api.elevenlabs.io' in p.read_text():raise RuntimeError('Forbidden voice fallback/client in '+str(p))
