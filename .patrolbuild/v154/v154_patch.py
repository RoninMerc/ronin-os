from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'; here=Path(__file__).parent

def rep(path,old,new):
    s=path.read_text()
    if old not in s: raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:160])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 153','versionCode 154')
rep(root/'app/build.gradle',"versionName '1.1.43'","versionName '1.1.44'")

for name in ['SupervisorPin.java','FleetUi.java','CheckpointSyncClient.java']:
    shutil.copyfile(here/name,java/name)

p=java/'CheckpointProximityUi.java'
s=p.read_text()

s=s.replace('    private String closedId="";\n    private long lapEpoch;',
'''    private String closedId="";
    private long lapEpoch;
    private long lastSyncPoll;''',1)

s=s.replace('''    public void tick(){
        if(card==null)return;''',
'''    public void tick(){
        if(card==null)return;
        syncTick();''',1)

old_save='''    private void save(){
        JSONArray a=new JSONArray();
        try{
            for(Checkpoint c:checkpoints){JSONObject o=new JSONObject();o.put("id",c.id);o.put("name",c.name);o.put("group",c.group);o.put("order",c.order);o.put("lat",c.lat);o.put("lon",c.lon);o.put("radius",c.radius);o.put("photoPath",c.photoPath==null?"":c.photoPath);o.put("assetPhoto",c.assetPhoto==null?"":c.assetPhoto);a.put(o);}
        }catch(Exception ignored){}
        prefs.edit().putString(KEY,a.toString()).apply();
    }'''

new_save='''    private void save(){
        JSONArray local=new JSONArray();
        try{
            for(Checkpoint c:checkpoints){
                JSONObject o=new JSONObject();
                o.put("id",c.id);o.put("name",c.name);o.put("group",c.group);o.put("order",c.order);
                o.put("lat",c.lat);o.put("lon",c.lon);o.put("radius",c.radius);
                o.put("photoPath",c.photoPath==null?"":c.photoPath);
                o.put("assetPhoto",c.assetPhoto==null?"":c.assetPhoto);
                local.put(o);
            }
        }catch(Exception ignored){}
        prefs.edit().putString(KEY,local.toString()).apply();
        if(CheckpointSyncClient.master(activity))CheckpointSyncClient.publish(activity,syncPayload());
    }

    private JSONArray syncPayload(){
        JSONArray out=new JSONArray();
        try{
            for(Checkpoint c:checkpoints){
                JSONObject o=new JSONObject();
                o.put("id",c.id).put("name",c.name).put("group",c.group).put("order",c.order)
                    .put("lat",c.lat).put("lon",c.lon).put("radius",c.radius);
                byte[] photoBytes=photoBytes(c);
                o.put("photoBase64",photoBytes.length==0?"":android.util.Base64.encodeToString(photoBytes,android.util.Base64.NO_WRAP));
                out.put(o);
            }
        }catch(Exception ignored){}
        return out;
    }

    private byte[] photoBytes(Checkpoint c){
        try{
            InputStream in=null;
            if(c.photoPath!=null&&!c.photoPath.isEmpty()){
                File f=new File(c.photoPath);if(f.isFile())in=new FileInputStream(f);
            }
            if(in==null&&c.assetPhoto!=null&&!c.assetPhoto.isEmpty())in=activity.getAssets().open("checkpoint_photos/"+c.assetPhoto);
            if(in==null)return new byte[0];
            try(InputStream use=in;ByteArrayOutputStream b=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];
                for(int n;(n=use.read(buf))>0;){
                    if(b.size()+n>650000)return new byte[0];
                    b.write(buf,0,n);
                }
                return b.toByteArray();
            }
        }catch(Exception e){return new byte[0];}
    }

    private void syncTick(){
        if(CheckpointSyncClient.master(activity)){
            long now=SystemClock.elapsedRealtime();
            if(now-lastSyncPoll>60000L){
                lastSyncPoll=now;
                CheckpointSyncClient.publish(activity,syncPayload());
            }
            return;
        }
        if(!CheckpointSyncClient.subscriber(activity))return;
        long now=SystemClock.elapsedRealtime();
        if(now-lastSyncPoll<15000L)return;
        lastSyncPoll=now;
        CheckpointSyncClient.poll(activity,(data,error)->{
            if(error!=null||data==null)return;
            long received=data.optLong("receivedAt",0);
            long applied=prefs.getLong("checkpoint_sync_received_at",0);
            JSONArray remote=data.optJSONArray("checkpoints");
            if(received<=0||received<=applied||remote==null)return;
            applyRemote(remote,received);
        });
    }

    private void applyRemote(JSONArray remote,long receivedAt){
        ArrayList<Checkpoint> incoming=new ArrayList<>();
        try{
            File dir=new File(activity.getFilesDir(),"checkpoint_photos");
            if(!dir.exists())dir.mkdirs();
            for(int i=0;i<remote.length();i++){
                JSONObject o=remote.optJSONObject(i);if(o==null)continue;
                String id=o.optString("id"),name=o.optString("name"),group=o.optString("group");
                if(id.isEmpty()||name.isEmpty()||!(group.equals("PBC1")||group.equals("PBC2")||group.equals("PBC3")))continue;
                String photoPath="";
                String b64=o.optString("photoBase64","");
                if(!b64.isEmpty()){
                    try{
                        byte[] data=android.util.Base64.decode(b64,android.util.Base64.DEFAULT);
                        if(data.length>0&&data.length<=650000){
                            File f=new File(dir,"synced_"+slug(id)+".jpg");
                            try(FileOutputStream out=new FileOutputStream(f)){out.write(data);}
                            photoPath=f.getAbsolutePath();
                        }
                    }catch(Exception ignored){}
                }
                incoming.add(new Checkpoint(id,name,group,o.optInt("order",900),
                    o.optDouble("lat"),o.optDouble("lon"),
                    (float)o.optDouble("radius",DEFAULT_TRIGGER),photoPath,""));
            }
            if(incoming.isEmpty())return;
            checkpoints.clear();checkpoints.addAll(incoming);
            JSONArray local=new JSONArray();
            for(Checkpoint c:checkpoints){
                JSONObject o=new JSONObject();
                o.put("id",c.id).put("name",c.name).put("group",c.group).put("order",c.order)
                    .put("lat",c.lat).put("lon",c.lon).put("radius",c.radius)
                    .put("photoPath",c.photoPath).put("assetPhoto","");
                local.put(o);
            }
            prefs.edit().putString(KEY,local.toString()).putLong("checkpoint_sync_received_at",receivedAt).apply();
            activeId="";closedId="";
            if(scanDialog!=null&&scanDialog.isShowing())scanDialog.dismiss();
            scanDialog=null;scanDialogId="";
            tick();
        }catch(Exception ignored){}
    }'''

if old_save not in s: raise RuntimeError('Checkpoint save block missing')
s=s.replace(old_save,new_save,1)

old_note='''        TextView note=text(supervisor()?"Manage PBC1 separately from the combined PBC2 + PBC3 patrol. Rename checkpoints, change their route order, attach/replace photos and re-register GPS locations at any time.":"Checkpoint coordinates and route settings are supervisor-managed.",12,0xff9daebb,false);note.setPadding(0,dp(8),0,dp(12));form.addView(note);'''
new_note='''        String sync=CheckpointSyncClient.status(activity);
        TextView note=text(supervisor()
            ?"Master owns the shared checkpoint set. Changes to names, PBC group, order, GPS, scan range and QR photo sync automatically to Patrol 1, 2 and 3.\n\n"+sync
            :"Checkpoint configuration is supplied by the Master phone.\n\n"+sync,
            12,0xff9daebb,false);
        note.setPadding(0,dp(8),0,dp(12));form.addView(note);'''
if old_note not in s: raise RuntimeError('Checkpoint manager note missing')
s=s.replace(old_note,new_note,1)

p.write_text(s)

print('Applied v1.1.44: Master has no patrol assignment, supervisor PIN lock preserved, and checkpoint config/QR photos sync from Master to Patrol 1/2/3.')
