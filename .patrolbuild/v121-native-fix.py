from pathlib import Path
import sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'
p=java/'LocalVoiceService.java';s=p.read_text()
old='model.generateWithConfigAndCallback(text,c,samples->epoch.get()==job?1:0)'
new='model.generateWithConfigAndCallback(text,c,new GenerationCallback(epoch,job))'
assert old in s;s=s.replace(old,new,1)
anchor='    private void ensureModel()throws Exception {'
callback='''    /** JNI explicitly resolves invoke(float[]) -> Integer; an erased Java lambda is not sufficient. */
    public static final class GenerationCallback implements kotlin.jvm.functions.Function1<float[], Integer> {
        private final AtomicLong generation;
        private final long expected;
        public GenerationCallback(AtomicLong generation,long expected){this.generation=generation;this.expected=expected;}
        @Override public Integer invoke(float[] samples){return generation.get()==expected?1:0;}
    }

'''
assert anchor in s;s=s.replace(anchor,callback+anchor,1);p.write_text(s)
# If the worker is killed, report an explicit test error rather than wasting a full generation timeout.
p=root/'app/src/androidTest/java/au/com/roningroup/patrollink/LocalSpeechTest.java';s=p.read_text()
s=s.replace('public void onServiceDisconnected(ComponentName n){}','public void onServiceDisconnected(ComponentName n){Bundle b=new Bundle();b.putString("id","default");b.putString("state","ERROR");b.putString("detail","The speech worker disconnected during the offline test.");events.add(b);}')
p.write_text(s)
print('Typed JNI generation callback installed; cancellation still active.')
