package au.com.roningroup.evieassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AssistantService : Service(), RecognitionListener {
    companion object {
        const val ACTION_START_WAKE =
            "au.com.roningroup.evieassistant.START_WAKE"
        const val ACTION_STOP_WAKE =
            "au.com.roningroup.evieassistant.STOP_WAKE"
        const val ACTION_LISTEN_ONCE =
            "au.com.roningroup.evieassistant.LISTEN_ONCE"
        const val ACTION_COMMAND =
            "au.com.roningroup.evieassistant.COMMAND"
        const val ACTION_SPEAK_ONLY =
            "au.com.roningroup.evieassistant.SPEAK_ONLY"
        const val EXTRA_TEXT = "text"

        private const val CHANNEL = "evie_assistant"
        private const val NOTIFICATION_ID = 501
        private val wakeRunningState = AtomicBoolean(false)

        fun isWakeRunning(): Boolean = wakeRunningState.get()

        fun startWake(context: android.content.Context) {
            val i = Intent(context, AssistantService::class.java)
                .setAction(ACTION_START_WAKE)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stopWake(context: android.content.Context) {
            context.startService(
                Intent(context, AssistantService::class.java)
                    .setAction(ACTION_STOP_WAKE)
            )
        }

        fun listenOnce(context: android.content.Context) {
            val i = Intent(context, AssistantService::class.java)
                .setAction(ACTION_LISTEN_ONCE)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun submitText(context: android.content.Context, text: String) {
            val i = Intent(context, AssistantService::class.java)
                .setAction(ACTION_COMMAND)
                .putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun speakOnly(context: android.content.Context, text: String) {
            val i = Intent(context, AssistantService::class.java)
                .setAction(ACTION_SPEAK_ONLY)
                .putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val voiceWorker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private var recognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private var recognizerErrorStreak = 0

    @Volatile private var wakeMode = false
    @Volatile private var activeCommandMode = false
    @Volatile private var listening = false

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    private var mediaPlayer: MediaPlayer? = null
    private var currentVoiceFile: File? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        setupTts()
        setupRecognizer()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private enum class ForegroundMode {
        MICROPHONE,
        ASSISTANT,
        PLAYBACK
    }

    private fun promoteForeground(
        content: String,
        mode: ForegroundMode
    ) {
        val n = notification(content)

        if (Build.VERSION.SDK_INT >= 34) {
            val type = when (mode) {
                ForegroundMode.MICROPHONE ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                ForegroundMode.ASSISTANT ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                ForegroundMode.PLAYBACK ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }

            startForeground(
                NOTIFICATION_ID,
                n,
                type
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            when (mode) {
                ForegroundMode.MICROPHONE ->
                    startForeground(
                        NOTIFICATION_ID,
                        n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )

                ForegroundMode.PLAYBACK ->
                    startForeground(
                        NOTIFICATION_ID,
                        n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )

                ForegroundMode.ASSISTANT ->
                    startForeground(
                        NOTIFICATION_ID,
                        n
                    )
            }
        } else {
            startForeground(
                NOTIFICATION_ID,
                n
            )
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START_WAKE -> {
                wakeMode = true
                wakeRunningState.set(true)
                Prefs.setWakeEnabled(this, true)
                promoteForeground(
                    "Hey Evie is listening",
                    ForegroundMode.MICROPHONE
                )
                startWakeListening(250)
            }

            ACTION_STOP_WAKE -> {
                wakeMode = false
                wakeRunningState.set(false)
                activeCommandMode = false
                Prefs.setWakeEnabled(this, false)
                stopRecognizer()
                stopVoicePlayback()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_LISTEN_ONCE -> {
                promoteForeground(
                    "Evie is listening…",
                    ForegroundMode.MICROPHONE
                )
                activeCommandMode = true
                startListeningNow()
            }

            ACTION_COMMAND -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().trim()
                if (text.isNotBlank()) {
                    promoteForeground(
                        "Evie is thinking…",
                        ForegroundMode.ASSISTANT
                    )
                    submitCommand(text)
                }
            }

            ACTION_SPEAK_ONLY -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().trim()
                if (text.isNotBlank()) {
                    promoteForeground(
                        "Evie is speaking…",
                        ForegroundMode.PLAYBACK
                    )
                    if (busy.compareAndSet(false, true)) {
                        speakReply(text)
                    }
                }
            }
        }

        return if (wakeMode) START_STICKY else START_NOT_STICKY
    }

    private fun setupTts() {
        tts = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(1.04f)
                tts?.setPitch(1.03f)
                tts?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == "evie_reply") {
                                busy.set(false)
                                if (wakeMode) {
                                    main.postDelayed(
                                        { startWakeListening(0) },
                                        250
                                    )
                                } else {
                                    main.post { finishIfIdle() }
                                }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId == "evie_reply") {
                                busy.set(false)
                                if (wakeMode) {
                                    main.postDelayed(
                                        { startWakeListening(0) },
                                        500
                                    )
                                } else {
                                    main.post { finishIfIdle() }
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(this@AssistantService)
        }

        recognizerIntent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                true
            )
            putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                5
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                900L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                600L
            )
        }
    }

    private fun startWakeListening(delay: Long) {
        if (!wakeMode || busy.get()) return

        main.postDelayed({
            if (!wakeMode || busy.get()) return@postDelayed

            runCatching {
                promoteForeground(
                    "Hey Evie is listening",
                    ForegroundMode.MICROPHONE
                )
            }.onFailure {
                wakeRunningState.set(false)
                toast(
                    "Android blocked microphone restart: " +
                        (it.message ?: it.javaClass.simpleName)
                )
                return@postDelayed
            }

            activeCommandMode = false
            startListeningNow()
        }, delay)
    }

    private fun startListeningNow() {
        val sr = recognizer
        if (sr == null) {
            toast("Speech recognition is unavailable on this phone.")
            return
        }

        if (listening) {
            runCatching { sr.cancel() }
            listening = false
        }

        updateNotification(
            if (activeCommandMode) "Evie is listening…"
            else "Hey Evie is listening"
        )

        runCatching {
            sr.startListening(recognizerIntent)
            listening = true
        }.onFailure {
            if (wakeMode) {
                main.postDelayed(
                    { startWakeListening(0) },
                    700
                )
            }
        }
    }

    private fun stopRecognizer() {
        listening = false
        runCatching { recognizer?.cancel() }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        listening = true
        recognizerErrorStreak = 0
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onError(error: Int) {
        listening = false
        recognizerErrorStreak =
            (recognizerErrorStreak + 1).coerceAtMost(8)

        if (wakeMode && !busy.get()) {
            val delay = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    1_500L + recognizerErrorStreak * 350L

                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
                    (5_000L + recognizerErrorStreak * 1_000L)
                        .coerceAtMost(15_000L)

                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    900L + recognizerErrorStreak * 200L

                else ->
                    1_200L + recognizerErrorStreak * 300L
            }

            main.postDelayed(
                { startWakeListening(0) },
                delay
            )
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (activeCommandMode) return

        val phrases = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()

        for (phrase in phrases) {
            val afterWake = extractAfterWake(phrase) ?: continue

            stopRecognizer()

            if (afterWake.isBlank()) {
                activeCommandMode = true
                speakImmediate("Yeah?")
                main.postDelayed(
                    { startListeningNow() },
                    650
                )
            } else {
                submitCommand(afterWake)
            }
            return
        }
    }

    override fun onResults(results: Bundle?) {
        listening = false
        recognizerErrorStreak = 0

        val phrases = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()

        val best = phrases.firstOrNull()?.trim().orEmpty()

        if (activeCommandMode) {
            activeCommandMode = false
            if (best.isNotBlank()) submitCommand(best)
            else if (wakeMode) startWakeListening(500)
            return
        }

        for (phrase in phrases) {
            val afterWake = extractAfterWake(phrase) ?: continue

            if (afterWake.isBlank()) {
                activeCommandMode = true
                speakImmediate("Yeah?")
                main.postDelayed(
                    { startListeningNow() },
                    650
                )
            } else {
                submitCommand(afterWake)
            }
            return
        }

        if (wakeMode) startWakeListening(350)
    }

    private fun extractAfterWake(value: String): String? {
        val lower = value.lowercase(Locale.ROOT)
        val variants = listOf(
            "hey evie",
            "hey evey",
            "okay evie",
            "ok evie",
            "evie"
        )

        for (variant in variants) {
            val index = lower.indexOf(variant)
            if (index < 0) continue

            return value.substring(
                (index + variant.length).coerceAtMost(value.length)
            )
                .trim()
                .trimStart(',', '.', '-', ':')
                .trim()
        }

        return null
    }

    private fun submitCommand(text: String) {
        if (text.isBlank()) return

        if (text.lowercase(Locale.ROOT) in listOf(
                "stop listening",
                "go to sleep",
                "stop hey evie",
                "turn off wake word"
            )
        ) {
            speakImmediate("Going quiet.")
            wakeMode = false
            Prefs.setWakeEnabled(this, false)
            main.postDelayed({
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }, 500)
            return
        }

        if (!busy.compareAndSet(false, true)) {
            speakImmediate("One sec, I'm still doing the last thing.")
            return
        }

        stopRecognizer()
        stopVoicePlayback()

        runCatching {
            promoteForeground(
                "Evie is thinking…",
                ForegroundMode.ASSISTANT
            )
        }

        updateNotification("Evie is thinking…")

        worker.execute {
            try {
                val result = AgentClient(applicationContext)
                    .runCommand(text)

                val reply = result.reply.trim()

                main.post {
                    if (Prefs.speakEnabled(this)) {
                        speakReply(reply)
                    } else {
                        toast(reply)
                        busy.set(false)
                        if (wakeMode) startWakeListening(400)
                        else finishIfIdle()
                    }
                }
            } catch (t: Throwable) {
                val message =
                    "I hit an error: " +
                        (t.message ?: t.javaClass.simpleName)

                main.post {
                    speakReply(message)
                }
            }
        }
    }

    private fun speakReply(text: String) {
        runCatching {
            promoteForeground(
                "Evie is speaking…",
                ForegroundMode.PLAYBACK
            )
        }

        updateNotification("Evie: " + text.take(80))

        if (text.isBlank()) {
            busy.set(false)
            if (wakeMode) startWakeListening(350)
            else finishIfIdle()
            return
        }

        val useQwen =
            Prefs.voiceMode(this).equals("qwen", ignoreCase = true) &&
                Prefs.qwenTtsUrl(this).isNotBlank()

        if (useQwen) {
            speakReplyWithQwen(text)
        } else {
            speakReplyWithAndroid(text)
        }
    }

    private fun speakReplyWithAndroid(text: String) {
        if (!ttsReady) {
            toast(text)
            busy.set(false)
            if (wakeMode) startWakeListening(500)
            else finishIfIdle()
            return
        }

        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "evie_reply"
        )
    }

    private fun speakReplyWithQwen(text: String) {
        updateNotification("Evie is generating her voice…")

        voiceWorker.execute {
            try {
                val bytes = QwenVoiceClient.synthesize(
                    applicationContext,
                    text
                )

                if (bytes.size < 44) {
                    throw IllegalStateException(
                        "Qwen3-TTS returned an empty or invalid audio response."
                    )
                }

                val file = File(
                    cacheDir,
                    "evie_qwen_" + System.currentTimeMillis() + ".wav"
                )

                FileOutputStream(file).use { out ->
                    out.write(bytes)
                }

                currentVoiceFile = file

                main.post {
                    playQwenFile(file)
                }
            } catch (t: Throwable) {
                main.post {
                    toast(
                        "Qwen3-TTS failed, using Android voice: " +
                            (t.message ?: t.javaClass.simpleName)
                    )
                    speakReplyWithAndroid(text)
                }
            }
        }
    }

    private fun playQwenFile(file: File) {
        stopVoicePlayback()

        val player = MediaPlayer()
        mediaPlayer = player

        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            player.setDataSource(file.absolutePath)

            player.setOnPreparedListener { mp ->
                updateNotification("Evie is speaking")
                mp.start()
            }

            player.setOnCompletionListener {
                finishQwenPlayback()
            }

            player.setOnErrorListener { _, _, _ ->
                toast("Qwen3-TTS audio playback failed.")
                finishQwenPlayback()
                true
            }

            player.prepareAsync()
        } catch (t: Throwable) {
            toast(
                "Qwen3-TTS playback error: " +
                    (t.message ?: t.javaClass.simpleName)
            )
            finishQwenPlayback()
        }
    }

    private fun finishQwenPlayback() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null

        currentVoiceFile?.let {
            runCatching { it.delete() }
        }
        currentVoiceFile = null

        busy.set(false)

        if (wakeMode) {
            startWakeListening(300)
        } else {
            finishIfIdle()
        }
    }

    private fun stopVoicePlayback() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null

        currentVoiceFile?.let {
            runCatching { it.delete() }
        }
        currentVoiceFile = null
    }

    private fun speakImmediate(text: String) {
        if (ttsReady) {
            tts?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "evie_prompt"
            )
        } else {
            toast(text)
        }
    }

    private fun finishIfIdle() {
        if (!wakeMode && !busy.get()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "Evie Assistant",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }
    }

    private fun notification(content: String): Notification {
        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE

        val openPi = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            flags
        )

        val listenPi = PendingIntent.getActivity(
            this,
            2,
            Intent(this, VoiceLaunchActivity::class.java)
                .putExtra(
                    VoiceLaunchActivity.EXTRA_MODE,
                    VoiceLaunchActivity.MODE_LISTEN_ONCE
                )
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            flags
        )

        val stopPi = PendingIntent.getService(
            this,
            3,
            Intent(this, AssistantService::class.java)
                .setAction(ACTION_STOP_WAKE),
            flags
        )

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Evie Assistant")
            .setContentText(content)
            .setContentIntent(openPi)
            .setOngoing(wakeMode)
            .addAction(
                android.R.drawable.ic_btn_speak_now,
                "Listen",
                listenPi
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
            .notify(
                NOTIFICATION_ID,
                notification(content)
            )
    }

    private fun toast(text: String) {
        main.post {
            Toast.makeText(
                applicationContext,
                text,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        wakeRunningState.set(false)
        stopRecognizer()
        recognizer?.destroy()
        recognizer = null

        stopVoicePlayback()

        runCatching {
            tts?.stop()
            tts?.shutdown()
        }

        worker.shutdownNow()
        voiceWorker.shutdownNow()

        super.onDestroy()
    }
}
