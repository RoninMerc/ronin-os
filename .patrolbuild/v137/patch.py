from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]);java=root/'app/src/main/java/au/com/roningroup/patrollink';here=Path(__file__).parent

def rep(s,old,new):
    if old not in s:raise RuntimeError('v137 missing anchor: '+old[:180])
    return s.replace(old,new,1)

p=root/'app/build.gradle';s=p.read_text()
s=rep(s,'versionCode 136','versionCode 137');s=rep(s,"versionName '1.1.26'","versionName '1.1.27'");p.write_text(s)
shutil.copyfile(here/'EntireScriptCopyUi.java',java/'EntireScriptCopyUi.java')

# Export the installed ordered phrase manifest, not an old draft or built-in-only list.
# Serializing with the existing voice-file executor prevents reading half an import.
p=java/'VoiceManager.java';s=p.read_text()
a=s.index('    public String completeLibraryScript(){')
b=s.index('    /**\n     * Import a full WAV generated',a)
s=s[:a]+r'''    public static final class ScriptExport {
        public final String text,source;
        public final int phrases;
        private final List<String> manifest;
        ScriptExport(List<String> list,String source) {
            manifest=Collections.unmodifiableList(new ArrayList<>(list));
            text=VoiceScriptText.format(manifest);phrases=manifest.size();this.source=source;
        }
    }
    public interface ScriptExportCallback { void done(ScriptExport export,String error); }

    private ScriptExport snapshotEntireScript(Profile profile) throws Exception {
        File dir=ExactPhrasePack.profileDir(context,profile.id);
        List<String> phrases;String source;
        if(new File(dir,"manifest.json").exists()) {
            // An incomplete installed library is an error, not permission to copy
            // a stale/base-only script and misleadingly call it the whole library.
            phrases=ExactPhrasePack.phrases(context,profile.id);
            if(phrases.isEmpty())throw new IOException("The installed voice manifest is empty.");
            source=profile.name;
        }else{
            File[] clips=dir.listFiles((d,n)->n.startsWith("clip-")&&n.endsWith(".wav"));
            if(clips!=null&&clips.length>0)throw new IOException("The voice manifest is missing; no partial script was copied.");
            phrases=savedCompleteTemplate();
            source=phrases.isEmpty()?"Built-in base":completeTemplateSource();
            if(phrases.isEmpty())phrases=canonicalPhrases();
        }
        // Validate exact order and uniqueness against the same parser used at import.
        List<String> validated=VoiceScriptText.parse(VoiceScriptText.format(phrases));
        return new ScriptExport(validated,source);
    }

    public String completeLibraryScript(){
        try{
            ScriptExport export=snapshotEntireScript(activeProfile());
            saveCompleteTemplate(export.manifest,export.source);
            return export.text;
        }catch(Exception e){throw new IllegalStateException(safe(e),e);}
    }

    /** Capture the selected source and queue behind pending WAV imports. */
    public void exportEntireScript(ScriptExportCallback callback){
        if(callback==null)return;
        final Profile selected=activeProfile();
        files.execute(()->{
            try{
                ScriptExport export=snapshotEntireScript(selected);
                saveCompleteTemplate(export.manifest,export.source);
                main.post(()->callback.done(export,""));
            }catch(Exception e){
                String error=safe(e);
                main.post(()->callback.done(null,error));
            }
        });
    }

'''+s[b:]
p.write_text(s)

# Dashboard shortcut; Voice Settings gets a fixed, always-visible copy panel.
p=java/'MainActivity.java';s=p.read_text()
s=rep(s,'    private WebView attachedWeb;', '    private WebView attachedWeb;\n    private AlertDialog voiceDialog;')
s=rep(s,'        buttonRow(dashboard,button("Voice settings",false,this::voiceLibrary),button("Diagnostics",false,this::diagnostics));',
'''        addCard(dashboard,new EntireScriptCopyUi(this,engine.voices));
        buttonRow(dashboard,button("Voice settings",false,this::voiceLibrary),button("Diagnostics",false,this::diagnostics));''')
s=rep(s,'    private void voiceLibrary() {','    private void voiceLibrary() {\n        if(voiceDialog!=null)voiceDialog.dismiss();')
a=s.index('        addCard(form,button("Copy ENTIRE current library script",true,()->{')
b=s.index('        form.addView(text("Saved complete template',a)
s=s[:a]+s[b:]
a=s.index('        ScrollView scroll=new ScrollView(this);scroll.addView(form);',s.index('    private void voiceLibrary()'))
b=s.index('    private void showAddVoicePhrases(){',a)
s=s[:a]+'''        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        LinearLayout shell=vertical();
        EntireScriptCopyUi copy=new EntireScriptCopyUi(this,engine.voices);
        copy.setPadding(dp(20),dp(6),dp(20),0);
        shell.addView(copy,new LinearLayout.LayoutParams(-1,-2));
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        voiceDialog=new AlertDialog.Builder(this).setTitle("Voice settings").setView(shell)
                .setPositiveButton("Close",null).create();
        voiceDialog.show();
        if(voiceDialog.getWindow()!=null)voiceDialog.getWindow().setLayout(-1,
                Math.round(getResources().getDisplayMetrics().heightPixels*.88f));
    }

'''+s[b:]
p.write_text(s)

# The speech-control screen shares the same action so either settings route works.
p=java/'SpeechSettingsUi.java';s=p.read_text()
s=rep(s,'        f.addView(text("EDIT WHAT FELICITY SAYS",12,ACCENT));',
'''        f.addView(new EntireScriptCopyUi(activity,engine.voices));
        f.addView(text("EDIT WHAT "+engine.voices.activeName().toUpperCase(Locale.ROOT)+" SAYS",12,ACCENT));''')
p.write_text(s)

shutil.copyfile(here/'EntireScriptCopyButtonTest.java',root/'app/src/androidTest/java/au/com/roningroup/patrollink/EntireScriptCopyButtonTest.java')
assert 'versionCode 137' in (root/'app/build.gradle').read_text()
assert 'Copy ENTIRE current library script' not in (java/'MainActivity.java').read_text()
assert 'exportEntireScript' in (java/'VoiceManager.java').read_text()
assert 'Copy entire voice script' in (java/'EntireScriptCopyUi.java').read_text()
