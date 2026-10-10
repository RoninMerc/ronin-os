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
 * supervisor-managed checkpoint metadata/photos and optional MyCentsys launch prompts.
 */
public final class CheckpointProximityUi {
    public static final int PHOTO_REQUEST=7245;
    private static final String PREFS="patrol_checkpoint_proximity_v1", KEY="checkpoints_json";
    private static final float DEFAULT_TRIGGER=70f, CLEAR_RADIUS=140f, GATE_TRIGGER=100f, GATE_CLEAR=180f;
    private static final String CENTSYS_PACKAGE="com.centurionsystems.mycentsysremote";
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
    private String activeId="",pendingPhotoId="",gateSuppressedId="";
    private boolean gateDialogOpen;
    private long lapEpoch;

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
        load();ensureDefaults();save();lapEpoch=prefs.getLong("lap_epoch",0);
    }

    private int dp(float x){return Math.round(x*activity.getResources().getDisplayMetrics().density);}
    private TextView text(String s,int sp,int colour,boolean bold){TextView t=new TextView(activity);t.setText(s);t.setTextSize(sp);t.setTextColor(colour);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));t.setIncludeFontPadding(false);return t;}
    private Button button(String s,Runnable r){Button b=new Button(activity);b.setText(s);b.setAllCaps(false);b.setMinHeight(dp(48));b.setOnClickListener(v->r.run());return b;}
    private View gap(int h){Space s=new Space(activity);s.setLayoutParams(new LinearLayout.LayoutParams(1,dp(h)));return s;}

    public View dashboardCard(){
        card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(18),dp(16),dp(18),dp(16));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(0xff121d27);bg.setCornerRadius(dp(16));bg.setStroke(dp(1),0xff263746);card.setBackground(bg);
        photo=new ImageView(activity);photo.setAdjustViewBounds(true);photo.setScaleType(ImageView.ScaleType.CENTER_CROP);photo.setVisibility(View.GONE);
        card.addView(photo,new LinearLayout.LayoutParams(-1,dp(170)));card.addView(gap(9));
        title=text("CHECKPOINT PROXIMITY",12,0xff70d7c4,true);card.addView(title);card.addView(gap(9));
        distance=text("No mapped checkpoint nearby",22,0xffeef4f9,true);card.addView(distance);card.addView(gap(7));
        hint=text("Supervisor can register checkpoint locations from the checkpoint manager.",12,0xff9daebb,false);card.addView(hint);
        complete=button("Complete",this::completeActive);complete.setVisibility(View.GONE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.topMargin=dp(12);card.addView(complete,lp);
        card.setOnClickListener(v->showManager());tick();return card;
    }

    public void tick(){
        if(card==null)return;
        long fix=LocalGpsService.prefs(activity).getLong("fix_time",0),age=LocalGpsService.fixAgeSeconds(activity);
        if(fix==0||age<0||age>90){showNone(age>90?"GPS position is stale":"Waiting for current GPS position");return;}
        SharedPreferences gp=LocalGpsService.prefs(activity);
        double lat=Double.longBitsToDouble(gp.getLong("lat",0)),lon=Double.longBitsToDouble(gp.getLong("lon",0));

        maybeGatePrompt(lat,lon);

        Checkpoint best=null;float bestDist=Float.MAX_VALUE;
        for(Checkpoint c:activeRoute()){
            if(!c.mapped()||completed(c.id))continue;
            float d=distance(lat,lon,c.lat,c.lon);
            if(d<=Math.max(c.radius,DEFAULT_TRIGGER)&&d<bestDist){best=c;bestDist=d;}
        }
        if(best==null){
            if(!activeId.isEmpty()){
                Checkpoint old=find(activeId);
                if(old!=null&&old.mapped()&&distance(lat,lon,old.lat,old.lon)<CLEAR_RADIUS)return;
            }
            activeId="";showNone(activeRoute().stream().noneMatch(Checkpoint::mapped)?"No checkpoint locations registered for this patrol":"No outstanding checkpoint nearby");return;
        }
        activeId=best.id;
        String reached=bestDist<=25?"CHECKPOINT REACHED":"CHECKPOINT NEARBY";
        title.setText(reached);distance.setText(best.name+"   "+arrow(bearing(lat,lon,best.lat,best.lon))+"  "+Math.round(bestDist)+" m");
        hint.setText("Scan the physical SilverTrack QR code, then tap Complete. GPS proximity alone never records a scan.");
        complete.setVisibility(View.VISIBLE);showPhoto(best);
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

    private boolean supervisor(){return "master".equalsIgnoreCase(activity.getSharedPreferences("patrol_settings",0).getString("device_mode","user"));}
    private String activePatrol(){return prefs.getString("active_patrol","PBC23");}
    private boolean completed(String id){return prefs.getLong("done_"+id,0)>=lapEpoch&&lapEpoch>0;}
    private void completeActive(){if(activeId.isEmpty())return;prefs.edit().putLong("done_"+activeId,System.currentTimeMillis()).apply();activeId="";tick();Toast.makeText(activity,"Checkpoint cleared from this Patrol Link lap.",Toast.LENGTH_SHORT).show();}
    public void newLap(){lapEpoch=System.currentTimeMillis();SharedPreferences.Editor e=prefs.edit().putLong("lap_epoch",lapEpoch);for(Checkpoint c:checkpoints)e.remove("done_"+c.id);e.apply();activeId="";tick();Toast.makeText(activity,"Checkpoint proximity reset for a new lap.",Toast.LENGTH_SHORT).show();}

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
        JSONArray a=new JSONArray();
        try{
            for(Checkpoint c:checkpoints){JSONObject o=new JSONObject();o.put("id",c.id);o.put("name",c.name);o.put("group",c.group);o.put("order",c.order);o.put("lat",c.lat);o.put("lon",c.lon);o.put("radius",c.radius);o.put("photoPath",c.photoPath==null?"":c.photoPath);o.put("assetPhoto",c.assetPhoto==null?"":c.assetPhoto);a.put(o);}
        }catch(Exception ignored){}
        prefs.edit().putString(KEY,a.toString()).apply();
    }

    public void showManager(){
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),dp(8),dp(18),dp(8));
        form.addView(text(supervisor()?"SUPERVISOR CHECKPOINT MANAGER":"CHECKPOINT STATUS",12,0xff70d7c4,true));
        TextView note=text(supervisor()?"Manage PBC1 separately from the combined PBC2 + PBC3 patrol. Rename checkpoints, change their route order, attach/replace photos and re-register GPS locations at any time.":"Checkpoint coordinates and route settings are supervisor-managed.",12,0xff9daebb,false);note.setPadding(0,dp(8),0,dp(12));form.addView(note);

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
            row.addView(text(c.mapped()?String.format(Locale.ROOT,"Mapped · %.6f, %.6f · trigger %.0f m",c.lat,c.lon,c.radius):"Location not registered · trigger "+Math.round(c.radius)+" m",12,0xff9daebb,false));
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
        EditText radius=new EditText(activity);radius.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);radius.setText(String.valueOf(Math.round(c.radius)));radius.setHint("Trigger metres");f.addView(radius);
        f.addView(button((bitmapFor(c)==null?"Add checkpoint photo":"Replace checkpoint photo"),()->choosePhoto(c)));
        f.addView(button("Register / update location to current GPS",()->register(c)));
        f.addView(button("Remove checkpoint photo",()->{removePhoto(c);save();toast("Checkpoint photo removed.");}));
        f.addView(button("Delete checkpoint",()->{checkpoints.remove(c);removePhoto(c);save();toast("Checkpoint deleted.");}));
        AlertDialog d=new AlertDialog.Builder(activity).setTitle("Edit checkpoint").setView(f).setPositiveButton("Save",null).setNegativeButton("Close",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{String n=name.getText().toString().trim();if(n.isEmpty()){toast("Enter a checkpoint name.");return;}float r;try{r=Float.parseFloat(radius.getText().toString());}catch(Exception e){r=DEFAULT_TRIGGER;}c.name=n;c.group=String.valueOf(group.getSelectedItem());c.radius=Math.max(20f,Math.min(300f,r));save();d.dismiss();showManager();tick();}));d.show();
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

    private void maybeGatePrompt(double lat,double lon){
        if("PBC1".equals(activePatrol()))return;
        Checkpoint nearest=null;float best=Float.MAX_VALUE;
        for(String id:Arrays.asList("solo-place","impeccable","quest")){Checkpoint c=find(id);if(c==null||!c.mapped())continue;float d=distance(lat,lon,c.lat,c.lon);if(id.equals(gateSuppressedId)&&d>GATE_CLEAR)gateSuppressedId="";if(d<=GATE_TRIGGER&&d<best&&!id.equals(gateSuppressedId)){nearest=c;best=d;}}
        if(nearest==null||gateDialogOpen)return;
        gateDialogOpen=true;Checkpoint gate=nearest;int metres=Math.round(best);
        new AlertDialog.Builder(activity).setTitle(gate.name+" gate nearby")
            .setMessage("You are about "+metres+" metres from "+gate.name+". Open MyCentsys Remote?")
            .setPositiveButton("OPEN",(d,w)->{gateSuppressedId=gate.id;gateDialogOpen=false;launchCentsys();})
            .setNegativeButton("NOT NOW",(d,w)->{gateSuppressedId=gate.id;gateDialogOpen=false;})
            .setOnCancelListener(d->{gateSuppressedId=gate.id;gateDialogOpen=false;}).show();
    }
    private void launchCentsys(){
        try{Intent i=activity.getPackageManager().getLaunchIntentForPackage(CENTSYS_PACKAGE);if(i==null){toast("MyCentsys Remote is not installed.");return;}i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);activity.startActivity(i);}catch(Exception e){toast("Could not open MyCentsys Remote.");}
    }

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