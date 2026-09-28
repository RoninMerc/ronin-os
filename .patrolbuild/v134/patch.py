from pathlib import Path
import shutil,sys
root=Path(sys.argv[1])
java=root/'app/src/main/java/au/com/roningroup/patrollink'
payload=Path(__file__).parent

def rep(s,old,new):
    if old not in s:
        raise RuntimeError('v134 patch anchor missing: '+old[:260])
    return s.replace(old,new,1)

# Version.
p=root/'app/build.gradle'
s=p.read_text()
s=rep(s,'versionCode 133','versionCode 134')
s=rep(s,"versionName '1.1.23'","versionName '1.1.24'")
p.write_text(s)

# VoiceManager: save/copy the complete CURRENT installed library and use that
# exact phrase manifest when importing a newly-generated full WAV into another voice.
p=java/'VoiceManager.java'
s=p.read_text()

s=rep(s,
'    private static final String PROFILES="voice_profiles_v5", ACTIVE="voice_profile_active";',
'''    private static final String PROFILES="voice_profiles_v5", ACTIVE="voice_profile_active";
    private static final String COMPLETE_TEMPLATE="voice_complete_library_template_v1";
    private static final String COMPLETE_TEMPLATE_SOURCE="voice_complete_library_template_source_v1";''')

# Preserve the current populated voice as the new-voice template automatically
# before switching to a freshly-created empty profile.
s=rep(s,
'''        String id="voice-"+UUID.randomUUID();
        try{''',
'''        try{
            Profile source=activeProfile();
            if(ExactPhrasePack.installed(context,source.id))
                saveCompleteTemplate(ExactPhrasePack.phrases(context,source.id),source.name);
        }catch(Exception captureError){}
        String id="voice-"+UUID.randomUUID();
        try{''')

anchor='''    public int installedPhraseCount(){
        try{return ExactPhrasePack.clips(context,activeProfile().id).size();}
        catch(Exception ignored){return 0;}
    }

    public void importAddOn(Uri uri,ImportCallback callback){'''
insert=r'''    public int installedPhraseCount(){
        try{return ExactPhrasePack.clips(context,activeProfile().id).size();}
        catch(Exception ignored){return 0;}
    }

    private void saveCompleteTemplate(List<String> phrases,String source){
        JSONArray a=new JSONArray();
        if(phrases!=null)for(String phrase:phrases){
            String clean=SpeechRules.clean(phrase);
            if(!clean.isEmpty())a.put(clean);
        }
        prefs.edit()
            .putString(COMPLETE_TEMPLATE,a.toString())
            .putString(COMPLETE_TEMPLATE_SOURCE,source==null||source.trim().isEmpty()?"Current library":source.trim())
            .apply();
    }

    private List<String> savedCompleteTemplate(){
        ArrayList<String> out=new ArrayList<>();
        try{
            JSONArray a=new JSONArray(prefs.getString(COMPLETE_TEMPLATE,"[]"));
            LinkedHashSet<String> seen=new LinkedHashSet<>();
            for(int i=0;i<a.length();i++){
                String phrase=SpeechRules.clean(a.optString(i));
                String key=ExactPhrasePack.normalize(phrase);
                if(!phrase.isEmpty()&&seen.add(key))out.add(phrase);
            }
        }catch(Exception ignored){}
        return out;
    }

    public List<String> completeTemplatePhrases(){
        List<String> saved=savedCompleteTemplate();
        return saved.isEmpty()?canonicalPhrases():saved;
    }

    public int completeTemplateCount(){return completeTemplatePhrases().size();}

    public String completeTemplateSource(){
        List<String> saved=savedCompleteTemplate();
        if(saved.isEmpty())return "Built-in base";
        String source=prefs.getString(COMPLETE_TEMPLATE_SOURCE,"Current library");
        return source==null||source.trim().isEmpty()?"Current library":source.trim();
    }

    /**
     * Copies exactly what the selected voice can currently say: original base
     * phrases plus every add-on/replacement installed up to this point.
     *
     * The same phrase list is persisted as the import manifest for another
     * voice, so the generated WAV can be mapped exactly after a new profile is created.
     */
    public String completeLibraryScript(){
        Profile p=activeProfile();
        List<String> phrases;
        try{
            if(ExactPhrasePack.installed(context,p.id)){
                phrases=ExactPhrasePack.phrases(context,p.id);
                saveCompleteTemplate(phrases,p.name);
            }else{
                phrases=savedCompleteTemplate();
                if(phrases.isEmpty()){
                    phrases=canonicalPhrases();
                    saveCompleteTemplate(phrases,"Built-in base");
                }
            }
        }catch(Exception e){
            phrases=savedCompleteTemplate();
            if(phrases.isEmpty())phrases=canonicalPhrases();
        }
        return addOnScript(phrases);
    }

    /**
     * Import a full WAV generated from the last complete-library script copied.
     * This intentionally replaces only the selected voice profile's library.
     */
    public void importCompleteLibrary(Uri uri,ImportCallback callback){
        final Profile p=activeProfile();
        final List<String> phrases=completeTemplatePhrases();
        final String source=completeTemplateSource();
        status="Importing complete "+p.name+" library";
        lastError="";
        files.execute(()->{
            try{
                ExactPhrasePack.ImportResult result=ExactPhrasePack.importPack(context,uri,p.id,phrases);
                main.post(()->{
                    refreshStatus();
                    deliver(callback,true,p.name+" complete library imported: "+result.total+
                            " exact recordings from the "+source+" template.");
                });
            }catch(Exception e){
                String msg=safe(e);
                main.post(()->{
                    status=p.name+" complete-library import failed";
                    lastError=msg;
                    deliver(callback,false,msg);
                });
            }
        });
    }

    public void importAddOn(Uri uri,ImportCallback callback){'''
s=rep(s,anchor,insert)

# Diagnostics make the new voice-cloning/template workflow visible.
s=s.replace(
'''                "\nPending add-on phrases: "+addOnDraft().size()+''',
'''                "\nPending add-on phrases: "+addOnDraft().size()+
                "\nComplete-library template: "+completeTemplateCount()+" phrases from "+completeTemplateSource()+''')
p.write_text(s)

# Main UI: one-button complete script + matching import for a new voice.
p=java/'MainActivity.java'
s=p.read_text()

s=rep(s,
'    private static final int REQ_VOICE_ADDON = 4103;',
'''    private static final int REQ_VOICE_ADDON = 4103;
    private static final int REQ_COMPLETE_LIBRARY = 4104;''')

old=r'''        form.addView(text("FULL BASE LIBRARY · OPTIONAL",11,MUTED,true));gap(form,5);
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
        }));'''
new=r'''        form.addView(text("COMPLETE LIBRARY · FOR ANOTHER VOICE",11,ACCENT,true));gap(form,5);
        form.addView(text("One button copies the entire CURRENT library: the original script plus every phrase you have added or replaced up to this point. Patrol Link also remembers that exact phrase order so a full WAV generated in another voice can be imported and mapped correctly.",12,MUTED,false));gap(form,8);

        addCard(form,button("Copy ENTIRE current library script",true,()->{
            try{
                String script=engine.voices.completeLibraryScript();
                ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                    ClipData.newPlainText(active.name+" complete exact voice library",script));
                toast("Entire library copied · "+engine.voices.completeTemplateCount()+
                    " phrases · source "+engine.voices.completeTemplateSource()+".");
                voiceLibrary();
            }catch(Exception e){toast(e.getMessage()==null?"Could not copy the complete library.":e.getMessage());}
        }));

        form.addView(text("Saved complete template · "+engine.voices.completeTemplateCount()+
            " phrases · "+engine.voices.completeTemplateSource(),11,MUTED,false));gap(form,8);

        addCard(form,button("Import complete-library WAV",false,()->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("audio/*");
            pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/wav","audio/x-wav","audio/vnd.wave","audio/wave","application/octet-stream"});
            try{startActivityForResult(pick,REQ_COMPLETE_LIBRARY);}catch(ActivityNotFoundException e){toast("No file picker is installed.");}
        }));

        form.addView(text("ORIGINAL BUILT-IN BASE · RESET ONLY",11,MUTED,true));gap(form,5);
        form.addView(text("You normally will not need these controls. They use only Patrol Link's original built-in base list and do not include later add-ons.",11,MUTED,false));gap(form,8);

        addCard(form,button("Copy original built-in base script",false,()->{
            String script=engine.voices.exactPackScript(engine.recent(),engine.guards());
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                ClipData.newPlainText(active.name+" original built-in base script",script));
            toast("Original built-in base script copied.");
        }));

        addCard(form,button((engine.voices.ready()?"Replace":"Import")+" from original base WAV",false,()->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("audio/*");
            pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/wav","audio/x-wav","audio/vnd.wave","audio/wave","application/octet-stream"});
            try{startActivityForResult(pick,REQ_EXACT_PACK);}catch(ActivityNotFoundException e){toast("No file picker is installed.");}
        }));'''
s=rep(s,old,new)

# New complete-template import handler comes before the original-base reset handler.
anchor=r'''        if(requestCode==REQ_EXACT_PACK&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            final String name=engine.voices.activeName();'''
insert=r'''        if(requestCode==REQ_COMPLETE_LIBRARY&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            final String name=engine.voices.activeName();
            final android.net.Uri uri=data.getData();
            final int count=engine.voices.completeTemplateCount();
            final String source=engine.voices.completeTemplateSource();
            Runnable perform=()->{
                Toast.makeText(this,"Importing "+count+" exact "+name+" recordings…",Toast.LENGTH_LONG).show();
                engine.voices.importCompleteLibrary(uri,(ok,message)->{
                    toast(message);if(ok&&!isFinishing())voiceLibrary();
                });
            };
            if(engine.voices.ready()){
                new AlertDialog.Builder(this)
                    .setTitle("Replace "+name+" with complete library?")
                    .setMessage("This imports "+count+" phrases from the saved "+source+
                        " complete-library template and replaces only "+name+"'s current exact library.")
                    .setPositiveButton("Replace "+name,(d,w)->perform.run())
                    .setNegativeButton("Cancel",null).show();
            }else perform.run();
            return;
        }

        if(requestCode==REQ_EXACT_PACK&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            final String name=engine.voices.activeName();'''
s=rep(s,anchor,insert)

# New-profile copy explains the full workflow.
s=s.replace(
'''            .setMessage("Create the profile, then either import the full base library or start adding exact phrases in small WAVs.")''',
'''            .setMessage("Create the profile, then import the complete-library WAV generated from the one-button complete script, or start with small add-on WAVs. Patrol Link preserves the complete template when you create this new voice.")''')

p.write_text(s)

# Test.
shutil.copyfile(payload/'CompleteLibraryTemplateTest.java',
                root/'app/src/androidTest/java/au/com/roningroup/patrollink/CompleteLibraryTemplateTest.java')

assert "versionName '1.1.24'" in (root/'app/build.gradle').read_text()
voice=(java/'VoiceManager.java').read_text()
main=(java/'MainActivity.java').read_text()
assert 'completeLibraryScript()' in voice
assert 'importCompleteLibrary' in voice
assert 'ExactPhrasePack.phrases(context,p.id)' in voice
assert 'Copy ENTIRE current library script' in main
assert 'REQ_COMPLETE_LIBRARY = 4104' in main
assert 'Import complete-library WAV' in main
