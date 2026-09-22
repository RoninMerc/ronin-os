from pathlib import Path
import sys

root=Path(sys.argv[1])
java=root/'app/src/main/java/au/com/roningroup/patrollink'

def rep(path,old,new):
    s=path.read_text()
    if old not in s:
        raise SystemExit(f'anchor missing in {path}: {old[:180]!r}')
    path.write_text(s.replace(old,new,1))

gradle=root/'app/build.gradle'
rep(gradle,'versionCode 117','versionCode 119')
rep(gradle,"versionName '1.1.7'","versionName '1.1.9'")

engine=java/'PatrolEngine.java'
s=engine.read_text()
old='"\\nCar host connected: " + carConnected + "\\nActive voice: " + voices.activeName() + "\\nOffline voice ready: " + voices.ready() +'
new='"\\nCar host connected: " + carConnected + "\\nActive voice: " + voices.activeName() + "\\nVoice API configured: " + voices.apiConfigured() + "\\nSingle-voice ready: " + voices.ready() + "\\nVoice error: " + (voices.lastError().isEmpty() ? "none" : voices.lastError()) +'
if old not in s: raise SystemExit('engine diagnostics voice anchor missing')
engine.write_text(s.replace(old,new,1))

main=java/'MainActivity.java'
s=main.read_text()

old='CheckBox voice=new CheckBox(this);voice.setText("Speak new activity with "+engine.voices.activeName());voice.setTextColor(WHITE);voice.setChecked(engine.prefs.getBoolean("voice",true));form.addView(voice);'
new='CheckBox voice=new CheckBox(this);voice.setText("Speak complete activity with selected voice only");voice.setTextColor(WHITE);voice.setChecked(engine.prefs.getBoolean("voice",true));form.addView(voice);'
if old not in s: raise SystemExit('settings checkbox anchor missing')
s=s.replace(old,new,1)

start=s.find('    private void voiceLibrary() {')
end=s.find('    private void nameImportedVoice(android.net.Uri uri) {',start)
if start<0 or end<0: raise SystemExit('voice library block missing')
voice_library=r'''    private void voiceLibrary() {
        LinearLayout form=vertical(); form.setPadding(dp(20),dp(8),dp(20),dp(8));
        VoiceManager.Profile active=engine.voices.activeProfile();
        form.addView(text("ACTIVE VOICE",11,MUTED,true)); gap(form,5);
        form.addView(text(active.name,24,WHITE,true)); gap(form,5);
        String state=engine.voices.ready()?"READY · ONLY "+active.name.toUpperCase()+" WILL SPEAK":
                engine.voices.apiConfigured()?"VOICE NEEDS PREPARING":"VOICE ENGINE SETUP REQUIRED";
        TextView status=text(state,12,engine.voices.ready()?ACCENT:AMBER,true); form.addView(status); gap(form,12);
        form.addView(text("Patrol Link has no system-TTS fallback. Every word of an announcement—including guard, matter/activity and property/location—is generated in the selected cloned voice. If the selected voice cannot be generated, Patrol Link stays silent rather than using another voice.",12,MUTED,false)); gap(form,14);

        Button setup=button(engine.voices.apiConfigured()?"Change voice engine API key":"Set up voice engine",false,this::voiceApiSetup);
        addCard(form,setup);
        Button prepare=button("Prepare "+active.name,true,()->prepareActiveVoice(active.name));
        addCard(form,prepare);

        form.addView(text("VOICE LIBRARY",11,MUTED,true)); gap(form,6);
        for(VoiceManager.Profile profile:engine.voices.profiles()) {
            boolean selected=profile.id.equals(engine.voices.activeProfile().id);
            String label=(selected?"●  ":"○  ")+profile.name+(profile.builtIn?"  ·  BUILT IN":"")+(profile.prepared()?"  ·  READY":"");
            Button b=button(label,selected,()->{
                engine.voices.setActive(profile.id);
                toast(profile.name+" selected.");
                if(engine.voices.apiConfigured()&&!profile.prepared()) prepareActiveVoice(profile.name);
                else if(profile.prepared()) engine.voices.test();
            });
            addCard(form,b);
        }
        Button test=button("Test selected voice",false,()->{
            if(!engine.voices.ready()) toast("Prepare the selected voice first. Patrol Link will not use a fallback voice.");
            else engine.voices.test();
        }); addCard(form,test);
        Button importButton=button("Import new voice recording",true,()->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT); pick.addCategory(Intent.CATEGORY_OPENABLE); pick.setType("audio/*");
            startActivityForResult(pick,REQ_VOICE_AUDIO);
        }); addCard(form,importButton);
        active=engine.voices.activeProfile();
        if(!active.builtIn) {
            VoiceManager.Profile deleting=active;
            addCard(form,dangerButton("Delete "+deleting.name,()->{
                new AlertDialog.Builder(this).setTitle("Delete "+deleting.name+"?")
                    .setMessage("The local voice profile will be removed. Felicity will become active. No other voice will be used automatically.")
                    .setPositiveButton("Delete",(d,w)->{engine.voices.delete(deleting.id);toast("Voice deleted. Felicity is active.");})
                    .setNegativeButton("Cancel",null).show();
            }));
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("Voice library").setView(scroll).setPositiveButton("Close",null).show();
    }

    private void voiceApiSetup() {
        EditText key=new EditText(this);
        key.setSingleLine(true); key.setHint("ElevenLabs API key");
        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTextColor(WHITE); key.setHintTextColor(MUTED); key.setPadding(dp(18),dp(10),dp(18),dp(10));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Voice engine setup")
                .setMessage("The API key is encrypted with Android Keystore on this device. It is used only to prepare the selected voice and generate its speech. Patrol Link never falls back to another phone voice.")
                .setView(key).setPositiveButton("Save & prepare",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            try {
                engine.voices.saveApiKey(key.getText().toString());
                dialog.dismiss();
                prepareActiveVoice(engine.voices.activeName());
            } catch(Exception ex) { toast(ex.getMessage()==null?"Check the API key.":ex.getMessage()); }
        }));
        dialog.show();
    }

    private void prepareActiveVoice(String name) {
        Toast.makeText(this,"Preparing "+name+"…",Toast.LENGTH_LONG).show();
        engine.voices.prepareActiveVoice((ok,message)->{
            toast(message);
            if(ok) engine.voices.test();
        });
    }

'''
s=s[:start]+voice_library+s[end:]

old='.setMessage("Use a recording generated from the exact training script. Patrol Link will store it locally and use the recorded alert phrases from that script.")'
new='.setMessage("Choose a clean recording of the voice. Patrol Link stores it locally. Once prepared, the selected voice will generate the entire live announcement; there is no system-voice fallback.")'
if old not in s: raise SystemExit('import dialog message anchor missing')
s=s.replace(old,new,1)

old='engine.prefs.edit().putBoolean("voice",true).apply(); dialog.dismiss(); toast(profile.name+" imported and set active."); engine.voices.test();'
new='engine.prefs.edit().putBoolean("voice",true).apply(); dialog.dismiss(); toast(profile.name+" imported and selected."); if(engine.voices.apiConfigured()) prepareActiveVoice(profile.name); else toast("Set up the voice engine, then prepare "+profile.name+".");'
if old not in s: raise SystemExit('import completion anchor missing')
s=s.replace(old,new,1)

old='Voice settings include the built-in Felicity voice and local import of additional training-script recordings. Voice playback is isolated from the 30-second monitor loop, so an audio failure does not stop feed refreshes.'
new='Voice settings use one selected cloned voice for the complete live announcement: update notice, guard, matter/activity and property/location. Patrol Link has no Android system-TTS fallback. If voice generation is unavailable, monitoring continues and the app stays silent rather than speaking with a different voice. Voice playback is isolated from the 30-second monitor loop, so an audio failure does not stop feed refreshes.'
if old not in s: raise SystemExit('help anchor missing')
s=s.replace(old,new,1)

main.write_text(s)

assert "versionCode 119" in gradle.read_text()
assert "no system-TTS fallback" in main.read_text()
assert "voices.apiConfigured()" in engine.read_text()
