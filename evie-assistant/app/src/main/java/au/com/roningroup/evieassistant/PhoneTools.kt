package au.com.roningroup.evieassistant

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import java.util.Locale

object PhoneTools {
    private val aliases = mapOf(
        "chatgpt" to "com.openai.chatgpt",
        "chat gpt" to "com.openai.chatgpt",
        "google maps" to "com.google.android.apps.maps",
        "maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "chrome" to "com.android.chrome",
        "youtube" to "com.google.android.youtube"
    )

    fun openApp(context: Context, value: String): String {
        val packageName = resolvePackage(context, value)
            ?: return "NOT_FOUND: Could not find an installed app matching \"" + value + "\"."

        val intent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?: return "ERROR: App " + packageName + " has no launch intent."

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            "OK: Opened " + value + " (" + packageName + ")."
        } catch (t: Throwable) {
            "ERROR: Failed to open " + value + ": " +
                (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun resolvePackage(context: Context, value: String): String? {
        val trimmed = value.trim()
        if (trimmed.contains('.') && !trimmed.contains(' ')) {
            val direct = runCatching {
                context.packageManager.getApplicationInfo(trimmed, 0)
            }.getOrNull()
            if (direct != null) return trimmed
        }

        val q = normalise(trimmed)
        aliases[q]?.let { known ->
            val installed = runCatching {
                context.packageManager.getApplicationInfo(known, 0)
            }.isSuccess
            if (installed) return known
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val apps = context.packageManager.queryIntentActivities(
            launcherIntent,
            0
        )

        var bestPackage: String? = null
        var bestScore = 0

        for (item in apps) {
            val label = item.loadLabel(context.packageManager)
                ?.toString()
                .orEmpty()
            val packageName = item.activityInfo.packageName
            val score = maxOf(
                labelScore(q, normalise(label)),
                labelScore(q, normalise(packageName))
            )
            if (score > bestScore) {
                bestScore = score
                bestPackage = packageName
            }
        }

        return if (bestScore >= 60) bestPackage else null
    }

    private fun labelScore(query: String, candidate: String): Int {
        if (candidate.isBlank()) return 0
        if (candidate == query) return 100
        if (candidate.startsWith(query)) return 90
        if (candidate.contains(query)) return 80
        if (query.contains(candidate) && candidate.length >= 4) return 65

        val qWords = query.split(' ').filter { it.length >= 2 }
        val cWords = candidate.split(' ').toSet()
        if (qWords.isEmpty()) return 0

        val overlap = qWords.count { it in cWords }
        return if (overlap > 0) 45 + (35 * overlap / qWords.size) else 0
    }

    private fun normalise(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    fun globalAction(action: String): String {
        val service = EvieAccessibilityService.current()
            ?: return "ERROR: Evie Accessibility is not enabled."

        val code = when (action.lowercase(Locale.ROOT)) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "recent", "overview" ->
                AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "notification shade" ->
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick settings", "quicksettings" ->
                AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return "ERROR: Unsupported global action: " + action
        }

        return if (service.performGlobalAction(code)) {
            "OK: Performed Android " + action + "."
        } else {
            "ERROR: Android rejected global action " + action + "."
        }
    }

    fun readScreen(): String =
        EvieAccessibilityService.current()?.readScreen()
            ?: "ERROR: Evie Accessibility is not enabled."

    fun tapText(text: String): String =
        EvieAccessibilityService.current()?.tapText(text)
            ?: "ERROR: Evie Accessibility is not enabled."

    fun typeText(text: String): String =
        EvieAccessibilityService.current()?.typeText(text)
            ?: "ERROR: Evie Accessibility is not enabled."

    fun scroll(direction: String): String =
        EvieAccessibilityService.current()?.scroll(direction)
            ?: "ERROR: Evie Accessibility is not enabled."

    fun openChatgptConversation(context: Context, title: String): String {
        val opened = openApp(context, "ChatGPT")
        if (opened.startsWith("ERROR") || opened.startsWith("NOT_FOUND")) {
            return opened
        }

        Thread.sleep(900)

        val service = EvieAccessibilityService.current()
            ?: return "ERROR: Evie Accessibility is not enabled."

        return service.openChatgptConversation(title)
    }

    fun tapChatgptMicrophone(): String =
        EvieAccessibilityService.current()?.tapChatgptMicrophone()
            ?: "ERROR: Evie Accessibility is not enabled."

    fun readClipboard(context: Context): String {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val clip = clipboard.primaryClip
            ?: return "CLIPBOARD: empty"

        if (clip.itemCount == 0) return "CLIPBOARD: empty"

        val value = clip.getItemAt(0)
            .coerceToText(context)
            .toString()

        return "CLIPBOARD:\n" + value
    }

    fun setClipboard(context: Context, text: String): String {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText("Evie", text)
        )

        return "OK: Copied text to clipboard."
    }

    fun navigateTo(context: Context, destination: String): String {
        val query = Uri.encode(destination)
        val uri = Uri.parse("google.navigation:q=" + query)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            "OK: Started Google Maps navigation to " + destination + "."
        } catch (_: Throwable) {
            val fallback = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=" + query)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.startActivity(fallback)
                "OK: Opened maps search for " + destination + "."
            } catch (t: Throwable) {
                "ERROR: Could not open navigation: " +
                    (t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun media(context: Context, command: String): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val key = when (command.lowercase(Locale.ROOT)) {
            "play", "pause", "play_pause", "toggle" ->
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return "ERROR: Unsupported media command: " + command
        }

        val now = android.os.SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0)
        )
        audio.dispatchMediaKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0)
        )

        return "OK: Sent media command " + command + "."
    }

    fun setMediaVolume(context: Context, percent: Int): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val value = ((percent.coerceIn(0, 100) / 100.0) * max)
            .toInt()
            .coerceIn(0, max)

        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            value,
            AudioManager.FLAG_SHOW_UI
        )

        return "OK: Media volume set to " + percent.coerceIn(0, 100) + "%."
    }

    fun openUrl(context: Context, url: String): String {
        val raw = url.trim()
        if (raw.isBlank()) return "ERROR: URL is empty."

        val uri = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            Uri.parse(raw)
        } else {
            Uri.parse("https://" + raw)
        }

        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            "OK: Opened " + uri.toString() + "."
        } catch (t: Throwable) {
            "ERROR: Could not open URL: " + (t.message ?: t.javaClass.simpleName)
        }
    }

    fun composeSms(
        context: Context,
        number: String,
        message: String
    ): String {
        return try {
            context.startActivity(
                Intent(
                    Intent.ACTION_SENDTO,
                    Uri.parse("smsto:" + Uri.encode(number))
                ).apply {
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            "OK: Opened SMS composer to " + number + " with the requested message. It has not been sent."
        } catch (t: Throwable) {
            "ERROR: Could not open SMS composer: " +
                (t.message ?: t.javaClass.simpleName)
        }
    }

    fun dialNumber(context: Context, number: String): String {
        return try {
            context.startActivity(
                Intent(
                    Intent.ACTION_DIAL,
                    Uri.parse("tel:" + Uri.encode(number))
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            "OK: Opened dialer for " + number + ". Call has not been placed."
        } catch (t: Throwable) {
            "ERROR: Could not open dialer: " +
                (t.message ?: t.javaClass.simpleName)
        }
    }

    fun setTimer(
        context: Context,
        seconds: Int,
        label: String
    ): String {
        return try {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(
                        AlarmClock.EXTRA_LENGTH,
                        seconds.coerceIn(1, 86_400)
                    )
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            "OK: Requested timer for " + seconds.coerceIn(1, 86_400) +
                " seconds" + if (label.isBlank()) "." else " labelled \"" + label + "\"."
        } catch (t: Throwable) {
            "ERROR: Could not set timer: " +
                (t.message ?: t.javaClass.simpleName)
        }
    }

    fun setAlarm(
        context: Context,
        hour: Int,
        minute: Int,
        label: String
    ): String {
        return try {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                    putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            "OK: Requested alarm for " +
                "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59)) +
                if (label.isBlank()) "." else " labelled \"" + label + "\"."
        } catch (t: Throwable) {
            "ERROR: Could not set alarm: " +
                (t.message ?: t.javaClass.simpleName)
        }
    }

    fun launchCamera(context: Context): String {
        return try {
            context.startActivity(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            "OK: Opened the camera."
        } catch (t: Throwable) {
            "ERROR: Could not open camera: " +
                (t.message ?: t.javaClass.simpleName)
        }
    }

    fun openSettings(context: Context, page: String): String {
        val action = when (page.lowercase(Locale.ROOT)) {
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "notifications" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            "sound", "audio" -> Settings.ACTION_SOUND_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

        return try {
            context.startActivity(
                Intent(action).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            "OK: Opened " + page + " settings."
        } catch (t: Throwable) {
            "ERROR: Could not open settings: " +
                (t.message ?: t.javaClass.simpleName)
        }
    }

    fun readNotifications(): String {
        if (!EvieNotificationService.isConnected()) {
            return "ERROR: Evie Notification Access is not enabled or connected."
        }

        return NotificationCache.snapshot(30)
    }

    fun openAssistantSettings(context: Context): String =
        openSettings(context, "accessibility")
}
