package au.com.ronin.evie;

import android.app.Notification;
import android.content.Intent;
import android.service.notification.*;

public class EvieNotificationListener extends NotificationListenerService {
    @Override public void onNotificationPosted(StatusBarNotification sbn){
        if(sbn==null||!"com.openai.chatgpt".equals(sbn.getPackageName()))return;
        SettingsStore s=new SettingsStore(this);if(!s.autoReadChatGpt())return;
        Notification n=sbn.getNotification();
        CharSequence text=n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if(text==null)text=n.extras.getCharSequence(Notification.EXTRA_TEXT);
        Intent i=new Intent(this,AssistantService.class).setAction(AssistantService.ACTION_CHATGPT_REPLY);
        if(text!=null)i.putExtra("snippet",text.toString());
        try{startForegroundService(i);}catch(Exception e){try{startService(i);}catch(Exception ignored){}}
    }
}
