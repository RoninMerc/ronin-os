from pathlib import Path
import sys, shutil, hashlib
root=Path(sys.argv[1]); new=Path(__file__).resolve().parent
j=root/'app/src/main/java/au/com/roningroup/patrollink'

def rep(p,old,new):
    s=p.read_text(); assert s.count(old)==1,(p,old[:100],s.count(old));p.write_text(s.replace(old,new,1))

before={name:hashlib.sha256((j/name).read_bytes()).hexdigest() for name in ['LocalVoiceService.java','VoiceAudio.java','Observation.java','FeedReducer.java','TimeParser.java','TlsPolicy.java']}
for name in ['SpeechRules.java','SpeechPreferences.java','SpeechSettingsUi.java']:shutil.copy2(new/name,j/name)
# Dialog close must be idempotent across Activity recreation and delayed dismiss callbacks.
u=j/'SpeechSettingsUi.java'
rep(u,'dialog.setOnDismissListener(d->{engine.remove(this);if(editor!=null)editor.dismiss();});','dialog.setOnDismissListener(d->{engine.remove(this);dismissSafely(editor);editor=null;});')
rep(u,'    public void close(){if(editor!=null)editor.dismiss();if(dialog!=null)dialog.dismiss();engine.remove(this);}', '''    private static void dismissSafely(AlertDialog d){
        if(d==null||!d.isShowing())return;
        try{d.dismiss();}catch(IllegalArgumentException detached){/* Android already removed the window during recreation. */}
    }
    public void close(){
        engine.remove(this);AlertDialog child=editor,parent=dialog;editor=null;dialog=null;
        dismissSafely(child);dismissSafely(parent);
    }''')
rep(root/'app/build.gradle','versionCode 121','versionCode 122');rep(root/'app/build.gradle',"versionName '1.1.11'","versionName '1.1.12'")
rep(j/'VoiceReadout.java','        String g=spokenGuard(guard),a=clean(issue),p=clean(property);','        return formatSpokenName(spokenGuard(guard),issue,property);\n    }\n    public static String formatSpokenName(String name,String issue,String property){\n        String g=clean(name),a=clean(issue),p=clean(property);')
vm=j/'VoiceManager.java'
rep(vm,'        final String id,text,voice,reference;\n        Entry(String text,Profile p){id=UUID.randomUUID().toString();this.text=text;voice=p.id;reference=p.builtIn?"":p.path;}','        final String id,voice,reference; final Observation observation; String text;\n        Entry(String text,Profile p,Observation observation){id=UUID.randomUUID().toString();this.text=text;this.observation=observation;voice=p.id;reference=p.builtIn?"":p.path;}')
rep(vm,'    private final Context context;private final SharedPreferences prefs;','    public final SpeechPreferences wording;\n    private final Context context;private final SharedPreferences prefs;')
rep(vm,'context=c.getApplicationContext();prefs=p;migrate();','context=c.getApplicationContext();prefs=p;wording=new SpeechPreferences(context);migrate();')
rep(vm,'    public void speak(Observation o){if(o!=null)enqueue(VoiceReadout.of(o));}','    public void remember(Collection<Observation> entries){try{wording.remember(entries);}catch(RuntimeException e){/* A settings storage failure must not fail a feed read. */}}\n    public void speak(Observation o){if(o!=null)enqueue(null,o);}\n    public void preview(String text){lastError="";disconnects=0;ensureService();if(remote!=null&&!modelReady)send(LocalVoiceService.INIT,"init","","");enqueue(text,null);}')
rep(vm,'enqueue(VoiceReadout.format("T.MURD","Third warning parking breach","Impeccable"));','speak(new Observation("preview","T.MURD","Impeccable","Third warning parking breach","",0));')
rep(vm,'    private void enqueue(String text){\n        if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(()->enqueue(text));return;}','    private void enqueue(String text,Observation observation){\n        if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(()->enqueue(text,observation));return;}')
rep(vm,'queue.addLast(new Entry(text,activeProfile()));','queue.addLast(new Entry(text,activeProfile(),observation));')
rep(vm,'            stage="Generating full readout";send(LocalVoiceService.SPEAK,e.id,e.text,e.reference);','            if(e.observation!=null)e.text=wording.readout(e.observation);\n            stage="Generating full readout";send(LocalVoiceService.SPEAK,e.id,e.text,e.reference);')
rep(j/'PatrolEngine.java','                List<Observation> selected = FeedReducer.selected(observations, guards());','                List<Observation> selected = FeedReducer.selected(observations, guards());\n                voices.remember(selected); // Speech dictionary only; no change to feed matching or refresh.')
main=j/'MainActivity.java'
rep(main,'    private TextView voiceStateText;','    private TextView voiceStateText;\n    private SpeechSettingsUi speechEditor;\n    private void speechWording(){if(speechEditor!=null)speechEditor.close();speechEditor=SpeechSettingsUi.show(this,engine);}')
rep(main,'        addCard(dashboard,dangerButton("Exit / sign out",this::confirmExit));','        addCard(dashboard,button("Spoken wording & nicknames",false,this::speechWording));\n        addCard(dashboard,dangerButton("Exit / sign out",this::confirmExit));')
rep(main,'        addCard(form,button("Test full readout",true,engine.voices::test));','        addCard(form,button("Alert wording & guard nicknames",false,this::speechWording));\n        addCard(form,button("Test full readout",true,engine.voices::test));')
rep(main,'    @Override public void onDestroy() { engine.remove(this);','    @Override public void onDestroy() { if(speechEditor!=null)speechEditor.close();engine.remove(this);')
assets=root/'app/src/main/assets'; assets.mkdir(parents=True,exist_ok=True); (assets/'speech-defaults.json').write_text('{"nicknames":{}}\n')
for name,digest in before.items():assert hashlib.sha256((j/name).read_bytes()).hexdigest()==digest,(name,'unexpected core change')
for name in ['LocalVoiceService.java','VoiceManager.java','SpeechRules.java','SpeechPreferences.java']:
    s=(j/name).read_text()
    for bad in ['new TextToSpeech','api.elevenlabs','xi-api-key','HttpURLConnection','R.raw.felicity']:assert bad not in s,(name,bad)
(root/'CORE-PRESERVATION.json').write_text(__import__('json').dumps(before,indent=2))
print('v1.1.12 speech-only settings patch applied; core worker, parser and reducer unchanged.')
