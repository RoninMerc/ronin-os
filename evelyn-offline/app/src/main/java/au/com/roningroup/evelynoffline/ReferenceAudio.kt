package au.com.roningroup.evelynoffline

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

data class EvelynReference(
    val samples: FloatArray,
    val sampleRate: Int
)

object ReferenceAudio {
    @Volatile private var cached: EvelynReference? = null

    @Synchronized
    fun load(context: Context): EvelynReference {
        cached?.let { return it }

        val temp = File(context.cacheDir, "evelyn_reference.flac")
        if (!temp.isFile || temp.length() < 1000L) {
            context.assets.open("evelyn_reference.flac").use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output, 128 * 1024)
                }
            }
        }

        val decoded = decode(temp)
        cached = decoded
        return decoded
    }

    private fun decode(file: File): EvelynReference {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var trackIndex = -1
        var format: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }

        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalStateException("Embedded Evelyn reference audio is unreadable.")
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalStateException("Embedded Evelyn audio has no codec.")

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var output = FloatArray(sampleRate * 8)
        var count = 0
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Could not allocate audio input buffer.")
                        val n = extractor.readSampleData(inputBuffer, 0)

                        if (n < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                n,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                .coerceAtLeast(1)
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        val wanted = sampleRate * 8
                        if (wanted > output.size) output = output.copyOf(wanted)
                    }

                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)

                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val frames = info.size / 4 / channels
                                repeat(frames) {
                                    var sum = 0f
                                    repeat(channels) { sum += buffer.float }
                                    if (count < output.size) output[count++] = sum / channels
                                }
                            } else {
                                val frames = info.size / 2 / channels
                                repeat(frames) {
                                    var sum = 0f
                                    repeat(channels) {
                                        sum += buffer.short.toFloat() / 32768f
                                    }
                                    if (count < output.size) output[count++] = sum / channels
                                }
                            }
                        }

                        outputDone =
                            (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 ||
                            count >= output.size

                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        if (count < sampleRate * 2) {
            throw IllegalStateException("Embedded Evelyn reference is too short after decoding.")
        }

        val samples = output.copyOf(count)
        var peak = 0f
        for (v in samples) {
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
        }
        if (peak > 1f) {
            for (i in samples.indices) samples[i] /= peak
        }

        return EvelynReference(samples, sampleRate)
    }
}
