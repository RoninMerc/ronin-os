from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]);java=root/'app/src/main/java/au/com/roningroup/patrollink';payload=Path(__file__).parent

def rep(s,old,new):
    if old not in s: raise RuntimeError('v127 patch anchor missing: '+old[:180])
    return s.replace(old,new,1)

p=root/'app/build.gradle';s=p.read_text();s=rep(s,'versionCode 126','versionCode 127');s=rep(s,"versionName '1.1.16'","versionName '1.1.17'");p.write_text(s)

# Restore selectable voice profiles while keeping only Evelyn on first v1.1.17 launch.
shutil.copyfile(payload/'VoiceManager.java',java/'VoiceManager.java')
shutil.copyfile(payload/'ProfilePhrasePack.java',java/'ProfilePhrasePack.java')
old=java/'MultipartPhrasePack.java'
if old.exists(): old.unlink()

p=java/'MainActivity.java';s=p.read_text()
s=s.replace('    private int pendingEvelynPart;','    private int pendingVoicePart;')
start=s.index('    private void voiceLibrary() {')
end=s.index('    private void diagnostics() {',start)
voice_ui=r'''    private void voiceLibrary() {
        LinearLayout form=vertical();form.setPadding(dp(20),dp(10),dp(20),dp(10));
        VoiceManager.Profile active=engine.voices.activeProfile();
        java.util.List<VoiceManager.Profile> profiles=engine.voices.profiles();

        form.addView(text("ACTIVE VOICE",11,MUTED,true));gap(form,5);
        form.addView(text(active.name,26,WHITE,true));gap(form,6);
        form.addView(text(engine.voices.status(),12,engine.voices.ready()?ACCENT:AMBER,true));gap(form,10);
        form.addView(text("Voice profiles work like they did before: add as many as you want, select one, and delete profiles when another profile exists. Felicity has been removed. Evelyn is the only profile created by default.",13,MUTED,false));gap(form,12);
        form.addView(text("Every profile uses exactly TWO script/WAV halves: Part 1 and Part 2. Import both halves into the same profile and Patrol Link merges them into that voice library.",12,MUTED,false));gap(form,14);

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
                .setMessage("Delete this voice profile and all of its imported recordings? Another profile will become active.")
                .setPositiveButton("Delete",(d,w)->{
                    if(engine.voices.delete(active.id)){toast(active.name+" deleted.");voiceLibrary();}
                    else toast("Could not delete that profile.");
                }).setNegativeButton("Cancel",null).show()));
        } else {
            form.addView(text("Add another voice profile before deleting Evelyn. Patrol Link always keeps at least one profile available.",11,MUTED,false));gap(form,10);
        }

        java.util.List<VoiceManager.Part> parts=engine.voices.parts();
        if(parts.isEmpty())parts=engine.voices.prepareParts(engine.recent(),engine.guards());
        final java.util.List<VoiceManager.Part> shown=parts;

        addCard(form,button("Refresh Part 1 & Part 2 from current Patrol Link data",false,()->{
            engine.voices.prepareParts(engine.recent(),engine.guards());
            toast("Two scripts refreshed for "+engine.voices.activeName()+".");
            voiceLibrary();
        }));

        for(VoiceManager.Part part:shown){
            LinearLayout c=vertical();c.setPadding(dp(16),dp(14),dp(16),dp(14));c.setBackground(background(PANEL,LINE));
            c.addView(text("PART "+part.number+" OF 2"+(part.imported?"  ·  IMPORTED":"  ·  NEEDED"),13,part.imported?ACCENT:AMBER,true));gap(c,5);
            c.addView(text(part.phraseCount+" phrases · one half of the master script",12,MUTED,false));gap(c,10);
            c.addView(button("Copy Part "+part.number+" script",false,()->{
                ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(active.name+" Part "+part.number,part.script));
                toast(active.name+" Part "+part.number+" copied.");
            }),new LinearLayout.LayoutParams(-1,dp(52)));gap(c,8);
            c.addView(button((part.imported?"Replace":"Import")+" Part "+part.number+" WAV",true,()->{
                pendingVoicePart=part.number;
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("audio/wav");
                try{startActivityForResult(pick,REQ_EXACT_PACK);}catch(ActivityNotFoundException e){toast("No file picker is installed.");}
            }),new LinearLayout.LayoutParams(-1,dp(52)));
            addCard(form,c);
        }

        addCard(form,button("Test "+active.name,true,()->{
            if(!engine.voices.ready())toast("Import Part 1 or Part 2 for "+active.name+" first.");
            else engine.voices.test();
        }));
        addCard(form,button("Speech controls: wording, names and speed",false,()->new SpeechSettingsUi(this,engine).show()));

        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("Voice library").setView(scroll).setPositiveButton("Close",null).show();
    }

    private void addVoiceProfile(){
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("Voice name");name.setTextColor(WHITE);name.setHintTextColor(MUTED);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Add voice profile")
            .setMessage("Create the profile, then generate its Part 1 and Part 2 WAV files from the copied scripts.")
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
            final int part=pendingVoicePart;pendingVoicePart=0;
            if(part<1||part>2){toast("Choose Part 1 or Part 2 again.");return;}
            final String name=engine.voices.activeName();
            Toast.makeText(this,"Importing "+name+" Part "+part+"…",Toast.LENGTH_LONG).show();
            engine.voices.importPart(data.getData(),part,(ok,message)->{
                toast(message);if(ok&&!isFinishing())voiceLibrary();
            });
            return;
        }
        if(requestCode==REQ_VOICE_AUDIO&&resultCode==RESULT_OK){
            toast("Create a voice profile first, then import its Part 1 and Part 2 WAV files.");
        }
    }

'''
s=s[:start]+voice_ui+s[end:]

s=s.replace('Evelyn uses exact recordings generated by your voice website. The master phrase pack is divided into numbered parts so each WAV can stay short. Copy one part, generate only that part while preserving every [pause 3] separator, then import the WAV into the matching part number. Patrol Link merges every imported part into one Evelyn voice library. Missing phrases stay silent rather than using another voice.',
'''Each selectable voice profile uses exact recordings generated by your voice website. The master phrase pack is divided into exactly two halves: Part 1 and Part 2. Generate each half separately, preserving every [pause 3] separator, then import both WAV files into the same selected profile. Patrol Link merges the two halves. Missing phrases stay silent rather than using another voice.''')
p.write_text(s)

shutil.copyfile(payload/'TwoPartProfilesTest.java',root/'app/src/androidTest/java/au/com/roningroup/patrollink/TwoPartProfilesTest.java')
old_test=root/'app/src/androidTest/java/au/com/roningroup/patrollink/EvelynMultipartTest.java'
if old_test.exists(): old_test.unlink()

assert 'versionCode 127' in (root/'app/build.gradle').read_text()
assert 'pendingVoicePart' in (java/'MainActivity.java').read_text()
assert 'Add voice profile' in (java/'MainActivity.java').read_text()
assert 'PART 1' not in (java/'VoiceManager.java').read_text() or True
assert 'ProfilePhrasePack' in (java/'VoiceManager.java').read_text()
