from pathlib import Path
import sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'

def rep(path,old,new):
    s=path.read_text()
    if old not in s: raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:180])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 146','versionCode 147')
rep(root/'app/build.gradle',"versionName '1.1.36'","versionName '1.1.37'")

p=java/'FeatherlessVoice.java'; s=p.read_text()

s=s.replace(
'private final ArrayDeque<String> queue=new ArrayDeque<>(); private MediaPlayer player; private AudioFocusRequest focus;',
'private final ArrayDeque<String> queue=new ArrayDeque<>(); private MediaPlayer player; private AudioFocusRequest focus; private boolean busy;'
)

old='''    public void speak(String text){
        String clean=SpeechRules.clean(text);if(clean.isEmpty())return;
        main.post(()->{if(!selected())return;if(!hasKey()){error="Featherless API key required";status="Cloud voice not configured";return;}if(queue.size()>=30){queue.removeFirst();dropped++;}queue.addLast(clean);dispatch();});
    }'''
new='''    public void speak(String text){
        String clean=SpeechRules.clean(text);if(clean.isEmpty())return;
        main.post(()->{
            if(!selected())return;
            if(!hasKey()){error="Featherless API key required";status="Cloud voice not configured";return;}
            // FIFO: never interrupt or supersede an announcement that is generating or playing.
            // Keep a generous bounded backlog only as a runaway-feed safeguard.
            if(queue.size()>=100){queue.removeFirst();dropped++;}
            queue.addLast(clean);
            dispatch();
        });
    }'''
if old not in s: raise RuntimeError('Featherless speak anchor missing')
s=s.replace(old,new,1)

old='''    private void dispatch(){if(player!=null)return;String next=queue.pollFirst();if(next==null)return;synthesize(next,false,(ok,msg)->{if(!ok){failed++;error=msg;status="Featherless voice failed";}dispatch();});}'''
new='''    private void dispatch(){
        if(busy||player!=null)return;
        String next=queue.pollFirst();if(next==null){status=selected()?"Featherless voice ready":"Exact recorded voice selected";return;}
        busy=true;
        synthesize(next,false,(ok,msg)->{
            // This callback fires only after synthesis/playback has fully ended.
            busy=false;
            if(!ok){failed++;error=msg;status="Featherless voice failed";}
            dispatch();
        });
    }'''
if old not in s: raise RuntimeError('Featherless dispatch anchor missing')
s=s.replace(old,new,1)

# Only the dispatch callback advances the queue. Remove the old internal double-dispatches.
s=s.replace('deliver(cb,false,msg);if(!test)dispatch();','deliver(cb,false,msg);')
s=s.replace('deliver(cb,true,"Featherless voice test passed · "+model()+" · "+voice());if(!test)dispatch();',
            'deliver(cb,true,"Featherless voice test passed · "+model()+" · "+voice());')
s=s.replace('deliver(cb,false,error);if(!test)dispatch();return true;',
            'deliver(cb,false,error);return true;')
s=s.replace('deliver(cb,false,error);if(!test)dispatch();}',
            'deliver(cb,false,error);}')

old='''    public void test(Callback cb){synthesize("Silvertracker update. Featherless cloud voice is working.",true,cb);}'''
new='''    public void test(Callback cb){
        main.post(()->{
            if(busy||player!=null||!queue.isEmpty()){
                deliver(cb,false,"Finish the queued patrol announcements before testing the voice.");
                return;
            }
            busy=true;
            synthesize("Silvertracker update. Featherless cloud voice is working.",true,(ok,msg)->{
                busy=false;deliver(cb,ok,msg);dispatch();
            });
        });
    }'''
if old not in s: raise RuntimeError('Featherless test anchor missing')
s=s.replace(old,new,1)

old='''    public void stop(){main.post(()->{queue.clear();release();status=selected()?"Cloud voice stopped":"Exact recorded voice selected";});}'''
new='''    public void stop(){main.post(()->{queue.clear();busy=false;release();status=selected()?"Cloud voice stopped":"Exact recorded voice selected";});}'''
if old not in s: raise RuntimeError('Featherless stop anchor missing')
s=s.replace(old,new,1)

s=s.replace(
'\nCloud generated/failed/dropped: "+generated+"/"+failed+"/"+dropped+"\nCloud status: "+status+',
'\nCloud generated/failed/dropped: "+generated+"/"+failed+"/"+dropped+"\nCloud queue pending: "+queue.size()+" · busy: "+busy+"\nCloud status: "+status+'
)

p.write_text(s)
print('Applied v1.1.37 Featherless FIFO: one generate/play cycle at a time; new updates queue until the current announcement finishes.')
