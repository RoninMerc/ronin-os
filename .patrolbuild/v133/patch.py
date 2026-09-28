from pathlib import Path
import shutil,sys
root=Path(sys.argv[1])
java=root/'app/src/main/java/au/com/roningroup/patrollink'
payload=Path(__file__).parent

def rep(s,old,new):
    if old not in s:
        raise RuntimeError('v133 patch anchor missing: '+old[:240])
    return s.replace(old,new,1)

# Version.
p=root/'app/build.gradle'
s=p.read_text()
s=rep(s,'versionCode 132','versionCode 133')
s=rep(s,"versionName '1.1.22'","versionName '1.1.23'")
p.write_text(s)

# Exact audio storage now supports indefinite additive phrase packs.
shutil.copyfile(payload/'ExactPhrasePack.java',java/'ExactPhrasePack.java')
shutil.copyfile(payload/'ExactPhraseResolver.java',java/'ExactPhraseResolver.java')

# VoiceManager: add incremental phrase drafts/imports and composable playback.
p=java/'VoiceManager.java'
s=p.read_text()

s=s.replace('''                "\\nCanonical manifest phrases: "+canonicalPhrases().size()+
                "\\nExact recorded phrases: "+clips+''',
'''                "\\nBase-script phrases: "+canonicalPhrases().size()+
                "\\nExact recorded phrases installed: "+clips+
                "\\nPending add-on phrases: "+addOnDraft().size()+''')

s=s.replace('''            stop();refreshStatus();deliver(callback,true,name+" created. Copy the single canonical script, generate one WAV, then import that WAV.");''',
'''            stop();refreshStatus();deliver(callback,true,name+" created. You can import the full base library or start adding exact phrases in small WAVs.");''')

s=s.replace('''                main.post(()->{refreshStatus();deliver(callback,true,p.name+" imported: "+result.phrases+" exact recordings from one WAV.");});''',
'''                main.post(()->{refreshStatus();deliver(callback,true,p.name+" base library replaced: "+result.phrases+" exact recordings.");});''')

anchor='''    public String recordingIssue(String text){
        Profile p=activeProfile();'''
addition=r'''    private String addOnDraftKey(String profile){return "voice_addon_draft:"+profile;}

    public List<String> parseAddOnPhrases(String raw){
        LinkedHashMap<String,String> unique=new LinkedHashMap<>();
        if(raw!=null)for(String line:raw.split("\\r?\\n")){
            String phrase=SpeechRules.clean(line);
            if(phrase.isEmpty()||phrase.equalsIgnoreCase("[pause 3]"))continue;
            if(phrase.length()>300)throw new IllegalArgumentException("Keep each phrase under 300 characters.");
            String key=ExactPhrasePack.normalize(phrase);
            if(!unique.containsKey(key))unique.put(key,phrase);
        }
        if(unique.isEmpty())throw new IllegalArgumentException("Enter at least one phrase, one per line.");
        if(unique.size()>250)throw new IllegalArgumentException("Use up to 250 phrases in one add-on. You can import unlimited add-on WAVs over time.");
        return new ArrayList<>(unique.values());
    }

    public void saveAddOnDraft(List<String> phrases){
        JSONArray a=new JSONArray();
        if(phrases!=null)for(String phrase:phrases)a.put(phrase);
        prefs.edit().putString(addOnDraftKey(activeProfile().id),a.toString()).apply();
    }

    public List<String> addOnDraft(){
        ArrayList<String> out=new ArrayList<>();
        try{
            JSONArray a=new JSONArray(prefs.getString(addOnDraftKey(activeProfile().id),"[]"));
            for(int i=0;i<a.length();i++){
                String phrase=SpeechRules.clean(a.optString(i));
                if(!phrase.isEmpty())out.add(phrase);
            }
        }catch(Exception ignored){}
        return out;
    }

    public String addOnScript(List<String> phrases){
        if(phrases==null||phrases.isEmpty())throw new IllegalArgumentException("Enter at least one phrase first.");
        StringBuilder b=new StringBuilder();
        for(int i=0;i<phrases.size();i++){
            if(i>0)b.append("[pause 3]\\n\\n");
            b.append(phrases.get(i)).append('\\n');
        }
        return b.toString();
    }

    public int installedPhraseCount(){
        try{return ExactPhrasePack.clips(context,activeProfile().id).size();}
        catch(Exception ignored){return 0;}
    }

    public void importAddOn(Uri uri,ImportCallback callback){
        final Profile p=activeProfile();
        final List<String> phrases=addOnDraft();
        if(phrases.isEmpty()){deliver(callback,false,"Enter and save the add-on phrase list first.");return;}
        status="Adding "+phrases.size()+" exact "+p.name+" phrase"+(phrases.size()==1?"":"s");
        lastError="";
        files.execute(()->{
            try{
                ExactPhrasePack.ImportResult result=ExactPhrasePack.importAdditions(context,uri,p.id,phrases);
                prefs.edit().remove(addOnDraftKey(p.id)).apply();
                main.post(()->{
                    refreshStatus();
                    String message=p.name+" library updated: "+result.added+" added, "+result.replaced+" replaced, "+result.total+" total exact recordings.";
                    deliver(callback,true,message);
                });
            }catch(Exception e){
                String msg=safe(e);
                main.post(()->{
                    status=p.name+" add-on import failed";
                    lastError=msg;
                    deliver(callback,false,msg);
                });
            }
        });
    }

    public String recordingIssue(String text){
        Profile p=activeProfile();'''
s=rep(s,anchor,addition)

old='''    public void speechSettingsChanged(){
        stop();status="Speech wording changed";lastError="The single canonical WAV is portable across phones. Custom wording can only be spoken when that exact phrase is already in the canonical pack.";
    }'''
new='''    public void speechSettingsChanged(){
        stop();
        status="Speech wording changed";
        lastError="If the wording is not already recorded, add only the missing words or phrase from Voice library → Add phrases. Existing recordings do not need to be rebuilt.";
    }'''
s=rep(s,old,new)

resolve_start=s.index('    private List<File> resolve(String profile,String text)throws Exception{')
resolve_end=s.index('    private void playNextClip(){',resolve_start)
s=s[:resolve_start]+'''    private List<File> resolve(String profile,String text)throws Exception{
        return ExactPhraseResolver.resolve(ExactPhrasePack.clips(context,profile),text);
    }
'''+s[resolve_end:]

old='''    private void refreshStatus(){
        Profile p=activeProfile();
        if(!ExactPhrasePack.installed(context,p.id)){status=p.name+" single WAV required";return;}
        try{
            int have=ExactPhrasePack.clips(context,p.id).size(), need=canonicalPhrases().size();
            status=have>=need?p.name+" exact single-WAV pack ready"
                    :p.name+" exact pack "+have+"/"+need+" phrases · update WAV for missing wording";
        }catch(Exception e){status=p.name+" exact pack needs checking";}
    }'''
new='''    private void refreshStatus(){
        Profile p=activeProfile();
        if(!ExactPhrasePack.installed(context,p.id)){status=p.name+" exact library empty · import base or add phrases";return;}
        try{
            int have=ExactPhrasePack.clips(context,p.id).size();
            status=p.name+" expandable exact library · "+have+" recordings";
        }catch(Exception e){status=p.name+" exact library needs checking";}
    }'''
s=rep(s,old,new)
p.write_text(s)

# Main voice UI: expandable library first; full master rebuild remains optional.
p=java/'MainActivity.java'
s=p.read_text()
s=rep(s,'    private static final int REQ_EXACT_PACK = 4102;',
      '    private static final int REQ_EXACT_PACK = 4102;\n    private static final int REQ_VOICE_ADDON = 4103;')

start=s.index('    private void voiceLibrary() {')
end=s.index('    private void diagnostics() {',start)
voice_ui=r'''    private void voiceLibrary() {
        LinearLayout form=vertical();form.setPadding(dp(20),dp(10),dp(20),dp(10));
        VoiceManager.Profile active=engine.voices.activeProfile();
        java.util.List<VoiceManager.Profile> profiles=engine.voices.profiles();

        form.addView(text("ACTIVE VOICE",11,MUTED,true));gap(form,5);
        form.addView(text(active.name,26,WHITE,true));gap(form,6);
        form.addView(text(engine.voices.status(),12,engine.voices.ready()?ACCENT:AMBER,true));gap(form,8);
        form.addView(text(engine.voices.installedPhraseCount()+" exact recordings installed",12,MUTED,false));gap(form,10);
        form.addView(text("This is now an expandable phrase library. Keep the recordings you already have and add small WAVs whenever a new street, guard, function or pronunciation is needed. There are no Part 1 / Part 2 / Part 3 limits.",13,MUTED,false));gap(form,12);
        form.addView(text("If an add-on contains a phrase that is already installed, only that phrase is replaced. Everything else stays untouched.",12,MUTED,false));gap(form,14);

        for(VoiceManager.Profile profile:profiles){
            boolean selected=profile.id.equals(active.id);
            addCard(form,button((selected?"●  ":"○  ")+profile.name,selected,()->{
                engine.voices.setActive(profile.id);toast(profile.name+" selected.");voiceLibrary();
            }));
        }

        addCard(form,button("Add / replace exact phrases",true,this::showAddVoicePhrases));
        addCard(form,button("Test "+active.name,false,engine.voices::test));
        addCard(form,button("Speech controls: wording, names and speed",false,()->new SpeechSettingsUi(this,engine).show()));
        addCard(form,button("Add voice profile",false,this::addVoiceProfile));

        if(profiles.size()>1){
            addCard(form,dangerButton("Delete "+active.name,()->new AlertDialog.Builder(this)
                .setTitle("Delete "+active.name+"?")
                .setMessage("Delete this voice profile and all of its exact recordings?")
                .setPositiveButton("Delete",(d,w)->{
                    if(engine.voices.delete(active.id)){toast(active.name+" deleted.");voiceLibrary();}
                    else toast("Could not delete that profile.");
                }).setNegativeButton("Cancel",null).show()));
        }

        form.addView(text("FULL BASE LIBRARY · OPTIONAL",11,MUTED,true));gap(form,5);
        form.addView(text("You do not need to rebuild the full script when adding phrases. These controls remain only for a new voice or an intentional complete rebuild.",12,MUTED,false));gap(form,8);

        addCard(form,button("Copy full base script",false,()->{
            String script=engine.voices.exactPackScript(engine.recent(),engine.guards());
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(active.name+" full base script",script));
            toast("Full base script copied.");
        }));

        addCard(form,button((engine.voices.ready()?"Replace":"Import")+" full base WAV",false,()->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("audio/*");
            pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/wav","audio/x-wav","audio/vnd.wave","audio/wave","application/octet-stream"});
            try{startActivityForResult(pick,REQ_EXACT_PACK);}catch(ActivityNotFoundException e){toast("No file picker is installed.");}
        }));

        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("Expandable exact voice library").setView(scroll).setPositiveButton("Close",null).show();
    }

    private void showAddVoicePhrases(){
        VoiceManager.Profile active=engine.voices.activeProfile();
        LinearLayout form=vertical();form.setPadding(dp(20),dp(10),dp(20),dp(10));
        form.addView(text("ADD PHRASES TO "+active.name.toUpperCase(Locale.ROOT),11,ACCENT,true));gap(form,8);
        form.addView(text("Enter only what you need, one phrase per line. For example, if General Patrol is already recorded, adding only Bobsled Lane is enough — Patrol Link can join the two exact recordings at playback.",13,MUTED,false));gap(form,10);
        form.addView(text("Generate these lines in the same order with [pause 3] between them. You can import a single phrase or hundreds, then do another add-on later. Matching phrases replace only their old recording.",12,MUTED,false));gap(form,10);

        EditText phrases=new EditText(this);
        phrases.setTextColor(WHITE);phrases.setHintTextColor(MUTED);phrases.setTextSize(16);
        phrases.setHint("Bobsled Lane\\nBalmara Place\\nTradition Place\\nD Rogers 1");
        phrases.setGravity(Gravity.TOP);phrases.setMinLines(7);phrases.setMaxLines(14);
        phrases.setSingleLine(false);
        phrases.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        java.util.List<String> draft=engine.voices.addOnDraft();
        if(!draft.isEmpty())phrases.setText(android.text.TextUtils.join("\n",draft));
        form.addView(phrases,new LinearLayout.LayoutParams(-1,-2));gap(form,10);

        addCard(form,button("Copy add-on script",true,()->{
            try{
                java.util.List<String> list=engine.voices.parseAddOnPhrases(phrases.getText().toString());
                engine.voices.saveAddOnDraft(list);
                String script=engine.voices.addOnScript(list);
                ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                    ClipData.newPlainText(active.name+" add-on exact voice script",script));
                toast(list.size()+" phrase"+(list.size()==1?"":"s")+" copied. Generate only this small add-on.");
            }catch(Exception e){toast(e.getMessage()==null?"Check the phrase list.":e.getMessage());}
        }));

        addCard(form,button("Import add-on WAV",true,()->{
            try{
                java.util.List<String> list=engine.voices.parseAddOnPhrases(phrases.getText().toString());
                engine.voices.saveAddOnDraft(list);
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("audio/*");
                pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/wav","audio/x-wav","audio/vnd.wave","audio/wave","application/octet-stream"});
                startActivityForResult(pick,REQ_VOICE_ADDON);
            }catch(ActivityNotFoundException e){toast("No file picker is installed.");}
             catch(Exception e){toast(e.getMessage()==null?"Check the phrase list.":e.getMessage());}
        }));

        addCard(form,button("Clear add-on draft",false,()->{
            phrases.setText("");
            engine.voices.saveAddOnDraft(Collections.emptyList());
            toast("Add-on draft cleared. Installed recordings were not changed.");
        }));

        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("Add exact phrases").setView(scroll).setPositiveButton("Close",null).show();
    }

    private void addVoiceProfile(){
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("Voice name");name.setTextColor(WHITE);name.setHintTextColor(MUTED);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Add voice profile")
            .setMessage("Create the profile, then either import the full base library or start adding exact phrases in small WAVs.")
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

        if(requestCode==REQ_VOICE_ADDON&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            final String name=engine.voices.activeName();
            final int count=engine.voices.addOnDraft().size();
            Toast.makeText(this,"Adding "+count+" "+name+" exact phrase"+(count==1?"":"s")+"…",Toast.LENGTH_LONG).show();
            engine.voices.importAddOn(data.getData(),(ok,message)->{
                toast(message);
                if(ok&&!isFinishing())voiceLibrary();
            });
            return;
        }

        if(requestCode==REQ_EXACT_PACK&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            final String name=engine.voices.activeName();
            new AlertDialog.Builder(this)
                .setTitle("Replace full "+name+" library?")
                .setMessage("This is the full-base import. It replaces the current exact library. Use Add / replace exact phrases instead if you only want to add a few new recordings.")
                .setPositiveButton("Replace full library",(d,w)->{
                    Toast.makeText(this,"Importing full "+name+" base WAV…",Toast.LENGTH_LONG).show();
                    engine.voices.importExactPack(data.getData(),(ok,message)->{
                        toast(message);if(ok&&!isFinishing())voiceLibrary();
                    });
                })
                .setNegativeButton("Cancel",null).show();
            return;
        }

        if(requestCode==REQ_VOICE_AUDIO&&resultCode==RESULT_OK){
            toast("Create/select a voice profile, then use Add / replace exact phrases.");
        }
    }

'''
s=s[:start]+voice_ui+s[end:]
p.write_text(s)

# Speech editor guidance now points to the incremental library.
p=java/'SpeechSettingsUi.java'
s=p.read_text()
s=s.replace('Exact recording missing: ','Exact recording missing — add the missing phrase in Voice library: ')
p.write_text(s)

# Tests.
shutil.copyfile(payload/'ExactPhraseResolverTest.java',
                root/'app/src/test/java/au/com/roningroup/patrollink/ExactPhraseResolverTest.java')
shutil.copyfile(payload/'AdditivePhrasePackTest.java',
                root/'app/src/androidTest/java/au/com/roningroup/patrollink/AdditivePhrasePackTest.java')

assert "versionName '1.1.23'" in (root/'app/build.gradle').read_text()
assert 'importAdditions' in (java/'ExactPhrasePack.java').read_text()
assert 'ExactPhraseResolver.resolve' in (java/'VoiceManager.java').read_text()
assert 'Add / replace exact phrases' in (java/'MainActivity.java').read_text()
assert 'REQ_VOICE_ADDON = 4103' in (java/'MainActivity.java').read_text()
