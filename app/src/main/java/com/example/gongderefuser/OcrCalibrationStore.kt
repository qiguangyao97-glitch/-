package com.example.gongderefuser

import android.content.Context
import android.graphics.RectF

object OcrCalibrationStore {
    private const val PREF_NAME = "gongde_refuser_ocr_calibration"

    val regionNames = listOf(
        "actionButton",
        "deliveryAnchor",
        "pickupAnchor",
        "dropoffAnchor",
        "type",
        "price",
        "trip",
        "merchant",
        "address"
    )

    fun displayName(name: String): String {
        return when (name) {
            "actionButton" -> "按钮定位"
            "deliveryAnchor" -> "取送定位"
            "deliveryAnchorSearch" -> "取送实际搜索"
            "pickupAnchor" -> "取货圆点"
            "dropoffAnchor" -> "送达方块"
            "type" -> "类型/单数"
            "price" -> "金额"
            "trip" -> "时间距离"
            "merchant" -> "商家文字"
            "address" -> "地址文字"
            else -> name
        }
    }

    fun load(context: Context): Map<String, RectF> {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return regionNames.associateWith { name ->
            val fallback = defaultRegions().getValue(name)
            RectF(
                prefs.getFloat("${name}_left", fallback.left),
                prefs.getFloat("${name}_top", fallback.top),
                prefs.getFloat("${name}_right", fallback.right),
                prefs.getFloat("${name}_bottom", fallback.bottom)
            ).sanitize()
        }
    }

    fun save(context: Context, regions: Map<String, RectF>) {
        val editor = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        regionNames.forEach { name ->
            val rect = regions[name]?.sanitize() ?: defaultRegions().getValue(name)
            editor
                .putFloat("${name}_left", rect.left)
                .putFloat("${name}_top", rect.top)
                .putFloat("${name}_right", rect.right)
                .putFloat("${name}_bottom", rect.bottom)
        }
        editor.apply()
    }

    fun reset(context: Context) {
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun defaultRegions(): Map<String, RectF> {
        return mapOf(
            "actionButton" to RectF(0.08f, 0.86f, 0.94f, 0.95f),
            "deliveryAnchor" to RectF(0.06f, 0.72f, 0.22f, 0.91f),
            "pickupAnchor" to RectF(0.09f, 0.755f, 0.15f, 0.805f),
            "dropoffAnchor" to RectF(0.09f, 0.835f, 0.15f, 0.885f),
            "type" to RectF(0.06f, 0.56f, 0.56f, 0.64f),
            "price" to RectF(0.05f, 0.61f, 0.45f, 0.71f),
            "trip" to RectF(0.05f, 0.70f, 0.92f, 0.79f),
            "merchant" to RectF(0.145f, 0.745f, 0.96f, 0.805f),
            "address" to RectF(0.145f, 0.795f, 0.96f, 0.905f)
        )
    }

    private fun RectF.sanitize(): RectF {
        val left = left.coerceIn(0f, 0.98f)
        val top = top.coerceIn(0f, 0.98f)
        val right = right.coerceIn(left + 0.02f, 1f)
        val bottom = bottom.coerceIn(top + 0.02f, 1f)
        return RectF(left, top, right, bottom)
    }
}
