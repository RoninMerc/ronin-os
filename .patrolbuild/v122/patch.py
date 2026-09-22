from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]);java=root/'app/src/main/java/au/com/roningroup/patrollink';payload=Path(__file__).parent

def rep(s,old,new):
    if old not in s: raise RuntimeError('Patch anchor missing: '+old[:180])
    return s.replace(old,new,1)

p=root/'app/build.gradle';s=p.read_text();s=rep(s,'versionCode 121','versionCode 122');s=rep(s,"versionName '1.1.11'","versionName '1.1.12'");p.write_text(s)
for name in ['SpeechRules.java','SpeechPreferences.java','SpeechSettingsUi.java']:shutil.copyfile(payload/name,java/name)

p=java/'VoiceManager.java';s=p.read_text()
s=rep(s,'private final Context context; private final SharedPreferences prefs;','private final Context context; private final SharedPreferences prefs;\n    public final SpeechPreferences speechSettings;\n    private volatile float lastPlaybackRate=1f;')
s=rep(s,'context = c.getApplicationContext(); prefs = p;','context = c.getApplicationContext(); prefs = p;\n        speechSettings = new SpeechPreferences(context);')
s=rep(s,'final String token = UUID.randomUUID().toString(), profile, text; final long queuedAt = SystemClock.elapsedRealtime();\n        Item(String profile, String text) { this.profile=profile; this.text=text; }','final String token = UUID.randomUUID().toString(), profile; String text; final long queuedAt = SystemClock.elapsedRealtime();\n        final Observation source; final float previewSpeed;\n        Item(String profile,String text){this(profile,text,null,0f);}\n        Item(String profile,String text,Observation source,float previewSpeed){this.profile=profile;this.text=text;this.source=source;this.previewSpeed=previewSpeed;}')
s=rep(s,'public void speak(Observation o){if(o==null)return;main.post(()->{if(prefs.getBoolean("voice",true))enqueue(AnnouncementText.format(o));});}','public void speak(Observation o){if(o==null)return;main.post(()->{if(prefs.getBoolean("voice",true)){speechSettings.remember(java.util.Collections.singletonList(o));enqueueRow(o);}});}')
s=rep(s,'"Voice test. " + AnnouncementText.format(','"Voice test. " + speechSettings.format(')
s=rep(s,'    private void enqueue(String text){','''    public float lastPlaybackRate(){return lastPlaybackRate;}
    public void preview(String text,float rate){
        main.post(()->{
            if(text==null||text.trim().isEmpty()||text.length()>12000){lastError="Preview is empty or too long";return;}
            if(queue.size()>=60){queue.removeFirst();dropped++;}
            queue.addLast(new Item(activeProfile().id,text,null,Math.max(.75f,Math.min(1.5f,rate))));ensureBound();dispatch();
        });
    }
    private void enqueueRow(Observation row){
        if(queue.size()>=60){queue.removeFirst();dropped++;}
        queue.addLast(new Item(activeProfile().id,"",row,0f));ensureBound();dispatch();
    }
    public void speechSettingsChanged(){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::speechSettingsChanged);return;}
        // Preserve queued matters and rerender them with the latest wording at dispatch.
        // Cancel only an unplayed generation; do not interrupt a sentence already playing.
        if(current!=null&&player==null&&current.source!=null){
            Item old=current;send(LocalVoiceService.CANCEL,null,null);
            if(deadline!=null)main.removeCallbacks(deadline);deadline=null;current=null;
            queue.addFirst(new Item(old.profile,"",old.source,0f));
        }
        dispatch();
    }
    private void enqueue(String text){''')
s=rep(s,'current=candidate;status="Generating complete update in "+activeName();lastError="";','if(candidate.source!=null)candidate.text=speechSettings.format(candidate.source);\n            current=candidate;status="Generating complete update in "+activeName();lastError="";')
s=rep(s,'status="Speaking full update in "+activeName();setDeadline(item.token,Math.max(10000,p.getDuration()+5000L));p.start();','''try {
                    float rate=item.previewSpeed>0?item.previewSpeed:speechSettings.speed();
                    p.setPlaybackParams(new PlaybackParams().allowDefaults().setPitch(1f).setSpeed(rate));
                    lastPlaybackRate=p.getPlaybackParams().getSpeed();
                    status="Speaking full update in "+activeName()+String.format(Locale.ROOT," at %.2fx",rate);
                    setDeadline(item.token,Math.max(10000,(long)(p.getDuration()/rate)+5000L));p.start();
                } catch(Exception ex){failCurrent("Could not apply the selected speech speed");}''')
s=rep(s,'"\\nLast generation (ms): "+lastGenerationMs','"\\nSpeech speed: "+speechSettings.speed()+"x\\nLast playback speed: "+lastPlaybackRate+"x\\nEdited alerts: "+speechSettings.overrides(SpeechPreferences.ALERT).size()+"\\nLast generation (ms): "+lastGenerationMs')
p.write_text(s)

p=java/'PatrolEngine.java';s=p.read_text();s=rep(s,'List<Observation> selected = FeedReducer.selected(observations, guards());','List<Observation> selected = FeedReducer.selected(observations, guards());\n                voices.speechSettings.remember(selected);');p.write_text(s)
p=java/'MainActivity.java';s=p.read_text()
s=rep(s,'buttonRow(dashboard,button("Voice settings",false,this::voiceLibrary),button("Diagnostics",false,this::diagnostics));','addCard(dashboard,button("Speech controls",false,()->new SpeechSettingsUi(this,engine).show()));\n        buttonRow(dashboard,button("Voice settings",false,this::voiceLibrary),button("Diagnostics",false,this::diagnostics));')
s=rep(s,'addCard(form,button("Test full update",true,engine.voices::test));','addCard(form,button("Speech controls: wording, names and speed",false,()->new SpeechSettingsUi(this,engine).show()));\n        addCard(form,button("Test full update",true,engine.voices::test));')
p.write_text(s)
p=java/'SpeechSettingsUi.java';s=p.read_text();s=rep(s,'private void saved(){engine.voices.stop();','private void saved(){engine.voices.speechSettingsChanged();');p.write_text(s)

unit=root/'app/src/test/java/au/com/roningroup/patrollink';instrument=root/'app/src/androidTest/java/au/com/roningroup/patrollink'
shutil.copyfile(payload/'SpeechRulesTest.java',unit/'SpeechRulesTest.java')
shutil.copyfile(payload/'SpeechEditorTest.java',instrument/'SpeechEditorTest.java')
# The existing real-model test now expects the configurable formatter, not the legacy one.
p=instrument/'VoicePipelineTest.java';s=p.read_text();s=s.replace('String text=AnnouncementText.format(new Observation(','String text=new SpeechPreferences(context).format(new Observation(');p.write_text(s)

assert 'versionCode 122' in (root/'app/build.gradle').read_text()
assert 'voices.speechSettings.remember(selected)' in (java/'PatrolEngine.java').read_text()
assert 'setPitch(1f).setSpeed(rate)' in (java/'VoiceManager.java').read_text()
assert ':voice' in (root/'app/src/main/AndroidManifest.xml').read_text()
for path in java.glob('*.java'):
    for bad in ['android.speech.tts','api.elevenlabs.io','xi-api-key']:
        assert bad not in path.read_text(),(path,bad)
