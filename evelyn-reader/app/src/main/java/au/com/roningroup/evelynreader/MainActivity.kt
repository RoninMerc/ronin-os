package au.com.roningroup.evelynreader

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val REQ_AUDIO = 8101
        private const val REQ_OVERLAY = 8102
        private const val REQ_NOTIFICATIONS = 8103
    }

    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var apiKey: EditText
    private lateinit var voiceSpinner: Spinner
    private lateinit var voiceId: EditText
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var bubbleButton: Button

    private var voices: List<VoiceChoice> = emptyList()
    private var pendingBubbleEnable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        apiKey.setText(Prefs.apiKey(this))
        voiceId.setText(Prefs.voiceId(this))
        refreshStatus()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }

        if (Prefs.bubbleEnabled(this) && Settings.canDrawOverlays(this)) {
            startBubble()
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
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
            text = "EVELYN READER"
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Streaming reader for ChatGPT replies · v1.0"
            textSize = 14f
            setPadding(0, 0, 0, dp(14))
        })

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = "1. ElevenLabs API key"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        apiKey = EditText(this).apply {
            hint = "Paste your ElevenLabs API key"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(apiKey)

        val apiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        root.addView(apiRow)

        apiRow.addView(Button(this).apply {
            text = "SAVE KEY"
            setOnClickListener {
                val key = apiKey.text.toString().trim()
                if (key.isBlank()) {
                    toast("Paste your ElevenLabs API key first.")
                } else {
                    Prefs.setApiKey(this@MainActivity, key)
                    refreshStatus()
                    toast("API key saved.")
                }
            }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))

        apiRow.addView(Button(this).apply {
            text = "LOAD VOICES"
            setOnClickListener {
                saveKeyIfPresent()
                loadVoices()
            }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))

        root.addView(TextView(this).apply {
            text = "2. Choose Evelyn"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, 0)
        })

        voiceSpinner = Spinner(this)
        root.addView(
            voiceSpinner,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
            )
        )

        voiceId = EditText(this).apply {
            hint = "Voice ID (auto-filled, or paste manually)"
            setSingleLine(true)
        }
        root.addView(voiceId)

        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        root.addView(voiceRow)

        voiceRow.addView(Button(this).apply {
            text = "USE SELECTED"
            setOnClickListener {
                val pos = voiceSpinner.selectedItemPosition
                if (pos >= 0 && pos < voices.size) {
                    val v = voices[pos]
                    voiceId.setText(v.id)
                    Prefs.setVoice(this@MainActivity, v.id, v.name)
                    refreshStatus()
                    toast(v.name + " selected.")
                } else {
                    saveManualVoice()
                }
            }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))

        voiceRow.addView(Button(this).apply {
            text = "SAVE VOICE ID"
            setOnClickListener { saveManualVoice() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))

        root.addView(Button(this).apply {
            text = "CREATE EVELYN FROM MY AUDIO FILE"
            setOnClickListener {
                saveKeyIfPresent()
                if (Prefs.apiKey(this@MainActivity).isBlank()) {
                    toast("Set your ElevenLabs API key first.")
                } else {
                    chooseCloneAudio()
                }
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54)
        ).apply { topMargin = dp(8) })

        root.addView(TextView(this).apply {
            text = "If Evelyn already exists in your ElevenLabs account, just Load Voices and select her. If not, the button above can upload the audio file you already made and create Evelyn as an instant voice clone."
            textSize = 13f
            setPadding(0, dp(6), 0, dp(14))
        })

        root.addView(TextView(this).apply {
            text = "3. Car controls"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        bubbleButton = Button(this).apply {
            setOnClickListener { toggleBubble() }
        }
        root.addView(
            bubbleButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
            )
        )
        refreshBubbleButton()

        root.addView(TextView(this).apply {
            text = "When the floating E button is on: tap ChatGPT's Copy button, then tap E. Evelyn reads whatever is on the clipboard. You can also Share text directly to “Read with Evelyn”, or highlight text and choose “Read with Evelyn” from Android's text menu."
            textSize = 13f
            setPadding(0, dp(6), 0, dp(14))
        })

        root.addView(TextView(this).apply {
            text = "Manual reader"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        input = EditText(this).apply {
            hint = "Paste a ChatGPT reply here…"
            minLines = 8
            maxLines = 18
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(input)

        root.addView(Button(this).apply {
            text = "PASTE CLIPBOARD & READ"
            setOnClickListener {
                val text = readClipboard()
                if (text.isBlank()) toast("Clipboard is empty.")
                else {
                    input.setText(text)
                    readText(text)
                }
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(56)
        ).apply { topMargin = dp(8) })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        root.addView(controls)

        controls.addView(Button(this).apply {
            text = "READ"
            setOnClickListener {
                readText(input.text.toString())
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f))

        controls.addView(Button(this).apply {
            text = "PAUSE"
            setOnClickListener {
                PlaybackService.action(this@MainActivity, PlaybackService.ACTION_PAUSE)
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f))

        controls.addView(Button(this).apply {
            text = "RESUME"
            setOnClickListener {
                PlaybackService.action(this@MainActivity, PlaybackService.ACTION_RESUME)
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f))

        controls.addView(Button(this).apply {
            text = "STOP"
            setOnClickListener {
                PlaybackService.action(this@MainActivity, PlaybackService.ACTION_STOP)
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
    }

    private fun saveKeyIfPresent() {
        val key = apiKey.text.toString().trim()
        if (key.isNotBlank()) Prefs.setApiKey(this, key)
    }

    private fun loadVoices() {
        val key = Prefs.apiKey(this)
        if (key.isBlank()) {
            toast("Set your ElevenLabs API key first.")
            return
        }

        showStatus("Loading your ElevenLabs voices…")
        worker.execute {
            try {
                val result = ElevenLabsClient.listVoices(key)
                runOnUiThread {
                    voices = result
                    val names = result.map { it.name + "  ·  " + it.id.take(8) }
                    val adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        names
                    )
                    voiceSpinner.adapter = adapter

                    if (result.isEmpty()) {
                        showStatus("No ElevenLabs voices were returned.")
                        return@runOnUiThread
                    }

                    val saved = Prefs.voiceId(this)
                    var index = result.indexOfFirst { it.id == saved }
                    if (index < 0) {
                        index = result.indexOfFirst {
                            it.name.contains("Evelyn", ignoreCase = true)
                        }
                    }
                    if (index < 0) index = 0

                    voiceSpinner.setSelection(index)
                    voiceId.setText(result[index].id)

                    if (result[index].name.contains("Evelyn", ignoreCase = true)) {
                        Prefs.setVoice(this, result[index].id, result[index].name)
                    }

                    refreshStatus()
                    showStatus("Loaded " + result.size + " voices.")
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    showStatus(t.message ?: "Could not load voices.")
                }
            }
        }
    }

    private fun saveManualVoice() {
        val id = voiceId.text.toString().trim()
        if (id.isBlank()) {
            toast("Enter or select a voice ID.")
            return
        }

        val selected = voices.getOrNull(voiceSpinner.selectedItemPosition)
        val name = if (selected?.id == id) selected.name else "Evelyn"
        Prefs.setVoice(this, id, name)
        refreshStatus()
        toast("Evelyn voice saved.")
    }

    private fun chooseCloneAudio() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(i, REQ_AUDIO)
    }

    @Deprecated("Compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_AUDIO && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            cloneFromUri(uri)
        }
    }

    private fun cloneFromUri(uri: Uri) {
        val key = Prefs.apiKey(this)
        if (key.isBlank()) {
            toast("Set your ElevenLabs API key first.")
            return
        }

        showStatus("Uploading your Evelyn reference audio to ElevenLabs…")

        worker.execute {
            var temp: File? = null
            try {
                val fileName = displayName(uri) ?: "evelyn_reference.wav"
                val mime = contentResolver.getType(uri)
                temp = File(cacheDir, "evelyn_clone_" + System.currentTimeMillis())
                contentResolver.openInputStream(uri).use { inputStream ->
                    val input = inputStream
                        ?: throw IllegalArgumentException("Could not open the selected audio.")
                    FileOutputStream(temp).use { output ->
                        input.copyTo(output, 128 * 1024)
                    }
                }

                val voice = ElevenLabsClient.createInstantClone(
                    apiKey = key,
                    sourceFile = temp,
                    originalFileName = fileName,
                    mimeType = mime,
                    voiceName = "Evelyn"
                )

                Prefs.setVoice(this, voice.id, voice.name)

                runOnUiThread {
                    voiceId.setText(voice.id)
                    refreshStatus()
                    showStatus("Evelyn created and selected.")
                    loadVoices()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    showStatus(t.message ?: "Could not create Evelyn.")
                }
            } finally {
                temp?.delete()
            }
        }
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

    private fun toggleBubble() {
        if (Prefs.bubbleEnabled(this)) {
            Prefs.setBubbleEnabled(this, false)
            stopService(
                Intent(this, BubbleService::class.java)
                    .setAction(BubbleService.ACTION_DISABLE)
            )
            refreshBubbleButton()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            pendingBubbleEnable = true
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + packageName)
            )
            startActivityForResult(i, REQ_OVERLAY)
            return
        }

        Prefs.setBubbleEnabled(this, true)
        startBubble()
        refreshBubbleButton()
    }

    override fun onResume() {
        super.onResume()
        if (pendingBubbleEnable && Settings.canDrawOverlays(this)) {
            pendingBubbleEnable = false
            Prefs.setBubbleEnabled(this, true)
            startBubble()
        }
        refreshBubbleButton()
    }

    private fun startBubble() {
        val i = Intent(this, BubbleService::class.java)
            .setAction(BubbleService.ACTION_ENABLE)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun refreshBubbleButton() {
        if (!::bubbleButton.isInitialized) return
        bubbleButton.text = if (Prefs.bubbleEnabled(this)) {
            "TURN OFF FLOATING EVELYN BUTTON"
        } else {
            "TURN ON FLOATING EVELYN BUTTON"
        }
    }

    private fun readText(value: String) {
        val text = value.trim()
        if (text.isBlank()) {
            toast("There is no text to read.")
            return
        }
        saveKeyIfPresent()

        val manualId = voiceId.text.toString().trim()
        if (Prefs.voiceId(this).isBlank() && manualId.isNotBlank()) {
            Prefs.setVoice(this, manualId, "Evelyn")
        }

        if (!Prefs.configured(this)) {
            toast("Set your ElevenLabs API key and Evelyn voice first.")
            return
        }

        PlaybackService.speak(this, text)
        showStatus("Sent to Evelyn. Audio will start as the stream arrives.")
    }

    private fun readClipboard(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this).toString()
    }

    private fun refreshStatus() {
        val voice = Prefs.voiceName(this).ifBlank {
            if (Prefs.voiceId(this).isBlank()) "not selected" else "Evelyn"
        }
        val keyStatus = if (Prefs.apiKey(this).isBlank()) "not set" else "saved"
        status.text = "API key: " + keyStatus + "   ·   Voice: " + voice
    }

    private fun showStatus(value: String) {
        status.text = value
    }

    private fun toast(value: String) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
