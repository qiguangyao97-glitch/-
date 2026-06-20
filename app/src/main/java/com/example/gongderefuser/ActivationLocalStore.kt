package com.example.gongderefuser

import android.content.Context

object ActivationLocalStore {
    private const val PREF_NAME = "gongde_refuser_activation"
    private const val KEY_CURRENT_CODE = "current_code"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_ACTIVATED_AT_MILLIS = "activated_at_millis"
    private const val KEY_EXPIRES_AT_MILLIS = "expires_at_millis"
    private const val KEY_IS_ACTIVATED = "is_activated"
    private const val KEY_LAST_CHECK_MILLIS = "last_check_millis"

    fun isActivationRequired(context: Context): Boolean {
        return !context.applicationContext.packageName.endsWith(".beta")
    }

    fun saveActivation(
        context: Context,
        currentCode: String,
        deviceId: String,
        activatedAtMillis: Long,
        expiresAtMillis: Long,
        lastCheckMillis: Long = System.currentTimeMillis()
    ) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CURRENT_CODE, currentCode)
            .putString(KEY_DEVICE_ID, deviceId)
            .putLong(KEY_ACTIVATED_AT_MILLIS, activatedAtMillis)
            .putLong(KEY_EXPIRES_AT_MILLIS, expiresAtMillis)
            .putBoolean(KEY_IS_ACTIVATED, true)
            .putLong(KEY_LAST_CHECK_MILLIS, lastCheckMillis)
            .apply()
    }

    fun getExpiresAtMillis(context: Context): Long {
        return prefs(context).getLong(KEY_EXPIRES_AT_MILLIS, 0L)
    }

    fun isLocalActive(context: Context): Boolean {
        if (!isActivationRequired(context)) return true
        val prefs = prefs(context)
        return prefs.getBoolean(KEY_IS_ACTIVATED, false) &&
            System.currentTimeMillis() < prefs.getLong(KEY_EXPIRES_AT_MILLIS, 0L)
    }

    fun clearActivationIfNeeded(context: Context) {
        if (!isActivationRequired(context)) return
        val prefs = prefs(context)
        val expiresAtMillis = prefs.getLong(KEY_EXPIRES_AT_MILLIS, 0L)
        if (expiresAtMillis > 0L && System.currentTimeMillis() >= expiresAtMillis) {
            prefs.edit()
                .putBoolean(KEY_IS_ACTIVATED, false)
                .putLong(KEY_LAST_CHECK_MILLIS, System.currentTimeMillis())
                .apply()
        }
    }

    fun getCurrentCode(context: Context): String {
        return prefs(context).getString(KEY_CURRENT_CODE, "").orEmpty()
    }

    fun getDeviceId(context: Context): String {
        return prefs(context).getString(KEY_DEVICE_ID, "").orEmpty()
    }

    fun getActivatedAtMillis(context: Context): Long {
        return prefs(context).getLong(KEY_ACTIVATED_AT_MILLIS, 0L)
    }

    fun getLastCheckMillis(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_CHECK_MILLIS, 0L)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
