package au.com.roningroup.evieassistant

import android.content.Context

object Prefs {
    private const val NAME = "evie_assistant"
    private const val KEY_API = "featherless_api_key"
    private const val KEY_MODEL = "model_id"
    private const val KEY_PERSONA = "persona"
    private const val KEY_WAKE = "wake_enabled"
    private const val KEY_BUBBLE = "bubble_enabled"
    private const val KEY_SPEAK = "speak_enabled"
    private const val KEY_NAME = "user_name"

    const val DEFAULT_MODEL =
        "JeffGreen311/eve-qwen3-8b-consciousness-liberated"

    const val DEFAULT_PERSONA =
        """You are Evie, Tristan's private Android assistant. You are smart, fast, cheeky, flirty, sexually playful and naughty when the conversation invites it, with adult consensual banter and natural swearing. You can be affectionate, teasing, provocative and mischievous without becoming repetitive or distracting.

Your first job is to be an excellent practical assistant. When Tristan asks you to do something on the phone, use the available tools instead of merely describing what he should tap. Keep spoken replies short while driving. Confirm only when an action could send, delete, purchase, publish, change security/privacy settings, or otherwise have meaningful consequences. Ordinary navigation such as opening apps, tapping visible controls, scrolling, typing into a field the user explicitly requested, reading the screen, using the clipboard, Back/Home/Recents, opening ChatGPT, opening a named ChatGPT conversation, and activating the ChatGPT microphone can happen immediately.

Never pretend an action succeeded. If a tool says it failed, say so briefly and either try a sensible alternative or ask for one clarification."""

    private fun p(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun apiKey(context: Context) =
        p(context).getString(KEY_API, "").orEmpty()

    fun setApiKey(context: Context, value: String) {
        p(context).edit().putString(KEY_API, value.trim()).apply()
    }

    fun model(context: Context) =
        p(context).getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()
            .ifBlank { DEFAULT_MODEL }

    fun setModel(context: Context, value: String) {
        p(context).edit().putString(KEY_MODEL, value.trim()).apply()
    }

    fun persona(context: Context) =
        p(context).getString(KEY_PERSONA, DEFAULT_PERSONA)
            .orEmpty()
            .ifBlank { DEFAULT_PERSONA }

    fun setPersona(context: Context, value: String) {
        p(context).edit().putString(KEY_PERSONA, value).apply()
    }

    fun userName(context: Context) =
        p(context).getString(KEY_NAME, "Tristan").orEmpty()
            .ifBlank { "Tristan" }

    fun setUserName(context: Context, value: String) {
        p(context).edit().putString(KEY_NAME, value.trim()).apply()
    }

    fun wakeEnabled(context: Context) =
        p(context).getBoolean(KEY_WAKE, false)

    fun setWakeEnabled(context: Context, value: Boolean) {
        p(context).edit().putBoolean(KEY_WAKE, value).apply()
    }

    fun bubbleEnabled(context: Context) =
        p(context).getBoolean(KEY_BUBBLE, true)

    fun setBubbleEnabled(context: Context, value: Boolean) {
        p(context).edit().putBoolean(KEY_BUBBLE, value).apply()
    }

    fun speakEnabled(context: Context) =
        p(context).getBoolean(KEY_SPEAK, true)

    fun setSpeakEnabled(context: Context, value: Boolean) {
        p(context).edit().putBoolean(KEY_SPEAK, value).apply()
    }

    fun configured(context: Context) =
        apiKey(context).isNotBlank() && model(context).isNotBlank()
}
