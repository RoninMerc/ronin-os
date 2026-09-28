package au.com.roningroup.voicereader.direct

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ModelManager {
    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2"

    data class Progress(val phase: String, val fraction: Float?)

    private val required = listOf(
        "lm_flow.int8.onnx",
        "lm_main.int8.onnx",
        "encoder.onnx",
        "decoder.int8.onnx",
        "text_conditioner.onnx",
        "vocab.json",
        "token_scores.json"
    )

    fun modelDir(context: Context): File? {
        val root = File(context.filesDir, "models/pocket")
        if (!root.exists()) return null
        return root.walkTopDown()
            .filter { it.isDirectory }
            .firstOrNull { dir -> required.all { File(dir, it).isFile } }
    }

    fun isInstalled(context: Context): Boolean = modelDir(context) != null

    fun install(context: Context, onProgress: (Progress) -> Unit) {
        val root = File(context.filesDir, "models/pocket")
        root.mkdirs()

        val archive = File(context.cacheDir, "pocket-tts-model.tar.bz2")
        val partial = File(context.cacheDir, "pocket-tts-model.tar.bz2.part")

        try {
            if (partial.exists()) partial.delete()
            download(partial, onProgress)
            if (archive.exists()) archive.delete()
            if (!partial.renameTo(archive)) {
                partial.copyTo(archive, overwrite = true)
                partial.delete()
            }

            onProgress(Progress("Extracting model…", null))
            extractTarBz2(archive, root)

            if (!isInstalled(context)) {
                throw IllegalStateException("PocketTTS model extracted, but required files were not found.")
            }

            onProgress(Progress("PocketTTS ready", 1f))
        } finally {
            partial.delete()
            archive.delete()
        }
    }

    private fun download(target: File, onProgress: (Progress) -> Unit) {
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 120_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Ronin-Voice-Reader/0.2")
            setRequestProperty("Accept", "application/octet-stream")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Model download failed: HTTP $code")
            }

            val total = connection.contentLengthLong
            var done = 0L
            var lastReport = 0L

            BufferedInputStream(connection.inputStream, 256 * 1024).use { input ->
                BufferedOutputStream(FileOutputStream(target), 256 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (done - lastReport >= 512 * 1024 || done == total) {
                            lastReport = done
                            val fraction = if (total > 0) (done.toDouble() / total).toFloat() else null
                            onProgress(Progress("Downloading PocketTTS…", fraction))
                        }
                    }
                }
            }

            if (target.length() < 1_000_000L) {
                throw IllegalStateException("Downloaded model archive is unexpectedly small.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTarBz2(archive: File, destination: File) {
        val canonicalRoot = destination.canonicalFile
        TarArchiveInputStream(
            BZip2CompressorInputStream(
                BufferedInputStream(FileInputStream(archive), 256 * 1024),
                true
            )
        ).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                val output = File(destination, entry.name).canonicalFile
                if (!output.path.startsWith(canonicalRoot.path + File.separator)) {
                    throw SecurityException("Unsafe path in model archive.")
                }

                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(output), 128 * 1024).use { out ->
                        tar.copyTo(out, 128 * 1024)
                    }
                }
                entry = tar.nextTarEntry
            }
        }
    }
}
