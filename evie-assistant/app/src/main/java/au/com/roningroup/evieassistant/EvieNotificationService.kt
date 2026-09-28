package au.com.roningroup.evieassistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

object NotificationCache {
    private val items = ConcurrentHashMap<String, String>()

    fun put(key: String, value: String) {
        items[key] = value
        if (items.size > 80) {
            items.keys.take(items.size - 80).forEach { items.remove(it) }
        }
    }

    fun remove(key: String) {
        items.remove(key)
    }

    fun snapshot(limit: Int = 30): String {
        val rows = items.values.toList().takeLast(limit)
        return if (rows.isEmpty()) {
            "NOTIFICATIONS: none cached."
        } else {
            "NOTIFICATIONS:\n" + rows.joinToString("\n")
        }
    }
}

class EvieNotificationService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val extras = n.notification.extras

        val title = extras
            .getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            .orEmpty()

        val text = extras
            .getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()
            .orEmpty()

        val bigText = extras
            .getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?.toString()
            .orEmpty()

        val body = if (bigText.isNotBlank()) bigText else text
        val packageName = n.packageName.orEmpty()

        NotificationCache.put(
            n.key,
            packageName + " | " +
                title.ifBlank { "(no title)" } +
                if (body.isBlank()) "" else " | " + body
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let(NotificationCache::remove)
    }
}
