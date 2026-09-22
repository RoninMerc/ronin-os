from pathlib import Path
import sys

root = Path(sys.argv[1])
java = root / 'app/src/main/java/au/com/roningroup/patrollink'

def replace_once(path, old, new):
    s = path.read_text()
    if old not in s:
        raise SystemExit(f'patch anchor missing in {path}: {old[:100]!r}')
    path.write_text(s.replace(old, new, 1))

# Version bump only; keep the v1.1.6 monitor/TLS implementation intact.
gradle = root / 'app/build.gradle'
replace_once(gradle, 'versionCode 116', 'versionCode 117')
replace_once(gradle, "versionName '1.1.6'", "versionName '1.1.7'")

engine = java / 'PatrolEngine.java'
s = engine.read_text()
s = s.replace('import android.speech.tts.*;\n', '')
s = s.replace('    public final SharedPreferences prefs;\n', '    public final SharedPreferences prefs;\n    public final VoiceManager voices;\n', 1)
s = s.replace('\n\n    private TextToSpeech tts;\n    private boolean voiceReady;\n', '\n', 1)
s = s.replace(
    '        this.prefs = context.getSharedPreferences("patrol_settings", Context.MODE_PRIVATE);\n',
    '        this.prefs = context.getSharedPreferences("patrol_settings", Context.MODE_PRIVATE);\n        this.voices = new VoiceManager(this.context, this.prefs);\n', 1)
s = s.replace(
    '                if (running && prefs.getBoolean("voice", false)) for (Observation o : announce) speak(o);',
    '                if (running && prefs.getBoolean("voice", true)) for (Observation o : announce) voices.speak(o);', 1)
s = s.replace('        running = true; handler.removeCallbacks(cycle); initVoice();\n', '        running = true; handler.removeCallbacks(cycle);\n', 1)
s = s.replace('        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; voiceReady = false; }\n', '        voices.stop();\n', 1)
s = s.replace(
    '                "\\nCar host connected: " + carConnected + "\\nOffline voice ready: " + voiceReady +\n',
    '                "\\nCar host connected: " + carConnected + "\\nActive voice: " + voices.activeName() + "\\nOffline voice ready: " + voices.ready() +\n', 1)
start = s.find('    private void initVoice() {')
if start < 0:
    raise SystemExit('PatrolEngine initVoice anchor missing')
end = s.rfind('\n}')
engine.write_text(s[:start] + s[end:])

main = java / 'MainActivity.java'
s = main.read_text()
s = s.replace(
    'public final class MainActivity extends Activity implements PatrolEngine.Listener {\n',
    'public final class MainActivity extends Activity implements PatrolEngine.Listener {\n    private static final int REQ_VOICE_AUDIO = 4101;\n', 1)
s = s.replace(
    '        buttonRow(dashboard,button("Silvertracker",false,this::showBrowser),button("Guard settings",false,this::settings));\n        buttonRow(dashboard,button("Diagnostics",false,this::diagnostics),dangerButton("Exit / sign out",this::confirmExit));\n',
    '        buttonRow(dashboard,button("Silvertracker",false,this::showBrowser),button("Guard settings",false,this::settings));\n        buttonRow(dashboard,button("Voice settings",false,this::voiceLibrary),button("Diagnostics",false,this::diagnostics));\n        addCard(dashboard,dangerButton("Exit / sign out",this::confirmExit));\n', 1)
s = s.replace(
    '        CheckBox voice=new CheckBox(this);voice.setText("Speak new activity (installed offline voice)");voice.setTextColor(WHITE);voice.setChecked(engine.prefs.getBoolean("voice",false));form.addView(voice);\n',
    '        CheckBox voice=new CheckBox(this);voice.setText("Speak new activity with "+engine.voices.activeName());voice.setTextColor(WHITE);voice.setChecked(engine.prefs.getBoolean("voice",true));form.addView(voice);\n', 1)

voice_ui = r'''
    private void voiceLibrary() {
        LinearLayout form=vertical(); form.setPadding(dp(20),dp(8),dp(20),dp(8));
        form.addView(text("ACTIVE VOICE",11,MUTED,true)); gap(form,5);
        form.addView(text(engine.voices.activeName(),24,WHITE,true)); gap(form,14);
        form.addView(text("Felicity is built in and is the default. Imported voices must be generated from the exact Patrol Link training script; the recording stays on this device.",12,MUTED,false)); gap(form,14);
        for(VoiceManager.Profile profile:engine.voices.profiles()) {
            boolean active=profile.id.equals(engine.voices.activeProfile().id);
            String label=(active?"●  ":"○  ")+profile.name+(profile.builtIn?"  ·  BUILT IN":"");
            Button b=button(label,active,()->{ engine.voices.setActive(profile.id); engine.voices.test(); toast(profile.name+" set active."); });
            addCard(form,b);
        }
        Button test=button("Test active voice",false,engine.voices::test); addCard(form,test);
        Button importButton=button("Import training audio",true,()->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT); pick.addCategory(Intent.CATEGORY_OPENABLE); pick.setType("audio/*");
            startActivityForResult(pick,REQ_VOICE_AUDIO);
        }); addCard(form,importButton);
        VoiceManager.Profile active=engine.voices.activeProfile();
        if(!active.builtIn) addCard(form,dangerButton("Delete active voice",()->{
            new AlertDialog.Builder(this).setTitle("Delete "+active.name+"?").setMessage("The imported recording will be removed from Patrol Link. Felicity will become active again.")
                    .setPositiveButton("Delete",(d,w)->{engine.voices.delete(active.id);toast("Voice deleted. Felicity is active.");}).setNegativeButton("Cancel",null).show();
        }));
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("Voice library").setView(scroll).setPositiveButton("Close",null).show();
    }

    private void nameImportedVoice(android.net.Uri uri) {
        EditText name=new EditText(this); name.setSingleLine(true); name.setHint("Voice name"); name.setTextColor(WHITE); name.setHintTextColor(MUTED); name.setPadding(dp(18),dp(10),dp(18),dp(10));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Import Patrol Link voice")
                .setMessage("Use a recording generated from the exact training script. Patrol Link will store it locally and use the recorded alert phrases from that script.")
                .setView(name).setPositiveButton("Import",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            try {
                VoiceManager.Profile profile=engine.voices.importTrainingAudio(uri,name.getText().toString());
                engine.prefs.edit().putBoolean("voice",true).apply(); dialog.dismiss(); toast(profile.name+" imported and set active."); engine.voices.test();
            } catch(Exception ex) { toast(ex.getMessage()==null?"Could not import that voice recording.":ex.getMessage()); }
        }));
        dialog.show();
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_VOICE_AUDIO && resultCode==RESULT_OK && data!=null && data.getData()!=null) {
            android.net.Uri uri=data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch(Exception ignored) {}
            nameImportedVoice(uri);
        }
    }

'''
marker = '    private void diagnostics() {'
if marker not in s:
    raise SystemExit('MainActivity diagnostics anchor missing')
s = s.replace(marker, voice_ui + marker, 1)
s = s.replace(
    'The Android Auto screen remains a native, read-only activity display. The data shown is Issue Monitor history, not live GPS.',
    'Voice settings include the built-in Felicity voice and local import of additional training-script recordings. Voice playback is isolated from the 30-second monitor loop, so an audio failure does not stop feed refreshes.\\n\\nThe Android Auto screen remains a native, read-only activity display. The data shown is Issue Monitor history, not live GPS.', 1)
main.write_text(s)

# Defensive checks.
assert 'versionCode 117' in gradle.read_text()
assert 'Voice settings' in main.read_text()
assert 'voices.speak(o)' in engine.read_text()
