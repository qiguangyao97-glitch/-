package com.example.gongderefuser

import android.content.Context
import android.graphics.RectF

object OcrCalibrationStore {
    private const val PREF_NAME = "gongde_refuser_ocr_calibration"

    val regionNames = listOf(
        "card",
        "closeSearch",
        "closeButton",
        "price",
        "trip",
        "type",
        "merchant",
        "address",
        "addressWide",
        "sameDropoff",
        "pickupAnchor",
        "dropoffAnchor",
        "deliveryAnchorSearch"
    )

    fun displayName(name: String): String {
        return when (name) {
            "card" -> "卡片参考框"
            "closeSearch" -> "關閉搜索框"
            "closeButton" -> "關閉按鈕模板"
            "price" -> "金額模板"
            "trip" -> "時間距離模板"
            "type" -> "訂單類型模板"
            "merchant" -> "商家模板"
            "address" -> "地址模板"
            "addressWide" -> "地址備用模板"
            "sameDropoff" -> "同地點配送"
            "pickupAnchor" -> "取餐定位"
            "dropoffAnchor" -> "送達定位"
            "deliveryAnchorSearch" -> "定位搜索框"
            "cardActual" -> "卡片實際檢測框"
            "closeButtonDetected" -> "關閉按鈕實際檢測"
            "typeActual" -> "訂單類型實際框"
            "priceActual" -> "金額實際框"
            "tripActual" -> "時間距離實際框"
            "merchantActual" -> "商家實際框"
            "addressActual" -> "地址實際框"
            "addressWideActual" -> "地址備用實際框"
            "sameDropoffActual" -> "同地點配送實際框"
            "deliveryAnchorSearchActual" -> "定位搜索實際框"
            "pickupAnchorShiftedReference" -> "取餐偏移参考框"
            "dropoffAnchorShiftedReference" -> "送達偏移参考框"
            "actionButton" -> "按鈕定位"
            "deliveryAnchor" -> "取送定位"
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
            "card" to RectF(0.04f, 0.53f, 0.96f, 0.965f),
            "actionButton" to RectF(0.08f, 0.86f, 0.94f, 0.95f),
            "closeSearch" to RectF(0.72f, 0.28f, 0.96f, 0.88f),
            "closeButton" to RectF(0.80f, 0.56f, 0.92f, 0.64f),
            "deliveryAnchor" to RectF(0.06f, 0.72f, 0.22f, 0.91f),
            "pickupAnchor" to RectF(0.09f, 0.755f, 0.15f, 0.805f),
            "dropoffAnchor" to RectF(0.09f, 0.835f, 0.15f, 0.885f),
            "pickupAnchorShifted" to RectF(0.09f, 0.755f, 0.15f, 0.805f),
            "dropoffAnchorShifted" to RectF(0.09f, 0.835f, 0.15f, 0.885f),
            "deliveryAnchorSearch" to RectF(0.06f, 0.72f, 0.22f, 0.91f),
            "type" to RectF(0.06f, 0.56f, 0.56f, 0.64f),
            "price" to RectF(0.05f, 0.61f, 0.45f, 0.71f),
            "trip" to RectF(0.05f, 0.70f, 0.92f, 0.79f),
            "sameDropoff" to RectF(0.20f, 0.50f, 0.92f, 0.56f),
            "merchant" to RectF(0.145f, 0.745f, 0.96f, 0.805f),
            "address" to RectF(0.145f, 0.795f, 0.96f, 0.905f),
            "addressWide" to RectF(0.145f, 0.815f, 0.96f, 0.875f)
        )
    }

    private fun RectF.sanitize(): RectF {
        val left = left.coerceIn(0f, 0.98f)
        val top = top.coerceIn(0f, 0.98f)
        val right = right.coerceIn(left + 0.008f, 1f)
        val bottom = bottom.coerceIn(top + 0.008f, 1f)
        return RectF(left, top, right, bottom)
    }
}
