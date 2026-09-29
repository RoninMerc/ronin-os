package au.com.roningroup.evieassistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

object NotificationCache {
    private val lock = Any()
    private val items = LinkedHashMap<String, String>()

    fun put(key: String, value: String) {
        synchronized(lock) {
            items.remove(key)
            items[key] = value

            while (items.size > 80) {
                val first = items.keys.firstOrNull() ?: break
                items.remove(first)
            }
        }
    }

    fun remove(key: String) {
        synchronized(lock) {
            items.remove(key)
        }
    }

    fun clear() {
        synchronized(lock) {
            items.clear()
        }
    }

    fun snapshot(limit: Int = 30): String {
        val rows = synchronized(lock) {
            items.values.toList().takeLast(limit)
        }

        return if (rows.isEmpty()) {
            "NOTIFICATIONS: none currently cached."
        } else {
            "NOTIFICATIONS:\n" + rows.joinToString("\n")
        }
    }

    fun count(): Int =
        synchronized(lock) { items.size }
}

class EvieNotificationService : NotificationListenerService() {
    companion object {
        private val connected = AtomicBoolean(false)

        fun isConnected(): Boolean = connected.get()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected.set(true)

        NotificationCache.clear()

        runCatching {
            activeNotifications
                ?.sortedBy { it.postTime }
                ?.forEach(::cacheNotification)
        }
    }

    override fun onListenerDisconnected() {
        connected.set(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let(::cacheNotification)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let(NotificationCache::remove)
    }

    private fun cacheNotification(n: StatusBarNotification) {
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

        val subText = extras
            .getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?.toString()
            .orEmpty()

        val body = when {
            bigText.isNotBlank() -> bigText
            text.isNotBlank() -> text
            subText.isNotBlank() -> subText
            else -> ""
        }

        val packageName = n.packageName.orEmpty()

        NotificationCache.put(
            n.key,
            packageName + " | " +
                title.ifBlank { "(no title)" } +
                if (body.isBlank()) "" else " | " + body
        )
    }
}
