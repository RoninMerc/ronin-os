from pathlib import Path
import sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'

def rep(path,old,new):
    s=path.read_text()
    if old not in s: raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:180])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 149','versionCode 150')
rep(root/'app/build.gradle',"versionName '1.1.39'","versionName '1.1.40'")

p=java/'CheckpointProximityUi.java'; s=p.read_text()

old='''        load();ensureDefaults();save();lapEpoch=prefs.getLong("lap_epoch",0);
    }'''
new='''        load();ensureDefaults();save();
        lapEpoch=prefs.getLong("lap_epoch",0);
        if(lapEpoch<=0){
            lapEpoch=System.currentTimeMillis();
            prefs.edit().putLong("lap_epoch",lapEpoch).apply();
        }
    }'''
if old not in s: raise RuntimeError('constructor lapEpoch anchor missing')
s=s.replace(old,new,1)

old='''    private boolean completed(String id){return prefs.getLong("done_"+id,0)>=lapEpoch&&lapEpoch>0;}'''
new='''    private boolean completed(String id){
        long done=prefs.getLong("done_"+id,0);
        if(done<=0)return false;
        return lapEpoch<=0 || done>=lapEpoch;
    }'''
if old not in s: raise RuntimeError('completed anchor missing')
s=s.replace(old,new,1)

old='''        Button done=button("CHECKPOINT COMPLETE",()->{
            prefs.edit().putLong("done_"+c.id,System.currentTimeMillis()).apply();
            activeId="";scanDialogId="";
            if(scanDialog!=null)scanDialog.dismiss();
            scanDialog=null;
            Toast.makeText(activity,c.name+" completed for this Patrol Link lap.",Toast.LENGTH_SHORT).show();
            tick();
        });'''
new='''        Button done=button("CHECKPOINT COMPLETE",()->{
            long now=System.currentTimeMillis();
            prefs.edit().putLong("done_"+c.id,now).commit();
            activeId="";scanDialogId="";
            AlertDialog closing=scanDialog;
            scanDialog=null;
            if(closing!=null&&closing.isShowing())closing.dismiss();
            Toast.makeText(activity,c.name+" completed for this Patrol Link lap.",Toast.LENGTH_SHORT).show();
            // Let Android fully remove the modal before the next proximity pass.
            new Handler(Looper.getMainLooper()).postDelayed(this::tick,350L);
        });'''
if old not in s: raise RuntimeError('scan modal completion anchor missing')
s=s.replace(old,new,1)

# Extra guard: never leave a completed checkpoint modal open if a GPS tick lands at the same time.
old='''    private void showScanDialog(Checkpoint c,float metres){
        if(c==null)return;
        if(scanDialog!=null&&scanDialog.isShowing()&&c.id.equals(scanDialogId))return;'''
new='''    private void showScanDialog(Checkpoint c,float metres){
        if(c==null||completed(c.id)){
            if(scanDialog!=null&&scanDialog.isShowing())scanDialog.dismiss();
            scanDialog=null;scanDialogId="";
            return;
        }
        if(scanDialog!=null&&scanDialog.isShowing()&&c.id.equals(scanDialogId))return;'''
if old not in s: raise RuntimeError('showScanDialog anchor missing')
s=s.replace(old,new,1)

p.write_text(s)
print('Applied v1.1.40 checkpoint modal dismissal fix: persistent completion, auto lap init, delayed resume, same-checkpoint reopen guard.')
