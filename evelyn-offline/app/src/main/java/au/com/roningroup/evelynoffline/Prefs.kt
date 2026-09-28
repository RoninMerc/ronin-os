package au.com.roningroup.evelynoffline

import android.content.Context

object Prefs {
    private const val NAME = "evelyn_offline"
    private const val KEY_BUBBLE = "bubble_enabled"

    private fun p(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun bubbleEnabled(context: Context): Boolean =
        p(context).getBoolean(KEY_BUBBLE, false)

    fun setBubbleEnabled(context: Context, value: Boolean) {
        p(context).edit().putBoolean(KEY_BUBBLE, value).apply()
    }
}
