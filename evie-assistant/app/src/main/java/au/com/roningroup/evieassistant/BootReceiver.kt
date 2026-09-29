package au.com.roningroup.evieassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val CHANNEL = "evie_boot"
        private const val NOTIFICATION_ID = 503
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        if (Prefs.wakeEnabled(context)) {
            postWakeReactivationNotification(context)
        }

        if (Prefs.bubbleEnabled(context) &&
            Settings.canDrawOverlays(context)
        ) {
            val bubbleIntent = Intent(
                context,
                BubbleService::class.java
            ).setAction(BubbleService.ACTION_ENABLE)

            runCatching {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(bubbleIntent)
                } else {
                    context.startService(bubbleIntent)
                }
            }
        }
    }

    private fun postWakeReactivationNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Evie startup",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val launch = Intent(
            context,
            VoiceLaunchActivity::class.java
        )
            .putExtra(
                VoiceLaunchActivity.EXTRA_MODE,
                VoiceLaunchActivity.MODE_START_WAKE
            )
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )

        val pending = PendingIntent.getActivity(
            context,
            503,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(
            context,
            CHANNEL
        )
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Reactivate Hey Evie")
            .setContentText(
                "Android requires a tap after reboot before Evie can use the microphone."
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        runCatching {
            nm.notify(
                NOTIFICATION_ID,
                notification
            )
        }
    }
}
