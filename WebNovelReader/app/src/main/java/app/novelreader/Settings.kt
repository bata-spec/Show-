package app.novelreader

import android.content.Context

/** 端末に保存する簡単なアプリ設定（SharedPreferences） */
object Settings {
    private const val PREFS_NAME = "settings"
    private const val KEY_TTS_ENABLED = "tts_enabled"

    fun isTtsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TTS_ENABLED, true)

    fun setTtsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TTS_ENABLED, enabled)
            .apply()
    }
}
