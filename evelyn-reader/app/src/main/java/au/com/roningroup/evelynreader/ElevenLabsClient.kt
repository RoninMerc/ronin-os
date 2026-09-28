package au.com.roningroup.evelynreader

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class VoiceChoice(val id: String, val name: String)

class ElevenLabsApiException(
    val statusCode: Int,
    message: String
) : Exception(message)

object ElevenLabsClient {
    private const val BASE = "https://api.elevenlabs.io"

    fun listVoices(apiKey: String): List<VoiceChoice> {
        val urls = listOf(
            "$BASE/v2/voices?page_size=100",
            "$BASE/v1/voices"
        )

        var lastError: Throwable? = null
        for (url in urls) {
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    setRequestProperty("xi-api-key", apiKey)
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    val code = c.responseCode
                    val body = readText(c, code)
                    if (code !in 200..299) {
                        throw ElevenLabsApiException(code, apiError(code, body))
                    }

                    val json = JSONObject(body)
                    val arr = json.optJSONArray("voices")
                        ?: throw IllegalStateException("ElevenLabs returned no voices list.")

                    val out = ArrayList<VoiceChoice>(arr.length())
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val id = item.optString("voice_id").trim()
                        val name = item.optString("name").trim()
                        if (id.isNotBlank() && name.isNotBlank()) {
                            out += VoiceChoice(id, name)
                        }
                    }
                    return out.distinctBy { it.id }.sortedBy { it.name.lowercase() }
                } finally {
                    c.disconnect()
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("Could not load voices.")
    }

    fun createInstantClone(
        apiKey: String,
        sourceFile: File,
        originalFileName: String,
        mimeType: String?,
        voiceName: String = "Evelyn"
    ): VoiceChoice {
        val boundary = "----EvelynReader" + System.currentTimeMillis()
        val c = (URL("$BASE/v1/voices/add").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("xi-api-key", apiKey)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            DataOutputStream(BufferedOutputStream(c.outputStream)).use { out ->
                fun textPart(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.write(value.toByteArray(StandardCharsets.UTF_8))
                    out.writeBytes("\r\n")
                }

                textPart("name", voiceName)
                textPart("description", "Evelyn voice for Ronin Voice Reader")
                textPart("remove_background_noise", "false")

                out.writeBytes("--$boundary\r\n")
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"files\"; filename=\"" +
                        originalFileName.replace("\"", "_") + "\"\r\n"
                )
                out.writeBytes("Content-Type: " + (mimeType ?: "audio/wav") + "\r\n\r\n")
                BufferedInputStream(FileInputStream(sourceFile), 128 * 1024).use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }

            val code = c.responseCode
            val body = readText(c, code)
            if (code !in 200..299) {
                throw ElevenLabsApiException(code, apiError(code, body))
            }

            val json = JSONObject(body)
            val voiceId = json.optString("voice_id").trim()
            if (voiceId.isBlank()) {
                throw IllegalStateException("Voice clone succeeded but no voice ID was returned.")
            }
            return VoiceChoice(voiceId, voiceName)
        } finally {
            c.disconnect()
        }
    }

    fun streamSpeech(
        apiKey: String,
        voiceId: String,
        text: String,
        previousText: String?,
        nextText: String?,
        modelId: String,
        onPcm: (ByteArray, Int) -> Unit
    ) {
        val encodedVoice = URLEncoder.encode(voiceId, "UTF-8")
        val url = URL(
            "$BASE/v1/text-to-speech/$encodedVoice/stream" +
                "?output_format=pcm_24000&optimize_streaming_latency=3"
        )

        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("xi-api-key", apiKey)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/pcm")
        }

        try {
            val body = JSONObject().apply {
                put("text", text)
                put("model_id", modelId)
                put("apply_text_normalization", "auto")
                put("seed", 42)
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.45)
                    put("similarity_boost", 0.85)
                    put("style", 0.0)
                    put("use_speaker_boost", true)
                })
                if (!previousText.isNullOrBlank()) {
                    put("previous_text", previousText.takeLast(100))
                }
                if (!nextText.isNullOrBlank()) {
                    put("next_text", nextText.take(100))
                }
            }.toString()

            c.outputStream.use { out ->
                out.write(body.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }

            val code = c.responseCode
            if (code !in 200..299) {
                val error = readText(c, code)
                throw ElevenLabsApiException(code, apiError(code, error))
            }

            BufferedInputStream(c.inputStream, 32 * 1024).use { input ->
                val buffer = ByteArray(32 * 1024)
                var carry: Int? = null

                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue

                    if (carry != null) {
                        val joined = ByteArray(n + 1)
                        joined[0] = carry!!.toByte()
                        System.arraycopy(buffer, 0, joined, 1, n)
                        val even = joined.size - (joined.size and 1)
                        if (even > 0) onPcm(joined, even)
                        carry = if (joined.size > even) joined[even].toInt() and 0xff else null
                    } else {
                        val even = n - (n and 1)
                        if (even > 0) onPcm(buffer, even)
                        carry = if (n > even) buffer[even].toInt() and 0xff else null
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun readText(c: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun apiError(code: Int, body: String): String {
        val detail = try {
            val json = JSONObject(body)
            val d = json.opt("detail")
            when (d) {
                is JSONObject -> d.optString("message").ifBlank { d.toString() }
                null -> body.take(500)
                else -> d.toString()
            }
        } catch (_: Throwable) {
            body.take(500)
        }

        return when (code) {
            401 -> "ElevenLabs rejected the API key."
            402 -> "ElevenLabs account does not have enough quota/credits."
            403 -> "ElevenLabs denied access to this voice or feature."
            429 -> "ElevenLabs rate limit or quota reached."
            else -> "ElevenLabs error $code" + if (detail.isBlank()) "" else ": $detail"
        }
    }
}

