package au.com.roningroup.evelynreader

import android.content.Context

object Prefs {
    private const val NAME = "evelyn_reader"
    private const val KEY_API = "api_key"
    private const val KEY_VOICE_ID = "voice_id"
    private const val KEY_VOICE_NAME = "voice_name"
    private const val KEY_BUBBLE = "bubble_enabled"

    private fun p(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun apiKey(context: Context): String =
        p(context).getString(KEY_API, "").orEmpty()

    fun setApiKey(context: Context, value: String) {
        p(context).edit().putString(KEY_API, value.trim()).apply()
    }

    fun voiceId(context: Context): String =
        p(context).getString(KEY_VOICE_ID, "").orEmpty()

    fun voiceName(context: Context): String =
        p(context).getString(KEY_VOICE_NAME, "").orEmpty()

    fun setVoice(context: Context, id: String, name: String) {
        p(context).edit()
            .putString(KEY_VOICE_ID, id.trim())
            .putString(KEY_VOICE_NAME, name.trim())
            .apply()
    }

    fun bubbleEnabled(context: Context): Boolean =
        p(context).getBoolean(KEY_BUBBLE, false)

    fun setBubbleEnabled(context: Context, value: Boolean) {
        p(context).edit().putBoolean(KEY_BUBBLE, value).apply()
    }

    fun configured(context: Context): Boolean =
        apiKey(context).isNotBlank() && voiceId(context).isNotBlank()
}
