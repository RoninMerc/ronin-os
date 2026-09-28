package au.com.roningroup.evelynoffline

import android.content.Context
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig

class EvelynEngine(private val context: Context) {
    @Volatile private var tts: OfflineTts? = null

    @Synchronized
    fun get(): OfflineTts {
        tts?.let { return it }

        val pocket = OfflineTtsPocketModelConfig(
            lmFlow = "pocket/lm_flow.int8.onnx",
            lmMain = "pocket/lm_main.int8.onnx",
            encoder = "pocket/encoder.onnx",
            decoder = "pocket/decoder.int8.onnx",
            textConditioner = "pocket/text_conditioner.onnx",
            vocabJson = "pocket/vocab.json",
            tokenScoresJson = "pocket/token_scores.json",
            voiceEmbeddingCacheCapacity = 4
        )

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                pocket = pocket,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1,
            silenceScale = 0.2f
        )

        return OfflineTts(
            assetManager = context.assets,
            config = config
        ).also { tts = it }
    }

    fun generationConfig(reference: EvelynReference, speed: Float): GenerationConfig =
        GenerationConfig(
            silenceScale = 0.2f,
            speed = speed.coerceIn(0.75f, 1.30f),
            sid = 0,
            referenceAudio = reference.samples,
            referenceSampleRate = reference.sampleRate,
            referenceText = null,
            numSteps = 5,
            extra = null
        )

    @Synchronized
    fun release() {
        runCatching { tts?.release() }
        tts = null
    }
}
