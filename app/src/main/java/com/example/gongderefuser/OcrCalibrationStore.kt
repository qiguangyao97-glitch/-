package com.example.gongderefuser

import android.content.Context
import android.graphics.RectF

object OcrCalibrationStore {
    private const val CLOSE_BUTTON_WIDTH = 0.0575f
    private const val CLOSE_BUTTON_HEIGHT = 0.0257f

    val mainFlowRegionNames = listOf(
        "card",
        "closeSearch",
        "closeButton",
        "actionButton",
        "type",
        "price",
        "trip",
        "sameDropoff",
        "merchantAddressBlock"
    )

    val legacyRegionNames = listOf(
        "merchant",
        "address",
        "addressWide",
        "pickupAnchor",
        "dropoffAnchor",
        "pickupCircleSearch",
        "dropoffSquareSearch",
        "deliveryAnchorSearch",
        "deliveryAnchor",
        "pickupAnchorShifted",
        "dropoffAnchorShifted"
    )

    val regionNames = mainFlowRegionNames + legacyRegionNames

    val editableRegionNames = mainFlowRegionNames

    val advancedRegionNames = listOf(
        "pickupAnchor",
        "dropoffAnchor",
        "pickupCircleSearch",
        "dropoffSquareSearch"
    )

    val debugRegionNames = advancedRegionNames + listOf(
        "merchant",
        "address",
        "addressWide",
        "deliveryAnchorSearch"
    )

    private fun withLegacyPrefix(label: String): String = "舊版/診斷：$label"

    fun displayName(name: String): String {
        if (name.startsWith("pickupCandidate#") || name.startsWith("dropoffCandidate#")) return name
        if (name.startsWith("pickupCandidateAccepted")) return "pickup候選命中"
        if (name.startsWith("dropoffCandidateAccepted")) return "dropoff候選命中"
        if (name.startsWith("pickupCandidateRejectedByShape")) return "pickup候選 rejectedByShape"
        if (name.startsWith("pickupCandidateRejectedBySize")) return "pickup候選 rejectedBySize"
        if (name.startsWith("dropoffCandidateRejectedByShape")) return "dropoff候選 rejectedByShape"
        if (name.startsWith("dropoffCandidateRejectedBySize")) return "dropoff候選 rejectedBySize"
        return when (name) {
            "card" -> "卡片範圍"
            "closeSearch" -> "關閉按鈕搜索區"
            "closeButton" -> "關閉按鈕"
            "price" -> "金額"
            "trip" -> "時間距離"
            "type" -> "類型"
            "merchant" -> withLegacyPrefix("商家小框")
            "address" -> withLegacyPrefix("地址小框")
            "merchantAddressBlock" -> "商家地址四行總文字區"
            "addressWide" -> withLegacyPrefix("地址兩行參考")
            "sameDropoff" -> "同地點配送文字區"
            "pickupCircleSearch" -> withLegacyPrefix("Pickup 搜索區")
            "dropoffSquareSearch" -> withLegacyPrefix("Dropoff 搜索區")
            "pickupSearchRect" -> "取餐搜尋區"
            "dropoffSearchRect" -> "送達搜尋區"
            "pickupDetectedRect" -> "取餐圓圈命中"
            "dropoffDetectedRect" -> "送達方塊命中"
            "pickupNotDetected" -> "pickup未命中"
            "dropoffNotDetected" -> "dropoff未命中"
            "pickupCircleSearchActual" -> "取餐圓圈搜尋實際框"
            "dropoffSquareSearchActual" -> "送達方塊搜尋實際框"
            "pickupAnchorActual" -> "取餐圓圈實際框"
            "dropoffAnchorActual" -> "送達方塊實際框"
            "pickupAnchor" -> withLegacyPrefix("Pickup Anchor")
            "dropoffAnchor" -> withLegacyPrefix("Dropoff Anchor")
            "deliveryAnchorSearch" -> withLegacyPrefix("定位搜尋框")
            "cardActual" -> "卡片實際檢測框"
            "closeButtonDetected" -> "關閉按鈕實際檢測"
            "typeActual" -> "訂單類型實際框"
            "priceActual" -> "金額實際框"
            "tripActual" -> "時間距離實際框"
            "merchantActual" -> "商家實際框"
            "merchantWideActual" -> "商家兩行實際框"
            "merchantAddressBlockActual" -> "商家地址總文字區實際框"
            "addressActual" -> "地址實際框"
            "addressWideActual" -> "地址兩行實際框"
            "sameDropoffActual" -> "同地點配送實際框"
            "deliveryAnchorSearchActual" -> "定位搜尋實際框"
            "pickupAnchorShiftedReference" -> "取餐偏移参考框"
            "dropoffAnchorShiftedReference" -> "送達偏移参考框"
            "actionButton" -> "按鈕定位"
            "deliveryAnchor" -> withLegacyPrefix("取送定位")
            else -> name
        }
    }

    fun load(context: Context): Map<String, RectF> {
        return OcrTemplateRepository.getActiveTemplate(context).regions
    }

    fun hasSaved(context: Context): Boolean {
        return OcrTemplateRepository.getActiveTemplate(context).hasUserSavedTemplate
    }

    fun save(context: Context, regions: Map<String, RectF>) {
        OcrTemplateRepository.save(context, regions)
    }

    fun saveAsDefault(context: Context, regions: Map<String, RectF>) {
        save(context, regions)
    }

    fun reset(context: Context) {
        OcrTemplateRepository.reset(context)
    }

    fun defaultRegions(): Map<String, RectF> {
        return mapOf(
            "card" to RectF(0.0f, 0.48f, 1.0f, 0.995f),
            "actionButton" to RectF(0.08f, 0.86f, 0.94f, 0.95f),
            "closeSearch" to RectF(0.7904557f, 0.45057994f, 0.9148396f, 0.61550575f),
            "closeButton" to RectF(0.82441443f, 0.5201919f, 0.88191444f, 0.5458919f),
            "deliveryAnchor" to RectF(0.06f, 0.72f, 0.22f, 0.91f),
            "pickupAnchor" to RectF(0.0861913f, 0.729803f, 0.15000406f, 0.75883967f),
            "dropoffAnchor" to RectF(0.07945643f, 0.802488f, 0.15570675f, 0.8361681f),
            "pickupAnchorShifted" to RectF(0.0758435f, 0.74373746f, 0.15080364f, 0.78232086f),
            "dropoffAnchorShifted" to RectF(0.07532745f, 0.80300397f, 0.15054548f, 0.8361682f),
            "deliveryAnchorSearch" to RectF(0.058321103f, 0.7048175f, 0.17138241f, 0.8764121f),
            "type" to RectF(0.07857338f, 0.49181068f, 0.48527998f, 0.55168414f),
            "price" to RectF(0.14437205f, 0.55245763f, 0.42206037f, 0.6133626f),
            "trip" to RectF(0.15987992f, 0.64916694f, 0.92619014f, 0.6891079f),
            "sameDropoff" to RectF(0.16105199f, 0.81809616f, 0.7990283f, 0.8580922f),
            "merchant" to RectF(0.16389231f, 0.70861727f, 0.89558125f, 0.7806099f),
            "address" to RectF(0.15196417f, 0.7893281f, 0.92517924f, 0.8545573f),
            "merchantAddressBlock" to RectF(0.15196419f, 0.7069406f, 0.92517924f, 0.864665f),
            "pickupCircleSearch" to RectF(0.07586898f, 0.7203867f, 0.162131f, 0.8079991f),
            "dropoffSquareSearch" to RectF(0.06577398f, 0.7634221f, 0.172226f, 0.87741643f),
            "addressWide" to RectF(0.15518843f, 0.8188458f, 0.9224712f, 0.86461985f)
        )
    }

    fun sanitizeRect(name: String, source: RectF): RectF {
        val rect = source.sanitize()
        return if (name == "closeButton") rect.fixedCloseButtonSize() else rect
    }

    private fun RectF.sanitize(): RectF {
        val left = left.coerceIn(0f, 0.98f)
        val top = top.coerceIn(0f, 0.98f)
        val right = right.coerceIn(left + 0.008f, 1f)
        val bottom = bottom.coerceIn(top + 0.008f, 1f)
        return RectF(left, top, right, bottom)
    }

    private fun RectF.fixedCloseButtonSize(): RectF {
        val centerX = centerX().coerceIn(CLOSE_BUTTON_WIDTH / 2f, 1f - CLOSE_BUTTON_WIDTH / 2f)
        val centerY = centerY().coerceIn(CLOSE_BUTTON_HEIGHT / 2f, 1f - CLOSE_BUTTON_HEIGHT / 2f)
        return RectF(
            centerX - CLOSE_BUTTON_WIDTH / 2f,
            centerY - CLOSE_BUTTON_HEIGHT / 2f,
            centerX + CLOSE_BUTTON_WIDTH / 2f,
            centerY + CLOSE_BUTTON_HEIGHT / 2f
        ).sanitize()
    }
}
