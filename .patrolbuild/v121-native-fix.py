from pathlib import Path
import sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'
p=java/'LocalVoiceService.java';s=p.read_text()
old='model.generateWithConfigAndCallback(text,c,samples->epoch.get()==job?1:0)'
new='model.generateWithConfigAndCallback(text,c,new GenerationCallback(epoch,job))'
assert old in s;s=s.replace(old,new,1)
anchor='    private void ensureModel()throws Exception {'
callback='''    /** JNI resolves invoke(float[]) -> Integer; an erased Java lambda is not sufficient. */
    public static final class GenerationCallback implements kotlin.jvm.functions.Function1<float[], Integer> {
        private final AtomicLong generation;
        private final long expected;
        public GenerationCallback(AtomicLong generation,long expected){this.generation=generation;this.expected=expected;}
        @Override public Integer invoke(float[] samples){return generation.get()==expected?1:0;}
    }

'''
assert anchor in s;s=s.replace(anchor,callback+anchor,1)
s=s.replace('c.setNumSteps(5);','c.setNumSteps(10);')
s=s.replace('extra.put("temperature","0.7");','extra.put("temperature","0.7");extra.put("seed","42");')
s=s.replace('pocket-20260126-steps5','pocket-20260126-steps10-seed42')
p.write_text(s)
p=java/'VoiceManager.java';s=p.read_text()
s=s.replace('enqueue("T Murd. Third warning parking breach. Impeccable.");','enqueue(VoiceReadout.format("T.MURD","Third warning parking breach","Impeccable"));')
p.write_text(s)
p=root/'app/src/androidTest/java/au/com/roningroup/patrollink/LocalSpeechTest.java';s=p.read_text()
s=s.replace('public void onServiceDisconnected(ComponentName n){}','public void onServiceDisconnected(ComponentName n){Bundle b=new Bundle();b.putString("id","default");b.putString("state","ERROR");b.putString("detail","The speech worker disconnected during the offline test.");events.add(b);}')
p.write_text(s)
print('Typed JNI callback, deterministic speech seed and complete live/test readout installed.')
