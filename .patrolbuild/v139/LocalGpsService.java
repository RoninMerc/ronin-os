package au.com.roningroup.patrollink;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.location.*;
import android.os.*;
import java.util.*;

/** Local device position only. No network requests, account access or PatrolEngine dependency. */
public final class LocalGpsService extends Service implements LocationListener {
    public static final String PREFS="patrol_local_gps_v1", CHANNEL="patrol_local_gps";
    public static final int ID=7019;
    public static volatile boolean active;
    private LocationManager manager;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;
    private final BroadcastReceiver providers=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){subscribe();}
    };
    private final Runnable pulse=new Runnable(){public void run(){
        if(!prefs(LocalGpsService.this).getBoolean("enabled",false)){stopSelf();return;}
        if(!hasPermission(LocalGpsService.this)){
            status("Location permission removed. Open Patrol GPS to allow it again.");stopSelf();return;
        }
        prefs(LocalGpsService.this).edit().putLong("heartbeat",System.currentTimeMillis()).apply();
        try{getSystemService(NotificationManager.class).notify(ID,notification());}catch(RuntimeException ignored){}
        handler.postDelayed(this,interval(LocalGpsService.this));
    }};
    public static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static long interval(Context c){return prefs(c).getInt("interval_seconds",15)==30?30000L:15000L;}
    public static boolean hasPermission(Context c){return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    public static boolean precise(Context c){return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    public static boolean locationEnabled(Context c){try{LocationManager m=c.getSystemService(LocationManager.class);return m!=null&&m.isLocationEnabled();}catch(RuntimeException e){return false;}}
    public static boolean start(Context c){
        if(!hasPermission(c)){prefs(c).edit().putString("status","Location permission is required.").apply();return false;}
        prefs(c).edit().putBoolean("enabled",true).apply();
        try{c.startForegroundService(new Intent(c,LocalGpsService.class));return true;}
        catch(RuntimeException e){prefs(c).edit().putString("status","Android paused GPS. Open Patrol GPS and tap Start GPS.").apply();return false;}
    }
    public static void stop(Context c){
        prefs(c).edit().putBoolean("enabled",false).putString("status","GPS stopped. Last position retained.").apply();
        c.stopService(new Intent(c,LocalGpsService.class));
    }
    @Override public void onCreate(){
        super.onCreate();manager=getSystemService(LocationManager.class);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"Local patrol GPS",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Visible GPS collection for this device only. Stop it from the notification or Patrol GPS.");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"STOP_GPS".equals(intent.getAction())){stop(this);stopSelf();return START_NOT_STICKY;}
        if(!prefs(this).getBoolean("enabled",false)||!hasPermission(this)){status("GPS stopped or permission unavailable.");stopSelf();return START_NOT_STICKY;}
        try{
            if(Build.VERSION.SDK_INT>=29)startForeground(ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(ID,notification());
            active=true;
            if(!receiverRegistered){
                IntentFilter f=new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION);
                if(Build.VERSION.SDK_INT>=33)registerReceiver(providers,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(providers,f);
                receiverRegistered=true;
            }
            subscribe();handler.removeCallbacks(pulse);handler.post(pulse);
            return START_STICKY;
        }catch(RuntimeException e){
            status("Android could not start GPS. Check location permission and tap Start GPS with the app open.");
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;
        }
    }
    private void subscribe(){
        if(manager==null){status("This device has no location service.");return;}
        if(!hasPermission(this)){status("Location permission is required.");stopSelf();return;}
        try{manager.removeUpdates(this);}catch(RuntimeException ignored){}
        boolean subscribed=false;
        for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}){
            try{
                if(!manager.getAllProviders().contains(provider))continue;
                manager.requestLocationUpdates(provider,interval(this),0f,this,Looper.getMainLooper());subscribed=true;
                Location cached=manager.getLastKnownLocation(provider);if(cached!=null)saveFix(this,cached);
            }catch(SecurityException ignored){}catch(IllegalArgumentException ignored){}
        }
        status(!locationEnabled(this)?"Device location is off. Enable Location in Android settings.":subscribed?"GPS running — waiting for a current position.":"No permitted location provider is available.");
    }
    /** Atomic preference snapshot; cached, invalid and out-of-order fixes never become fresh readings. */
    public static boolean saveFix(Context c,Location fix){
        if(fix==null)return false;
        long now=SystemClock.elapsedRealtime(),elapsed=fix.getElapsedRealtimeNanos()/1000000L;
        if(!GpsFixPolicy.valid(fix.getLatitude(),fix.getLongitude(),fix.hasAccuracy()?fix.getAccuracy():Float.NaN,elapsed,now))return false;
        SharedPreferences p=prefs(c);
        long boot=System.currentTimeMillis()-now,oldBoot=p.getLong("boot",Long.MIN_VALUE);
        boolean sameBoot=oldBoot!=Long.MIN_VALUE&&Math.abs(boot-oldBoot)<60000L;
        long previous=sameBoot?p.getLong("fix_elapsed",0):0;
        if(elapsed<previous)return false;
        float accuracy=fix.getAccuracy(),oldAccuracy=p.getFloat("accuracy",Float.MAX_VALUE);
        if(previous>0&&now-previous<interval(c)*2&&accuracy>Math.max(50f,oldAccuracy*3f))return false;
        p.edit().putLong("lat",Double.doubleToRawLongBits(fix.getLatitude()))
            .putLong("lon",Double.doubleToRawLongBits(fix.getLongitude()))
            .putFloat("accuracy",accuracy).putLong("fix_time",fix.getTime())
            .putLong("fix_elapsed",elapsed).putLong("boot",boot)
            .putString("provider",fix.getProvider()==null?"location":fix.getProvider())
            .putBoolean("mock",fix.isFromMockProvider()).putString("status","GPS position received.").apply();
        return true;
    }
    public static long fixAgeSeconds(Context c){
        SharedPreferences p=prefs(c);long time=p.getLong("fix_time",0);if(time==0)return -1;
        long now=SystemClock.elapsedRealtime(),boot=System.currentTimeMillis()-now;
        long storedBoot=p.getLong("boot",Long.MIN_VALUE);
        long age=storedBoot!=Long.MIN_VALUE&&Math.abs(boot-storedBoot)<60000L?now-p.getLong("fix_elapsed",0):System.currentTimeMillis()-time;
        return Math.max(0,age/1000L);
    }
    @Override public void onLocationChanged(Location location){saveFix(this,location);}
    @Override public void onProviderEnabled(String p){status("Location enabled — waiting for a current position.");}
    @Override public void onProviderDisabled(String p){status("Location provider changed — last position retained.");}
    @Override public void onStatusChanged(String p,int s,Bundle b){}
    private void status(String s){prefs(this).edit().putString("status",s).apply();}
    private Notification notification(){
        Intent openIntent=new Intent(this,MainActivity.class).putExtra("open_patrol_gps",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open=PendingIntent.getActivity(this,7019,openIntent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,7020,new Intent(this,LocalGpsService.class).setAction("STOP_GPS"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        long age=fixAgeSeconds(this);
        String message=!locationEnabled(this)?"Device location off — last position retained":age<0?"Waiting for GPS · saved on this device only":age>90?"Last position is stale · saved on this device only":"GPS running · saved on this device only";
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Ronin Patrol GPS")
            .setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(new Notification.Action.Builder(null,"Stop GPS",stop).build()).build();
    }
    @Override public void onDestroy(){
        active=false;handler.removeCallbacksAndMessages(null);
        if(manager!=null)try{manager.removeUpdates(this);}catch(RuntimeException ignored){}
        if(receiverRegistered)try{unregisterReceiver(providers);}catch(RuntimeException ignored){}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
