from pathlib import Path
import hashlib,re,shutil,sys,json
root=Path(sys.argv[1]);payload=Path(__file__).parent;j=root/'app/src/main/java/au/com/roningroup/patrollink'
def rep(path,old,new):
 s=path.read_text();assert s.count(old)==1,(str(path),old[:120],s.count(old));path.write_text(s.replace(old,new,1))
protected=['PatrolEngine.java','Observation.java','FeedReducer.java','TimeParser.java','TlsPolicy.java']
before={n:hashlib.sha256((j/n).read_bytes()).hexdigest() for n in protected}
rep(root/'app/build.gradle','versionCode 122','versionCode 123');rep(root/'app/build.gradle',"versionName '1.1.12'","versionName '1.1.13'")
p=root/'app/build.gradle';s=p.read_text();s=s.replace("    implementation files('libs/local-speech.aar')\n",'').replace("    implementation 'org.jetbrains.kotlin:kotlin-stdlib:2.1.20'\n",'');p.write_text(s)
for name in ['ExactWav.java','RecordedSpeechStore.java','RecordingSettingsUi.java','SpeechRules.java','VoiceManager.java']:shutil.copyfile(payload/name,j/name)
p=j/'VoiceManager.java'
rep(p,'stage="Playing "+activeName()+" recording";deadline=', 'stage="Playing "+activeName()+" recording";if(deadline!=null)handler.removeCallbacks(deadline);deadline=')
rep(p,'private Runnable listener,deadline;private int completed,failed,missing;', 'private Runnable listener,deadline;private volatile int completed,failed,missing;')
rep(j/'RecordingSettingsUi.java','((ClipboardManager)activity.getSystemService','((android.content.ClipboardManager)activity.getSystemService')
(j/'LocalVoiceService.java').unlink()
p=root/'app/src/main/AndroidManifest.xml';s=p.read_text();s=re.sub(r'<service\s+android:name="\.LocalVoiceService"[^>]*/>','',s);p.write_text(s)
for path in [root/'app/src/main/assets/speech-model',root/'app/libs']:shutil.rmtree(path,ignore_errors=True)
main=j/'MainActivity.java'
rep(main,'    private SpeechSettingsUi speechEditor;','    private SpeechSettingsUi speechEditor;\n    private RecordingSettingsUi recordedEditor;\n    public void recordedAnnouncements(){if(recordedEditor!=null)recordedEditor.close();recordedEditor=RecordingSettingsUi.show(this,engine);}')
rep(main,'        addCard(form,button("Alert wording & guard nicknames",false,this::speechWording));','        addCard(form,button("Exact recorded announcements",true,this::recordedAnnouncements));\n        addCard(form,button("Alert wording & guard nicknames",false,this::speechWording));')
rep(main,'        addCard(dashboard,button("Spoken wording & nicknames",false,this::speechWording));','        addCard(dashboard,button("Spoken wording & nicknames",false,this::speechWording));\n        addCard(dashboard,button("Exact recorded announcements",false,this::recordedAnnouncements));')
rep(main,'        super.onActivityResult(requestCode,resultCode,data);','        super.onActivityResult(requestCode,resultCode,data);\n        if(requestCode==RecordingSettingsUi.PICK_SOURCE){if(recordedEditor!=null)recordedEditor.onResult(resultCode,data);return;}')
rep(main,'    @Override public void onDestroy() { if(speechEditor!=null)speechEditor.close();','    @Override public void onDestroy() { if(recordedEditor!=null)recordedEditor.close();if(speechEditor!=null)speechEditor.close();')
p=main;s=p.read_text()
s=s.replace('Full announcements are generated on this device from the selected voice recording. No voice account, API or second voice. The first start installs the bundled speech model locally; your patrol feed continues separately.','Exact recorded mode plays the original WAV sections only. No cloning, generated approximation, voice API or system voice. Open Exact recorded announcements to copy your real phrase script and assign the recordings. Phrases not recorded for the selected voice are visibly flagged, not replaced with generic speech.')
s=s.replace('Select the WAV generated with your chosen voice. A short speech reference is prepared locally from the recording. It is not uploaded to a voice service.','Select the original 16-bit PCM WAV from your chosen voice website. The full recording is copied unchanged. Open Exact recorded announcements to assign its spoken sections to the final alert phrases. It is not a voice-cloning reference.')
s=s.replace('"Test full readout"','"Test assigned recording"')
s=s.replace('Every spoken announcement is generated from the selected local voice reference and includes the guard, full Issue Monitor activity and property. Speech runs in a separate process from the feed. Network status stays visual; the app does not play connection-status clips on activity updates.','Enabled wording replacements are the complete announcement: no guard, location or other wrapper is added. Speech plays only original recorded phrases belonging to the selected voice; missing phrases are flagged and never synthesised or replaced with generic speech. Recordings are imported and cut locally without changing their PCM samples. Network status remains visual.')
s=s.replace('This removes its local recordings and selects Felicity. The original file in Downloads is not changed.','This removes the voice from the selectable list and selects Felicity. Your original file in Downloads is not changed.')
p.write_text(s)
u=j/'SpeechSettingsUi.java'
rep(u,'"Replace this phrase inside longer alerts"','"Also match this wording inside longer alerts"')
rep(u,'"Whole-alert matching ignores case and extra spaces. Phrase matching preserves the remaining words; the longest overlapping phrase wins. The property/location is still included."','"When enabled, SAY THIS INSTEAD is the entire announcement. No guard, location, introduction or unedited remainder is added. The checkbox above changes matching only, not what is spoken."')
rep(u,'"PREVIEW — EXAMPLE ONLY"','"COMPLETE ANNOUNCEMENT — EXACT WORDS"')
rep(u,'preview.setText(prefs.preview(id,f,s,phrase.isChecked(),enabled.isChecked(),example(item,f)));','preview.setText(enabled.isChecked()?SpeechRules.clean(s):prefs.preview(id,f,s,phrase.isChecked(),false,example(item,f)));')
rep(u,'"Preview queued in "+engine.voices.activeName()+"."','"Plays the assigned original recording only. Missing recordings are shown in voice status."')
rep(u,'        row(root,add,button("Refresh list",()->{prefs.remember(engine.recent());render();}));','        row(root,add,button("Refresh list",()->{prefs.remember(engine.recent());render();}));\n        root.addView(button("Record these exact phrases",()->((MainActivity)activity).recordedAnnouncements()));')
p=u;s=p.read_text().replace('" · phrase replacement"','" · match inside longer alerts"').replace('" · whole-alert replacement"','" · complete spoken replacement"');p.write_text(s)
a=root/'app/src/androidTest/java/au/com/roningroup/patrollink';a.mkdir(parents=True,exist_ok=True)
for p in a.glob('*.java'):
 if p.name not in ['RefreshLifecycleTest.java','TlsRecoveryTest.java']:p.unlink()
shutil.copyfile(payload/'ExactRecordingAndroidTest.java',a/'ExactRecordingAndroidTest.java')
t=root/'app/src/test/java/au/com/roningroup/patrollink';t.mkdir(parents=True,exist_ok=True)
for p in t.glob('*.java'):
 if p.name not in ['CoreTest.java','CoreChecks.java','SnapshotPolicyTest.java','TlsPolicyTest.java']:p.unlink()
shutil.copyfile(payload/'ExactSpeechTest.java',t/'ExactSpeechTest.java')
for name,sha in before.items():assert hashlib.sha256((j/name).read_bytes()).hexdigest()==sha,(name,'unexpected monitor change')
for p in j.glob('*.java'):
 for bad in ['new TextToSpeech','android.speech.tts','api.elevenlabs','generateWithConfig','new OfflineTts','R.raw.felicity']:
  assert bad not in p.read_text(),(p.name,bad)
(root/'v123-core-preservation.json').write_text(json.dumps(before,indent=2))
print('Exact recorded mode applied. Monitor, parser, TLS, activity-selection sources unchanged.')
