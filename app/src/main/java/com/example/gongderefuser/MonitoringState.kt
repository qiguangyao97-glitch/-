package com.example.gongderefuser

import android.content.Context
import android.content.Intent

object MonitoringState {
    private const val PREFS_NAME = "gongde_refuser_monitoring"
    private const val KEY_ENABLED = "enabled"

    @Volatile
    private var cachedEnabled: Boolean? = null

    fun isEnabled(context: Context): Boolean {
        return cachedEnabled ?: context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
            .also { cachedEnabled = it }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        cachedEnabled = enabled
        appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()

        if (!enabled) {
            appContext.stopService(Intent(appContext, ScreenCaptureService::class.java))
        }

        MyAccessibilityService.refreshStatusOverlay()
    }
}
