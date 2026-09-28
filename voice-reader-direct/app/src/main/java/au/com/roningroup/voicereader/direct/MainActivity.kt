package au.com.roningroup.voicereader.direct

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val REQ_AUDIO = 7001
        private const val PREFS = "ronin_voice_reader_direct"
        private const val KEY_VOICE_NAME = "voice_name"
    }

    private val modelExecutor = Executors.newSingleThreadExecutor()
    private val speakExecutor = Executors.newSingleThreadExecutor()
    private lateinit var speaker: PocketSpeaker

    private lateinit var status: TextView
    private lateinit var modelStatus: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var input: EditText
    private lateinit var progress: ProgressBar
    private lateinit var downloadButton: Button
    private lateinit var voiceButton: Button
    private lateinit var readButton: Button
    private lateinit var pauseButton: Button
    private lateinit var speedLabel: TextView

    @Volatile private var reference: ReferenceAudio? = null
    private var speed = 1.0f
    private var autoReadPending = false

    private val referenceFile: File
        get() = File(filesDir, "reference_audio.bin")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = PocketSpeaker(applicationContext)
        buildUi()
        refreshSetupState()
        loadSavedReference()
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "RONIN VOICE READER"
            textSize = 27f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Direct offline PocketTTS · v0.2.0"
            textSize = 14f
            setPadding(0, 0, 0, dp(16))
        })

        val setupBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(setupBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        modelStatus = TextView(this).apply { textSize = 15f }
        setupBox.addView(modelStatus)

        downloadButton = Button(this).apply {
            text = "DOWNLOAD POCKETTTS MODEL"
            setOnClickListener { downloadModel() }
        }
        setupBox.addView(downloadButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ))

        voiceStatus = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(10), 0, 0)
        }
        setupBox.addView(voiceStatus)

        voiceButton = Button(this).apply {
            text = "IMPORT / REPLACE CUSTOM VOICE"
            setOnClickListener { chooseAudio() }
        }
        setupBox.addView(voiceButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ))

        status = TextView(this).apply {
            text = "Ready"
            textSize = 15f
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(status)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        root.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(8)
        ))

        input = EditText(this).apply {
            hint = "Paste or share a ChatGPT reply here…"
            gravity = Gravity.TOP or Gravity.START
            minLines = 9
            maxLines = 20
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        root.addView(Button(this).apply {
            text = "PASTE & READ"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                val value = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(this@MainActivity).toString()
                } else ""
                if (value.isBlank()) {
                    showStatus("Clipboard is empty.")
                } else {
                    input.setText(value)
                    startReading()
                }
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
        ).apply { topMargin = dp(12) })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        root.addView(controls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        readButton = Button(this).apply {
            text = "READ"
            setOnClickListener { startReading() }
        }
        pauseButton = Button(this).apply {
            text = "PAUSE"
            setOnClickListener {
                if (speaker.isPaused()) {
                    speaker.resume()
                    text = "PAUSE"
                    showStatus("Reading…")
                } else {
                    speaker.pause()
                    text = "RESUME"
                    showStatus("Paused")
                }
            }
        }
        val stopButton = Button(this).apply {
            text = "STOP"
            setOnClickListener {
                speaker.stop()
                pauseButton.text = "PAUSE"
                showStatus("Stopped")
            }
        }

        controls.addView(readButton, LinearLayout.LayoutParams(0, dp(54), 1f))
        controls.addView(pauseButton, LinearLayout.LayoutParams(0, dp(54), 1f))
        controls.addView(stopButton, LinearLayout.LayoutParams(0, dp(54), 1f))

        speedLabel = TextView(this).apply {
            text = "Speed: 1.00×"
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(speedLabel)

        root.addView(SeekBar(this).apply {
            max = 50
            progress = 25
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    speed = 0.75f + value / 100f
                    speedLabel.text = String.format(Locale.US, "Speed: %.2f×", speed)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })

        root.addView(TextView(this).apply {
            text = "Your imported audio stays on this phone. For long source recordings, only the first 10 seconds are used as the PocketTTS voice reference. Share text from ChatGPT directly to Ronin Voice Reader to load it here."
            textSize = 13f
            setPadding(0, dp(10), 0, dp(10))
        })
    }

    private fun refreshSetupState() {
        val installed = ModelManager.isInstalled(this)
        modelStatus.text = if (installed) {
            "PocketTTS model: INSTALLED"
        } else {
            "PocketTTS model: NOT INSTALLED"
        }
        downloadButton.isEnabled = !installed
        downloadButton.text = if (installed) "POCKETTTS INSTALLED" else "DOWNLOAD POCKETTTS MODEL"

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val voiceName = prefs.getString(KEY_VOICE_NAME, null)
        voiceStatus.text = if (referenceFile.isFile && voiceName != null) {
            "Custom voice: $voiceName"
        } else {
            "Custom voice: NOT IMPORTED"
        }
    }

    private fun downloadModel() {
        downloadButton.isEnabled = false
        progress.isIndeterminate = false
        progress.progress = 0
        showStatus("Starting PocketTTS download…")

        modelExecutor.execute {
            try {
                ModelManager.install(applicationContext) { p ->
                    runOnUiThread {
                        showStatus(p.phase)
                        if (p.fraction == null) {
                            progress.isIndeterminate = true
                        } else {
                            progress.isIndeterminate = false
                            progress.progress = (p.fraction.coerceIn(0f, 1f) * 1000).toInt()
                        }
                    }
                }
                runOnUiThread {
                    progress.isIndeterminate = false
                    progress.progress = 1000
                    showStatus("PocketTTS installed.")
                    refreshSetupState()
                    maybeAutoRead()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    progress.isIndeterminate = false
                    progress.progress = 0
                    showStatus("MODEL ERROR: " + (t.message ?: t.javaClass.simpleName))
                    downloadButton.isEnabled = true
                }
            }
        }
    }

    private fun chooseAudio() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(i, REQ_AUDIO)
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_AUDIO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        showStatus("Importing custom voice…")
        voiceButton.isEnabled = false

        modelExecutor.execute {
            try {
                val target = referenceFile
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw IllegalArgumentException("Could not open selected audio.")
                    FileOutputStream(target).use { output -> input.copyTo(output, 256 * 1024) }
                }

                val loaded = AudioLoader.loadReference(target, 10)
                reference = loaded
                val name = displayName(uri) ?: "Custom voice"
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_VOICE_NAME, name)
                    .apply()

                runOnUiThread {
                    voiceButton.isEnabled = true
                    refreshSetupState()
                    showStatus(
                        "Voice ready: " +
                            String.format(Locale.US, "%.1f", loaded.durationSeconds) +
                            " seconds used at " + loaded.sampleRate + " Hz."
                    )
                    maybeAutoRead()
                }
            } catch (t: Throwable) {
                targetDeleteQuietly()
                reference = null
                runOnUiThread {
                    voiceButton.isEnabled = true
                    refreshSetupState()
                    showStatus("VOICE ERROR: " + (t.message ?: t.javaClass.simpleName))
                }
            }
        }
    }

    private fun targetDeleteQuietly() {
        runCatching { referenceFile.delete() }
    }

    private fun displayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        } finally {
            cursor?.close()
        }
    }

    private fun loadSavedReference() {
        if (!referenceFile.isFile) return
        modelExecutor.execute {
            try {
                val loaded = AudioLoader.loadReference(referenceFile, 10)
                reference = loaded
                runOnUiThread {
                    refreshSetupState()
                    maybeAutoRead()
                }
            } catch (t: Throwable) {
                targetDeleteQuietly()
                reference = null
                runOnUiThread {
                    refreshSetupState()
                    showStatus("Saved voice could not be loaded: " + (t.message ?: "unknown error"))
                }
            }
        }
    }

    private fun startReading() {
        val text = input.text?.toString().orEmpty().trim()
        if (text.isEmpty()) {
            showStatus("Paste or share some text first.")
            return
        }
        if (!ModelManager.isInstalled(this)) {
            autoReadPending = false
            showStatus("PocketTTS model is not installed yet.")
            return
        }
        val ref = reference
        if (ref == null) {
            autoReadPending = false
            showStatus("Import your custom voice audio first.")
            return
        }

        speaker.stop()
        pauseButton.text = "PAUSE"
        progress.isIndeterminate = false
        progress.progress = 0
        readButton.isEnabled = false

        speakExecutor.execute {
            try {
                speaker.speak(
                    text = text,
                    reference = ref,
                    speed = speed,
                    onStatus = { s -> runOnUiThread { showStatus(s) } },
                    onProgress = { f ->
                        runOnUiThread {
                            progress.progress = (f.coerceIn(0f, 1f) * 1000).toInt()
                        }
                    }
                )
            } catch (t: Throwable) {
                runOnUiThread {
                    showStatus("SPEECH ERROR: " + (t.message ?: t.javaClass.simpleName))
                }
            } finally {
                runOnUiThread {
                    readButton.isEnabled = true
                    pauseButton.text = "PAUSE"
                }
            }
        }
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
            if (shared.isNotEmpty()) {
                input.setText(shared)
                autoReadPending = true
                showStatus("Shared text received.")
                maybeAutoRead()
            }
        }
    }

    private fun maybeAutoRead() {
        if (
            autoReadPending &&
            ModelManager.isInstalled(this) &&
            reference != null &&
            input.text?.isNotBlank() == true
        ) {
            autoReadPending = false
            startReading()
        }
    }

    private fun showStatus(value: String) {
        status.text = value
    }

    override fun onDestroy() {
        speaker.stop()
        if (isFinishing) speaker.release()
        modelExecutor.shutdownNow()
        speakExecutor.shutdownNow()
        super.onDestroy()
    }
}
