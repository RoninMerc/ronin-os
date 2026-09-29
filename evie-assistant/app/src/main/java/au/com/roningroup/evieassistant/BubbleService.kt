package au.com.roningroup.evieassistant

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
        const val ACTION_ENABLE =
            "au.com.roningroup.evieassistant.BUBBLE_ENABLE"
        const val ACTION_DISABLE =
            "au.com.roningroup.evieassistant.BUBBLE_DISABLE"

        private const val CHANNEL = "evie_bubble"
        private const val NOTIFICATION_ID = 502
    }

    private var windowManager: WindowManager? = null
    private var bubble: View? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
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
                startForeground(
                    NOTIFICATION_ID,
                    notification()
                )
                showBubble()
            }
        }

        return START_STICKY
    }

    private fun showBubble() {
        if (bubble != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val density = resources.displayMetrics.density
        val size = (62 * density).toInt()

        val background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(27, 27, 30))
            setStroke(
                (2 * density).toInt(),
                Color.rgb(232, 120, 183)
            )
        }

        val view = TextView(this).apply {
            text = "E"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            this.background = background
            elevation = 16f
            contentDescription = "Evie assistant"
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (10 * density).toInt()
            y = (230 * density).toInt()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (downX - event.rawX).toInt()
                    val dy = (event.rawY - downY).toInt()

                    if (abs(dx) > 8 || abs(dy) > 8) moved = true

                    params.x = startX + dx
                    params.y = startY + dy

                    runCatching {
                        wm.updateViewLayout(view, params)
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        startActivity(
                            Intent(this, VoiceLaunchActivity::class.java)
                                .putExtra(
                                    VoiceLaunchActivity.EXTRA_MODE,
                                    VoiceLaunchActivity.MODE_LISTEN_ONCE
                                )
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                                )
                        )
                    }
                    true
                }

                else -> false
            }
        }

        wm.addView(view, params)
        bubble = view
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "Evie floating button",
                        NotificationManager.IMPORTANCE_MIN
                    )
                )
        }
    }

    private fun notification(): Notification {
        val openPi = PendingIntent.getActivity(
            this,
            20,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Evie is available")
            .setContentText("Tap the floating E and speak a command.")
            .setContentIntent(openPi)
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
