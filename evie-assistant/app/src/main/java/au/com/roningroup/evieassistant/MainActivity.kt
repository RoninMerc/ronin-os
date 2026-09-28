package au.com.roningroup.evieassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val REQ_MIC = 9001
        private const val REQ_NOTIFICATIONS = 9002
        private const val REQ_OVERLAY = 9003
        private const val REQ_VOICE_AUDIO = 9004
    }

    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var userName: EditText
    private lateinit var persona: EditText
    private lateinit var speakCheck: CheckBox
    private lateinit var qwenVoiceCheck: CheckBox
    private lateinit var qwenUrl: EditText
    private lateinit var qwenApiKey: EditText
    private lateinit var qwenVoice: EditText
    private lateinit var qwenLanguage: EditText
    private lateinit var refTranscript: EditText
    private lateinit var voiceStatus: TextView
    private lateinit var memoryStatus: TextView
    private lateinit var wakeButton: Button
    private lateinit var bubbleButton: Button
    private lateinit var testCommand: EditText

    private var pendingStartWake = false
    private var pendingStartBubble = false
    private var pendingListenOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadSettings()
        requestNotificationPermissionIfNeeded()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(36))
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
            text = "EVIE ASSISTANT"
            textSize = 29f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Full pre-OS Android agent · v0.2"
            textSize = 14f
            setPadding(0, 0, 0, dp(12))
        })

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(status)

        root.addView(section("Brain"))

        apiKey = EditText(this).apply {
            hint = "Featherless API key"
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(apiKey)

        model = EditText(this).apply {
            hint = "Featherless model ID"
            setSingleLine(true)
        }
        root.addView(model)

        userName = EditText(this).apply {
            hint = "Your name"
            setSingleLine(true)
        }
        root.addView(userName)

        persona = EditText(this).apply {
            hint = "Evie personality"
            minLines = 8
            maxLines = 18
            gravity = Gravity.TOP or Gravity.START
        }
        root.addView(persona)

        speakCheck = CheckBox(this).apply {
            text = "Speak Evie's replies aloud"
        }
        root.addView(speakCheck)

        root.addView(Button(this).apply {
            text = "SAVE EVIE SETTINGS"
            setOnClickListener {
                saveSettings()
                toast("Evie settings saved.")
                refreshStatus()
            }
        }, matchButton())

        root.addView(section("Evie voice"))

        qwenVoiceCheck = CheckBox(this).apply {
            text = "Use Qwen3-TTS Evie voice (Android TTS is fallback)"
            setOnCheckedChangeListener { _, checked ->
                if (::qwenUrl.isInitialized) {
                    setVoiceFieldsEnabled(checked)
                }
            }
        }
        root.addView(qwenVoiceCheck)

        qwenUrl = EditText(this).apply {
            hint = "Qwen3-TTS server URL, e.g. http://192.168.1.20:8001"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        root.addView(qwenUrl)

        qwenApiKey = EditText(this).apply {
            hint = "Qwen3-TTS server API key (optional)"
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(qwenApiKey)

        qwenVoice = EditText(this).apply {
            hint = "Voice profile ID, e.g. clone:Evie or vc_..."
            setSingleLine(true)
        }
        root.addView(qwenVoice)

        qwenLanguage = EditText(this).apply {
            hint = "Voice language"
            setSingleLine(true)
        }
        root.addView(qwenLanguage)

        voiceStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(5), 0, dp(5))
        }
        root.addView(voiceStatus)

        val serverRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        root.addView(serverRow)

        serverRow.addView(Button(this).apply {
            text = "TEST SERVER"
            setOnClickListener {
                saveSettings()
                voiceStatus.text = "Checking Qwen3-TTS server…"
                worker.execute {
                    val result = runCatching {
                        QwenVoiceClient.health(applicationContext)
                    }.getOrElse {
                        "ERROR: " + (it.message ?: it.javaClass.simpleName)
                    }

                    runOnUiThread {
                        voiceStatus.text = result
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f))

        serverRow.addView(Button(this).apply {
            text = "TEST EVIE VOICE"
            setOnClickListener {
                saveSettings()
                AssistantService.speakOnly(
                    this@MainActivity,
                    "This is Evie. My voice system is connected and working."
                )
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f))

        refTranscript = EditText(this).apply {
            hint = "Exact transcript of the WAV you are about to import. This gives Qwen3-TTS the highest-fidelity clone."
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
        }
        root.addView(refTranscript)

        root.addView(Button(this).apply {
            text = "IMPORT WAV & CREATE / UPDATE EVIE VOICE"
            setOnClickListener {
                saveSettings()
                if (Prefs.qwenTtsUrl(this@MainActivity).isBlank()) {
                    toast("Set the Qwen3-TTS server URL first.")
                    return@setOnClickListener
                }
                chooseVoiceAudio()
            }
        }, matchButton())

        root.addView(TextView(this).apply {
            text =
                "Qwen3-TTS 1.7B is intentionally not stuffed into this APK. This app contains the client, clone-profile setup and playback path. Point it at your self-hosted Qwen3-TTS server now; the same voice layer carries into EvieOS later. Exact reference transcript + clean WAV gives the best clone quality."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        })

        root.addView(section("Phone control"))

        root.addView(Button(this).apply {
            text = "ENABLE / CHECK ACCESSIBILITY CONTROL"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        }, matchButton())

        root.addView(Button(this).apply {
            text = "ENABLE / CHECK NOTIFICATION ACCESS"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                )
            }
        }, matchButton())

        root.addView(TextView(this).apply {
            text =
                "Accessibility lets Evie read visible controls and tap, type, scroll, Back, Home and Recents. Notification access lets her answer questions such as “what notifications are waiting?”"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(10))
        })

        root.addView(section("Memory & learning"))

        memoryStatus = TextView(this).apply {
            textSize = 14f
        }
        root.addView(memoryStatus)

        root.addView(Button(this).apply {
            text = "REFRESH MEMORY STATUS"
            setOnClickListener {
                refreshMemoryStatus()
            }
        }, matchButton())

        root.addView(TextView(this).apply {
            text =
                "Evie stores durable memories, corrections, learned routine descriptions and successful phone-action history locally on this device. Ask things like “remember that I call this patrol mode” or “when I say start patrol, open Maps then ChatGPT.”"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        })

        root.addView(section("Voice control"))

        wakeButton = Button(this).apply {
            setOnClickListener {
                saveSettings()

                if (Prefs.wakeEnabled(this@MainActivity)) {
                    Prefs.setWakeEnabled(this@MainActivity, false)
                    AssistantService.stopWake(this@MainActivity)
                    refreshStatus()
                    refreshButtons()
                } else {
                    pendingStartWake = true
                    ensureMicThenStartWake()
                }
            }
        }
        root.addView(wakeButton, matchButton())

        root.addView(Button(this).apply {
            text = "LISTEN ONCE NOW"
            setOnClickListener {
                saveSettings()

                if (!Prefs.configured(this@MainActivity)) {
                    toast("Add your Featherless API key first.")
                    return@setOnClickListener
                }

                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    pendingListenOnce = true
                    requestPermissions(
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        REQ_MIC
                    )
                } else {
                    AssistantService.listenOnce(this@MainActivity)
                }
            }
        }, matchButton())

        bubbleButton = Button(this).apply {
            setOnClickListener {
                if (Prefs.bubbleEnabled(this@MainActivity) &&
                    Settings.canDrawOverlays(this@MainActivity)
                ) {
                    Prefs.setBubbleEnabled(this@MainActivity, false)
                    stopService(
                        Intent(
                            this@MainActivity,
                            BubbleService::class.java
                        ).setAction(BubbleService.ACTION_DISABLE)
                    )
                    refreshButtons()
                } else {
                    pendingStartBubble = true
                    ensureOverlayThenStartBubble()
                }
            }
        }
        root.addView(bubbleButton, matchButton())

        root.addView(TextView(this).apply {
            text =
                "Hey Evie mode uses Android speech recognition in a foreground microphone service. The floating E button is the dependable push-to-talk fallback until Evie becomes a privileged/default assistant in the custom OS."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        })

        root.addView(section("Test Evie"))

        testCommand = EditText(this).apply {
            hint =
                "e.g. Open ChatGPT, find the Ronin Sentinel conversation, then hit the microphone."
            minLines = 3
            maxLines = 7
            gravity = Gravity.TOP or Gravity.START
        }
        root.addView(testCommand)

        root.addView(Button(this).apply {
            text = "SEND COMMAND TO EVIE"
            setOnClickListener {
                saveSettings()
                val command = testCommand.text.toString().trim()

                if (command.isBlank()) {
                    toast("Type a test command first.")
                    return@setOnClickListener
                }

                if (!Prefs.configured(this@MainActivity)) {
                    toast("Add your Featherless API key first.")
                    return@setOnClickListener
                }

                AssistantService.submitText(
                    this@MainActivity,
                    command
                )
            }
        }, matchButton())

        root.addView(TextView(this).apply {
            text =
                "Try:\n" +
                "• Open ChatGPT and go to the Ronin Sentinel conversation.\n" +
                "• Read what's on my screen.\n" +
                "• Scroll down and tap Settings.\n" +
                "• What notifications are waiting?\n" +
                "• Remember that when I say start patrol I mean open Maps, then ChatGPT.\n" +
                "• Navigate to Surfers Paradise.\n" +
                "• Set my media volume to 60 percent.\n" +
                "• Set a ten minute timer called patrol check.\n" +
                "• Open Chrome and then go Home."
            textSize = 13f
            setPadding(0, dp(8), 0, dp(12))
        })
    }

    private fun section(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(5))
        }

    private fun matchButton() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54)
        ).apply {
            topMargin = dp(7)
        }

    private fun loadSettings() {
        apiKey.setText(Prefs.apiKey(this))
        model.setText(Prefs.model(this))
        userName.setText(Prefs.userName(this))
        persona.setText(Prefs.persona(this))
        speakCheck.isChecked = Prefs.speakEnabled(this)

        qwenVoiceCheck.isChecked =
            Prefs.voiceMode(this).equals("qwen", ignoreCase = true)

        qwenUrl.setText(Prefs.qwenTtsUrl(this))
        qwenApiKey.setText(Prefs.qwenTtsApiKey(this))
        qwenVoice.setText(Prefs.qwenTtsVoice(this))
        qwenLanguage.setText(Prefs.qwenTtsLanguage(this))

        setVoiceFieldsEnabled(qwenVoiceCheck.isChecked)

        refreshStatus()
        refreshButtons()
        refreshMemoryStatus()

        voiceStatus.text =
            if (qwenVoiceCheck.isChecked) {
                "Qwen3-TTS selected. Voice profile: " +
                    Prefs.qwenTtsVoice(this)
            } else {
                "Android TTS selected. Enable Qwen3-TTS above for the high-fidelity Evie voice."
            }

        if (Prefs.bubbleEnabled(this) &&
            Settings.canDrawOverlays(this)
        ) {
            startBubble()
        }

        if (Prefs.wakeEnabled(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { AssistantService.startWake(this) }
        }
    }

    private fun saveSettings() {
        Prefs.setApiKey(
            this,
            apiKey.text.toString()
        )

        Prefs.setModel(
            this,
            model.text.toString()
        )

        Prefs.setUserName(
            this,
            userName.text.toString()
        )

        Prefs.setPersona(
            this,
            persona.text.toString()
        )

        Prefs.setSpeakEnabled(
            this,
            speakCheck.isChecked
        )

        Prefs.setVoiceMode(
            this,
            if (qwenVoiceCheck.isChecked) "qwen" else "android"
        )

        Prefs.setQwenTtsUrl(
            this,
            qwenUrl.text.toString()
        )

        Prefs.setQwenTtsApiKey(
            this,
            qwenApiKey.text.toString()
        )

        Prefs.setQwenTtsVoice(
            this,
            qwenVoice.text.toString()
        )

        Prefs.setQwenTtsLanguage(
            this,
            qwenLanguage.text.toString()
        )
    }

    private fun setVoiceFieldsEnabled(enabled: Boolean) {
        if (!::qwenUrl.isInitialized) return

        qwenUrl.isEnabled = enabled
        qwenApiKey.isEnabled = enabled
        qwenVoice.isEnabled = enabled
        qwenLanguage.isEnabled = enabled
        refTranscript.isEnabled = enabled
    }

    private fun chooseVoiceAudio() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            },
            REQ_VOICE_AUDIO
        )
    }

    private fun importVoiceAudio(uri: Uri) {
        voiceStatus.text = "Uploading Evie reference audio to Qwen3-TTS…"

        worker.execute {
            var temp: File? = null

            try {
                val fileName = displayName(uri) ?: "evie_reference.wav"
                val mime = contentResolver.getType(uri)
                temp = File(
                    cacheDir,
                    "evie_voice_" + System.currentTimeMillis() + ".wav"
                )

                contentResolver.openInputStream(uri).use { inputStream ->
                    val input = inputStream
                        ?: throw IllegalArgumentException(
                            "Could not open selected audio."
                        )

                    FileOutputStream(temp).use { output ->
                        input.copyTo(output, 128 * 1024)
                    }
                }

                val profile = QwenVoiceClient.registerClone(
                    context = applicationContext,
                    sourceFile = temp,
                    fileName = fileName,
                    mimeType = mime,
                    refText = refTranscript.text.toString(),
                    name = "Evie"
                )

                Prefs.setQwenTtsVoice(
                    applicationContext,
                    profile.id
                )
                Prefs.setVoiceMode(
                    applicationContext,
                    "qwen"
                )

                runOnUiThread {
                    qwenVoiceCheck.isChecked = true
                    qwenVoice.setText(profile.id)
                    voiceStatus.text =
                        "Evie voice profile ready: " + profile.id
                    toast("Evie voice profile created.")
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    voiceStatus.text =
                        "VOICE ERROR: " +
                            (t.message ?: t.javaClass.simpleName)
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

            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        } finally {
            cursor?.close()
        }
    }

    private fun refreshMemoryStatus() {
        if (!::memoryStatus.isInitialized) return

        memoryStatus.text = runCatching {
            LearningStore.get(this).summary()
        }.getOrElse {
            "Memory database error: " +
                (it.message ?: it.javaClass.simpleName)
        }
    }

    private fun ensureMicThenStartWake() {
        if (!Prefs.configured(this)) {
            toast("Add your Featherless API key first.")
            pendingStartWake = false
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_MIC
            )
            return
        }

        startWake()
    }

    private fun startWake() {
        pendingStartWake = false
        Prefs.setWakeEnabled(this, true)
        AssistantService.startWake(this)
        refreshStatus()
        refreshButtons()
    }

    private fun ensureOverlayThenStartBubble() {
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                ),
                REQ_OVERLAY
            )
            return
        }

        startBubble()
    }

    private fun startBubble() {
        pendingStartBubble = false
        Prefs.setBubbleEnabled(this, true)

        val i = Intent(
            this,
            BubbleService::class.java
        ).setAction(
            BubbleService.ACTION_ENABLE
        )

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i)
        } else {
            startService(i)
        }

        refreshButtons()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQ_MIC) {
            val granted =
                grantResults.firstOrNull() ==
                    PackageManager.PERMISSION_GRANTED

            if (granted && pendingStartWake) {
                startWake()
            } else if (granted && pendingListenOnce) {
                pendingListenOnce = false
                AssistantService.listenOnce(this)
            } else if (!granted) {
                pendingStartWake = false
                pendingListenOnce = false
                toast(
                    "Microphone permission is required for voice control."
                )
            }
        }
    }

    @Deprecated("Compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    startBubble()
                } else {
                    pendingStartBubble = false
                    toast(
                        "Appear-on-top permission is required for the floating Evie button."
                    )
                }
            }

            REQ_VOICE_AUDIO -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let(::importVoiceAudio)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (pendingStartBubble &&
            Settings.canDrawOverlays(this)
        ) {
            startBubble()
        }

        refreshStatus()
        refreshButtons()
        refreshMemoryStatus()
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return

        val api =
            if (Prefs.apiKey(this).isBlank()) {
                "brain key missing"
            } else {
                "brain configured"
            }

        val accessibility =
            if (EvieAccessibilityService.isConnected()) {
                "Accessibility ON"
            } else {
                "Accessibility OFF"
            }

        val wake =
            if (Prefs.wakeEnabled(this)) {
                "Hey Evie ON"
            } else {
                "Hey Evie OFF"
            }

        val voice =
            if (Prefs.voiceMode(this).equals("qwen", true)) {
                "Qwen voice"
            } else {
                "Android voice"
            }

        status.text =
            api + "  ·  " +
                accessibility + "  ·  " +
                wake + "  ·  " +
                voice
    }

    private fun refreshButtons() {
        if (::wakeButton.isInitialized) {
            wakeButton.text =
                if (Prefs.wakeEnabled(this)) {
                    "TURN OFF HEY EVIE"
                } else {
                    "TURN ON HEY EVIE"
                }
        }

        if (::bubbleButton.isInitialized) {
            bubbleButton.text =
                if (Prefs.bubbleEnabled(this) &&
                    Settings.canDrawOverlays(this)
                ) {
                    "TURN OFF FLOATING EVIE BUTTON"
                } else {
                    "TURN ON FLOATING EVIE BUTTON"
                }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(
            this,
            text,
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
