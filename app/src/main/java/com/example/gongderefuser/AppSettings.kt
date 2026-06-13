package com.example.gongderefuser

import android.content.Context

object AppSettings {
    private const val PREF_NAME = "gongde_refuser_app_settings"
    private const val KEY_SOUND_ENABLED = "sound_enabled"
    private const val KEY_DEBUG_SAMPLES_ENABLED = "debug_samples_enabled"
    private const val KEY_ACCESSIBILITY_LOG_ENABLED = "accessibility_log_enabled"

    fun isSoundEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SOUND_ENABLED, true)
    }

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

    fun isDebugSamplesEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DEBUG_SAMPLES_ENABLED, false)
    }

    fun setDebugSamplesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEBUG_SAMPLES_ENABLED, enabled).apply()
    }

    fun isAccessibilityLogEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ACCESSIBILITY_LOG_ENABLED, false)
    }

    fun setAccessibilityLogEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACCESSIBILITY_LOG_ENABLED, enabled).apply()
    }

    fun debugSamplePath(context: Context): String {
        return "${context.applicationContext.getExternalFilesDir(null)?.absolutePath.orEmpty()}/debug_samples"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
