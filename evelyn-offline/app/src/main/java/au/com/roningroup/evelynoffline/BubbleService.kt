package au.com.roningroup.evelynoffline

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
            "au.com.roningroup.evelynoffline.BUBBLE_ENABLE"

        const val ACTION_DISABLE =
            "au.com.roningroup.evelynoffline.BUBBLE_DISABLE"

        private const val CHANNEL = "evelyn_bubble"
        private const val NOTIFICATION_ID = 922
    }

    private var wm: WindowManager? = null
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

        val windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        wm = windowManager

        val density = resources.displayMetrics.density
        val size = (58 * density).toInt()

        val backgroundShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(28, 28, 28))
            setStroke(
                (2 * density).toInt(),
                Color.rgb(243, 215, 83)
            )
        }

        val view = TextView(this).apply {
            text = "E"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = backgroundShape
            elevation = 14f
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
            x = (12 * density).toInt()
            y = (210 * density).toInt()
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

                    if (abs(dx) > 8 || abs(dy) > 8) {
                        moved = true
                    }

                    params.x = startX + dx
                    params.y = startY + dy

                    runCatching {
                        windowManager.updateViewLayout(
                            view,
                            params
                        )
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        readClipboard()
                    }
                    true
                }

                else -> false
            }
        }

        windowManager.addView(view, params)
        bubble = view
    }

    private fun readClipboard() {
        startActivity(
            Intent(
                this,
                ClipboardProxyActivity::class.java
            )
                .setAction(
                    ClipboardProxyActivity.ACTION_READ_CLIPBOARD
                )
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
        )
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
        val openPi = PendingIntent.getActivity(
            this,
            20,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(
                android.R.drawable.ic_btn_speak_now
            )
            .setContentTitle(
                "Evelyn button is active"
            )
            .setContentText(
                "Copy text, then tap E to read it."
            )
            .setContentIntent(openPi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        bubble?.let { view ->
            runCatching {
                wm?.removeView(view)
            }
        }

        bubble = null
        wm = null

        super.onDestroy()
    }
}
