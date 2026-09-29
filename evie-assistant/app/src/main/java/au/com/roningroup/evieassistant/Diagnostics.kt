package au.com.roningroup.evieassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.speech.SpeechRecognizer

object Diagnostics {
    fun report(context: Context): String {
        val micGranted =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        val overlay =
            Settings.canDrawOverlays(context)

        val power =
            context.getSystemService(Context.POWER_SERVICE) as PowerManager

        val unrestricted =
            runCatching {
                power.isIgnoringBatteryOptimizations(context.packageName)
            }.getOrDefault(false)

        val brain =
            Prefs.apiKey(context).isNotBlank()

        val qwenSelected =
            Prefs.voiceMode(context).equals("qwen", ignoreCase = true)

        val qwenReady =
            !qwenSelected || Prefs.qwenTtsUrl(context).isNotBlank()

        return buildString {
            appendLine(
                mark(brain) +
                    " Featherless brain configured"
            )
            appendLine(
                mark(EvieAccessibilityService.isConnected()) +
                    " Accessibility service connected"
            )
            appendLine(
                mark(EvieNotificationService.isConnected()) +
                    " Notification listener connected"
            )
            appendLine(
                mark(micGranted) +
                    " Microphone permission"
            )
            appendLine(
                mark(SpeechRecognizer.isRecognitionAvailable(context)) +
                    " Android speech recognition available"
            )
            appendLine(
                mark(overlay) +
                    " Floating-overlay permission"
            )
            appendLine(
                mark(unrestricted) +
                    " Battery optimization exemption"
            )
            appendLine(
                mark(!Prefs.wakeEnabled(context) || AssistantService.isWakeRunning()) +
                    " Hey Evie runtime state"
            )
            appendLine(
                mark(qwenReady) +
                    if (qwenSelected) {
                        " Qwen3-TTS endpoint configured"
                    } else {
                        " Android TTS fallback selected"
                    }
            )
            append(
                LearningStore.get(context).summary()
            )
        }.trim()
    }

    private fun mark(ok: Boolean): String =
        if (ok) "✓" else "✗"
}
