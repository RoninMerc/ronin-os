package au.com.roningroup.patrollink;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Persistent foreground monitor. Explicit sign-out is handled only by the app's Exit control. */
public final class MonitorService extends Service implements PatrolEngine.Listener {
    private static final int ID = 7001;
    private static final String CHANNEL = "patrol_monitor";
    private PatrolEngine engine;
    private boolean destroyed;
    private PowerManager.WakeLock wakeLock;
    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Patrol monitoring", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Persistent 30-second Silvertracker activity monitor."); channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); else startForeground(ID, notification());
            if (wakeLock == null) {
                wakeLock = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoninPatrol:monitor");
                wakeLock.setReferenceCounted(false); wakeLock.acquire(6 * 60 * 60 * 1000L);
            }
            ((PatrolApp)getApplication()).prepare((prepared,failure)->{
                if(destroyed)return;
                if(failure!=null){StartupState.failed(this,"engine",failure);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return;}
                engine=prepared;engine.add(this);
                try{if(!engine.running)engine.start();}
                catch(RuntimeException|LinkageError problem){StartupState.failed(this,"engine",problem);engine.stopRuntime("OS_LIMIT","Monitoring could not start; saved settings retained.");stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
            });
        } catch (RuntimeException ex) {
            if(engine!=null)engine.status("OS_LIMIT", "Android did not allow background monitoring. Opening Patrol Link restarts it."); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
        return START_STICKY;
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("TRL Patrol Link")
                .setContentText("30-second Silvertracker feed · " + (engine==null?"Starting":engine.sourceStatus())).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
    }
    @Override public void changed() { if (engine!=null&&engine.running) getSystemService(NotificationManager.class).notify(ID, notification()); }
    @Override public void onTimeout(int startId, int fgsType) {
        if(engine==null){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return;}
        if (engine.carConnected || engine.appVisible) {
            engine.notifyChanged(); // A service lifecycle event is not a successful feed read.
        } else engine.stopRuntime("OS_LIMIT", "Android background-service time budget ended. Opening Patrol Link restarts monitoring without signing out.");
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onTaskRemoved(Intent rootIntent) { if (engine!=null&&!engine.running) engine.start(); super.onTaskRemoved(rootIntent); }
    @Override public void onDestroy() { destroyed=true; if (engine != null) engine.remove(this); if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
