from pathlib import Path
import sys,re
root=Path(sys.argv[1]);java=root/'app/src/main/java/au/com/roningroup/patrollink'
gradle=root/'app/build.gradle'
s=gradle.read_text().replace('versionCode 120','versionCode 121').replace("versionName '1.1.10'","versionName '1.1.11'")
s=s.replace("dependencies {","dependencies {\n    implementation files('libs/local-speech.aar')\n    implementation 'org.jetbrains.kotlin:kotlin-stdlib:2.1.20'")
s=s.replace('buildFeatures { buildConfig true }','buildFeatures { buildConfig true }\n    packaging { jniLibs { useLegacyPackaging true } }\n    defaultConfig { ndk { abiFilters "arm64-v8a", "x86_64" } }')
gradle.write_text(s)
manifest=root/'app/src/main/AndroidManifest.xml';s=manifest.read_text()
s=re.sub(r'<queries>.*?</queries>','',s,flags=re.S)
s=s.replace('<service android:name=".MonitorService"','<service android:name=".LocalVoiceService" android:exported="false" android:process=":voice" />\n        <service android:name=".MonitorService"')
manifest.write_text(s)
for n in ['VoiceReadout.java','VoiceAudio.java','LocalVoiceService.java']:(java/n).write_text((Path('.patrolbuild')/n).read_text())
s=(Path('.patrolbuild')/'VoiceManager121.java').read_text().replace('removeCallbacks(watchdog)','removeCallbacks(this.watchdog)')
(java/'VoiceManager.java').write_text(s)
for p in (root/'app/src/main/res/raw').glob('felicity*'):p.unlink()
engine=java/'PatrolEngine.java';s=engine.read_text()
s=s.replace('this.voices = new VoiceManager(this.context, this.prefs);','this.voices = new VoiceManager(this.context, this.prefs);\n        this.voices.setListener(this::notifyChanged);')
s=s.replace('"\\nOffline voice ready: " + voices.ready() +','"\\nLocal speech ready: " + voices.ready() + "\\n" + voices.diagnostics() +')
# The old refreshAttempt is also invalidated on failure; it is not a request counter.
s=s.replace('public long lastRead, lastReadElapsed, nextCheckElapsed;','public long lastRead, lastReadElapsed, nextCheckElapsed;\n    private long actualRequests;\n    private String lastFailureCode="none";')
s=s.replace('private void fail(String code, String message) {','private void fail(String code, String message) {\n        lastFailureCode=code;')
s=s.replace('mainDocumentUrl = url;\n        view.loadUrl(url, headers);','mainDocumentUrl = url;\n        actualRequests++;\n        view.loadUrl(url, headers);')
s=s.replace('"\\nRefresh attempts/completed/failed: " + refreshAttempt','"\\nRequests sent/completed/failed: " + actualRequests')
s=s.replace('"\\nRequest active: " + refreshActive','"\\nLast refresh failure: " + lastFailureCode + "\\nRequest active: " + refreshActive')
engine.write_text(s)
main=java/'MainActivity.java';s=main.read_text()
s=s.replace('private static final int REQ_VOICE_AUDIO = 4101;','private static final int REQ_VOICE_AUDIO = 4101;\n    private TextView voiceStateText;')
anchor='        buttonRow(dashboard,button("Voice settings",false,this::voiceLibrary),button("Diagnostics",false,this::diagnostics));'
assert anchor in s
s=s.replace(anchor,'        voiceStateText=text("Voice loading",12,MUTED,false); dashboard.addView(voiceStateText); gap(dashboard,8);\n'+anchor)
s=s.replace('        long now=System.currentTimeMillis();','        if(voiceStateText!=null)voiceStateText.setText("Voice: "+engine.voices.activeName()+" · "+engine.voices.status());\n        long now=System.currentTimeMillis();',1)
s=s.replace('Speak new activity with ','Read guard, activity and location with ')
s=s.replace('                SharedPreferences.Editor edit=engine.prefs.edit();','                engine.voices.stop();\n                SharedPreferences.Editor edit=engine.prefs.edit();',1)
start=s.index('    private void voiceLibrary() {');end=s.index('    @Override protected void onActivityResult(',start)
s=s[:start]+'''    private void voiceLibrary() {
        LinearLayout form=vertical();form.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView title=text(engine.voices.activeName()+" · "+engine.voices.status(),20,WHITE,true);form.addView(title);gap(form,12);
        form.addView(text("Full announcements are generated on this device from the selected voice recording. No voice account, API or second voice. The first start installs the bundled speech model locally; your patrol feed continues separately.",12,MUTED,false));gap(form,12);
        for(VoiceManager.Profile p:engine.voices.profiles()) {
            addCard(form,button(p.name+(p.id.equals(engine.voices.activeProfile().id)?" · SELECTED":""),false,()->{engine.voices.setActive(p.id);title.setText(p.name+" · "+engine.voices.status());toast(p.name+" selected for all spoken updates.");}));
        }
        addCard(form,button("Test full readout",true,engine.voices::test));
        addCard(form,button("Read current guard cards",false,()->{for(Observation o:engine.latest.values())engine.voices.speak(o);}));
        addCard(form,button("Import voice WAV",false,()->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("audio/*");startActivityForResult(pick,REQ_VOICE_AUDIO);
        }));
        VoiceManager.Profile p=engine.voices.activeProfile();
        if(!p.builtIn)addCard(form,dangerButton("Delete "+p.name,()->new AlertDialog.Builder(this).setTitle("Delete "+p.name+"?")
            .setMessage("This removes its local recordings and selects Felicity. The original file in Downloads is not changed.")
            .setPositiveButton("Delete",(d,w)->{engine.voices.delete(p.id);title.setText(engine.voices.activeName());}).setNegativeButton("Cancel",null).show()));
        addCard(form,button("Stop speech",false,engine.voices::stop));
        ScrollView scroll=new ScrollView(this);scroll.addView(form);new AlertDialog.Builder(this).setTitle("Voice library").setView(scroll).setPositiveButton("Close",null).show();
    }
    private void nameImportedVoice(android.net.Uri uri) {
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("Voice name");name.setTextColor(WHITE);name.setHintTextColor(MUTED);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Import voice WAV")
            .setMessage("Select the WAV generated with your chosen voice. A short speech reference is prepared locally from the recording. It is not uploaded to a voice service.")
            .setView(name).setPositiveButton("Import",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            String chosen=name.getText().toString().trim();if(chosen.isEmpty()){name.setError("Enter a voice name");return;}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);dialog.setCancelable(false);name.setEnabled(false);
            engine.voices.importAudio(uri,chosen,(profile,error)->{
                if(isFinishing()||isDestroyed())return;
                if(profile==null){dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);dialog.setCancelable(true);name.setEnabled(true);toast(error);}
                else{dialog.dismiss();engine.prefs.edit().putBoolean("voice",true).apply();toast(profile.name+" imported and selected.");engine.voices.test();}
            });
        }));dialog.show();
    }

'''+s[end:]
s=s.replace('Voice settings are local-only. Felicity is built in, and additional training-script recordings can be imported as local voice packs. Patrol Link contains no external voice API integration and no Android/system TTS fallback; the selected voice is the only voice the app can play. Voice playback is isolated from the 30-second monitor loop, so an audio failure does not stop feed refreshes.','Every spoken announcement is generated from the selected local voice reference and includes the guard, full Issue Monitor activity and property. Speech runs in a separate process from the feed. Network status stays visual; the app does not play connection-status clips on activity updates.')
main.write_text(s)
assert 'versionCode 121' in gradle.read_text()
assert 'VoiceReadout.of(o)' in (java/'VoiceManager.java').read_text()
assert 'actualRequests++' in engine.read_text()
