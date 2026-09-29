package au.com.roningroup.evieassistant

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class QwenVoiceProfile(
    val id: String,
    val name: String
)

object QwenVoiceClient {
    fun registerClone(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String?,
        refText: String,
        name: String = "Evie"
    ): QwenVoiceProfile {
        val base = Prefs.qwenTtsUrl(context)
        if (base.isBlank()) {
            throw IllegalStateException("Qwen3-TTS server URL is not configured.")
        }

        val boundary = "----EvieVoice" + System.currentTimeMillis()
        val connection = (URL(base + "/v1/voices")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 25_000
            readTimeout = 180_000
            setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=" + boundary
            )
            setRequestProperty("Accept", "application/json")
            applyAuth(context, this)
        }

        try {
            DataOutputStream(
                BufferedOutputStream(connection.outputStream)
            ).use { out ->
                fun textPart(field: String, value: String) {
                    out.writeBytes("--" + boundary + "\r\n")
                    out.writeBytes(
                        "Content-Disposition: form-data; name=\"" +
                            field + "\"\r\n\r\n"
                    )
                    out.write(
                        value.toByteArray(StandardCharsets.UTF_8)
                    )
                    out.writeBytes("\r\n")
                }

                textPart("name", name)
                if (refText.isNotBlank()) {
                    textPart("ref_text", refText)
                }

                out.writeBytes("--" + boundary + "\r\n")
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"audio\"; filename=\"" +
                        fileName.replace("\"", "_") + "\"\r\n"
                )
                out.writeBytes(
                    "Content-Type: " +
                        (mimeType ?: "audio/wav") +
                        "\r\n\r\n"
                )

                BufferedInputStream(
                    FileInputStream(sourceFile),
                    128 * 1024
                ).use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }

                out.writeBytes("\r\n--" + boundary + "--\r\n")
                out.flush()
            }

            val code = connection.responseCode
            val body = readText(connection, code)

            if (code !in 200..299) {
                throw IllegalStateException(
                    "Qwen3-TTS clone failed HTTP " + code + ": " +
                        body.take(800)
                )
            }

            val json = JSONObject(body)
            val id = sequenceOf(
                json.optString("id"),
                json.optString("voice_id"),
                json.optString("voice"),
                json.optString("name")
            ).firstOrNull { it.isNotBlank() } ?: name

            return QwenVoiceProfile(id, name)
        } finally {
            connection.disconnect()
        }
    }

    fun synthesize(
        context: Context,
        text: String
    ): ByteArray {
        val base = Prefs.qwenTtsUrl(context)
        if (base.isBlank()) {
            throw IllegalStateException("Qwen3-TTS server URL is not configured.")
        }

        val connection = (URL(base + "/v1/audio/speech")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 25_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/wav")
            applyAuth(context, this)
        }

        try {
            val body = JSONObject()
                .put("model", "qwen3-tts")
                .put("input", text)
                .put("voice", Prefs.qwenTtsVoice(context))
                .put("language", Prefs.qwenTtsLanguage(context))
                .put("response_format", "wav")
                .toString()

            connection.outputStream.use { out ->
                out.write(body.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }

            val code = connection.responseCode

            if (code !in 200..299) {
                val error = readText(connection, code)
                throw IllegalStateException(
                    "Qwen3-TTS speech failed HTTP " + code + ": " +
                        error.take(800)
                )
            }

            val bytes =
                connection.inputStream.use { it.readBytes() }

            val isWav =
                bytes.size >= 12 &&
                    String(
                        bytes,
                        0,
                        4,
                        Charsets.US_ASCII
                    ) == "RIFF" &&
                    String(
                        bytes,
                        8,
                        4,
                        Charsets.US_ASCII
                    ) == "WAVE"

            if (!isWav) {
                throw IllegalStateException(
                    "Qwen3-TTS server returned HTTP 200 but not a WAV file. " +
                        "Content-Type=" +
                        connection.contentType.orEmpty()
                )
            }

            return bytes
        } finally {
            connection.disconnect()
        }
    }

    fun health(context: Context): String {
        val base = Prefs.qwenTtsUrl(context)
        if (base.isBlank()) return "Qwen3-TTS URL is not configured."

        val candidates = listOf("/health", "/v1/models", "/v1/voices")
        var last = ""

        for (path in candidates) {
            try {
                val connection = (URL(base + path)
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    setRequestProperty("Accept", "application/json")
                    applyAuth(context, this)
                }

                try {
                    val code = connection.responseCode
                    val body = readText(connection, code)
                    if (code in 200..299) {
                        return "OK: Qwen3-TTS server responded on " + path + "."
                    }
                    last = "HTTP " + code + " " + body.take(300)
                } finally {
                    connection.disconnect()
                }
            } catch (t: Throwable) {
                last = t.message ?: t.javaClass.simpleName
            }
        }

        return "ERROR: Qwen3-TTS server did not respond successfully. " + last
    }

    private fun applyAuth(
        context: Context,
        connection: HttpURLConnection
    ) {
        val key = Prefs.qwenTtsApiKey(context)
        if (key.isNotBlank()) {
            connection.setRequestProperty(
                "Authorization",
                "Bearer " + key
            )
        }
    }

    private fun readText(
        connection: HttpURLConnection,
        code: Int
    ): String {
        val stream = if (code in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        return stream
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
    }
}
