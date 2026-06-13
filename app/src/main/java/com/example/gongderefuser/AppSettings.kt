package com.example.gongderefuser

import android.content.Context

object AppSettings {
    private const val PREF_NAME = "gongde_refuser_app_settings"
    private const val KEY_SOUND_ENABLED = "sound_enabled"

    fun isSoundEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SOUND_ENABLED, true)
    }

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
