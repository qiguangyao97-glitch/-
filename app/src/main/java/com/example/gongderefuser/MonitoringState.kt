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
        if (enabled && !ActivationLocalStore.isLocalActive(appContext)) {
            ActivationLocalStore.clearActivationIfNeeded(appContext)
            DiagnosticLogStore.append(appContext, "ACTIVATION", "refuse_monitoring_enable expiresAt=${ActivationLocalStore.getExpiresAtMillis(appContext)}")
            cachedEnabled = false
            appContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, false)
                .apply()
            MonitorNotificationHelper.cancel(appContext)
            MyAccessibilityService.refreshForegroundNotification()
            MyAccessibilityService.notifyMonitoringStateChanged(false)
            MyAccessibilityService.refreshStatusOverlay()
            return
        }
        cachedEnabled = enabled
        appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        DiagnosticLogStore.append(appContext, "MONITORING", "setEnabled=$enabled package=${appContext.packageName}")

        if (enabled) {
            MonitorNotificationHelper.showRunning(appContext)
            MyAccessibilityService.refreshForegroundNotification()
            MyAccessibilityService.notifyMonitoringStateChanged(true)
        } else {
            MonitorNotificationHelper.cancel(appContext)
            MyAccessibilityService.refreshForegroundNotification()
            MyAccessibilityService.notifyMonitoringStateChanged(false)
            appContext.stopService(Intent(appContext, ScreenCaptureService::class.java))
        }

        MyAccessibilityService.refreshStatusOverlay()
    }
}
