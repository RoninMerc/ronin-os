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
                        "Do not invent UI elements, app state or successful actions."
                )
        )
        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", userText)
        )

        val trace = mutableListOf<String>()

        repeat(8) {
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
                return Result(
                    if (content.isBlank()) "Done." else content,
                    trace
                )
            }

            val assistantMessage = JSONObject()
                .put("role", "assistant")
                .put("content", if (message.isNull("content")) JSONObject.NULL else message.opt("content"))
                .put("tool_calls", toolCalls)

            messages.put(assistantMessage)

            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.getJSONObject(i)
                val id = call.optString("id").ifBlank { "tool_$i" }
                val function = call.getJSONObject("function")
                val name = function.getString("name")
                val argsText = function.optString("arguments", "{}")
                val args = try {
                    JSONObject(argsText)
                } catch (_: Throwable) {
                    JSONObject()
                }

                val result = executeTool(name, args)
                trace += "$name -> $result"

                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", result)
                )
            }
        }

        return Result(
            "I hit the tool-step limit before I could finish that.",
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
            setRequestProperty("User-Agent", "Evie-Assistant-Android/0.1")
        }

        try {
            val body = JSONObject()
                .put("model", Prefs.model(context))
                .put("messages", messages)
                .put("tools", toolSchemas())
                .put("tool_choice", "auto")
                .put("temperature", 0.65)
                .put("top_p", 0.9)
                .put("max_tokens", 900)
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
                    "Featherless HTTP $code: $detail"
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

                else ->
                    "ERROR: Unknown tool $name."
            }
        } catch (t: Throwable) {
            "ERROR: Tool $name crashed: " +
                (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun toolSchemas(): JSONArray {
        fun stringProperty(description: String) =
            JSONObject()
                .put("type", "string")
                .put("description", description)

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
                    "Open an installed Android app. Use the human app name such as ChatGPT, Google Maps, Spotify or Chrome.",
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
                    "Perform an Android system navigation action.",
                    JSONObject().put(
                        "action",
                        stringProperty("One of: back, home, recents, notifications, quick settings.")
                    ),
                    listOf("action")
                )
            )

            put(
                tool(
                    "read_screen",
                    "Read the active app package and visible accessible text/control descriptions. Use this before guessing what is on screen."
                )
            )

            put(
                tool(
                    "tap_text",
                    "Tap a visible Android control by its text or content description.",
                    JSONObject().put(
                        "text",
                        stringProperty("Visible text or control description to tap.")
                    ),
                    listOf("text")
                )
            )

            put(
                tool(
                    "type_text",
                    "Replace text in the currently focused or first visible editable field. Use only when the user's request clearly specifies what to type.",
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

            put(
                tool(
                    "read_clipboard",
                    "Read the current Android clipboard text."
                )
            )

            put(
                tool(
                    "set_clipboard",
                    "Copy text to the Android clipboard.",
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
                    "Open ChatGPT and search the currently accessible conversation list for a conversation title, scrolling if necessary.",
                    JSONObject().put(
                        "title",
                        stringProperty("ChatGPT conversation title.")
                    ),
                    listOf("title")
                )
            )

            put(
                tool(
                    "tap_chatgpt_microphone",
                    "Tap the visible ChatGPT voice or microphone control. ChatGPT must be the active app."
                )
            )

            put(
                tool(
                    "navigate_to",
                    "Start Google Maps navigation or map search for a destination.",
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
                    "Control the active media session.",
                    JSONObject().put(
                        "command",
                        stringProperty("play_pause, next, or previous")
                    ),
                    listOf("command")
                )
            )
        }
    }
}
