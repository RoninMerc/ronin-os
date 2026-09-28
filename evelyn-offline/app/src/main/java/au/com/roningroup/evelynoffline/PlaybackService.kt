package au.com.roningroup.evelynoffline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class PlaybackService : Service() {
    companion object {
        const val ACTION_SPEAK = "au.com.roningroup.evelynoffline.SPEAK"
        const val ACTION_STOP = "au.com.roningroup.evelynoffline.STOP"
        const val ACTION_PAUSE = "au.com.roningroup.evelynoffline.PAUSE"
        const val ACTION_RESUME = "au.com.roningroup.evelynoffline.RESUME"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SPEED = "speed"

        private const val CHANNEL = "evelyn_offline_playback"
        private const val NOTIFICATION_ID = 921

        fun speak(context: Context, text: String, speed: Float = 1.0f) {
            val i = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_SPEAK)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_SPEED, speed)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun action(context: Context, action: String) {
            val i = Intent(context, PlaybackService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong(0)
    private val paused = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var track: AudioTrack? = null
    private lateinit var engineHolder: EvelynEngine

    override fun onCreate() {
        super.onCreate()
        engineHolder = EvelynEngine(applicationContext)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SPEAK -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().trim()
                val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
                if (text.isNotEmpty()) begin(text, speed)
            }
            ACTION_STOP -> stopNow()
            ACTION_PAUSE -> {
                paused.set(true)
                runCatching { track?.pause() }
                updateNotification("Paused")
            }
            ACTION_RESUME -> {
                paused.set(false)
                runCatching { track?.play() }
                updateNotification("Evelyn is reading")
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(rawText: String, speed: Float) {
        val token = generation.incrementAndGet()
        paused.set(false)

        runCatching {
            track?.pause()
            track?.flush()
            track?.stop()
        }

        startForeground(NOTIFICATION_ID, notification("Loading Evelyn…"))

        executor.execute {
            runSpeech(rawText, speed, token)
        }
    }

    private fun stopNow() {
        generation.incrementAndGet()
        paused.set(false)
        runCatching {
            track?.pause()
            track?.flush()
            track?.stop()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun runSpeech(rawText: String, speed: Float, token: Long) {
        val text = sanitise(rawText)
        val chunks = chunkText(text)

        if (chunks.isEmpty()) {
            toast("There is no readable text.")
            finishToken(token)
            return
        }

        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EvelynOffline:Playback"
            ).apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L)
            }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            updateNotification("Preparing Evelyn voice…")
            ensureActive(token)

            val reference = ReferenceAudio.load(applicationContext)
            ensureActive(token)

            val engine = engineHolder.get()
            ensureActive(token)

            val sampleRate = engine.sampleRate()
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate)

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuffer * 2)
                .build()

            track = audioTrack

            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )

            audioTrack.play()

            for (index in chunks.indices) {
                ensureActive(token)
                waitIfPaused(token, audioTrack)

                updateNotification(
                    if (chunks.size == 1) "Evelyn is reading"
                    else "Evelyn is reading · " + (index + 1) + "/" + chunks.size
                )

                val config = engineHolder.generationConfig(reference, speed)

                engine.generateWithConfigAndCallback(
                    chunks[index],
                    config
                ) { samples ->
                    if (generation.get() != token) {
                        return@generateWithConfigAndCallback 0
                    }

                    waitIfPaused(token, audioTrack)

                    if (samples.isNotEmpty()) {
                        val pcm = ShortArray(samples.size)
                        for (i in samples.indices) {
                            pcm[i] = (
                                samples[i].coerceIn(-1f, 1f) * 32767f
                            ).roundToInt().toShort()
                        }

                        var offset = 0
                        while (offset < pcm.size) {
                            ensureActive(token)
                            waitIfPaused(token, audioTrack)

                            val written = audioTrack.write(
                                pcm,
                                offset,
                                pcm.size - offset,
                                AudioTrack.WRITE_BLOCKING
                            )

                            if (written < 0) {
                                throw IllegalStateException(
                                    "Android audio output failed: " + written
                                )
                            }

                            if (written == 0) continue
                            offset += written
                        }
                    }

                    if (generation.get() == token) 1 else 0
                }
            }

            ensureActive(token)
            updateNotification("Finished")
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            val message = when (t) {
                is OutOfMemoryError ->
                    "Phone ran out of memory while loading Evelyn."
                else ->
                    t.message ?: t.javaClass.simpleName
            }
            toast(message)
            updateNotification("Evelyn error")
        } finally {
            val current = track
            runCatching {
                if (current != null &&
                    current.playState != AudioTrack.PLAYSTATE_STOPPED
                ) {
                    current.stop()
                }
            }
            runCatching { current?.flush() }
            runCatching { current?.release() }
            if (track === current) track = null

            if (wakeLock.isHeld) wakeLock.release()

            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)

            finishToken(token)
        }
    }

    private fun waitIfPaused(token: Long, audioTrack: AudioTrack) {
        while (paused.get()) {
            ensureActive(token)
            Thread.sleep(40)
        }
        if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { audioTrack.play() }
        }
    }

    private fun ensureActive(token: Long) {
        if (generation.get() != token) throw InterruptedException()
    }

    private fun finishToken(token: Long) {
        if (generation.get() == token) {
            main.postDelayed({
                if (generation.get() == token) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }, 700)
        }
    }

    private fun chunkText(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val rawParts = text
            .replace("\r\n", "\n")
            .split(Regex("(?<=[.!?])\\s+|\\n+"))

        val out = mutableListOf<String>()
        val buffer = StringBuilder()
        val maxChars = 320

        fun flush() {
            val value = buffer.toString().trim()
            if (value.isNotEmpty()) out += value
            buffer.setLength(0)
        }

        for (raw in rawParts) {
            var part = raw.trim()
            if (part.isEmpty()) continue

            while (part.length > maxChars) {
                if (buffer.isNotEmpty()) flush()

                var cut = part.lastIndexOfAny(
                    charArrayOf(' ', ',', ';', ':'),
                    maxChars
                )
                if (cut < 100) cut = maxChars

                out += part.substring(0, cut).trim()
                part = part.substring(cut).trim()
            }

            if (buffer.isNotEmpty() &&
                buffer.length + part.length + 1 > maxChars
            ) {
                flush()
            }

            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(part)
        }

        flush()
        return out
    }

    private fun sanitise(text: String): String {
        var s = text
        s = s.replace(
            Regex("(?s)\\x60\\x60\\x60.*?\\x60\\x60\\x60"),
            " Code block omitted. "
        )
        s = s.replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        s = s.replace(Regex("(?m)^#{1,6}\\s*"), "")
        s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        s = s.replace("**", "")
            .replace("__", "")
            .replace(96.toChar().toString(), "")
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "Evelyn playback",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }
    }

    private fun notification(content: String): Notification {
        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openPi = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            flags
        )

        val pausePi = PendingIntent.getService(
            this,
            2,
            Intent(this, PlaybackService::class.java)
                .setAction(ACTION_PAUSE),
            flags
        )

        val stopPi = PendingIntent.getService(
            this,
            3,
            Intent(this, PlaybackService::class.java)
                .setAction(ACTION_STOP),
            flags
        )

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Evelyn")
            .setContentText(content)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                pausePi
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPi
            )
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(content))
    }

    private fun toast(message: String) {
        main.post {
            Toast.makeText(
                applicationContext,
                "Evelyn: " + message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        runCatching {
            track?.pause()
            track?.flush()
            track?.stop()
            track?.release()
        }
        track = null
        engineHolder.release()
        executor.shutdownNow()
        super.onDestroy()
    }
}
