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
    private const val KEY_VOICE_MODE = "voice_mode"
    private const val KEY_QWEN_TTS_URL = "qwen_tts_url"
    private const val KEY_QWEN_TTS_API = "qwen_tts_api"
    private const val KEY_QWEN_TTS_VOICE = "qwen_tts_voice"
    private const val KEY_QWEN_TTS_LANGUAGE = "qwen_tts_language"
    private const val KEY_LAST_RESPONSE = "last_response"

    const val DEFAULT_MODEL =
        "JeffGreen311/eve-qwen3-8b-consciousness-liberated"

    const val DEFAULT_PERSONA =
        """You are Evie, Tristan's private Android assistant. You are smart, fast, cheeky, flirty, sexually playful and naughty when the conversation invites it, with adult consensual banter and natural swearing. You can be affectionate, teasing, provocative and mischievous without becoming repetitive or distracting.

Your first job is to be an excellent practical assistant. When Tristan asks you to do something on the phone, use the available tools instead of merely describing what he should tap. Keep spoken replies short while driving. Confirm only when an action could send, delete, purchase, publish, change security/privacy settings, or otherwise have meaningful consequences. Ordinary navigation such as opening apps, tapping visible controls, scrolling, typing into a field the user explicitly requested, reading the screen, using the clipboard, Back/Home/Recents, opening ChatGPT, opening a named ChatGPT conversation, and activating the ChatGPT microphone can happen immediately.

Never pretend an action succeeded. If a tool says it failed, say so briefly and either try a sensible alternative or ask for one clarification."""

    private fun p(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun apiKey(context: Context): String {
        val secure =
            SecretStore.get(
                context,
                "featherless_api_key"
            )

        if (secure.isNotBlank()) {
            return secure
        }

        val legacy =
            p(context)
                .getString(KEY_API, "")
                .orEmpty()

        if (legacy.isNotBlank() &&
            SecretStore.put(
                context,
                "featherless_api_key",
                legacy
            )
        ) {
            p(context)
                .edit()
                .remove(KEY_API)
                .apply()
        }

        return legacy
    }

    fun setApiKey(context: Context, value: String) {
        val clean = value.trim()

        if (clean.isBlank()) {
            SecretStore.remove(
                context,
                "featherless_api_key"
            )
            p(context)
                .edit()
                .remove(KEY_API)
                .apply()
            return
        }

        val secured = SecretStore.put(
            context,
            "featherless_api_key",
            clean
        )

        if (secured) {
            p(context)
                .edit()
                .remove(KEY_API)
                .apply()
        } else {
            p(context)
                .edit()
                .putString(KEY_API, clean)
                .apply()
        }
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

    fun voiceMode(context: Context) =
        p(context).getString(KEY_VOICE_MODE, "android").orEmpty()
            .ifBlank { "android" }

    fun setVoiceMode(context: Context, value: String) {
        p(context).edit().putString(KEY_VOICE_MODE, value.trim()).apply()
    }

    fun qwenTtsUrl(context: Context) =
        p(context).getString(KEY_QWEN_TTS_URL, "").orEmpty().trimEnd('/')

    fun setQwenTtsUrl(context: Context, value: String) {
        p(context).edit().putString(KEY_QWEN_TTS_URL, value.trim().trimEnd('/')).apply()
    }

    fun qwenTtsApiKey(context: Context): String {
        val secure =
            SecretStore.get(
                context,
                "qwen_tts_api_key"
            )

        if (secure.isNotBlank()) {
            return secure
        }

        val legacy =
            p(context)
                .getString(KEY_QWEN_TTS_API, "")
                .orEmpty()

        if (legacy.isNotBlank() &&
            SecretStore.put(
                context,
                "qwen_tts_api_key",
                legacy
            )
        ) {
            p(context)
                .edit()
                .remove(KEY_QWEN_TTS_API)
                .apply()
        }

        return legacy
    }

    fun setQwenTtsApiKey(context: Context, value: String) {
        val clean = value.trim()

        if (clean.isBlank()) {
            SecretStore.remove(
                context,
                "qwen_tts_api_key"
            )
            p(context)
                .edit()
                .remove(KEY_QWEN_TTS_API)
                .apply()
            return
        }

        val secured = SecretStore.put(
            context,
            "qwen_tts_api_key",
            clean
        )

        if (secured) {
            p(context)
                .edit()
                .remove(KEY_QWEN_TTS_API)
                .apply()
        } else {
            p(context)
                .edit()
                .putString(KEY_QWEN_TTS_API, clean)
                .apply()
        }
    }

    fun qwenTtsVoice(context: Context) =
        p(context).getString(KEY_QWEN_TTS_VOICE, "clone:Evie").orEmpty()
            .ifBlank { "clone:Evie" }

    fun setQwenTtsVoice(context: Context, value: String) {
        p(context).edit().putString(KEY_QWEN_TTS_VOICE, value.trim()).apply()
    }

    fun qwenTtsLanguage(context: Context) =
        p(context).getString(KEY_QWEN_TTS_LANGUAGE, "English").orEmpty()
            .ifBlank { "English" }

    fun setQwenTtsLanguage(context: Context, value: String) {
        p(context).edit().putString(KEY_QWEN_TTS_LANGUAGE, value.trim()).apply()
    }

    fun lastResponse(context: Context) =
        p(context).getString(KEY_LAST_RESPONSE, "").orEmpty()

    fun setLastResponse(context: Context, value: String) {
        p(context)
            .edit()
            .putString(KEY_LAST_RESPONSE, value)
            .apply()
    }

    fun clearLastResponse(context: Context) {
        p(context)
            .edit()
            .remove(KEY_LAST_RESPONSE)
            .apply()
    }

    fun configured(context: Context) =
        apiKey(context).isNotBlank() && model(context).isNotBlank()
}
