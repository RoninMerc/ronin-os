package au.com.roningroup.patrollink;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Device-only GPS UI. Declining location never disables the Silvertracker monitor. */
public final class LocalGpsUi {
    public static final int LOCATION_REQUEST=6219, BACKGROUND_REQUEST=6220;
    private final Activity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private TextView reading;
    private boolean pendingStart;
    private final Runnable tick=new Runnable(){public void run(){
        if(dialog==null||!dialog.isShowing()||activity.isFinishing())return;
        reading.setText(details());handler.postDelayed(this,1000);
    }};
    public LocalGpsUi(Activity a){activity=a;}
    private int dp(int x){return Math.round(x*activity.getResources().getDisplayMetrics().density);}
    private TextView label(String value,int size){TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setTextColor(0xffeef4f9);v.setPadding(0,dp(8),0,dp(8));return v;}
    private Button button(String title,Runnable r){Button b=new Button(activity);b.setText(title);b.setAllCaps(false);b.setMinHeight(dp(48));b.setOnClickListener(v->r.run());return b;}
    public void offerFirstRun(){
        if(activity.isFinishing()||activity.isDestroyed()||LocalGpsService.prefs(activity).getBoolean("intro_seen",false))return;
        LocalGpsService.prefs(activity).edit().putBoolean("intro_seen",true).apply();
        new AlertDialog.Builder(activity).setTitle("Set up this device's Patrol GPS?")
            .setMessage("Patrol Link can save this phone or vehicle's latest GPS position every 15 or 30 seconds, including while the screen is off. Location stays on this device: there is no website, account or server to configure, and it is not sent to Silvertracker. A visible Android service notification provides a Stop GPS control. Android may delay fixes when reception or battery restrictions interfere.\n\nYou approve location permissions in Android. Choosing Not now leaves your existing patrol reader and voices unchanged.")
            .setPositiveButton("Set up GPS",(d,w)->requestStart()).setNegativeButton("Not now",null).show();
    }
    public void show(){
        if(dialog!=null)dialog.dismiss();
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(20),dp(8),dp(20),dp(12));
        form.addView(label("THIS DEVICE ONLY",12));
        reading=label(details(),15);reading.setTextIsSelectable(true);form.addView(reading);
        form.addView(label("Update request interval",13));
        RadioGroup intervals=new RadioGroup(activity);intervals.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton fifteen=new RadioButton(activity);fifteen.setId(6315);fifteen.setText("15 seconds");
        RadioButton thirty=new RadioButton(activity);thirty.setId(6330);thirty.setText("30 seconds");
        intervals.addView(fifteen);intervals.addView(thirty);intervals.check(LocalGpsService.interval(activity)==30000?6330:6315);
        intervals.setOnCheckedChangeListener((g,id)->{
            LocalGpsService.prefs(activity).edit().putInt("interval_seconds",id==6330?30:15).apply();
            if(LocalGpsService.prefs(activity).getBoolean("enabled",false))LocalGpsService.start(activity);
        });form.addView(intervals);
        form.addView(button("Start / resume GPS",this::requestStart));
        form.addView(button("Stop GPS",()->{pendingStart=false;LocalGpsService.stop(activity);reading.setText(details());}));
        form.addView(button("Allow all the time / permissions",this::backgroundHelp));
        form.addView(button("Device location settings",()->openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS,false)));
        form.addView(button("App battery / permission settings",()->openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,true)));
        form.addView(button("Copy last position",()->{
            if(LocalGpsService.prefs(activity).getLong("fix_time",0)==0){toast("No GPS position has been received yet.");return;}
            ((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Patrol GPS last position",details()));toast("Last position copied.");
        }));
        form.addView(label("This is the position of this phone, not another guard or vehicle. No remote tracking or external map service is used. The last fix and its original timestamp remain visible if GPS stops; a timer never turns an old fix into a new one. For long screen-off shifts, Android's app battery setting may need Unrestricted. Reopen Patrol Link after a phone restart or force-stop.",12));
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        dialog=new AlertDialog.Builder(activity).setTitle("Patrol GPS · v1.1.29").setView(scroll).setNegativeButton("Close",null).create();
        dialog.setOnDismissListener(d->{handler.removeCallbacks(tick);dialog=null;reading=null;});dialog.show();
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,Math.round(activity.getResources().getDisplayMetrics().heightPixels*.88f));
        handler.post(tick);
    }
    public String summary(){
        boolean enabled=LocalGpsService.prefs(activity).getBoolean("enabled",false);
        long age=LocalGpsService.fixAgeSeconds(activity);
        String state=!enabled?"Stopped":!LocalGpsService.hasPermission(activity)?"Permission required":!LocalGpsService.locationEnabled(activity)?"Device location off":!LocalGpsService.active?"Paused — open Patrol GPS":age<0?"Waiting for position":age>90?"Position stale":"Running";
        return "THIS DEVICE GPS · "+state+(age<0?"":" · last fix "+age+"s ago");
    }
    private String details(){
        SharedPreferences p=LocalGpsService.prefs(activity);long fix=p.getLong("fix_time",0);
        StringBuilder b=new StringBuilder(summary());
        b.append("\n\n").append(p.getString("status","Not started. Tap Start GPS to set up local location updates."));
        b.append("\nPermission: ").append(!LocalGpsService.hasPermission(activity)?"not granted":LocalGpsService.precise(activity)?"precise":"approximate only — enable Precise location in Android");
        boolean allTime=Build.VERSION.SDK_INT<29||activity.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED;
        b.append("\nBackground permission: ").append(allTime?"allowed":"not granted; use Allow all the time above");
        b.append("\nRequested interval: ").append(LocalGpsService.interval(activity)/1000).append(" seconds");
        if(fix>0){
            double lat=Double.longBitsToDouble(p.getLong("lat",0)),lon=Double.longBitsToDouble(p.getLong("lon",0));
            b.append(String.format(Locale.ROOT,"\n\nLatitude: %.6f\nLongitude: %.6f\nAccuracy: ±%.0f metres",lat,lon,p.getFloat("accuracy",0)));
            ZoneId zone;try{zone=ZoneId.of(activity.getSharedPreferences("patrol_settings",0).getString("zone","Australia/Brisbane"));}catch(Exception e){zone=ZoneId.of("Australia/Brisbane");}
            b.append("\nFix recorded: ").append(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss z").format(Instant.ofEpochMilli(fix).atZone(zone)));
            b.append("\nSource: ").append(p.getString("provider","location"));
            if(p.getBoolean("mock",false))b.append(" · simulated / mock location");
            if(LocalGpsService.fixAgeSeconds(activity)>90)b.append("\nSTALE: this is not a current location fix.");
        }else b.append("\n\nNo position received yet. GPS can take longer indoors; no coordinates are guessed.");
        return b.toString();
    }
    public void requestStart(){
        if(!LocalGpsService.hasPermission(activity)){
            pendingStart=true;
            activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION_REQUEST);return;
        }
        if(!LocalGpsService.locationEnabled(activity)){
            pendingStart=true;
            new AlertDialog.Builder(activity).setTitle("Turn on device location")
                .setMessage("Enable Location in Android, then return here. Silvertracker continues independently.")
                .setPositiveButton("Open location settings",(d,w)->openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS,false))
                .setNegativeButton("Cancel",(d,w)->pendingStart=false).show();return;
        }
        pendingStart=false;
        if(LocalGpsService.start(activity)){
            toast("Local GPS started. Waiting for an actual position fix.");
            if(!LocalGpsService.prefs(activity).getBoolean("background_explained",false)){
                LocalGpsService.prefs(activity).edit().putBoolean("background_explained",true).apply();backgroundHelp();
            }
        }else toast("Android paused GPS. Check permissions and try again with Patrol Link open.");
    }
    private void backgroundHelp(){
        if(!LocalGpsService.hasPermission(activity)){requestStart();return;}
        if(Build.VERSION.SDK_INT<29){toast("This Android version does not require a separate background location approval.");return;}
        boolean granted=activity.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED;
        new AlertDialog.Builder(activity).setTitle(granted?"GPS permissions":"Allow screen-off/background location")
            .setMessage("In Android's app settings, select Permissions → Location → Allow all the time and enable Use precise location. These are Android approvals, not a website setup. The app cannot approve them for you.\n\nGPS started while Patrol Link is open uses a visible location service. Declining the separate background permission does not disable Silvertracker. To change power restrictions, use the app's Battery setting.")
            .setPositiveButton("Open Android settings",(d,w)->{
                if(Build.VERSION.SDK_INT==29&&!granted)activity.requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},BACKGROUND_REQUEST);
                else openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,true);
            }).setNegativeButton("Later",null).show();
    }
    public void permissions(int request){
        if(request==LOCATION_REQUEST){
            if(LocalGpsService.hasPermission(activity))requestStart();
            else{pendingStart=false;new AlertDialog.Builder(activity).setTitle("GPS permission not granted")
                .setMessage("Your existing patrol monitor still works. Enable Location in Android app permissions to use Patrol GPS.")
                .setPositiveButton("App permissions",(d,w)->openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,true)).setNegativeButton("Close",null).show();}
        }else if(request==BACKGROUND_REQUEST)toast("Location permissions updated.");
    }
    public void resume(){
        if(pendingStart&&LocalGpsService.hasPermission(activity)&&LocalGpsService.locationEnabled(activity))requestStart();
        else if(LocalGpsService.prefs(activity).getBoolean("enabled",false)&&!LocalGpsService.active&&LocalGpsService.hasPermission(activity)&&LocalGpsService.locationEnabled(activity))LocalGpsService.start(activity);
        handler.removeCallbacks(tick);if(dialog!=null&&dialog.isShowing())handler.post(tick);
    }
    public void pause(){handler.removeCallbacks(tick);}
    public void close(){handler.removeCallbacksAndMessages(null);if(dialog!=null)dialog.dismiss();}
    private void openSettings(String action,boolean app){try{Intent i=new Intent(action);if(app)i.setData(Uri.parse("package:"+activity.getPackageName()));activity.startActivity(i);}catch(RuntimeException e){toast("Open Android Settings → Apps → Ronin Patrol Link.");}}
    private void toast(String s){Toast.makeText(activity,s,Toast.LENGTH_LONG).show();}
}
