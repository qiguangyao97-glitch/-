package com.example.gongderefuser

import android.content.Context
import java.util.UUID

object DeviceIdManager {
    private const val PREF_NAME = "gongde_refuser_device"
    private const val KEY_DEVICE_ID = "device_id"

    fun getDeviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, "").orEmpty()
        if (existing.isNotBlank()) return existing

        val deviceId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        return deviceId
    }
}
