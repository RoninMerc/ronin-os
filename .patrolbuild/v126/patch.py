from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]);java=root/'app/src/main/java/au/com/roningroup/patrollink';payload=Path(__file__).parent

def rep(s,old,new):
    if old not in s: raise RuntimeError('v126 patch anchor missing: '+old[:180])
    return s.replace(old,new,1)

p=root/'app/build.gradle';s=p.read_text();s=rep(s,'versionCode 125','versionCode 126');s=rep(s,"versionName '1.1.15'","versionName '1.1.16'");p.write_text(s)

# Replace exact voice layer with Evelyn-only multi-part implementation.
shutil.copyfile(payload/'VoiceManager.java',java/'VoiceManager.java')
shutil.copyfile(payload/'MultipartPhrasePack.java',java/'MultipartPhrasePack.java')

# Replace voice library UI.
p=java/'MainActivity.java';s=p.read_text()
s=rep(s,'private PatrolEngine engine;','private PatrolEngine engine;\n    private int pendingEvelynPart;')
start=s.index('    private void voiceLibrary() {')
end=s.index('    private void diagnostics() {',start)
voice_ui=r'''    private void voiceLibrary() {
        LinearLayout form=vertical();form.setPadding(dp(20),dp(10),dp(20),dp(10));
        form.addView(text("ACTIVE VOICE",11,MUTED,true));gap(form,5);
        form.addView(text("Evelyn",26,WHITE,true));gap(form,6);
        form.addView(text(engine.voices.status(),12,engine.voices.ready()?ACCENT:AMBER,true));gap(form,10);
        form.addView(text("Evelyn is the only voice profile. The master script is split into short parts so your voice website does not have to generate one huge WAV. Import every part into this same Evelyn profile. Patrol Link merges them automatically.",13,MUTED,false));gap(form,12);
        form.addView(text("Each part is deliberately limited to 22 phrases and keeps the [pause 3] separators. Generate only one part at a time on the voice website, download that WAV, then import it against the matching part number.",12,MUTED,false));gap(form,14);

        java.util.List<VoiceManager.Part> parts=engine.voices.parts();
        if(parts.isEmpty()) parts=engine.voices.prepareParts(engine.recent(),engine.guards());
        final java.util.List<VoiceManager.Part> shown=parts;

        addCard(form,button("Refresh script parts from current Patrol Link data",false,()->{
            engine.voices.prepareParts(engine.recent(),engine.guards());
            toast("Evelyn script parts refreshed. Existing recordings stay installed; regenerate only parts containing new wording.");
            voiceLibrary();
        }));

        for(VoiceManager.Part part:shown){
            LinearLayout card=vertical();card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(background(PANEL,LINE));
            card.addView(text("PART "+part.number+" OF "+part.total+(part.imported?"  ·  IMPORTED":"  ·  NEEDED"),13,part.imported?ACCENT:AMBER,true));gap(card,5);
            card.addView(text(part.phraseCount+" recorded phrases",12,MUTED,false));gap(card,10);
            Button copy=button("Copy Part "+part.number+" script",false,()->{
                ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Evelyn Part "+part.number,part.script));
                toast("Evelyn Part "+part.number+" copied. Generate only this part on the voice website.");
            });
            card.addView(copy,new LinearLayout.LayoutParams(-1,dp(52)));gap(card,8);
            Button upload=button((part.imported?"Replace":"Import")+" Part "+part.number+" WAV",true,()->{
                pendingEvelynPart=part.number;
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("audio/wav");
                try{startActivityForResult(pick,REQ_EXACT_PACK);}catch(ActivityNotFoundException e){toast("No file picker is installed.");}
            });
            card.addView(upload,new LinearLayout.LayoutParams(-1,dp(52)));
            addCard(form,card);
        }

        addCard(form,button("Test Evelyn",true,()->{
            if(!engine.voices.ready())toast("Import at least one Evelyn WAV part first.");
            else engine.voices.test();
        }));
        addCard(form,button("Speech controls: wording, names and speed",false,()->new SpeechSettingsUi(this,engine).show()));

        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("Evelyn · Multi-part voice pack").setView(scroll).setPositiveButton("Close",null).show();
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_EXACT_PACK&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            final int part=pendingEvelynPart;pendingEvelynPart=0;
            if(part<1){toast("Choose the Evelyn part number again.");return;}
            android.net.Uri uri=data.getData();
            Toast.makeText(this,"Importing Evelyn Part "+part+"…",Toast.LENGTH_LONG).show();
            engine.voices.importPart(uri,part,(ok,message)->{
                toast(message);
                if(ok&&!isFinishing())voiceLibrary();
            });
            return;
        }
        if(requestCode==REQ_VOICE_AUDIO&&resultCode==RESULT_OK){
            toast("Evelyn now uses multi-part exact recordings. Open Voice settings and import the WAV against its matching part number.");
        }
    }

'''
s=s[:start]+voice_ui+s[end:]
p.write_text(s)

# Update help copy if present.
p=java/'MainActivity.java';s=p.read_text()
s=s.replace('Exact voice mode plays only the recordings generated by your voice website. Copy the exact phrase-pack script from Voice settings, generate it with the selected website voice, preserving every [pause 3] separator, and import the resulting 16-bit PCM WAV. Patrol Link splits those deliberate pauses and maps the website recordings to the phrases. If a future alert or custom wording is not in the installed pack, the app stays silent for that update and tells you which exact recording is missing rather than substituting or reconstructing another voice.',
'''Evelyn uses exact recordings generated by your voice website. The master phrase pack is divided into numbered parts so each WAV can stay short. Copy one part, generate only that part while preserving every [pause 3] separator, then import the WAV into the matching part number. Patrol Link merges every imported part into one Evelyn voice library. Missing phrases stay silent rather than using another voice.''')
p.write_text(s)

# New tests.
unit=root/'app/src/test/java/au/com/roningroup/patrollink';instrument=root/'app/src/androidTest/java/au/com/roningroup/patrollink'
shutil.copyfile(payload/'EvelynMultipartTest.java',instrument/'EvelynMultipartTest.java')

assert 'versionCode 126' in (root/'app/build.gradle').read_text()
assert 'pendingEvelynPart' in (java/'MainActivity.java').read_text()
assert 'MultipartPhrasePack' in (java/'VoiceManager.java').read_text()
assert 'DEFAULT_NAME="Evelyn"' in (java/'VoiceManager.java').read_text()
