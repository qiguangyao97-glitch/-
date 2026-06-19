package com.example.gongderefuser

import com.example.gongderefuser.model.OrderData

object OrderResultGate {
    data class Decision(
        val shouldShow: Boolean,
        val skipResultReason: String = "",
        val debugLog: String = ""
    )

    fun evaluate(
        order: OrderData?,
        hasAnchoredCard: Boolean = true,
        tripText: String = ""
    ): Decision {
        if (order == null || !hasAnchoredCard) {
            return buildDecision(
                order = order,
                tripText = tripText,
                shouldShow = false,
                reason = "NO_ORDER_CARD"
            )
        }

        if (order.price <= 0 && order.minutes <= 0 && order.distance <= 0.0) {
            return buildDecision(
                order = order,
                tripText = tripText,
                shouldShow = false,
                reason = "ALL_CORE_FIELDS_EMPTY"
            )
        }

        if (order.price <= 0 || order.priceStatus != "OK") {
            return buildDecision(
                order = order,
                tripText = tripText,
                shouldShow = false,
                reason = "OCR_MONEY_INVALID"
            )
        }

        if (order.tripStatus != "OK") {
            return buildDecision(
                order = order,
                tripText = tripText,
                shouldShow = false,
                reason = "TRIP_STATUS_NOT_OK"
            )
        }

        if (order.minutes <= 0) {
            return buildDecision(
                order = order,
                tripText = tripText,
                shouldShow = false,
                reason = "MISSING_MINUTES"
            )
        }
        if (order.distance <= 0.0) {
            return buildDecision(
                order = order,
                tripText = tripText,
                shouldShow = false,
                reason = "MISSING_DISTANCE"
            )
        }

        val foundMinuteKeyword = minuteKeywordRegex.containsMatchIn(tripText)
        val foundKmKeyword = kmKeywordRegex.containsMatchIn(tripText)
        val foundTotalKeyword = tripText.contains("總計") || tripText.contains("总计")
        val matchesTripFormat = tripFormatRegex.containsMatchIn(tripText)
        val hasOrderTripText = (foundMinuteKeyword && foundKmKeyword && foundTotalKeyword) || matchesTripFormat
        if (!hasOrderTripText) {
            return buildDecision(
                order = order,
                tripText = tripText,
                shouldShow = false,
                reason = "MISSING_TRIP_KEYWORDS"
            )
        }

        return buildDecision(
            order = order,
            tripText = tripText,
            shouldShow = true,
            reason = "OK"
        )
    }

    private fun buildDecision(
        order: OrderData?,
        tripText: String,
        shouldShow: Boolean,
        reason: String
    ): Decision {
        val minutes = order?.minutes ?: 0
        val distance = order?.distance ?: 0.0
        val tripStatus = order?.tripStatus ?: "MISSING"
        val foundMinuteKeyword = minuteKeywordRegex.containsMatchIn(tripText)
        val foundKmKeyword = kmKeywordRegex.containsMatchIn(tripText)
        val foundTotalKeyword = tripText.contains("總計") || tripText.contains("总计")
        val debugLog = buildString {
            appendLine("===== ORDER POPUP VALIDATION =====")
            appendLine("isOrderPopup=$shouldShow")
            appendLine("tripStatus=$tripStatus")
            appendLine("minutes=$minutes")
            appendLine("distanceKm=$distance")
            appendLine("foundMinuteKeyword=$foundMinuteKeyword")
            appendLine("foundKmKeyword=$foundKmKeyword")
            appendLine("foundTotalKeyword=$foundTotalKeyword")
            appendLine("validationReason=$reason")
            appendLine("analysisStarted=$shouldShow")
            appendLine("resultPopupShown=$shouldShow")
        }.trim()
        return Decision(
            shouldShow = shouldShow,
            skipResultReason = if (shouldShow) "" else reason,
            debugLog = debugLog
        )
    }

    private val minuteKeywordRegex = Regex("(分鐘|分钟|分鍾|分锺|分鈡|分鋒|\\d+\\s*分)")
    private val kmKeywordRegex = Regex("(公里|公裏|公哩|\\d+(?:\\.\\d+)?\\s*里)")
    private val tripFormatRegex = Regex("\\d+\\s*(?:分鐘|分钟|分鍾|分锺|分鈡|分鋒|分)\\s*[（(]\\s*\\d+(?:\\.\\d+)?\\s*(?:公里|公裏|公哩|里)\\s*[）)]\\s*(?:總計|总计)")
}
