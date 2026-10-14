package au.com.roningroup.patrollink;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.location.Location;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

/**
 * GPS proximity assistant for physical SilverTrack QR checkpoints.
 * This never creates a SilverTrack scan. It only provides proximity prompts,
 * supervisor-managed checkpoint metadata/photos and full-screen scan prompts.
 */
public final class CheckpointProximityUi {
    public static final int PHOTO_REQUEST=7245;
    private static final String PREFS="patrol_checkpoint_proximity_v1", KEY="checkpoints_json";
    private static final float DEFAULT_TRIGGER=70f, CLEAR_RADIUS=140f;
    private static final List<String> ROUTE_IDS=Arrays.asList(
        "seal-circuit","rec-centre","slipstream","bobsled","christina","solo-place",
        "impeccable","quest","eolo","elusive","serenade"
    );

    private final Activity activity;
    private final SharedPreferences prefs;
    private final ArrayList<Checkpoint> checkpoints=new ArrayList<>();
    private LinearLayout card;
    private ImageView photo;
    private TextView title,distance,hint;
    private Button complete;
    private String activeId="",pendingPhotoId="";
    private AlertDialog scanDialog;
    private String scanDialogId="";
    private String closedId="";
    private long lapEpoch;
    private long lastSyncPoll;
    private boolean syncInitialising=true;
    private static final java.util.concurrent.ExecutorService PHOTO_IO=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Handler photoUi=new Handler(Looper.getMainLooper());

    static final class Checkpoint {
        String id,name,group,photoPath,assetPhoto;
        double lat,lon;
        float radius;
        int order;
        Checkpoint(String id,String name,String group,int order,double lat,double lon,float radius,String photoPath,String assetPhoto){
            this.id=id;this.name=name;this.group=group;this.order=order;this.lat=lat;this.lon=lon;this.radius=radius;this.photoPath=photoPath;this.assetPhoto=assetPhoto;
        }
        boolean mapped(){return Math.abs(lat)>0.000001||Math.abs(lon)>0.000001;}
    }

    public CheckpointProximityUi(Activity a){
        activity=a;prefs=a.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        load();ensureDefaults();save();
        lapEpoch=prefs.getLong("lap_epoch",0);
        if(lapEpoch<=0){
            lapEpoch=System.currentTimeMillis();
            prefs.edit().putLong("lap_epoch",lapEpoch).apply();
        }
        syncInitialising=false;
    }

    private int dp(float x){return Math.round(x*activity.getResources().getDisplayMetrics().density);}
    private TextView text(String s,int sp,int colour,boolean bold){TextView t=new TextView(activity);t.setText(s);t.setTextSize(sp);t.setTextColor(colour);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));t.setIncludeFontPadding(false);return t;}
    private Button button(String s,Runnable r){Button b=new Button(activity);b.setText(s);b.setAllCaps(false);b.setMinHeight(dp(48));b.setOnClickListener(v->r.run());return b;}
    private View gap(int h){Space s=new Space(activity);s.setLayoutParams(new LinearLayout.LayoutParams(1,dp(h)));return s;}

    public List<Checkpoint> tabletCheckpoints(boolean onlyActive){
        syncTick();
        return new ArrayList<>(onlyActive?activeRoute():checkpoints);
    }
    public void openTabletQr(String id){
        Checkpoint c=find(id);if(c==null)return;
        SharedPreferences gp=LocalGpsService.prefs(activity);
        double lat=Double.longBitsToDouble(gp.getLong("lat",0)),lon=Double.longBitsToDouble(gp.getLong("lon",0));
        long age=LocalGpsService.fixAgeSeconds(activity);
        float metres=gp.getLong("fix_time",0)>0&&age>=0&&age<=10&&gp.contains("lat")&&gp.contains("lon")&&TabletPolicy.point(lat,lon)?distance(lat,lon,c.lat,c.lon):Float.NaN;
        showScanDialog(c,metres);
    }
    public void closeTabletQr(){if(scanDialog!=null&&scanDialog.isShowing())scanDialog.dismiss();scanDialog=null;scanDialogId="";}
    public boolean tabletMatches(String id,Observation o){
        return id.equals(canonicalId(o.property))||id.equals(canonicalId(o.issue));
    }
    public View dashboardCard(){
        card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(18),dp(16),dp(18),dp(16));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(0xff121d27);bg.setCornerRadius(dp(16));bg.setStroke(dp(1),0xff263746);card.setBackground(bg);
        photo=new ImageView(activity);photo.setAdjustViewBounds(true);photo.setScaleType(ImageView.ScaleType.CENTER_CROP);photo.setVisibility(View.GONE);
        card.addView(photo,new LinearLayout.LayoutParams(-1,dp(170)));card.addView(gap(9));
        title=text("CHECKPOINT PROXIMITY",12,0xff70d7c4,true);card.addView(title);card.addView(gap(9));
        distance=text("No mapped checkpoint nearby",22,0xffeef4f9,true);card.addView(distance);card.addView(gap(7));
        hint=text("Supervisor can register checkpoint locations from the checkpoint manager.",12,0xff9daebb,false);card.addView(hint);
        complete=button("SCAN COMPLETE",this::completeActive);complete.setVisibility(View.GONE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.topMargin=dp(12);card.addView(complete,lp);
        card.setOnClickListener(v->{if(scanDialog==null||!scanDialog.isShowing())showManager();});tick();return card;
    }

    public void tick(){
        if(card==null)return;
        syncTick();
        long fix=LocalGpsService.prefs(activity).getLong("fix_time",0),age=LocalGpsService.fixAgeSeconds(activity);
        if(fix==0||age<0||age>10){showNone(age>10?"Waiting for a fresh checkpoint GPS fix":"Waiting for current GPS position");return;}
        SharedPreferences gp=LocalGpsService.prefs(activity);
        double lat=Double.longBitsToDouble(gp.getLong("lat",0)),lon=Double.longBitsToDouble(gp.getLong("lon",0));

        Checkpoint best=null;float bestDist=Float.MAX_VALUE;
        for(Checkpoint c:activeRoute()){
            if(!c.mapped())continue;
            float d=distance(lat,lon,c.lat,c.lon);
            if(d<bestDist){best=c;bestDist=d;}
        }
        if(best==null){
            activeId="";
            showNone("No checkpoint locations registered for this patrol");
            return;
        }

        // A different physically closer checkpoint always takes over immediately.
        if(!best.id.equals(activeId)){
            activeId=best.id;
            if(scanDialog!=null&&scanDialog.isShowing()){
                scanDialog.dismiss();
                scanDialog=null;
                scanDialogId="";
            }
            if(!best.id.equals(closedId)) closedId="";
        }

        boolean scanRange=bestDist<=best.radius&&LocalGpsService.precise(activity)&&!gp.getBoolean("mock",false)&&gp.getFloat("accuracy",Float.MAX_VALUE)<=best.radius;
        title.setText(scanRange?"CHECKPOINT IN SCAN RANGE":"CLOSEST CHECKPOINT");
        distance.setText(best.name+"   "+arrow(bearing(lat,lon,best.lat,best.lon))+"  "+Math.round(bestDist)+" m");
        complete.setVisibility(View.GONE);
        if(photo!=null)photo.setVisibility(View.GONE);

        // Re-arm a previously closed QR only after the vehicle has left its configured scan radius.
        if(best.id.equals(closedId) && bestDist>best.radius){
            closedId="";
        }

        if(scanRange){
            hint.setText("Full checkpoint QR opens automatically. Scan it with SilverTrack, then tap CLOSE.");
            if(!best.id.equals(closedId)) showScanDialog(best,bestDist);
        }else{
            closeTabletQr();hint.setText("Activation "+Math.round(best.radius)+" m · GPS ±"+Math.round(gp.getFloat("accuracy",0))+" m. A precise fix accurate enough for this radius is required.");
        }
    }

    private void showNone(String s){
        title.setText("CHECKPOINT PROXIMITY");distance.setText(s);
        hint.setText(supervisor()?"Tap here to manage checkpoints, photos, groups, order and GPS locations.":"Mapped checkpoints will appear automatically as you approach them.");
        complete.setVisibility(View.GONE);if(photo!=null)photo.setVisibility(View.GONE);
    }

    private void showPhoto(Checkpoint c){
        if(photo==null)return;
        Bitmap b=null;
        try{
            if(c.photoPath!=null&&!c.photoPath.isEmpty()){File f=new File(c.photoPath);if(f.isFile())b=BitmapFactory.decodeFile(f.getAbsolutePath());}
            if(b==null&&c.assetPhoto!=null&&!c.assetPhoto.isEmpty()){
                try(InputStream in=activity.getAssets().open("checkpoint_photos/"+c.assetPhoto)){b=BitmapFactory.decodeStream(in);}
            }
        }catch(Exception ignored){}
        if(b==null){photo.setImageDrawable(null);photo.setVisibility(View.GONE);}else{photo.setImageBitmap(b);photo.setVisibility(View.VISIBLE);}
    }

    private static float distance(double a,double o,double b,double p){float[] r=new float[2];Location.distanceBetween(a,o,b,p,r);return r[0];}
    private static float bearing(double a,double o,double b,double p){float[] r=new float[2];Location.distanceBetween(a,o,b,p,r);return (r[1]+360f)%360f;}
    private static String arrow(float b){if(b<22.5||b>=337.5)return "↑";if(b<67.5)return "↗";if(b<112.5)return "→";if(b<157.5)return "↘";if(b<202.5)return "↓";if(b<247.5)return "↙";if(b<292.5)return "←";return "↖";}

    private boolean supervisor(){return !CheckpointSyncClient.central(activity)&&CheckpointSyncClient.master(activity);}
    private String activePatrol(){return prefs.getString("active_patrol","PBC23");}
    private boolean completed(String id){ return false; }
    private void completeActive(){ }
    public void newLap(){lapEpoch=System.currentTimeMillis();prefs.edit().putLong("lap_epoch",lapEpoch).apply();activeId="";closedId="";scanDialogId="";if(scanDialog!=null&&scanDialog.isShowing())scanDialog.dismiss();scanDialog=null;tick();}

    private List<Checkpoint> activeRoute(){
        ArrayList<Checkpoint> out=new ArrayList<>();
        boolean pbc1="PBC1".equals(activePatrol());
        for(Checkpoint c:checkpoints)if(pbc1?"PBC1".equals(c.group):("PBC2".equals(c.group)||"PBC3".equals(c.group)))out.add(c);
        out.sort(Comparator.comparingInt(c->c.order));return out;
    }
    private Checkpoint find(String id){for(Checkpoint c:checkpoints)if(c.id.equals(id))return c;return null;}

    private void load(){
        checkpoints.clear();
        try{
            JSONArray a=new JSONArray(prefs.getString(KEY,"[]"));
            for(int i=0;i<a.length();i++){
                JSONObject o=a.optJSONObject(i);if(o==null)continue;
                String name=o.optString("name"),id=canonicalId(o.optString("id").isEmpty()?name:o.optString("id"));
                checkpoints.add(new Checkpoint(id,name,o.optString("group",defaultGroup(id)),o.optInt("order",defaultOrder(id)),
                    o.optDouble("lat"),o.optDouble("lon"),(float)o.optDouble("radius",DEFAULT_TRIGGER),o.optString("photoPath"),o.optString("assetPhoto",defaultPhoto(id))));
            }
        }catch(Exception ignored){}
    }

    private void ensureDefaults(){
        String[][] defs={
            {"seal-circuit","Seal Circuit","PBC3","30870.jpg"},{"rec-centre","Rec Centre","PBC3",""},
            {"slipstream","Slipstream","PBC3","30877.jpg"},{"bobsled","Bobsled Lane","PBC3","30885.jpg"},
            {"christina","Christina","PBC3","30879.jpg"},{"solo-place","Solo Place","PBC2","30880.jpg"},
            {"impeccable","Impeccable","PBC2",""},{"quest","Quest","PBC2",""},{"eolo","Eolo","PBC3","30884.jpg"},
            {"elusive","Elusive","PBC3","30886.jpg"},{"serenade","Serenade","PBC3","30882.jpg"},
            {"boambillee","Boambillee 001","PBC3","30878.jpg"}
        };
        for(String[] d:defs){
            Checkpoint c=find(d[0]);
            if(c==null)checkpoints.add(new Checkpoint(d[0],d[1],d[2],defaultOrder(d[0]),0,0,DEFAULT_TRIGGER,"",d[3]));
            else{
                if(c.group==null||c.group.isEmpty())c.group=d[2];
                if(c.order<=0)c.order=defaultOrder(c.id);
                if((c.assetPhoto==null||c.assetPhoto.isEmpty())&&!d[3].isEmpty())c.assetPhoto=d[3];
            }
        }
    }

    private void save(){
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
        if(!syncInitialising&&CheckpointSyncClient.master(activity)&&mappedCount()>0)
            CheckpointSyncClient.publish(activity,syncPayload());
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
        if(!CheckpointSyncClient.subscriber(activity)&&!CheckpointSyncClient.master(activity))return;
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

    private int mappedCount(){
        int n=0;for(Checkpoint c:checkpoints)if(c.mapped())n++;return n;
    }

    private int photoCount(){
        int n=0;
        for(Checkpoint c:checkpoints)if(bitmapFor(c)!=null)n++;
        return n;
    }

    private void showSyncRecovery(){
        LinearLayout f=new LinearLayout(activity);f.setOrientation(LinearLayout.VERTICAL);f.setPadding(dp(18),dp(8),dp(18),dp(8));
        TextView state=text("LOCAL: "+checkpoints.size()+" checkpoints · "+mappedCount()+" mapped · "+photoCount()+" photos\nREMOTE: "+CheckpointSyncClient.status(activity),13,0xffeef4f9,false);
        f.addView(state);f.addView(gap(10));

        f.addView(button("DOWNLOAD SHARED CHECKPOINTS",()->{
            state.setText("Downloading shared checkpoint set…");
            CheckpointSyncClient.download(activity,(data,error)->{
                if(error!=null){state.setText(error);return;}
                JSONArray remote=data==null?null:data.optJSONArray("checkpoints");
                if(remote==null||remote.length()==0){state.setText("Relay is reachable, but there is no shared checkpoint set yet.");return;}
                long received=data.optLong("receivedAt",System.currentTimeMillis());
                state.setText("Applying shared checkpoint photos…");
                applyRemote(remote,received,()->{
                    state.setText("Downloaded "+remote.length()+" checkpoints. Local set now has "+mappedCount()+" mapped checkpoints.");
                    Toast.makeText(activity,"Shared checkpoints loaded onto this phone.",Toast.LENGTH_LONG).show();
                });
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

    private void applyRemote(JSONArray remote,long receivedAt){applyRemote(remote,receivedAt,()->{});}
    private void applyRemote(JSONArray remote,long receivedAt,Runnable ready){
        if(receivedAt<=prefs.getLong("checkpoint_sync_received_at",0)){ready.run();return;}
        PHOTO_IO.execute(()->{
        AnrHooks.photo();
        ArrayList<Checkpoint> incoming=new ArrayList<>();
        try{
            File dir=new File(activity.getApplicationContext().getFilesDir(),"checkpoint_photos");
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
                            File f=new File(dir,"synced_"+slug(id)+"_"+receivedAt+".jpg");
                            try(FileOutputStream out=new FileOutputStream(f)){out.write(data);}
                            photoPath=f.getAbsolutePath();
                        }
                    }catch(Exception ignored){}
                }
                incoming.add(new Checkpoint(id,name,group,o.optInt("order",900),
                    o.optDouble("lat"),o.optDouble("lon"),
                    (float)o.optDouble("radius",DEFAULT_TRIGGER),photoPath,""));
            }
            if(incoming.isEmpty()&&remote.length()!=0)return;
            JSONArray local=new JSONArray();
            for(Checkpoint c:incoming){
                JSONObject o=new JSONObject();
                o.put("id",c.id).put("name",c.name).put("group",c.group).put("order",c.order)
                    .put("lat",c.lat).put("lon",c.lon).put("radius",c.radius)
                    .put("photoPath",c.photoPath).put("assetPhoto","");
                local.put(o);
            }
            String stored=local.toString();
            photoUi.post(()->{
            if(activity.isFinishing()||activity.isDestroyed()||receivedAt<=prefs.getLong("checkpoint_sync_received_at",0))return;
            checkpoints.clear();checkpoints.addAll(incoming);
            prefs.edit().putString(KEY,stored).putLong("checkpoint_sync_received_at",receivedAt).apply();
            activeId="";closedId="";
            if(scanDialog!=null&&scanDialog.isShowing())scanDialog.dismiss();
            scanDialog=null;scanDialogId="";
            tick();ready.run();
            });
        }catch(Exception ignored){}
        });
    }

    public void showManager(){
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),dp(8),dp(18),dp(8));
        form.addView(text(supervisor()?"SUPERVISOR CHECKPOINT MANAGER":"CHECKPOINT STATUS",12,0xff70d7c4,true));
        String sync=CheckpointSyncClient.status(activity);
        TextView note=text(supervisor()
            ?"Mother Base can centrally own the shared checkpoint set. Changes to names, PBC group, order, GPS, scan range and QR photo sync automatically to Patrol 1, 2 and 3.\n\n"+sync
            :"Checkpoint configuration is supplied by Mother Base through the cloud relay.\n\n"+sync,
            12,0xff9daebb,false);
        note.setPadding(0,dp(8),0,dp(12));form.addView(note);
        form.addView(button("CHECKPOINT SYNC / RECOVERY",this::showSyncRecovery));
        form.addView(gap(10));

        Spinner active=new Spinner(activity);ArrayAdapter<String> activeAd=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,Arrays.asList("PBC 1 patrol","PBC 2 + PBC 3 patrol"));activeAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);active.setAdapter(activeAd);active.setSelection("PBC1".equals(activePatrol())?0:1);active.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){prefs.edit().putString("active_patrol",pos==0?"PBC1":"PBC23").apply();tick();}});form.addView(active);form.addView(gap(10));

        if(supervisor()){
            form.addView(button("Add checkpoint",this::addCheckpoint));
            form.addView(button("Start new patrol lap",this::newLap));form.addView(gap(12));
        }
        addRouteSection(form,"PBC 1",routeFor("PBC1"),false);
        addRouteSection(form,"PBC 2 + PBC 3 combined route",routeFor("PBC23"),true);

        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        new AlertDialog.Builder(activity).setTitle("Checkpoint proximity").setView(scroll).setPositiveButton("Close",null).show();
    }

    private List<Checkpoint> routeFor(String route){
        ArrayList<Checkpoint> out=new ArrayList<>();
        for(Checkpoint c:checkpoints){
            boolean match="PBC1".equals(route)?"PBC1".equals(c.group):("PBC2".equals(c.group)||"PBC3".equals(c.group));
            if(match)out.add(c);
        }
        out.sort(Comparator.comparingInt(c->c.order));return out;
    }

    private void addRouteSection(LinearLayout form,String heading,List<Checkpoint> list,boolean combined){
        form.addView(text(heading,16,0xff70d7c4,true));form.addView(gap(6));
        if(list.isEmpty())form.addView(text("No checkpoints assigned.",13,0xff9daebb,false));
        for(int i=0;i<list.size();i++){
            Checkpoint c=list.get(i);LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(0,dp(7),0,dp(11));
            ImageView thumb=new ImageView(activity);thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);Bitmap b=bitmapFor(c);if(b!=null){thumb.setImageBitmap(b);row.addView(thumb,new LinearLayout.LayoutParams(-1,dp(110)));}
            row.addView(text(String.format(Locale.ROOT,"%02d  %s  ·  %s",i+1,c.name,c.group),16,0xffeef4f9,true));
            row.addView(text(c.mapped()?String.format(Locale.ROOT,"Mapped · %.6f, %.6f · scan range %.0f m",c.lat,c.lon,c.radius):"Location not registered · scan range "+Math.round(c.radius)+" m",12,0xff9daebb,false));
            if(supervisor()){
                LinearLayout controls=new LinearLayout(activity);controls.setOrientation(LinearLayout.HORIZONTAL);
                Button up=button("↑",()->move(c,-1,combined));Button down=button("↓",()->move(c,1,combined));Button edit=button("Edit",()->editCheckpoint(c));Button gps=button("Set GPS",()->register(c));
                controls.addView(up,new LinearLayout.LayoutParams(0,dp(48),.7f));controls.addView(down,new LinearLayout.LayoutParams(0,dp(48),.7f));controls.addView(edit,new LinearLayout.LayoutParams(0,dp(48),1.3f));controls.addView(gps,new LinearLayout.LayoutParams(0,dp(48),1.5f));row.addView(controls);
            }
            form.addView(row);
        }
    }

    private Bitmap bitmapFor(Checkpoint c){
        try{
            if(c.photoPath!=null&&!c.photoPath.isEmpty()){File f=new File(c.photoPath);if(f.isFile()){Bitmap b=BitmapFactory.decodeFile(f.getAbsolutePath());if(b!=null)return b;}}
            if(c.assetPhoto!=null&&!c.assetPhoto.isEmpty())try(InputStream in=activity.getAssets().open("checkpoint_photos/"+c.assetPhoto)){return BitmapFactory.decodeStream(in);}
        }catch(Exception ignored){}
        return null;
    }

    private void move(Checkpoint c,int delta,boolean combined){
        List<Checkpoint> list=combined?routeFor("PBC23"):routeFor("PBC1");int idx=list.indexOf(c),other=idx+delta;if(idx<0||other<0||other>=list.size())return;
        Checkpoint x=list.get(other);int t=c.order;c.order=x.order;x.order=t;normalizeOrders(list);save();showManager();
    }
    private void normalizeOrders(List<Checkpoint> list){for(int i=0;i<list.size();i++)list.get(i).order=(i+1)*10;}

    private void addCheckpoint(){
        LinearLayout f=new LinearLayout(activity);f.setOrientation(LinearLayout.VERTICAL);f.setPadding(dp(18),dp(6),dp(18),dp(6));
        EditText name=new EditText(activity);name.setHint("Checkpoint name");f.addView(name);
        Spinner group=new Spinner(activity);ArrayAdapter<String> ga=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,Arrays.asList("PBC1","PBC2","PBC3"));ga.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);group.setAdapter(ga);f.addView(group);
        AlertDialog d=new AlertDialog.Builder(activity).setTitle("Add checkpoint").setView(f).setPositiveButton("Add",null).setNegativeButton("Cancel",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{String n=name.getText().toString().trim();if(n.isEmpty()){toast("Enter a checkpoint name.");return;}String g=String.valueOf(group.getSelectedItem());String id=uniqueId(canonicalId(n));int order=maxOrderFor(g)+10;checkpoints.add(new Checkpoint(id,n,g,order,0,0,DEFAULT_TRIGGER,"",""));save();d.dismiss();showManager();}));d.show();
    }

    private void editCheckpoint(Checkpoint c){
        LinearLayout f=new LinearLayout(activity);f.setOrientation(LinearLayout.VERTICAL);f.setPadding(dp(18),dp(6),dp(18),dp(6));
        EditText name=new EditText(activity);name.setText(c.name);f.addView(name);
        Spinner group=new Spinner(activity);ArrayAdapter<String> ga=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,Arrays.asList("PBC1","PBC2","PBC3"));ga.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);group.setAdapter(ga);group.setSelection(Math.max(0,Arrays.asList("PBC1","PBC2","PBC3").indexOf(c.group)));f.addView(group);
        EditText radius=new EditText(activity);radius.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);radius.setText(String.valueOf(Math.round(c.radius)));radius.setHint("Scan range metres");f.addView(radius);
        f.addView(button((bitmapFor(c)==null?"Add checkpoint photo":"Replace checkpoint photo"),()->choosePhoto(c)));
        f.addView(button("Register / update location to current GPS",()->register(c)));
        f.addView(button("Remove checkpoint photo",()->{removePhoto(c);save();toast("Checkpoint photo removed.");}));
        f.addView(button("Delete checkpoint",()->{checkpoints.remove(c);removePhoto(c);save();toast("Checkpoint deleted.");}));
        AlertDialog d=new AlertDialog.Builder(activity).setTitle("Edit checkpoint").setView(f).setPositiveButton("Save",null).setNegativeButton("Close",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{String n=name.getText().toString().trim();if(n.isEmpty()){toast("Enter a checkpoint name.");return;}float r;try{r=Float.parseFloat(radius.getText().toString());}catch(Exception e){toast("Enter a distance from 1 to 500 metres.");return;}try{TabletPolicy.radius(r);}catch(Exception invalid){toast(invalid.getMessage());return;}c.name=n;c.group=String.valueOf(group.getSelectedItem());c.radius=r;save();d.dismiss();showManager();tick();}));d.show();
    }

    private int maxOrderFor(String group){int max=0;boolean pbc1="PBC1".equals(group);for(Checkpoint c:checkpoints){boolean same=pbc1?"PBC1".equals(c.group):("PBC2".equals(c.group)||"PBC3".equals(c.group));if(same)max=Math.max(max,c.order);}return max;}
    private String uniqueId(String base){if(base.isEmpty())base="checkpoint";String id=base;int n=2;while(find(id)!=null)id=base+"-"+(n++);return id;}

    private void register(Checkpoint c){
        if(!supervisor()){toast("Supervisor mode is required.");return;}
        SharedPreferences gp=LocalGpsService.prefs(activity);long t=gp.getLong("fix_time",0),age=LocalGpsService.fixAgeSeconds(activity);
        if(t==0||age<0||age>30){toast("Need a current GPS fix less than 30 seconds old.");return;}
        if(!LocalGpsService.precise(activity)){toast("Enable Precise location before registering a checkpoint.");return;}
        c.lat=Double.longBitsToDouble(gp.getLong("lat",0));c.lon=Double.longBitsToDouble(gp.getLong("lon",0));save();toast(c.name+" location registered.");tick();
    }

    private void showScanDialog(Checkpoint c,float metres){
        if(c==null)return;
        if(scanDialog!=null&&scanDialog.isShowing()&&c.id.equals(scanDialogId))return;
        if(scanDialog!=null&&scanDialog.isShowing())scanDialog.dismiss();

        LinearLayout shell=new LinearLayout(activity);shell.setOrientation(LinearLayout.VERTICAL);shell.setPadding(dp(14),dp(12),dp(14),dp(14));
        TextView h=text(c.name+" · "+(Float.isFinite(metres)?Math.round(metres)+" m":"distance unavailable"),20,0xffeef4f9,true);h.setGravity(Gravity.CENTER);shell.addView(h);shell.addView(gap(10));

        Bitmap b=bitmapFor(c);
        if(b!=null){
            ImageView qr=new ImageView(activity);qr.setAdjustViewBounds(true);qr.setScaleType(ImageView.ScaleType.FIT_CENTER);qr.setImageBitmap(b);
            int maxH=Math.round(activity.getResources().getDisplayMetrics().heightPixels*0.62f);
            shell.addView(qr,new LinearLayout.LayoutParams(-1,maxH));
        }else{
            TextView missing=text("No checkpoint photo is attached to this checkpoint.",16,0xffffc266,true);missing.setGravity(Gravity.CENTER);missing.setPadding(0,dp(40),0,dp(40));shell.addView(missing);
        }

        shell.addView(gap(12));
        Button close=button("CLOSE",()->{
            closedId=c.id;
            scanDialogId="";
            AlertDialog closing=scanDialog;
            scanDialog=null;
            if(closing!=null&&closing.isShowing())closing.dismiss();
        });
        close.setTextSize(18);close.setMinHeight(dp(64));shell.addView(close,new LinearLayout.LayoutParams(-1,dp(64)));

        scanDialog=new AlertDialog.Builder(activity).setView(shell).create();
        scanDialog.setCancelable(false);scanDialogId=c.id;scanDialog.show();
        if(scanDialog.getWindow()!=null)scanDialog.getWindow().setLayout(-1,-2);
    }

    private void choosePhoto(Checkpoint c){
        pendingPhotoId=c.id;Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("image/*");
        try{activity.startActivityForResult(pick,PHOTO_REQUEST);}catch(Exception e){toast("No photo picker is available.");}
    }
    public boolean onActivityResult(int requestCode,int resultCode,Intent data){
        if(requestCode!=PHOTO_REQUEST)return false;
        if(resultCode!=Activity.RESULT_OK||data==null||data.getData()==null){pendingPhotoId="";return true;}
        Checkpoint c=find(pendingPhotoId);pendingPhotoId="";if(c==null)return true;
        try{importPhoto(c,data.getData());save();toast("Checkpoint photo saved.");tick();}catch(Exception e){toast("Could not save checkpoint photo.");}
        return true;
    }
    private void importPhoto(Checkpoint c,Uri uri)throws Exception{
        File dir=new File(activity.getFilesDir(),"checkpoint_photos");if(!dir.exists()&&!dir.mkdirs())throw new IOException();
        File out=new File(dir,c.id+".jpg");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(InputStream in=activity.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,bounds);}
        int sample=1;while(Math.max(bounds.outWidth,bounds.outHeight)/sample>1400)sample*=2;
        BitmapFactory.Options opt=new BitmapFactory.Options();opt.inSampleSize=sample;Bitmap b;
        try(InputStream in=activity.getContentResolver().openInputStream(uri)){b=BitmapFactory.decodeStream(in,null,opt);}
        if(b==null)throw new IOException();
        try(OutputStream os=new FileOutputStream(out)){if(!b.compress(Bitmap.CompressFormat.JPEG,84,os))throw new IOException();}
        b.recycle();c.photoPath=out.getAbsolutePath();c.assetPhoto="";
    }
    private void removePhoto(Checkpoint c){if(c.photoPath!=null&&!c.photoPath.isEmpty())try{new File(c.photoPath).delete();}catch(Exception ignored){}c.photoPath="";c.assetPhoto="";}

    private static int defaultOrder(String id){int i=ROUTE_IDS.indexOf(id);return i<0?900:(i+1)*10;}
    private static String defaultGroup(String id){return Arrays.asList("solo-place","impeccable","quest").contains(id)?"PBC2":"PBC3";}
    private static String defaultPhoto(String id){
        if("seal-circuit".equals(id))return "30870.jpg";if("slipstream".equals(id))return "30877.jpg";if("boambillee".equals(id))return "30878.jpg";
        if("christina".equals(id))return "30879.jpg";if("solo-place".equals(id))return "30880.jpg";if("serenade".equals(id))return "30882.jpg";
        if("eolo".equals(id))return "30884.jpg";if("bobsled".equals(id))return "30885.jpg";if("elusive".equals(id))return "30886.jpg";return "";
    }
    private static String canonicalId(String name){
        String k=slug(name);if(k.startsWith("general-patrol-"))k=k.substring("general-patrol-".length());
        if(k.startsWith("seal")||k.startsWith("ceil"))return "seal-circuit";if(k.startsWith("rec-centre")||k.startsWith("rec-center"))return "rec-centre";
        if(k.startsWith("slipstream"))return "slipstream";if(k.startsWith("bobsled"))return "bobsled";if(k.startsWith("christina"))return "christina";
        if(k.startsWith("solo"))return "solo-place";if(k.startsWith("impeccable"))return "impeccable";if(k.startsWith("quest"))return "quest";
        if(k.startsWith("eolo"))return "eolo";if(k.startsWith("elusive"))return "elusive";if(k.startsWith("serenade"))return "serenade";
        if(k.startsWith("boambillee"))return "boambillee";return k;
    }
    private static String slug(String s){return s==null?"":s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("(^-|-$)","");}
    private void toast(String s){Toast.makeText(activity,s,Toast.LENGTH_LONG).show();}
}