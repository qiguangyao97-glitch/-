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

    val editableRegionNames = listOf(
        "closeButton",
        "price",
        "trip",
        "merchant",
        "address",
        "deliveryAnchorSearch",
        "sameDropoff"
    )

    fun displayName(name: String): String {
        return when (name) {
            "card" -> "卡片參考框"
            "closeSearch" -> "關閉按鈕搜尋框"
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
            "deliveryAnchorSearch" -> "定位搜尋框"
            "cardActual" -> "卡片實際檢測框"
            "closeButtonDetected" -> "關閉按鈕實際檢測"
            "typeActual" -> "訂單類型實際框"
            "priceActual" -> "金額實際框"
            "tripActual" -> "時間距離實際框"
            "merchantActual" -> "商家實際框"
            "addressActual" -> "地址實際框"
            "addressWideActual" -> "地址備用實際框"
            "sameDropoffActual" -> "同地點配送實際框"
            "deliveryAnchorSearchActual" -> "定位搜尋實際框"
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

    fun hasSaved(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.contains("${regionNames.first()}_left")
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

    fun saveAsDefault(context: Context, regions: Map<String, RectF>) {
        save(context, regions)
    }

    fun reset(context: Context) {
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun defaultRegions(): Map<String, RectF> {
        return mapOf(
            "card" to RectF(0.0f, 0.48f, 1.0f, 0.995f),
            "actionButton" to RectF(0.08f, 0.86f, 0.94f, 0.95f),
            "closeSearch" to RectF(0.7904557f, 0.48825273f, 0.9148396f, 0.6531786f),
            "closeButton" to RectF(0.7958729f, 0.5429691f, 0.91045594f, 0.5984604f),
            "deliveryAnchor" to RectF(0.06f, 0.72f, 0.22f, 0.91f),
            "pickupAnchor" to RectF(0.075714394f, 0.7437098f, 0.15067454f, 0.7822932f),
            "dropoffAnchor" to RectF(0.074682645f, 0.80274576f, 0.14990067f, 0.83591f),
            "pickupAnchorShifted" to RectF(0.075714394f, 0.7437098f, 0.15067454f, 0.7822932f),
            "dropoffAnchorShifted" to RectF(0.074682645f, 0.80274576f, 0.14990067f, 0.83591f),
            "deliveryAnchorSearch" to RectF(0.0646428f, 0.7261927f, 0.16402844f, 0.8666499f),
            "type" to RectF(0.07238079f, 0.5279358f, 0.47908738f, 0.58780926f),
            "price" to RectF(0.07315461f, 0.58884084f, 0.4808926f, 0.6492296f),
            "trip" to RectF(0.15987991f, 0.6723901f, 0.92619014f, 0.7329739f),
            "sameDropoff" to RectF(0.16105202f, 0.8277047f, 0.7990283f, 0.87454516f),
            "merchant" to RectF(0.15409087f, 0.74396807f, 0.9051245f, 0.7828776f),
            "address" to RectF(0.1522221f, 0.78003883f, 0.9236317f, 0.8633302f),
            "addressWide" to RectF(0.15454362f, 0.8185876f, 0.9218264f, 0.86436164f)
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
