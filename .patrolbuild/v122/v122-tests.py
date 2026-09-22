from pathlib import Path
import sys,shutil
root=Path(sys.argv[1]);new=Path(__file__).resolve().parent
j=root/'app/src/test/java/au/com/roningroup/patrollink';a=root/'app/src/androidTest/java/au/com/roningroup/patrollink'
shutil.copy2(new/'SpeechRulesTest.java',j);shutil.copy2(new/'SpeechPreferencesTest.java',a);shutil.copy2(new/'SpeechEditorTest.java',a)
p=a/'VoicePlaybackTest.java';s=p.read_text()
s=s.replace('main(()->vm.speak(first));awaitReadout(vm,VoiceReadout.of(first));','main(()->{vm.wording.nickname("T.MURD","Tristan");vm.wording.saveRule(null,"Third warning parking breach","Parking breach, third warning",false,true);vm.speak(first);});awaitReadout(vm,"Guard Tristan recorded Parking breach, third warning at Impeccable.");')
s=s.replace('main(()->vm.speak(second));awaitReadout(vm,VoiceReadout.of(second));','main(()->{vm.wording.nickname("D.ROGERS1","Test guard");vm.speak(second);});awaitReadout(vm,"Guard Test guard recorded Parking Breach 3 at Solo.");')
s=s.replace('manager.get().stop();});','manager.get().stop();manager.get().wording.nickname("T.MURD","");manager.get().wording.nickname("D.ROGERS1","");for(SpeechRules.Rule rule:manager.get().wording.rules())if(rule.original.equals("Third warning parking breach"))manager.get().wording.removeRule(rule.id);});')
p.write_text(s)
assert 'Guard Tristan recorded Parking breach, third warning' in s
print('Added 18 wording unit tests, storage/UI instrumentation and custom-wording actual playback checks.')
