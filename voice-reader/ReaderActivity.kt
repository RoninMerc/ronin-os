package com.nekospeak.tts.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import java.util.UUID

class ReaderActivity : Activity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingAutoRead = false
    private var chunks: List<String> = emptyList()
    private var chunkIndex = 0
    private var paused = false
    private var speed = 1.0f

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var readButton: Button
    private lateinit var pauseButton: Button
    private lateinit var speedLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Ronin Voice Reader"
        buildUi()
        handleIncoming(intent)
        tts = TextToSpeech(this, this, packageName)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
        if (ready && pendingAutoRead) {
            pendingAutoRead = false
            startReading()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(TextView(this).apply {
            text = "RONIN VOICE"
            textSize = 27f
            setTypeface(typeface, Typeface.BOLD)
        })
        titleBlock.addView(TextView(this).apply {
            text = "Local custom-voice reader"
            textSize = 14f
        })
        headerRow.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(Button(this).apply {
            text = "SETUP"
            setOnClickListener { openVoiceSetup() }
        })
        root.addView(headerRow)

        status = TextView(this).apply {
            text = "Voice engine starting…"
            textSize = 15f
            setPadding(0, dp(18), 0, dp(8))
        }
        root.addView(status)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))

        input = EditText(this).apply {
            hint = "Paste or share a ChatGPT reply here…"
            minLines = 11
            maxLines = 22
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        root.addView(Button(this).apply {
            text = "PASTE & READ"
            setOnClickListener { pasteAndRead() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(12) })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        root.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        readButton = Button(this).apply {
            text = "READ"
            setOnClickListener { if (paused) resumeReading() else startReading() }
        }
        pauseButton = Button(this).apply {
            text = "PAUSE"
            isEnabled = false
            setOnClickListener { pauseReading() }
        }
        val stop = Button(this).apply {
            text = "STOP"
            setOnClickListener { stopReading() }
        }
        controls.addView(readButton, LinearLayout.LayoutParams(0, dp(54), 1f))
        controls.addView(pauseButton, LinearLayout.LayoutParams(0, dp(54), 1f))
        controls.addView(stop, LinearLayout.LayoutParams(0, dp(54), 1f))

        speedLabel = TextView(this).apply {
            text = "Speed: 1.00×"
            textSize = 14f
            setPadding(0, dp(18), 0, 0)
        }
        root.addView(speedLabel)

        root.addView(SeekBar(this).apply {
            max = 60
            progress = 25
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    speed = 0.75f + value / 100f
                    speedLabel.text = String.format(Locale.US, "Speed: %.2f×", speed)
                    tts?.setSpeechRate(speed)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(Button(this).apply {
            text = "VOICE SETUP / IMPORT WAV"
            setOnClickListener { openVoiceSetup() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(12) })

        root.addView(TextView(this).apply {
            text = "Tip: in ChatGPT, use Share → Ronin Voice Reader. Shared text starts reading automatically once the local voice engine is ready."
            textSize = 13f
            setPadding(0, dp(14), 0, dp(24))
        })
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
            if (shared.isNotEmpty()) {
                input.setText(shared)
                pendingAutoRead = true
                status.text = "Shared text received — preparing voice…"
            }
        }
    }

    override fun onInit(initStatus: Int) {
        if (initStatus == TextToSpeech.SUCCESS) {
            ready = true
            tts?.setLanguage(Locale.US)
            tts?.setSpeechRate(speed)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread {
                        pauseButton.isEnabled = true
                        readButton.text = "READ"
                        status.text = "Reading " + (chunkIndex + 1) + " of " + chunks.size
                        progress.progress = if (chunks.isEmpty()) 0 else ((chunkIndex.toFloat() / chunks.size) * 1000).toInt()
                    }
                }
                override fun onDone(utteranceId: String?) {
                    if (!paused) {
                        chunkIndex++
                        if (chunkIndex < chunks.size) {
                            speakCurrent(false)
                        } else {
                            runOnUiThread {
                                pauseButton.isEnabled = false
                                progress.progress = 1000
                                status.text = "Finished"
                            }
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        pauseButton.isEnabled = false
                        status.text = "Voice generation failed. Open Voice Setup and verify PocketTTS and the cloned voice."
                    }
                }
                override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
            })
            runOnUiThread {
                status.text = "Ready"
                if (pendingAutoRead) {
                    pendingAutoRead = false
                    startReading()
                }
            }
        } else {
            runOnUiThread { status.text = "Voice engine unavailable — open Voice Setup." }
        }
    }

    private fun chunkText(text: String): List<String> {
        val cleaned = text.replace("\r\n", "\n").replace(Regex("[ \\t]+"), " ").trim()
        if (cleaned.isBlank()) return emptyList()
        val sentences = cleaned.split(Regex("(?<=[.!?])\\s+|\\n+"))
        val output = mutableListOf<String>()
        val maxChars = 420
        for (raw in sentences) {
            var s = raw.trim()
            if (s.isBlank()) continue
            while (s.length > maxChars) {
                var cut = s.lastIndexOfAny(charArrayOf(',', ';', ':', ' '), maxChars)
                if (cut < 120) cut = maxChars
                output += s.substring(0, cut).trim()
                s = s.substring(cut).trim()
            }
            if (s.isNotBlank()) output += s
        }
        return output
    }

    private fun startReading() {
        if (!ready) {
            status.text = "Voice engine is still starting…"
            pendingAutoRead = true
            return
        }
        val text = input.text?.toString().orEmpty().trim()
        if (text.isEmpty()) {
            status.text = "Paste or share some text first."
            return
        }
        tts?.stop()
        chunks = chunkText(text)
        chunkIndex = 0
        paused = false
        readButton.text = "READ"
        progress.progress = 0
        tts?.setSpeechRate(speed)
        speakCurrent(true)
    }

    private fun speakCurrent(flush: Boolean) {
        if (chunkIndex !in chunks.indices || paused) return
        val id = "ronin-" + chunkIndex + "-" + UUID.randomUUID().toString()
        tts?.speak(chunks[chunkIndex], if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun pauseReading() {
        if (!pauseButton.isEnabled) return
        paused = true
        tts?.stop()
        pauseButton.isEnabled = false
        readButton.text = "RESUME"
        status.text = "Paused"
    }

    private fun resumeReading() {
        if (!paused || chunks.isEmpty()) return
        paused = false
        readButton.text = "READ"
        speakCurrent(true)
    }

    private fun stopReading() {
        paused = false
        tts?.stop()
        chunks = emptyList()
        chunkIndex = 0
        pauseButton.isEnabled = false
        readButton.text = "READ"
        progress.progress = 0
        status.text = "Stopped"
    }

    private fun pasteAndRead() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString().trim() else ""
        if (text.isNotEmpty()) {
            input.setText(text)
            startReading()
        } else {
            status.text = "Clipboard is empty."
        }
    }

    private fun openVoiceSetup() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}