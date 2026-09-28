package au.com.roningroup.voicereader.direct

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ReferenceAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val durationSeconds: Float
)

object AudioLoader {
    fun loadReference(file: File, maxSeconds: Int = 10): ReferenceAudio {
        return if (isWav(file)) {
            readWav(file, maxSeconds)
        } else {
            decodeWithMediaCodec(file, maxSeconds)
        }
    }

    private fun isWav(file: File): Boolean {
        if (!file.isFile || file.length() < 12) return false
        RandomAccessFile(file, "r").use { raf ->
            val h = ByteArray(12)
            raf.readFully(h)
            return String(h, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(h, 8, 4, Charsets.US_ASCII) == "WAVE"
        }
    }

    private fun readWav(file: File, maxSeconds: Int): ReferenceAudio {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)

            var formatCode = 1
            var channels = 1
            var sampleRate = 24000
            var bitsPerSample = 16
            var dataOffset = -1L
            var dataSize = 0L

            while (raf.filePointer + 8 <= raf.length()) {
                val chunkHeader = ByteArray(8)
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = leInt(chunkHeader, 4).toLong() and 0xffffffffL
                val chunkData = raf.filePointer

                when (id) {
                    "fmt " -> {
                        if (size >= 16) {
                            val fmt = ByteArray(16)
                            raf.readFully(fmt)
                            formatCode = leShort(fmt, 0)
                            channels = leShort(fmt, 2).coerceAtLeast(1)
                            sampleRate = leInt(fmt, 4).coerceAtLeast(8000)
                            bitsPerSample = leShort(fmt, 14)
                        }
                    }
                    "data" -> {
                        dataOffset = chunkData
                        dataSize = size.coerceAtMost(raf.length() - chunkData)
                        break
                    }
                }

                var next = chunkData + size
                if ((size and 1L) != 0L) next++
                if (next > raf.length()) break
                raf.seek(next)
            }

            if (dataOffset < 0 || dataSize <= 0) {
                throw IllegalArgumentException("This WAV file has no readable audio data.")
            }

            val bytesPerSample = bitsPerSample / 8
            if (bytesPerSample !in 1..4) {
                throw IllegalArgumentException("Unsupported WAV bit depth: $bitsPerSample-bit.")
            }

            val bytesPerFrame = bytesPerSample * channels
            val maxFrames = sampleRate.toLong() * maxSeconds
            val availableFrames = dataSize / bytesPerFrame
            val frames = minOf(maxFrames, availableFrames).toInt()
            if (frames <= sampleRate / 2) {
                throw IllegalArgumentException("Reference audio is too short.")
            }

            val toRead = frames * bytesPerFrame
            val bytes = ByteArray(toRead)
            raf.seek(dataOffset)
            raf.readFully(bytes)

            val samples = FloatArray(frames)
            var p = 0
            for (frame in 0 until frames) {
                var sum = 0f
                for (ch in 0 until channels) {
                    sum += when (bitsPerSample) {
                        8 -> ((bytes[p].toInt() and 0xff) - 128) / 128f
                        16 -> {
                            val v = (bytes[p].toInt() and 0xff) or (bytes[p + 1].toInt() shl 8)
                            v.toShort().toFloat() / 32768f
                        }
                        24 -> {
                            var v = (bytes[p].toInt() and 0xff) or
                                ((bytes[p + 1].toInt() and 0xff) shl 8) or
                                ((bytes[p + 2].toInt() and 0xff) shl 16)
                            if ((v and 0x800000) != 0) v = v or 0xff000000.toInt()
                            v / 8388608f
                        }
                        32 -> {
                            val bits = (bytes[p].toInt() and 0xff) or
                                ((bytes[p + 1].toInt() and 0xff) shl 8) or
                                ((bytes[p + 2].toInt() and 0xff) shl 16) or
                                ((bytes[p + 3].toInt() and 0xff) shl 24)
                            if (formatCode == 3) Float.fromBits(bits) else bits / 2147483648f
                        }
                        else -> 0f
                    }
                    p += bytesPerSample
                }
                samples[frame] = (sum / channels).coerceIn(-1f, 1f)
            }

            normaliseInPlace(samples)
            return ReferenceAudio(samples, sampleRate, frames.toFloat() / sampleRate)
        }
    }

    private fun decodeWithMediaCodec(file: File, maxSeconds: Int): ReferenceAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var track = -1
        var sourceFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                track = i
                sourceFormat = f
                break
            }
        }

        if (track < 0 || sourceFormat == null) {
            extractor.release()
            throw IllegalArgumentException("No audio track was found in the selected file.")
        }

        extractor.selectTrack(track)
        val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalArgumentException("Unsupported audio file.")
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(sourceFormat, null, null, 0)
        codec.start()

        var sampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var capacity = sampleRate * maxSeconds + 4096
        var mono = FloatArray(capacity)
        var count = 0
        var inputEos = false
        var outputEos = false
        val info = MediaCodec.BufferInfo()

        try {
            while (!outputEos && count < sampleRate * maxSeconds) {
                if (!inputEos) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)!!
                        val n = extractor.readSampleData(input, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        val needed = sampleRate * maxSeconds + 4096
                        if (needed > mono.size) mono = mono.copyOf(needed)
                    }
                    else -> if (outputIndex >= 0) {
                        val out = codec.getOutputBuffer(outputIndex)
                        if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            out.order(ByteOrder.LITTLE_ENDIAN)

                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val frames = info.size / 4 / channels
                                repeat(frames) {
                                    var sum = 0f
                                    repeat(channels) { sum += out.float }
                                    if (count < mono.size) mono[count++] = sum / channels
                                }
                            } else {
                                val frames = info.size / 2 / channels
                                repeat(frames) {
                                    var sum = 0f
                                    repeat(channels) { sum += out.short.toFloat() / 32768f }
                                    if (count < mono.size) mono[count++] = sum / channels
                                }
                            }
                        }
                        outputEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        if (count < sampleRate / 2) {
            throw IllegalArgumentException("The selected audio could not be decoded.")
        }

        val result = mono.copyOf(count)
        normaliseInPlace(result)
        return ReferenceAudio(result, sampleRate, count.toFloat() / sampleRate)
    }

    private fun normaliseInPlace(samples: FloatArray) {
        var peak = 0f
        for (v in samples) {
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
        }
        if (peak > 0.001f && peak > 0.95f) {
            val scale = 0.95f / peak
            for (i in samples.indices) samples[i] *= scale
        }
    }

    private fun leShort(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun leInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or
            ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or
            ((b[o + 3].toInt() and 0xff) shl 24)
}
