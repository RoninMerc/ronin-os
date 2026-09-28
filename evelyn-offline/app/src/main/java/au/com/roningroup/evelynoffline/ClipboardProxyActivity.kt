package au.com.roningroup.evelynoffline

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class ClipboardProxyActivity : Activity() {
    companion object {
        const val ACTION_READ_CLIPBOARD =
            "au.com.roningroup.evelynoffline.READ_CLIPBOARD"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = when (intent?.action) {
            Intent.ACTION_SEND ->
                intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()

            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                    ?.toString()
                    .orEmpty()

            ACTION_READ_CLIPBOARD ->
                readClipboard()

            else -> ""
        }.trim()

        if (text.isBlank()) {
            Toast.makeText(
                this,
                "Evelyn: no text found.",
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }

        PlaybackService.speak(this, text)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun readClipboard(): String {
        val cm =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""

        return clip.getItemAt(0)
            .coerceToText(this)
            .toString()
    }
}
