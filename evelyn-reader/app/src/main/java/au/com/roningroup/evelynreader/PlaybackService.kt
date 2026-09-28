package au.com.roningroup.evelynreader

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

class PlaybackService : Service() {
    companion object {
        const val ACTION_SPEAK = "au.com.roningroup.evelynreader.SPEAK"
        const val ACTION_STOP = "au.com.roningroup.evelynreader.STOP"
        const val ACTION_PAUSE = "au.com.roningroup.evelynreader.PAUSE"
        const val ACTION_RESUME = "au.com.roningroup.evelynreader.RESUME"
        const val EXTRA_TEXT = "text"

        private const val CHANNEL = "evelyn_playback"
        private const val NOTIFICATION_ID = 711

        fun speak(context: Context, text: String) {
            val i = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_SPEAK)
                .putExtra(EXTRA_TEXT, text)
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
    @Volatile private var track: AudioTrack? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SPEAK -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().trim()
                if (text.isNotEmpty()) startSpeech(text)
            }
            ACTION_STOP -> stopSpeech()
            ACTION_PAUSE -> {
                paused.set(true)
                runCatching { track?.pause() }
                updateNotification("Paused")
            }
            ACTION_RESUME -> {
                paused.set(false)
                runCatching { track?.play() }
                updateNotification("Reading with Evelyn")
            }
        }
        return START_NOT_STICKY
    }

    private fun startSpeech(rawText: String) {
        if (!Prefs.configured(this)) {
            toast("Open Evelyn Reader first and set your ElevenLabs API key and Evelyn voice.")
            stopSelf()
            return
        }

        val token = generation.incrementAndGet()
        paused.set(false)
        runCatching {
            track?.pause()
            track?.flush()
            track?.stop()
        }
        startForeground(NOTIFICATION_ID, notification("Preparing Evelyn…"))

        executor.execute {
            speakOnWorker(rawText, token)
        }
    }

    private fun stopSpeech() {
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

    private fun speakOnWorker(rawText: String, token: Long) {
        val apiKey = Prefs.apiKey(this)
        val voiceId = Prefs.voiceId(this)
        val voiceName = Prefs.voiceName(this).ifBlank { "Evelyn" }
        val text = sanitise(rawText)
        val chunks = chunkText(text)

        if (chunks.isEmpty()) {
            toast("There is no readable text.")
            finishToken(token)
            return
        }

        val sampleRate = 24_000
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate)

        val audioTrack = try {
            AudioTrack.Builder()
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
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            toast("Audio output failed: " + (t.message ?: t.javaClass.simpleName))
            finishToken(token)
            return
        }

        track = audioTrack

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(
            null,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EvelynReader:Playback"
        ).apply { acquire(30 * 60 * 1000L) }

        try {
            audioTrack.play()

            for (index in chunks.indices) {
                ensureActive(token)
                val current = chunks[index]
                val previous = chunks.getOrNull(index - 1)
                val next = chunks.getOrNull(index + 1)

                updateNotification(
                    if (chunks.size == 1) "Reading with " + voiceName
                    else "Reading with " + voiceName + " · " + (index + 1) + "/" + chunks.size
                )

                var wroteAudio = false

                try {
                    ElevenLabsClient.streamSpeech(
                        apiKey = apiKey,
                        voiceId = voiceId,
                        text = current,
                        previousText = previous,
                        nextText = next,
                        modelId = "eleven_flash_v2_5"
                    ) { bytes, count ->
                        ensureActive(token)
                        waitIfPaused(token, audioTrack)
                        writeAll(audioTrack, bytes, count, token)
                        wroteAudio = true
                    }
                } catch (e: ElevenLabsApiException) {
                    if (!wroteAudio && (e.statusCode == 400 || e.statusCode == 422)) {
                        ElevenLabsClient.streamSpeech(
                            apiKey = apiKey,
                            voiceId = voiceId,
                            text = current,
                            previousText = previous,
                            nextText = next,
                            modelId = "eleven_multilingual_v2"
                        ) { bytes, count ->
                            ensureActive(token)
                            waitIfPaused(token, audioTrack)
                            writeAll(audioTrack, bytes, count, token)
                        }
                    } else {
                        throw e
                    }
                }
            }

            ensureActive(token)
            updateNotification("Finished")
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            toast(message)
            updateNotification("Evelyn error")
        } finally {
            runCatching {
                if (audioTrack.playState != AudioTrack.PLAYSTATE_STOPPED) audioTrack.stop()
            }
            runCatching { audioTrack.flush() }
            runCatching { audioTrack.release() }
            if (track === audioTrack) track = null
            if (wakeLock.isHeld) wakeLock.release()
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
            finishToken(token)
        }
    }

    private fun writeAll(
        audioTrack: AudioTrack,
        bytes: ByteArray,
        count: Int,
        token: Long
    ) {
        var offset = 0
        while (offset < count) {
            ensureActive(token)
            waitIfPaused(token, audioTrack)
            val n = audioTrack.write(
                bytes,
                offset,
                count - offset,
                AudioTrack.WRITE_BLOCKING
            )
            if (n < 0) throw IllegalStateException("Android audio output error " + n)
            if (n == 0) continue
            offset += n
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
        val sentences = text
            .replace("\r\n", "\n")
            .split(Regex("(?<=[.!?])\\s+|\\n+"))

        val out = mutableListOf<String>()
        val buffer = StringBuilder()
        val max = 3200

        fun flush() {
            val s = buffer.toString().trim()
            if (s.isNotEmpty()) out += s
            buffer.setLength(0)
        }

        for (raw in sentences) {
            var s = raw.trim()
            if (s.isEmpty()) continue

            while (s.length > max) {
                if (buffer.isNotEmpty()) flush()
                var cut = s.lastIndexOfAny(charArrayOf(' ', ',', ';', ':'), max)
                if (cut < 800) cut = max
                out += s.substring(0, cut).trim()
                s = s.substring(cut).trim()
            }

            if (buffer.isNotEmpty() && buffer.length + s.length + 1 > max) flush()
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(s)
        }
        flush()
        return out
    }

    private fun sanitise(text: String): String {
        var s = text
        s = s.replace(Regex("(?s)\\x60\\x60\\x60.*?\\x60\\x60\\x60"), " Code block omitted. ")
        s = s.replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        s = s.replace(Regex("(?m)^#{1,6}\\s*"), "")
        s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        s = s.replace("**", "").replace("__", "").replace(96.toChar().toString(), "")
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Evelyn playback",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun notification(content: String): Notification {
        val stopIntent = Intent(this, PlaybackService::class.java).setAction(ACTION_STOP)
        val pauseIntent = Intent(this, PlaybackService::class.java).setAction(ACTION_PAUSE)
        val openIntent = Intent(this, MainActivity::class.java)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopPi = PendingIntent.getService(this, 2, stopIntent, flags)
        val pausePi = PendingIntent.getService(this, 3, pauseIntent, flags)
        val openPi = PendingIntent.getActivity(this, 4, openIntent, flags)

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Evelyn Reader")
            .setContentText(content)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pausePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification(content))
    }

    private fun toast(message: String) {
        main.post {
            Toast.makeText(applicationContext, "Evelyn: " + message, Toast.LENGTH_LONG).show()
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
        executor.shutdownNow()
        super.onDestroy()
    }
}
