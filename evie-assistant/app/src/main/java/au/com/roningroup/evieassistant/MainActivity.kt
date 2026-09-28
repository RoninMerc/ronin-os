package au.com.roningroup.evieassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

class MainActivity : Activity() {
    companion object {
        private const val REQ_MIC = 9001
        private const val REQ_NOTIFICATIONS = 9002
        private const val REQ_OVERLAY = 9003
    }

    private lateinit var status: TextView
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var userName: EditText
    private lateinit var persona: EditText
    private lateinit var speakCheck: CheckBox
    private lateinit var wakeButton: Button
    private lateinit var bubbleButton: Button
    private lateinit var testCommand: EditText

    private var pendingStartWake = false
    private var pendingStartBubble = false

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
            setPadding(dp(18), dp(18), dp(18), dp(30))
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
            text = "Voice-driven Android agent · v0.1"
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
            maxLines = 16
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

        root.addView(section("Phone control"))

        root.addView(Button(this).apply {
            text = "ENABLE / CHECK ACCESSIBILITY CONTROL"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        }, matchButton())

        root.addView(TextView(this).apply {
            text = "In Android Accessibility settings, enable Evie Assistant. This is what lets Evie read visible controls and tap/scroll/type when you ask."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(10))
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
            text = "Hey Evie mode uses Android speech recognition as a foreground microphone service. The floating E button is the reliable push-to-talk fallback."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        })

        root.addView(section("Test command"))

        testCommand = EditText(this).apply {
            hint = "e.g. Open ChatGPT, find the Ronin Sentinel conversation, then hit the microphone."
            minLines = 3
            maxLines = 6
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
                "Examples:\n" +
                "• Open ChatGPT.\n" +
                "• Open the conversation called Ronin Sentinel.\n" +
                "• Read what's on my screen.\n" +
                "• Scroll down and tap Settings.\n" +
                "• Go Home.\n" +
                "• Navigate to Surfers Paradise.\n" +
                "• Pause my music."
            textSize = 13f
            setPadding(0, dp(8), 0, dp(12))
        })
    }

    private fun section(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
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
        refreshStatus()
        refreshButtons()

        if (Prefs.bubbleEnabled(this) &&
            Settings.canDrawOverlays(this)
        ) {
            startBubble()
        }

        if (Prefs.wakeEnabled(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            AssistantService.startWake(this)
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
                    Uri.parse("package:$packageName")
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
        ).setAction(BubbleService.ACTION_ENABLE)

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
            } else if (!granted) {
                pendingStartWake = false
                toast("Microphone permission is required for voice control.")
            }
        }
    }

    @Deprecated("Compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                startBubble()
            } else {
                pendingStartBubble = false
                toast("Appear-on-top permission is required for the floating Evie button.")
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
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return

        val api =
            if (Prefs.apiKey(this).isBlank()) "API key missing"
            else "brain configured"

        val accessibility =
            if (EvieAccessibilityService.isConnected()) "Accessibility ON"
            else "Accessibility OFF"

        val wake =
            if (Prefs.wakeEnabled(this)) "Hey Evie ON"
            else "Hey Evie OFF"

        status.text =
            "$api  ·  $accessibility  ·  $wake"
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
}
