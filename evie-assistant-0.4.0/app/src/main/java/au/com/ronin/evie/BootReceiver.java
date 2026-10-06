package au.com.ronin.evie;

import android.content.*;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        Intent s=new Intent(c,AssistantService.class).setAction(AssistantService.ACTION_START);
        try{if(Build.VERSION.SDK_INT>=26)c.startForegroundService(s);else c.startService(s);}catch(Exception ignored){}
    }
}
