package au.com.roningroup.evelynoffline

import android.content.Context
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

data class EvelynReference(
    val samples: FloatArray,
    val sampleRate: Int
)

object ReferenceAudio {
    @Volatile private var cached: EvelynReference? = null

    @Synchronized
    fun load(context: Context): EvelynReference {
        cached?.let { return it }

        val wavBytes = context.assets.open("evelyn_reference.wav.gz").use { raw ->
            GZIPInputStream(raw).use { gzip ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = gzip.read(buffer)
                    if (n < 0) break
                    if (n > 0) out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }

        val decoded = parsePcmWav(wavBytes)
        cached = decoded
        return decoded
    }

    private fun parsePcmWav(bytes: ByteArray): EvelynReference {
        if (bytes.size < 44) {
            throw IllegalStateException("Embedded Evelyn WAV is too small.")
        }

        fun ascii(offset: Int, length: Int): String =
            String(bytes, offset, length, Charsets.US_ASCII)

        fun u16(offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8)

        fun i32(offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)

        if (ascii(0, 4) != "RIFF" || ascii(8, 4) != "WAVE") {
            throw IllegalStateException("Embedded Evelyn reference is not a RIFF/WAVE file.")
        }

        var offset = 12
        var audioFormat = -1
        var channels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var dataOffset = -1
        var dataSize = -1

        while (offset + 8 <= bytes.size) {
            val id = ascii(offset, 4)
            val size = i32(offset + 4)
            if (size < 0) break

            val payload = offset + 8
            if (payload + size > bytes.size) break

            when (id) {
                "fmt " -> {
                    if (size >= 16) {
                        audioFormat = u16(payload)
                        channels = u16(payload + 2)
                        sampleRate = i32(payload + 4)
                        bitsPerSample = u16(payload + 14)
                    }
                }
                "data" -> {
                    dataOffset = payload
                    dataSize = size
                    break
                }
            }

            offset = payload + size + (size and 1)
        }

        if (audioFormat != 1) {
            throw IllegalStateException("Embedded Evelyn WAV is not PCM.")
        }
        if (channels != 1) {
            throw IllegalStateException("Embedded Evelyn WAV is not mono.")
        }
        if (sampleRate != 24000) {
            throw IllegalStateException("Embedded Evelyn WAV is not 24 kHz.")
        }
        if (bitsPerSample != 16) {
            throw IllegalStateException("Embedded Evelyn WAV is not 16-bit PCM.")
        }
        if (dataOffset < 0 || dataSize <= 0 || dataOffset + dataSize > bytes.size) {
            throw IllegalStateException("Embedded Evelyn WAV has no valid audio data.")
        }

        val sampleCount = dataSize / 2
        if (sampleCount < sampleRate * 2) {
            throw IllegalStateException("Embedded Evelyn reference is too short.")
        }

        val samples = FloatArray(sampleCount)
        val buffer = ByteBuffer
            .wrap(bytes, dataOffset, dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until sampleCount) {
            samples[i] = buffer.short.toFloat() / 32768f
        }

        return EvelynReference(samples, sampleRate)
    }
}
