package au.com.roningroup.evelynreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

class BubbleService : Service() {
    companion object {
        const val ACTION_ENABLE = "au.com.roningroup.evelynreader.BUBBLE_ENABLE"
        const val ACTION_DISABLE = "au.com.roningroup.evelynreader.BUBBLE_DISABLE"

        private const val CHANNEL = "evelyn_bubble"
        private const val NOTIFICATION_ID = 712
    }

    private var windowManager: WindowManager? = null
    private var bubble: View? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE -> {
                Prefs.setBubbleEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                Prefs.setBubbleEnabled(this, true)
                startForeground(NOTIFICATION_ID, notification())
                showBubble()
            }
        }
        return START_STICKY
    }

    private fun showBubble() {
        if (bubble != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val size = (56 * resources.displayMetrics.density).toInt()
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(32, 32, 32))
            setStroke(
                (2 * resources.displayMetrics.density).toInt(),
                Color.rgb(245, 220, 92)
            )
        }

        val view = TextView(this).apply {
            text = "E"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = shape
            elevation = 12f
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * resources.displayMetrics.density).toInt()
            y = (220 * resources.displayMetrics.density).toInt()
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (touchX - event.rawX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { wm.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) readClipboard()
                    true
                }
                else -> false
            }
        }

        wm.addView(view, params)
        bubble = view
    }

    private fun readClipboard() {
        val i = Intent(this, ClipboardProxyActivity::class.java)
            .setAction(ClipboardProxyActivity.ACTION_READ_CLIPBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        startActivity(i)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "Evelyn floating button",
                        NotificationManager.IMPORTANCE_MIN
                    )
                )
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            20,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Evelyn button is active")
            .setContentText("Copy text, then tap the E bubble to read it.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        bubble?.let { v ->
            runCatching { windowManager?.removeView(v) }
        }
        bubble = null
        windowManager = null
        super.onDestroy()
    }
}
