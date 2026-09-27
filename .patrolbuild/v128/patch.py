from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]);java=root/'app/src/main/java/au/com/roningroup/patrollink';payload=Path(__file__).parent

def rep(s,old,new):
    if old not in s: raise RuntimeError('v128 patch anchor missing: '+old[:180])
    return s.replace(old,new,1)

p=root/'app/build.gradle';s=p.read_text();s=rep(s,'versionCode 127','versionCode 128');s=rep(s,"versionName '1.1.17'","versionName '1.1.18'");p.write_text(s)

# Restore one-WAV-per-profile architecture. The manifest is fixed and identical on every phone.
shutil.copyfile(payload/'VoiceManager.java',java/'VoiceManager.java')
for name in ['ProfilePhrasePack.java','MultipartPhrasePack.java']:
    old=java/name
    if old.exists(): old.unlink()

# The combined Evelyn recording is larger than either old half. Keep the import streamed,
# but raise only the file-size ceiling so a full single WAV is not rejected.
p=java/'ExactPhrasePack.java';s=p.read_text()
s=rep(s,'private static final int MAX_WAV_BYTES = 180 * 1024 * 1024;','private static final int MAX_WAV_BYTES = 512 * 1024 * 1024;')
s=s.replace('The phrase-pack WAV is larger than 180 MB.','The phrase-pack WAV is larger than 512 MB.')
p.write_text(s)

# Voice UI: normal multi-profile library, one canonical script and one WAV for each profile.
p=java/'MainActivity.java';s=p.read_text()
s=s.replace('    private int pendingVoicePart;','')
start=s.index('    private void voiceLibrary() {')
end=s.index('    private void diagnostics() {',start)
voice_ui=r'''    private void voiceLibrary() {
        LinearLayout form=vertical();form.setPadding(dp(20),dp(10),dp(20),dp(10));
        VoiceManager.Profile active=engine.voices.activeProfile();
        java.util.List<VoiceManager.Profile> profiles=engine.voices.profiles();

        form.addView(text("ACTIVE VOICE",11,MUTED,true));gap(form,5);
        form.addView(text(active.name,26,WHITE,true));gap(form,6);
        form.addView(text(engine.voices.status(),12,engine.voices.ready()?ACCENT:AMBER,true));gap(form,10);
        form.addView(text("Back to one exact WAV per voice profile. The script is now a fixed canonical manifest built into Patrol Link, so the same WAV is expected to contain the same phrases in the same order on every phone.",13,MUTED,false));gap(form,12);

        for(VoiceManager.Profile profile:profiles){
            boolean selected=profile.id.equals(active.id);
            addCard(form,button((selected?"●  ":"○  ")+profile.name,selected,()->{
                engine.voices.setActive(profile.id);toast(profile.name+" selected.");voiceLibrary();
            }));
        }

        addCard(form,button("Add voice profile",false,this::addVoiceProfile));

        if(profiles.size()>1){
            addCard(form,dangerButton("Delete "+active.name,()->new AlertDialog.Builder(this)
                .setTitle("Delete "+active.name+"?")
                .setMessage("Delete this voice profile and its imported exact WAV?")
                .setPositiveButton("Delete",(d,w)->{
                    if(engine.voices.delete(active.id)){toast(active.name+" deleted.");voiceLibrary();}
                    else toast("Could not delete that profile.");
                }).setNegativeButton("Cancel",null).show()));
        } else {
            form.addView(text("Add another voice profile before deleting the only remaining profile.",11,MUTED,false));gap(form,10);
        }

        addCard(form,button("Copy single canonical script",true,()->{
            String script=engine.voices.exactPackScript(engine.recent(),engine.guards());
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(active.name+" canonical exact voice script",script));
            toast("Single canonical "+active.name+" script copied.");
        }));

        addCard(form,button((engine.voices.ready()?"Replace":"Import")+" single WAV",true,()->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("audio/*");
            pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/wav","audio/x-wav","audio/vnd.wave","audio/wave","application/octet-stream"});
            try{startActivityForResult(pick,REQ_EXACT_PACK);}catch(ActivityNotFoundException e){toast("No file picker is installed.");}
        }));

        addCard(form,button("Test "+active.name,false,()->{
            if(!engine.voices.ready())toast("Import the single "+active.name+" WAV first.");
            else engine.voices.test();
        }));

        addCard(form,button("Speech controls: wording, names and speed",false,()->new SpeechSettingsUi(this,engine).show()));

        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("Exact voice library").setView(scroll).setPositiveButton("Close",null).show();
    }

    private void addVoiceProfile(){
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("Voice name");name.setTextColor(WHITE);name.setHintTextColor(MUTED);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Add voice profile")
            .setMessage("Create the profile, copy the same canonical script, generate it in that voice, then import one WAV.")
            .setView(name).setPositiveButton("Create",null).setNegativeButton("Cancel",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            engine.voices.importTrainingAudio(null,name.getText().toString(),(ok,msg)->{
                toast(msg);if(ok){d.dismiss();voiceLibrary();}
            });
        }));
        d.show();
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_EXACT_PACK&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            final String name=engine.voices.activeName();
            Toast.makeText(this,"Importing single "+name+" WAV…",Toast.LENGTH_LONG).show();
            engine.voices.importExactPack(data.getData(),(ok,message)->{
                toast(message);if(ok&&!isFinishing())voiceLibrary();
            });
            return;
        }
        if(requestCode==REQ_VOICE_AUDIO&&resultCode==RESULT_OK){
            toast("Create a voice profile, copy its canonical script, then import one exact WAV.");
        }
    }

'''
s=s[:start]+voice_ui+s[end:]
s=s.replace('Each selectable voice profile uses exact recordings generated by your voice website. The master phrase pack is divided into exactly two halves: Part 1 and Part 2. Generate each half separately, preserving every [pause 3] separator, then import both WAV files into the same selected profile. Patrol Link merges the two halves. Missing phrases stay silent rather than using another voice.',
'''Each selectable voice profile uses one exact WAV generated by your voice website from Patrol Link's fixed canonical script. The canonical phrase list is identical on every phone, so the same WAV can be transferred between devices without changing the expected phrase count or order. Missing phrases stay silent rather than using another voice.''')
p.write_text(s)

# Fixed canonical asset.
assets=root/'app/src/main/assets/exact';assets.mkdir(parents=True,exist_ok=True)
shutil.copyfile(payload/'canonical_phrases.txt',assets/'canonical_phrases.txt')

# Remove obsolete two-part tests and add fixed-manifest regression.
old=root/'app/src/androidTest/java/au/com/roningroup/patrollink/TwoPartProfilesTest.java'
if old.exists(): old.unlink()
shutil.copyfile(payload/'SingleWavCanonicalTest.java',root/'app/src/androidTest/java/au/com/roningroup/patrollink/SingleWavCanonicalTest.java')

assert 'versionCode 128' in (root/'app/build.gradle').read_text()
assert 'single canonical script' in (java/'MainActivity.java').read_text()
assert 'MAX_WAV_BYTES = 512 * 1024 * 1024' in (java/'ExactPhrasePack.java').read_text()
assert (assets/'canonical_phrases.txt').is_file()
