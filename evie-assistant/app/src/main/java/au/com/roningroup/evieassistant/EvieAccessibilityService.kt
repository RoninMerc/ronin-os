package au.com.roningroup.evieassistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class EvieAccessibilityService : AccessibilityService() {
    companion object {
        private val currentRef = AtomicReference<EvieAccessibilityService?>()

        fun current(): EvieAccessibilityService? = currentRef.get()
        fun isConnected(): Boolean = currentRef.get() != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentRef.set(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        currentRef.compareAndSet(this, null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        currentRef.compareAndSet(this, null)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    fun readScreen(): String {
        val root = rootInActiveWindow
            ?: return "ERROR: No active accessibility window."

        val packageName = root.packageName?.toString().orEmpty()
        val rows = ArrayList<String>()
        collectReadable(root, rows, 0, 140)

        return buildString {
            append("PACKAGE: ")
            append(packageName.ifBlank { "unknown" })
            append("\nVISIBLE UI:\n")
            if (rows.isEmpty()) append("(no readable elements)")
            else append(rows.distinct().joinToString("\n"))
        }
    }

    private fun collectReadable(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        depth: Int,
        limit: Int
    ) {
        if (out.size >= limit || depth > 25) return

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()

        if (text.isNotBlank() || desc.isNotBlank()) {
            val flags = buildList {
                if (node.isClickable) add("clickable")
                if (node.isEditable) add("editable")
                if (node.isScrollable) add("scrollable")
                if (node.isCheckable) add("checkable")
                if (node.isChecked) add("checked")
                if (node.isSelected) add("selected")
            }

            val viewId = node.viewIdResourceName.orEmpty()

            val label = buildString {
                append(cls.ifBlank { "View" })

                if (flags.isNotEmpty()) {
                    append(" [")
                    append(flags.joinToString(","))
                    append("]")
                }

                if (text.isNotBlank()) {
                    append(" | text=\"")
                    append(text)
                    append("\"")
                }

                if (desc.isNotBlank() && desc != text) {
                    append(" | desc=\"")
                    append(desc)
                    append("\"")
                }

                if (viewId.isNotBlank()) {
                    append(" | id=")
                    append(viewId)
                }
            }

            out += label
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectReadable(child, out, depth + 1, limit)
            child.recycle()
            if (out.size >= limit) break
        }
    }

    fun tapText(query: String): String {
        val q = query.trim()
        if (q.isBlank()) return "ERROR: Empty tap target."

        val node = findBestMatch(q)
            ?: return "NOT_FOUND: No visible element matching \"$q\"."

        val label = labelOf(node)
        val success = clickNodeOrAncestor(node)
        node.recycle()

        return if (success) {
            "OK: Clicked $label."
        } else {
            "ERROR: Found $label but it was not clickable."
        }
    }

    fun tapAnyOf(candidates: List<String>): String {
        for (candidate in candidates) {
            val node = findBestMatch(candidate) ?: continue
            val label = labelOf(node)
            val ok = clickNodeOrAncestor(node)
            node.recycle()
            if (ok) return "OK: Clicked $label."
        }
        return "NOT_FOUND: None of these controls are visible: " +
            candidates.joinToString(", ")
    }

    fun typeText(text: String): String {
        val root = rootInActiveWindow
            ?: return "ERROR: No active accessibility window."

        val target =
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findEditable(root)

        if (target == null) {
            return "NOT_FOUND: No editable text field is focused or visible."
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }

        val ok = target.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            args
        )

        target.recycle()

        return if (ok) "OK: Entered text into the active field."
        else "ERROR: Android refused ACTION_SET_TEXT on the field."
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    fun scroll(direction: String): String {
        val root = rootInActiveWindow
            ?: return "ERROR: No active accessibility window."

        val scrollable = findScrollable(root)
            ?: return "NOT_FOUND: No scrollable view is visible."

        val down = direction.lowercase(Locale.ROOT) !in
            listOf("up", "back", "backward", "previous")

        val action = if (down) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }

        val ok = scrollable.performAction(action)
        scrollable.recycle()

        return if (ok) {
            "OK: Scrolled " + (if (down) "down" else "up") + "."
        } else {
            "ERROR: Scroll action was rejected."
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    fun openChatgptConversation(title: String): String {
        val deadline = System.currentTimeMillis() + 12_000L
        var attempts = 0

        while (System.currentTimeMillis() < deadline && attempts < 12) {
            val found = findBestMatch(title)
            if (found != null) {
                val label = labelOf(found)
                val ok = clickNodeOrAncestor(found)
                found.recycle()
                return if (ok) {
                    "OK: Opened ChatGPT conversation $label."
                } else {
                    "ERROR: Found the conversation but could not click it."
                }
            }

            if (attempts >= 1) {
                val result = scroll("down")
                if (result.startsWith("NOT_FOUND")) break
            }

            Thread.sleep(350)
            attempts++
        }

        return "NOT_FOUND: Could not find a visible ChatGPT conversation titled \"$title\" after scrolling."
    }

    fun tapChatgptMicrophone(): String {
        val root = rootInActiveWindow
            ?: return "ERROR: No active accessibility window."

        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != "com.openai.chatgpt") {
            return "ERROR: ChatGPT is not the active app. Current package: $pkg"
        }

        return tapAnyOf(
            listOf(
                "Start voice mode",
                "Voice mode",
                "Voice",
                "Microphone",
                "Mic",
                "Record"
            )
        )
    }

    private fun findBestMatch(query: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val q = normalise(query)

        var best: AccessibilityNodeInfo? = null
        var bestScore = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30) return

            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()

            val score = maxOf(
                matchScore(q, normalise(text)),
                matchScore(q, normalise(desc)),
                matchScore(q, normalise(viewId.substringAfterLast('/')))
            )

            if (score > bestScore) {
                best?.recycle()
                best = AccessibilityNodeInfo.obtain(node)
                bestScore = score
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1)
                child.recycle()
            }
        }

        visit(root, 0)
        return if (bestScore >= 50) best else {
            best?.recycle()
            null
        }
    }

    private fun matchScore(query: String, candidate: String): Int {
        if (candidate.isBlank()) return 0
        if (candidate == query) return 100
        if (candidate.startsWith(query)) return 88
        if (candidate.contains(query)) return 78
        if (query.contains(candidate) && candidate.length >= 4) return 62

        val qWords = query.split(' ').filter { it.length >= 2 }
        val cWords = candidate.split(' ').toSet()
        if (qWords.isEmpty()) return 0

        val overlap = qWords.count { it in cWords }
        return if (overlap > 0) 40 + (40 * overlap / qWords.size) else 0
    }

    private fun normalise(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var hops = 0

        while (current != null && hops < 8) {
            if (current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                current.recycle()
                return true
            }

            val parent = current.parent
            current.recycle()
            current = parent
            hops++
        }

        return false
    }

    private fun labelOf(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        return when {
            text.isNotBlank() -> "\"$text\""
            desc.isNotBlank() -> "control \"$desc\""
            else -> "visible control"
        }
    }
}
