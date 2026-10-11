from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'; here=Path(__file__).parent

def rep(path,old,new):
    s=path.read_text()
    if old not in s: raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:180])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 155','versionCode 156')
rep(root/'app/build.gradle',"versionName '1.1.45'","versionName '1.1.46'")

shutil.copyfile(here/'CheckpointSyncClient.java',java/'CheckpointSyncClient.java')

p=java/'CheckpointProximityUi.java'; s=p.read_text()

s=s.replace(
'''    private long lapEpoch;
    private long lastSyncPoll;''',
'''    private long lapEpoch;
    private long lastSyncPoll;
    private boolean syncInitialising=true;''',1)

old_ctor='''        if(lapEpoch<=0){
            lapEpoch=System.currentTimeMillis();
            prefs.edit().putLong("lap_epoch",lapEpoch).apply();
        }
    }'''
new_ctor='''        if(lapEpoch<=0){
            lapEpoch=System.currentTimeMillis();
            prefs.edit().putLong("lap_epoch",lapEpoch).apply();
        }
        syncInitialising=false;
    }'''
if old_ctor not in s: raise RuntimeError('constructor end anchor missing')
s=s.replace(old_ctor,new_ctor,1)

s=s.replace(
'''        prefs.edit().putString(KEY,local.toString()).apply();
        if(CheckpointSyncClient.master(activity))CheckpointSyncClient.publish(activity,syncPayload());''',
'''        prefs.edit().putString(KEY,local.toString()).apply();
        if(!syncInitialising&&CheckpointSyncClient.master(activity)&&mappedCount()>0)
            CheckpointSyncClient.publish(activity,syncPayload());''',1)

s=s.replace(
'''        if(CheckpointSyncClient.master(activity)){
            long now=SystemClock.elapsedRealtime();
            if(now-lastSyncPoll>60000L){
                lastSyncPoll=now;
                CheckpointSyncClient.publish(activity,syncPayload());
            }
            return;
        }''',
'''        if(CheckpointSyncClient.master(activity)){
            long now=SystemClock.elapsedRealtime();
            if(mappedCount()>0&&now-lastSyncPoll>60000L){
                lastSyncPoll=now;
                CheckpointSyncClient.publish(activity,syncPayload());
            }
            return;
        }''',1)

anchor='''    private void applyRemote(JSONArray remote,long receivedAt){'''
helpers='''    private int mappedCount(){
        int n=0;for(Checkpoint c:checkpoints)if(c.mapped())n++;return n;
    }

    private int photoCount(){
        int n=0;
        for(Checkpoint c:checkpoints)if(bitmapFor(c)!=null)n++;
        return n;
    }

    private void showSyncRecovery(){
        LinearLayout f=new LinearLayout(activity);f.setOrientation(LinearLayout.VERTICAL);f.setPadding(dp(18),dp(8),dp(18),dp(8));
        TextView state=text("LOCAL: "+checkpoints.size()+" checkpoints · "+mappedCount()+" mapped · "+photoCount()+" photos\\nREMOTE: "+CheckpointSyncClient.status(activity),13,0xffeef4f9,false);
        f.addView(state);f.addView(gap(10));

        f.addView(button("DOWNLOAD SHARED CHECKPOINTS",()->{
            state.setText("Downloading shared checkpoint set…");
            CheckpointSyncClient.download(activity,(data,error)->{
                if(error!=null){state.setText(error);return;}
                JSONArray remote=data==null?null:data.optJSONArray("checkpoints");
                if(remote==null||remote.length()==0){state.setText("Relay is reachable, but there is no shared checkpoint set yet.");return;}
                long received=data.optLong("receivedAt",System.currentTimeMillis());
                applyRemote(remote,received);
                state.setText("Downloaded "+remote.length()+" checkpoints. Local set now has "+mappedCount()+" mapped checkpoints.");
                Toast.makeText(activity,"Shared checkpoints loaded onto this phone.",Toast.LENGTH_LONG).show();
            });
        }));

        if(CheckpointSyncClient.master(activity)){
            f.addView(button("PUBLISH THIS MASTER CHECKPOINT SET",()->{
                if(mappedCount()==0){state.setText("Nothing published: this Master has no mapped checkpoints.");return;}
                CheckpointSyncClient.publish(activity,syncPayload());
                state.setText("Publishing "+checkpoints.size()+" checkpoints from Master…");
            }));
        }else{
            f.addView(button("RECOVER / UPLOAD THIS PHONE'S SAVED CHECKPOINTS",()->{
                if(mappedCount()==0){state.setText("This phone has no mapped checkpoint locations to recover.");return;}
                requireSupervisorPinThenRecover(state);
            }));
        }

        f.addView(gap(8));
        f.addView(text("Recovery is designed for the old patrol phone that still contains your original checkpoint setup. A patrol phone can seed the relay only while the shared relay does not already contain mapped checkpoints. Once recovered, the Samsung Master can use DOWNLOAD SHARED CHECKPOINTS and then becomes the authority.",12,0xff9daebb,false));
        ScrollView scroll=new ScrollView(activity);scroll.addView(f);
        new AlertDialog.Builder(activity).setTitle("Checkpoint sync / recovery").setView(scroll).setPositiveButton("Close",null).show();
    }

    private void requireSupervisorPinThenRecover(TextView state){
        if(!SupervisorPin.configured(activity)){
            state.setText("Supervisor PIN is not configured on this phone. Temporarily unlock/configure supervisor mode first.");
            return;
        }
        long locked=SupervisorPin.lockedSeconds(activity);
        if(locked>0){state.setText("Supervisor PIN locked for "+locked+" seconds.");return;}
        EditText pin=new EditText(activity);pin.setHint("Supervisor PIN");pin.setSingleLine(true);
        pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog d=new AlertDialog.Builder(activity).setTitle("Authorise checkpoint recovery").setView(pin)
            .setPositiveButton("Upload",null).setNegativeButton("Cancel",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            if(!SupervisorPin.verify(activity,pin.getText().toString())){
                state.setText("Incorrect supervisor PIN.");pin.setText("");return;
            }
            d.dismiss();state.setText("Uploading this phone's saved checkpoint set for recovery…");
            CheckpointSyncClient.recover(activity,syncPayload(),(data,error)->{
                if(error!=null){state.setText(error);return;}
                int accepted=data==null?0:data.optInt("accepted",0);
                state.setText("Recovery uploaded successfully · "+accepted+" checkpoints. Now use DOWNLOAD SHARED CHECKPOINTS on the Samsung Master.");
                Toast.makeText(activity,"Checkpoint recovery uploaded.",Toast.LENGTH_LONG).show();
            });
        }));d.show();
    }

    private void applyRemote(JSONArray remote,long receivedAt){'''
if anchor not in s: raise RuntimeError('applyRemote anchor missing')
s=s.replace(anchor,helpers,1)

manager_anchor='''        note.setPadding(0,dp(8),0,dp(12));form.addView(note);

        Spinner active=new Spinner(activity);'''
manager_new='''        note.setPadding(0,dp(8),0,dp(12));form.addView(note);
        form.addView(button("CHECKPOINT SYNC / RECOVERY",this::showSyncRecovery));
        form.addView(gap(10));

        Spinner active=new Spinner(activity);'''
if manager_anchor not in s: raise RuntimeError('manager sync button anchor missing')
s=s.replace(manager_anchor,manager_new,1)

p.write_text(s)
print('Applied v1.1.46 checkpoint recovery: prevents empty Master overwrite, adds explicit upload/download recovery controls, and lets Master import shared checkpoints.')
