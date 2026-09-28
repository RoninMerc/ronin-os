package au.com.roningroup.evieassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        if (Prefs.wakeEnabled(context)) {
            runCatching {
                AssistantService.startWake(context)
            }
        }

        if (Prefs.bubbleEnabled(context) &&
            Settings.canDrawOverlays(context)
        ) {
            val i = Intent(context, BubbleService::class.java)
                .setAction(BubbleService.ACTION_ENABLE)

            runCatching {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }
        }
    }
}
