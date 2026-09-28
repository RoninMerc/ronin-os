package au.com.roningroup.evelynoffline

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    companion object {
        private const val REQ_OVERLAY = 6101
    }

    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var bubbleButton: Button
    private lateinit var speedLabel: TextView

    private var pendingBubbleEnable = false
    private var speed = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        if (
            Prefs.bubbleEnabled(this) &&
            Settings.canDrawOverlays(this)
        ) {
            startBubble()
        }

        status.text =
            "Evelyn is built into this app. No account, API key or model download is required."
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(18),
                dp(18),
                dp(18),
                dp(28)
            )
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

        root.addView(
            TextView(this).apply {
                text = "EVELYN"
                textSize = 30f
                setTypeface(
                    typeface,
                    android.graphics.Typeface.BOLD
                )
            }
        )

        root.addView(
            TextView(this).apply {
                text =
                    "Offline ChatGPT reply reader · Evelyn voice embedded"
                textSize = 14f
                setPadding(
                    0,
                    0,
                    0,
                    dp(14)
                )
            }
        )

        status = TextView(this).apply {
            textSize = 15f
            setPadding(
                0,
                0,
                0,
                dp(14)
            )
        }
        root.addView(status)

        bubbleButton = Button(this).apply {
            setOnClickListener {
                toggleBubble()
            }
        }

        root.addView(
            bubbleButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            )
        )

        refreshBubbleButton()

        root.addView(
            TextView(this).apply {
                text =
                    "Driving mode: tap ChatGPT's Copy button, then tap the floating E. Evelyn reads the copied reply through your phone or car Bluetooth."
                textSize = 14f
                setPadding(
                    0,
                    dp(8),
                    0,
                    dp(12)
                )
            }
        )

        root.addView(
            Button(this).apply {
                text = "TEST EVELYN"
                setOnClickListener {
                    read(
                        "This is Evelyn. The offline voice reader is working."
                    )
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        )

        root.addView(
            Button(this).apply {
                text = "PASTE CLIPBOARD & READ"
                setOnClickListener {
                    val value = readClipboard()

                    if (value.isBlank()) {
                        toast("Clipboard is empty.")
                    } else {
                        input.setText(value)
                        read(value)
                    }
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            ).apply {
                topMargin = dp(8)
            }
        )

        input = EditText(this).apply {
            hint =
                "Paste a ChatGPT reply here if you want to use the app manually…"
            minLines = 8
            maxLines = 18
            gravity =
                Gravity.TOP or Gravity.START
            setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(12)
            )
        }

        root.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )

        val controls = LinearLayout(this).apply {
            orientation =
                LinearLayout.HORIZONTAL
        }

        root.addView(controls)

        controls.addView(
            Button(this).apply {
                text = "READ"
                setOnClickListener {
                    read(
                        input.text
                            ?.toString()
                            .orEmpty()
                    )
                }
            },
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            )
        )

        controls.addView(
            Button(this).apply {
                text = "PAUSE"
                setOnClickListener {
                    PlaybackService.action(
                        this@MainActivity,
                        PlaybackService.ACTION_PAUSE
                    )
                }
            },
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            )
        )

        controls.addView(
            Button(this).apply {
                text = "RESUME"
                setOnClickListener {
                    PlaybackService.action(
                        this@MainActivity,
                        PlaybackService.ACTION_RESUME
                    )
                }
            },
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            )
        )

        controls.addView(
            Button(this).apply {
                text = "STOP"
                setOnClickListener {
                    PlaybackService.action(
                        this@MainActivity,
                        PlaybackService.ACTION_STOP
                    )
                }
            },
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            )
        )

        speedLabel = TextView(this).apply {
            text = "Speed: 1.00×"
            textSize = 14f
            setPadding(
                0,
                dp(12),
                0,
                0
            )
        }

        root.addView(speedLabel)

        root.addView(
            SeekBar(this).apply {
                max = 50
                progress = 25

                setOnSeekBarChangeListener(
                    object :
                        SeekBar.OnSeekBarChangeListener {

                        override fun onProgressChanged(
                            seekBar: SeekBar?,
                            value: Int,
                            fromUser: Boolean
                        ) {
                            speed =
                                0.75f +
                                    value / 100f

                            speedLabel.text =
                                String.format(
                                    Locale.US,
                                    "Speed: %.2f×",
                                    speed
                                )
                        }

                        override fun onStartTrackingTouch(
                            seekBar: SeekBar?
                        ) = Unit

                        override fun onStopTrackingTouch(
                            seekBar: SeekBar?
                        ) = Unit
                    }
                )
            }
        )

        root.addView(
            TextView(this).apply {
                text =
                    "You can also use Android Share → Read with Evelyn, or highlight text and choose Read with Evelyn from the text menu."
                textSize = 13f
                setPadding(
                    0,
                    dp(12),
                    0,
                    0
                )
            }
        )
    }

    private fun read(value: String) {
        val text = value.trim()

        if (text.isBlank()) {
            toast("There is no text to read.")
            return
        }

        status.text =
            "Starting Evelyn. The first sentence can take a few seconds while the offline model loads."

        PlaybackService.speak(
            this,
            text,
            speed
        )
    }

    private fun readClipboard(): String {
        val cm =
            getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

        val clip =
            cm.primaryClip ?: return ""

        if (clip.itemCount == 0) {
            return ""
        }

        return clip.getItemAt(0)
            .coerceToText(this)
            .toString()
    }

    private fun toggleBubble() {
        if (Prefs.bubbleEnabled(this)) {
            Prefs.setBubbleEnabled(
                this,
                false
            )

            stopService(
                Intent(
                    this,
                    BubbleService::class.java
                ).setAction(
                    BubbleService.ACTION_DISABLE
                )
            )

            refreshBubbleButton()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            pendingBubbleEnable = true

            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse(
                        "package:" + packageName
                    )
                ),
                REQ_OVERLAY
            )
            return
        }

        Prefs.setBubbleEnabled(
            this,
            true
        )

        startBubble()
        refreshBubbleButton()
    }

    private fun startBubble() {
        val intent =
            Intent(
                this,
                BubbleService::class.java
            ).setAction(
                BubbleService.ACTION_ENABLE
            )

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onResume() {
        super.onResume()

        if (
            pendingBubbleEnable &&
            Settings.canDrawOverlays(this)
        ) {
            pendingBubbleEnable = false

            Prefs.setBubbleEnabled(
                this,
                true
            )

            startBubble()
        }

        refreshBubbleButton()
    }

    private fun refreshBubbleButton() {
        if (!::bubbleButton.isInitialized) {
            return
        }

        bubbleButton.text =
            if (Prefs.bubbleEnabled(this)) {
                "TURN OFF FLOATING EVELYN BUTTON"
            } else {
                "TURN ON FLOATING EVELYN BUTTON"
            }
    }

    private fun toast(value: String) {
        Toast.makeText(
            this,
            value,
            Toast.LENGTH_LONG
        ).show()
    }
}
