package au.com.roningroup.evieassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class AgentClient(private val context: Context) {
    companion object {
        private const val ENDPOINT =
            "https://api.featherless.ai/v1/chat/completions"
    }

    data class Result(
        val reply: String,
        val toolTrace: List<String>
    )

    fun runCommand(userText: String): Result {
        val apiKey = Prefs.apiKey(context)
        if (apiKey.isBlank()) {
            return Result(
                "I need your Featherless API key first. Open Evie and add it in Settings.",
                emptyList()
            )
        }

        val learning = LearningStore.get(context)
        val learnedContext = learning.contextFor(userText)

        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put(
                    "content",
                    Prefs.persona(context) +
                        "\n\nThe user's name is " +
                        Prefs.userName(context) +
                        ". You are running inside an Android phone-control app. " +
                        "Use tools whenever the user asks you to operate the phone. " +
                        "After tools complete, briefly report the real result. " +
                        "Do not invent UI elements, app state or successful actions. " +
                        "For ordinary navigation, act rather than explaining. " +
                        "Use read_screen when uncertain about the current interface. " +
                        "You have persistent memory. Store durable preferences, corrections, " +
                        "nicknames, workflow facts and useful routines when they are clearly " +
                        "worth remembering. Do not store every casual sentence. " +
                        "If an action failed previously, use the learned action history only " +
                        "as a hint and inspect the current screen before assuming the UI is unchanged." +
                        if (learnedContext.isBlank()) {
                            ""
                        } else {
                            "\n\nLOCAL LEARNED CONTEXT:\n" + learnedContext
                        }
                )
        )

        learning.recentConversation(10).forEach { (role, content) ->
            messages.put(
                JSONObject()
                    .put("role", role)
                    .put("content", content)
            )
        }

        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", userText)
        )

        val trace = mutableListOf<String>()
        val repeatedFailures = mutableMapOf<String, Int>()

        repeat(10) {
            val response = request(messages)
            val choices = response.optJSONArray("choices")
                ?: throw IllegalStateException("Featherless returned no choices.")

            if (choices.length() == 0) {
                throw IllegalStateException("Featherless returned an empty choices list.")
            }

            val message = choices.getJSONObject(0).getJSONObject("message")
            val toolCalls = message.optJSONArray("tool_calls")

            if (toolCalls == null || toolCalls.length() == 0) {
                val content = message.optString("content").trim()
                val reply =
                    if (content.isBlank()) "Done." else content

                learning.addConversationTurn(
                    "user",
                    userText
                )
                learning.addConversationTurn(
                    "assistant",
                    reply
                )

                return Result(
                    reply,
                    trace
                )
            }

            val assistantMessage = JSONObject()
                .put("role", "assistant")
                .put(
                    "content",
                    if (message.isNull("content")) JSONObject.NULL
                    else message.opt("content")
                )
                .put("tool_calls", toolCalls)

            messages.put(assistantMessage)

            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.getJSONObject(i)
                val id = call.optString("id").ifBlank { "tool_" + i }
                val function = call.getJSONObject("function")
                val name = function.getString("name")
                val argsText = function.optString("arguments", "{}")
                val args = try {
                    JSONObject(argsText)
                } catch (_: Throwable) {
                    JSONObject()
                }

                val signature =
                    name + ":" + args.toString()

                val result = executeTool(name, args)

                if (result.startsWith("ERROR") ||
                    result.startsWith("NOT_FOUND")
                ) {
                    val count =
                        (repeatedFailures[signature] ?: 0) + 1

                    repeatedFailures[signature] = count

                    if (count >= 2) {
                        messages.put(
                            JSONObject()
                                .put("role", "system")
                                .put(
                                    "content",
                                    "Do not repeat the same failed tool call again. " +
                                        "Inspect the current screen, try a materially different method, " +
                                        "or tell the user what blocked the action."
                                )
                        )
                    }
                } else {
                    repeatedFailures.remove(signature)
                }

                learning.logAction(
                    userCommand = userText,
                    toolName = name,
                    arguments = args.toString(),
                    result = result
                )

                trace += name + " -> " + result

                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", result)
                )
            }
        }

        val reply =
            "I hit the tool-step limit before I could finish that."

        learning.addConversationTurn(
            "user",
            userText
        )
        learning.addConversationTurn(
            "assistant",
            reply
        )

        return Result(
            reply,
            trace
        )
    }

    private fun request(messages: JSONArray): JSONObject {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 25_000
            readTimeout = 90_000
            setRequestProperty("Authorization", "Bearer " + Prefs.apiKey(context))
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Title", "Evie Assistant")
            setRequestProperty("User-Agent", "Evie-Assistant-Android/0.2")
        }

        try {
            val body = JSONObject()
                .put("model", Prefs.model(context))
                .put("messages", messages)
                .put("tools", toolSchemas())
                .put("tool_choice", "auto")
                .put("temperature", 0.45)
                .put("top_p", 0.92)
                .put("max_tokens", 1200)
                .toString()

            connection.outputStream.use { out ->
                out.write(body.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = stream
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                val detail = try {
                    val json = JSONObject(responseText)
                    json.opt("error")?.toString()
                        ?: json.opt("detail")?.toString()
                        ?: responseText.take(1000)
                } catch (_: Throwable) {
                    responseText.take(1000)
                }

                throw IllegalStateException(
                    "Featherless HTTP " + code + ": " + detail
                )
            }

            return JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun executeTool(name: String, args: JSONObject): String {
        return try {
            when (name) {
                "open_app" ->
                    PhoneTools.openApp(
                        context,
                        args.optString("app")
                    )

                "global_action" ->
                    PhoneTools.globalAction(
                        args.optString("action")
                    )

                "read_screen" ->
                    PhoneTools.readScreen()

                "tap_text" ->
                    PhoneTools.tapText(
                        args.optString("text")
                    )

                "type_text" ->
                    PhoneTools.typeText(
                        args.optString("text")
                    )

                "scroll" ->
                    PhoneTools.scroll(
                        args.optString("direction", "down")
                    )

                "read_clipboard" ->
                    PhoneTools.readClipboard(context)

                "set_clipboard" ->
                    PhoneTools.setClipboard(
                        context,
                        args.optString("text")
                    )

                "open_chatgpt_conversation" ->
                    PhoneTools.openChatgptConversation(
                        context,
                        args.optString("title")
                    )

                "tap_chatgpt_microphone" ->
                    PhoneTools.tapChatgptMicrophone()

                "navigate_to" ->
                    PhoneTools.navigateTo(
                        context,
                        args.optString("destination")
                    )

                "media_control" ->
                    PhoneTools.media(
                        context,
                        args.optString("command")
                    )

                "set_media_volume" ->
                    PhoneTools.setMediaVolume(
                        context,
                        args.optInt("percent", 50)
                    )

                "open_url" ->
                    PhoneTools.openUrl(
                        context,
                        args.optString("url")
                    )

                "compose_sms" ->
                    PhoneTools.composeSms(
                        context,
                        args.optString("number"),
                        args.optString("message")
                    )

                "dial_number" ->
                    PhoneTools.dialNumber(
                        context,
                        args.optString("number")
                    )

                "set_timer" ->
                    PhoneTools.setTimer(
                        context,
                        args.optInt("seconds", 60),
                        args.optString("label")
                    )

                "set_alarm" ->
                    PhoneTools.setAlarm(
                        context,
                        args.optInt("hour", 7),
                        args.optInt("minute", 0),
                        args.optString("label")
                    )

                "launch_camera" ->
                    PhoneTools.launchCamera(context)

                "open_settings" ->
                    PhoneTools.openSettings(
                        context,
                        args.optString("page", "settings")
                    )

                "read_notifications" ->
                    PhoneTools.readNotifications()

                "remember" -> {
                    val id = LearningStore.get(context).remember(
                        content = args.optString("content"),
                        kind = args.optString("kind", "general"),
                        importance = args.optInt("importance", 5)
                    )
                    if (id > 0) {
                        "OK: Remembered as memory #" + id + "."
                    } else {
                        "ERROR: Memory was empty."
                    }
                }

                "forget_memory" -> {
                    val count = LearningStore.get(context)
                        .forget(args.optString("query"))
                    "OK: Removed " + count + " matching memories."
                }

                "search_memory" ->
                    LearningStore.get(context)
                        .contextFor(args.optString("query"))

                "learn_routine" ->
                    LearningStore.get(context).saveRoutine(
                        name = args.optString("name"),
                        trigger = args.optString("trigger"),
                        description = args.optString("description")
                    )

                "find_routine" ->
                    LearningStore.get(context)
                        .findRoutine(args.optString("query"))

                "memory_summary" ->
                    LearningStore.get(context).summary()

                else ->
                    "ERROR: Unknown tool " + name + "."
            }
        } catch (t: Throwable) {
            "ERROR: Tool " + name + " crashed: " +
                (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun toolSchemas(): JSONArray {
        fun stringProperty(description: String) =
            JSONObject()
                .put("type", "string")
                .put("description", description)

        fun integerProperty(description: String, min: Int, max: Int) =
            JSONObject()
                .put("type", "integer")
                .put("description", description)
                .put("minimum", min)
                .put("maximum", max)

        fun tool(
            name: String,
            description: String,
            properties: JSONObject = JSONObject(),
            required: List<String> = emptyList()
        ): JSONObject {
            val params = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("additionalProperties", false)

            if (required.isNotEmpty()) {
                params.put(
                    "required",
                    JSONArray().apply {
                        required.forEach { put(it) }
                    }
                )
            }

            return JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", name)
                        .put("description", description)
                        .put("parameters", params)
                )
        }

        return JSONArray().apply {
            put(
                tool(
                    "open_app",
                    "Open an installed Android app. Use the human app name or package name.",
                    JSONObject().put(
                        "app",
                        stringProperty("App name or Android package name.")
                    ),
                    listOf("app")
                )
            )

            put(
                tool(
                    "global_action",
                    "Perform Android navigation: back, home, recents, notifications or quick settings.",
                    JSONObject().put(
                        "action",
                        stringProperty("back, home, recents, notifications, or quick settings")
                    ),
                    listOf("action")
                )
            )

            put(
                tool(
                    "read_screen",
                    "Read the active app package and visible accessible text/control descriptions. Use before guessing what is on screen."
                )
            )

            put(
                tool(
                    "tap_text",
                    "Tap a visible Android control by its text or content description.",
                    JSONObject().put(
                        "text",
                        stringProperty("Visible text or control description.")
                    ),
                    listOf("text")
                )
            )

            put(
                tool(
                    "type_text",
                    "Enter text into the focused or visible editable field. Use only when the user's requested content is clear.",
                    JSONObject().put(
                        "text",
                        stringProperty("Exact text to enter.")
                    ),
                    listOf("text")
                )
            )

            put(
                tool(
                    "scroll",
                    "Scroll the visible scrollable area.",
                    JSONObject().put(
                        "direction",
                        stringProperty("down or up")
                    ),
                    listOf("direction")
                )
            )

            put(tool("read_clipboard", "Read current Android clipboard text."))

            put(
                tool(
                    "set_clipboard",
                    "Copy text to Android clipboard.",
                    JSONObject().put(
                        "text",
                        stringProperty("Exact text to copy.")
                    ),
                    listOf("text")
                )
            )

            put(
                tool(
                    "open_chatgpt_conversation",
                    "Open ChatGPT and find a named conversation in the accessible conversation list, scrolling if needed.",
                    JSONObject().put(
                        "title",
                        stringProperty("Conversation title.")
                    ),
                    listOf("title")
                )
            )

            put(
                tool(
                    "tap_chatgpt_microphone",
                    "Tap the visible ChatGPT microphone or voice control. ChatGPT must be active."
                )
            )

            put(
                tool(
                    "navigate_to",
                    "Start Google Maps navigation or a maps search.",
                    JSONObject().put(
                        "destination",
                        stringProperty("Destination name or address.")
                    ),
                    listOf("destination")
                )
            )

            put(
                tool(
                    "media_control",
                    "Control active media playback.",
                    JSONObject().put(
                        "command",
                        stringProperty("play_pause, next, or previous")
                    ),
                    listOf("command")
                )
            )

            put(
                tool(
                    "set_media_volume",
                    "Set media volume.",
                    JSONObject().put(
                        "percent",
                        integerProperty("Volume percentage.", 0, 100)
                    ),
                    listOf("percent")
                )
            )

            put(
                tool(
                    "open_url",
                    "Open a web URL in the user's browser.",
                    JSONObject().put(
                        "url",
                        stringProperty("URL or hostname.")
                    ),
                    listOf("url")
                )
            )

            put(
                tool(
                    "compose_sms",
                    "Open an SMS composer with a number and draft message. This does not send the message.",
                    JSONObject()
                        .put("number", stringProperty("Phone number."))
                        .put("message", stringProperty("Draft message.")),
                    listOf("number", "message")
                )
            )

            put(
                tool(
                    "dial_number",
                    "Open the dialer with a number. This does not place the call.",
                    JSONObject().put(
                        "number",
                        stringProperty("Phone number.")
                    ),
                    listOf("number")
                )
            )

            put(
                tool(
                    "set_timer",
                    "Request an Android timer.",
                    JSONObject()
                        .put(
                            "seconds",
                            integerProperty("Timer duration in seconds.", 1, 86400)
                        )
                        .put(
                            "label",
                            stringProperty("Optional timer label.")
                        ),
                    listOf("seconds")
                )
            )

            put(
                tool(
                    "set_alarm",
                    "Request an Android alarm using 24-hour local time.",
                    JSONObject()
                        .put("hour", integerProperty("Hour 0-23.", 0, 23))
                        .put("minute", integerProperty("Minute 0-59.", 0, 59))
                        .put("label", stringProperty("Optional alarm label.")),
                    listOf("hour", "minute")
                )
            )

            put(tool("launch_camera", "Open the phone camera."))

            put(
                tool(
                    "open_settings",
                    "Open an Android settings page.",
                    JSONObject().put(
                        "page",
                        stringProperty("accessibility, wifi, bluetooth, location, notifications, sound, display, apps, battery, or settings")
                    ),
                    listOf("page")
                )
            )

            put(
                tool(
                    "read_notifications",
                    "Read notifications Evie's notification listener has observed. Use when the user asks what notifications/messages are waiting."
                )
            )

            put(
                tool(
                    "remember",
                    "Store a durable fact, preference, correction or workflow detail in Evie's local long-term memory.",
                    JSONObject()
                        .put("content", stringProperty("What to remember."))
                        .put("kind", stringProperty("general, preference, person, project, correction, or workflow"))
                        .put("importance", integerProperty("Importance 1-10.", 1, 10)),
                    listOf("content")
                )
            )

            put(
                tool(
                    "forget_memory",
                    "Remove local memories matching a user's explicit request to forget something.",
                    JSONObject().put(
                        "query",
                        stringProperty("Memory text/topic to remove.")
                    ),
                    listOf("query")
                )
            )

            put(
                tool(
                    "search_memory",
                    "Search Evie's local learned memory and successful action history.",
                    JSONObject().put(
                        "query",
                        stringProperty("What to recall.")
                    ),
                    listOf("query")
                )
            )

            put(
                tool(
                    "learn_routine",
                    "Save or update a reusable routine description after a workflow is clear or the user asks Evie to remember the routine.",
                    JSONObject()
                        .put("name", stringProperty("Short routine name."))
                        .put("trigger", stringProperty("Likely phrase the user will say."))
                        .put("description", stringProperty("Exact sequence or purpose of the routine in plain language.")),
                    listOf("name", "description")
                )
            )

            put(
                tool(
                    "find_routine",
                    "Search for an existing learned routine.",
                    JSONObject().put(
                        "query",
                        stringProperty("Routine name, trigger or purpose.")
                    ),
                    listOf("query")
                )
            )

            put(tool("memory_summary", "Report the size of Evie's local memory, routine and action-history stores."))
        }
    }
}
