package au.com.ronin.evie;

import android.app.*;
import android.content.*;
import android.os.Build;

public class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        String text=i.getStringExtra("text");if(text==null)text="Reminder";
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel("evie_reminders","Evie reminders",NotificationManager.IMPORTANCE_HIGH));
        Notification n=new Notification.Builder(c,Build.VERSION.SDK_INT>=26?"evie_reminders":null)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Evie reminder").setContentText(text).setAutoCancel(true).build();
        nm.notify((int)(System.currentTimeMillis()%100000),n);
        Intent s=new Intent(c,AssistantService.class).setAction(AssistantService.ACTION_SPEAK_REMINDER).putExtra("text",text);
        try{if(Build.VERSION.SDK_INT>=26)c.startForegroundService(s);else c.startService(s);}catch(Exception ignored){}
    }
}
