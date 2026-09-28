package au.com.roningroup.voicereader.direct

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.PowerManager
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class PocketSpeaker(private val context: Context) {
    private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null
    private val stopFlag = AtomicBoolean(false)
    private val pauseFlag = AtomicBoolean(false)

    @Suppress("DEPRECATION")
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    @Synchronized
    private fun engine(): OfflineTts {
        tts?.let { return it }
        val dir = ModelManager.modelDir(context)
            ?: throw IllegalStateException("PocketTTS model is not installed.")

        fun p(name: String): String {
            val f = File(dir, name)
            if (!f.isFile) throw IllegalStateException("Missing PocketTTS file: $name")
            return f.absolutePath
        }

        val pocket = OfflineTtsPocketModelConfig(
            lmFlow = p("lm_flow.int8.onnx"),
            lmMain = p("lm_main.int8.onnx"),
            encoder = p("encoder.onnx"),
            decoder = p("decoder.int8.onnx"),
            textConditioner = p("text_conditioner.onnx"),
            vocabJson = p("vocab.json"),
            tokenScoresJson = p("token_scores.json"),
            voiceEmbeddingCacheCapacity = 8
        )

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                pocket = pocket,
                numThreads = threads,
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1,
            silenceScale = 0.25f
        )

        return OfflineTts(assetManager = null, config = config).also { tts = it }
    }

    fun stop() {
        stopFlag.set(true)
        pauseFlag.set(false)
        runCatching {
            track?.pause()
            track?.flush()
            track?.stop()
        }
    }

    fun pause() {
        pauseFlag.set(true)
        runCatching { track?.pause() }
    }

    fun resume() {
        pauseFlag.set(false)
        runCatching { track?.play() }
    }

    fun isPaused(): Boolean = pauseFlag.get()

    fun release() {
        stop()
        runCatching { track?.release() }
        track = null
        synchronized(this) {
            runCatching { tts?.release() }
            tts = null
        }
    }

    fun speak(
        text: String,
        reference: ReferenceAudio,
        speed: Float,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit
    ) {
        stop()
        stopFlag.set(false)
        pauseFlag.set(false)

        val clean = sanitise(text)
        val pieces = chunkText(clean)
        if (pieces.isEmpty()) throw IllegalArgumentException("There is no text to read.")

        onStatus("Loading PocketTTS…")
        val engine = engine()
        val sampleRate = engine.sampleRate()

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate / 2)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer * 2)
            .build()

        track = audioTrack

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(
            focusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RoninVoiceReader:Playback"
        ).apply { acquire(30 * 60 * 1000L) }

        try {
            audioTrack.play()

            for ((index, piece) in pieces.withIndex()) {
                if (stopFlag.get()) break
                onStatus("Reading " + (index + 1) + " of " + pieces.size)

                val config = GenerationConfig(
                    silenceScale = 0.25f,
                    speed = speed.coerceIn(0.70f, 1.35f),
                    sid = 0,
                    referenceAudio = reference.samples,
                    referenceSampleRate = reference.sampleRate,
                    referenceText = null,
                    numSteps = 5,
                    extra = mapOf(
                        "max_reference_audio_len" to "10",
                        "seed" to "42"
                    )
                )

                engine.generateWithConfigAndCallback(piece, config) { samples ->
                    if (stopFlag.get()) return@generateWithConfigAndCallback 0

                    while (pauseFlag.get() && !stopFlag.get()) {
                        Thread.sleep(40)
                    }
                    if (stopFlag.get()) return@generateWithConfigAndCallback 0

                    val pcm = ShortArray(samples.size)
                    for (i in samples.indices) {
                        pcm[i] = (samples[i].coerceIn(-1f, 1f) * 32767f)
                            .roundToInt()
                            .toShort()
                    }

                    var offset = 0
                    while (offset < pcm.size && !stopFlag.get()) {
                        while (pauseFlag.get() && !stopFlag.get()) Thread.sleep(40)
                        if (stopFlag.get()) break
                        val wrote = audioTrack.write(
                            pcm,
                            offset,
                            pcm.size - offset,
                            AudioTrack.WRITE_BLOCKING
                        )
                        if (wrote <= 0) break
                        offset += wrote
                    }
                    if (stopFlag.get()) 0 else 1
                }

                onProgress((index + 1).toFloat() / pieces.size)
            }

            if (!stopFlag.get()) {
                onStatus("Finished")
                onProgress(1f)
            } else {
                onStatus("Stopped")
            }
        } finally {
            runCatching {
                if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.stop()
                }
            }
            runCatching { audioTrack.flush() }
            runCatching { audioTrack.release() }
            if (track === audioTrack) track = null
            if (wakeLock.isHeld) wakeLock.release()
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private fun chunkText(text: String): List<String> {
        val result = mutableListOf<String>()
        val sentenceParts = text
            .replace("\r\n", "\n")
            .split(Regex("(?<=[.!?])\\s+|\\n+"))

        for (part in sentenceParts) {
            var s = part.trim()
            if (s.isEmpty()) continue
            while (s.length > 360) {
                var cut = s.lastIndexOfAny(charArrayOf(',', ';', ':', ' '), 360)
                if (cut < 120) cut = 360
                result += s.substring(0, cut).trim()
                s = s.substring(cut).trim()
            }
            if (s.isNotEmpty()) result += s
        }
        return result
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
}
