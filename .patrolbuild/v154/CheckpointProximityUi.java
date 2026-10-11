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

        boolean scanRange=bestDist<=best.radius;
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
            hint.setText("Live checkpoint GPS ¬∑ full QR opens automatically inside the configured "+Math.round(best.radius)+" m scan range.");
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
    private static String arrow(float b){if(b<22.5||b>=337.5)return "‚Üê";if(b<67.5)return "‚Üó";w◊^{r´≤⁄Óù∆≠y