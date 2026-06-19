package com.example.gongderefuser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.gongderefuser.analyzer.OrderAnalyzer
import com.example.gongderefuser.parser.OrderParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugSampleStore {
    private val formatter = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    fun saveCapture(
        context: Context,
        bitmap: Bitmap,
        regionText: OcrHelper.OrderRegionText,
        parsed: Boolean
    ) {
        if (!AppSettings.isDebugSamplesEnabled(context)) return
        if (!parsed) return

        runCatching {
            val dir = DebugFileDirs.resolve(context, "debug_samples")
            val order = parseOrder(regionText)
            val analysis = order?.let { OrderAnalyzer.analyzeResult(context, it) }
            val merchant = analysis?.storeName
                ?.ifBlank { "order" }
                ?.replace(Regex("[^\\p{IsHan}A-Za-z0-9_-]+"), "_")
                ?.trim('_')
                ?.take(24)
                ?.ifBlank { "order" }
                ?: "order"
            val price = analysis?.price?.let { "${it}元" } ?: "order"
            val name = "${formatter.format(Date())}-$price-$merchant"
            File(dir, "$name.jpg").outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            }
            saveRegionOverlay(dir, "$name-regions.jpg", bitmap, regionText.debugRegions)
            File(dir, "$name.txt").writeText(
                buildString {
                    appendLine("parsed=$parsed")
                    if (analysis != null) {
                        appendLine("===== SUMMARY =====")
                        appendLine("建议=${analysis.score}分 · ${analysis.recommendation}")
                        appendLine("商家=${analysis.storeName}")
                        appendLine("地址=${analysis.storeAddress}")
                        appendLine("类型=${analysis.orderType}")
                        appendLine("金额=${analysis.price} 元")
                        appendLine("时间=${analysis.minutes} 分钟")
                        appendLine("距离=${OrderAnalyzer.formatDistance(analysis.distance)} 公里")
                        appendLine("预计时薪=${OrderAnalyzer.formatMoney(analysis.effectiveHourly)} 元/小时")
                        appendLine("元/公里=${OrderAnalyzer.formatMoney(analysis.yuanPerKm)} 元/公里")
                        appendLine("配送数量=${analysis.deliveryCount}")
                        appendLine("同地點配送=${analysis.isSameLocationStack}")
                        appendLine("标签备注=${analysis.isWhitelisted} ${analysis.matchedWhitelistKeyword} ${analysis.whitelistNote}")
                        appendLine("避雷标签=${analysis.isBlacklisted} ${analysis.matchedBlacklistKeyword} ${analysis.blacklistNote}")
                        order?.let {
                            appendLine("sameDropoffText=${it.sameDropoffText}")
                            appendLine("sameDropoffMatched=${it.sameDropoffMatched}")
                            appendLine("sameDropoff=${it.isSameLocationStack}")
                            appendLine("priceStatus=${it.priceStatus}")
                            appendLine("tripStatus=${it.tripStatus}")
                            appendLine("merchantStatus=${it.merchantStatus}")
                            appendLine("addressStatus=${it.addressStatus}")
                            appendLine("addressSource=${it.addressSource}")
                            appendLine("typeStatus=${it.typeStatus}")
                        }
                    }
                    appendLine("===== DISTANCE DEBUG =====")
                    appendLine(OrderParser.distanceDebugInfo())
                    appendLine("===== OCR PREPROCESS =====")
                    appendLine(regionText.ocrPreprocessDebugInfo)
                    appendLine("isPairOffer=${regionText.isPairOffer}")
                    appendLine("hasAnchoredCard=${regionText.hasAnchoredCard}")
                    appendAnchorDebug(regionText.anchorDebugInfo)
                    appendLine("===== 定位诊断 =====")
                    appendLine(regionText.fullText)
                    appendLine("===== CARD =====")
                    appendLine(regionText.cardText)
                    appendLine("===== TYPE =====")
                    appendLine(regionText.typeText)
                    appendLine("===== PRICE =====")
                    appendLine(regionText.priceText)
                    appendLine("===== TRIP =====")
                    appendLine(regionText.tripText)
                    appendLine("===== SAME_DROPOFF =====")
                    appendLine(regionText.sameDropoffText)
                    appendLine("===== MERCHANT =====")
                    appendLine(regionText.merchantText)
                    appendLine("===== ADDRESS =====")
                    appendLine(regionText.addressText)
                },
                Charsets.UTF_8
            )
        }
    }

    private fun saveRegionOverlay(
        dir: File,
        fileName: String,
        source: Bitmap,
        regions: List<OcrHelper.DebugRegion>
    ) {
        if (regions.isEmpty()) return
        val overlay = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(overlay)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (source.width * 0.006f).coerceAtLeast(4f)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            textSize = (source.width * 0.028f).coerceAtLeast(26f)
        }
        val labelBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        regions
            .filter { it.rect.width() > 2 && it.rect.height() > 2 }
            .forEach { region ->
                val color = regionColor(region.name)
                stroke.color = color
                fill.color = withAlpha(color, 34)
                canvas.drawRect(region.rect, fill)
                canvas.drawRect(region.rect, stroke)
                labelBackground.color = withAlpha(color, 180)
                val label = OcrCalibrationStore.displayName(region.name)
                val padding = 8f
                val labelWidth = labelPaint.measureText(label) + padding * 2
                val labelHeight = labelPaint.textSize + padding * 2
                val left = region.rect.left.toFloat()
                val top = (region.rect.top - labelHeight).coerceAtLeast(0f)
                canvas.drawRect(left, top, left + labelWidth, top + labelHeight, labelBackground)
                canvas.drawText(label, left + padding, top + labelPaint.textSize + padding / 2, labelPaint)
            }

        File(dir, fileName).outputStream().use { output ->
            overlay.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }
    }

    private fun regionColor(name: String): Int {
        return when (name) {
            "actionButton" -> Color.rgb(255, 45, 85)
            "closeSearch" -> Color.rgb(175, 82, 222)
            "closeButton", "closeButtonDetected" -> Color.rgb(255, 45, 85)
            "deliveryAnchorSearch", "deliveryAnchorSearchActual" -> Color.rgb(0, 180, 255)
            "deliveryAnchor" -> Color.rgb(0, 122, 255)
            "pickupAnchor", "pickupAnchorShiftedReference" -> Color.rgb(90, 200, 250)
            "dropoffAnchor", "dropoffAnchorShiftedReference" -> Color.rgb(88, 86, 214)
            "card", "cardActual" -> Color.rgb(0, 122, 255)
            "type", "typeActual" -> Color.rgb(128, 0, 255)
            "price", "priceActual" -> Color.rgb(255, 149, 0)
            "trip", "tripActual" -> Color.rgb(255, 214, 10)
            "sameDropoff", "sameDropoffActual" -> Color.rgb(48, 209, 88)
            "merchant", "merchantWide", "merchantActual" -> Color.rgb(52, 199, 89)
            "address", "addressWide", "addressLower", "addressActual", "addressWideActual" -> Color.rgb(255, 59, 48)
            else -> Color.rgb(90, 200, 250)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun parseOrder(regionText: OcrHelper.OrderRegionText) =
        if (regionText.hasAnchoredCard) OrderParser.parse(
            OrderParser.RegionInput(
                fullText = regionText.fullText,
                cardText = regionText.cardText,
                typeText = regionText.typeText,
                priceText = regionText.priceText,
                tripText = regionText.tripText,
                detailText = regionText.detailText,
                sameDropoffText = regionText.sameDropoffText,
                merchantText = regionText.merchantText,
                merchantWideText = regionText.merchantWideText,
                addressText = regionText.addressText,
                addressWideText = regionText.addressWideText,
                addressLowerText = regionText.addressLowerText
            )
        ) else null

    private fun StringBuilder.appendAnchorDebug(info: OcrHelper.AnchorDebugInfo) {
        appendLine("anchorSource=${info.anchorSource}")
        appendLine("pickupDetected=${info.pickupDetected}")
        appendLine("dropoffDetected=${info.dropoffDetected}")
        appendLine("cardRect=${formatRect(info.cardRect)}")
        appendLine("cardTop=${info.cardRect?.top ?: ""}")
        appendLine("cardBottom=${info.cardRect?.bottom ?: ""}")
        appendLine("cardHeight=${info.cardRect?.height() ?: ""}")
        appendLine("closeButtonRect=${formatRect(info.closeButtonRect)}")
        appendLine("templateCloseY=${info.templateCloseY ?: ""}")
        appendLine("actualCloseY=${info.actualCloseY ?: ""}")
        appendLine("closeShiftY=${info.closeShiftY ?: ""}")
        appendLine("pickupAnchorRect=${formatRect(info.pickupAnchorRect)}")
        appendLine("dropoffAnchorRect=${formatRect(info.dropoffAnchorRect)}")
        appendLine("priceRect=${formatRect(info.priceRect)}")
        appendLine("tripRect=${formatRect(info.tripRect)}")
        appendLine("merchantRect=${formatRect(info.merchantRect)}")
        appendLine("addressRect=${formatRect(info.addressRect)}")
        appendLine("addressWideRect=${formatRect(info.addressWideRect)}")
    }

    private fun formatRect(rect: android.graphics.Rect?): String {
        return rect?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty()
    }
}
