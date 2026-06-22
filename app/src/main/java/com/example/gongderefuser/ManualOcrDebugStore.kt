package com.example.gongderefuser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import com.example.gongderefuser.analyzer.OrderAnalyzer
import com.example.gongderefuser.model.OrderData
import com.example.gongderefuser.parser.OrderParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object ManualOcrDebugStore {
    private val formatter = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    fun save(
        context: Context,
        bitmap: Bitmap,
        regionText: OcrHelper.OrderRegionText,
        order: OrderData?,
        source: String
    ): String {
        return runCatching {
            val dir = DebugFileDirs.resolve(context, "manual_ocr_debug")
            val name = "${formatter.format(Date())}-${source}-${order?.price?.let { "${it}元" } ?: "no-order"}"
            File(dir, "$name.jpg").outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
            }
            val regionFile = saveRegionOverlay(dir, "$name-regions.jpg", bitmap, regionText.debugRegions)
            val textFile = File(dir, "$name.txt")
            textFile.writeText(
                buildString {
                    appendLine("source=$source")
                    appendLine("parsed=${order != null}")
                    appendLine("isPairOffer=${regionText.isPairOffer}")
                    appendLine("hasAnchoredCard=${regionText.hasAnchoredCard}")
                    appendAnchorDebug(regionText.anchorDebugInfo)
                    if (order != null) {
                        val analysis = OrderAnalyzer.analyzeResult(context, order)
                        appendLine("===== SUMMARY =====")
                        appendLine("建議=${analysis.score}分 · ${analysis.recommendation}")
                        appendLine("接单模式=${acceptModeLabel(analysis.acceptMode)}")
                        appendLine("趟獎加分=${analysis.rewardModeBonus}")
                        appendLine("趟獎原因=${analysis.rewardModeReason}")
                        appendLine("商家=${analysis.storeName}")
                        appendLine("地址=${analysis.storeAddress}")
                        appendLine("類型=${analysis.orderType}")
                        appendLine("金額=${analysis.price} 元")
                        appendLine("時間=${analysis.minutes} 分鐘")
                        appendLine("距離=${OrderAnalyzer.formatDistance(analysis.distance)} 公里")
                        appendLine("预计时薪=${OrderAnalyzer.formatMoney(analysis.effectiveHourly)} 元/小时")
                        appendLine("元/公里=${OrderAnalyzer.formatMoney(analysis.yuanPerKm)} 元/公里")
                        appendLine("配送数量=${analysis.deliveryCount}")
                        appendLine("===== SCORE DEBUG =====")
                        appendLine("模式=${acceptModeLabel(analysis.acceptMode)}")
                        appendLine("金額来源=${analysis.scoreMoneySource}")
                        appendLine("时薪評分=${analysis.hourlyScore.roundToInt()}")
                        appendLine("元公里評分=${analysis.yuanPerKmScore.roundToInt()}")
                        appendLine("平均单价評分=${analysis.averagePriceScore.roundToInt()}")
                        appendLine("基礎評分=${analysis.baseScore.roundToInt()}")
                        appendLine("趟獎加权=${analysis.rewardModeBonus}")
                        appendLine("趟獎原因=${analysis.rewardModeReason}")
                        appendLine("最終評分=${analysis.score}")
                        appendLine("同地點配送=${analysis.isSameLocationStack}")
                        appendLine("sameDropoffText=${order.sameDropoffText}")
                        appendLine("sameDropoffMatched=${order.sameDropoffMatched}")
                        appendLine("sameDropoff=${order.isSameLocationStack}")
                        appendLine("priceStatus=${order.priceStatus}")
                        appendLine("tripStatus=${order.tripStatus}")
                        appendLine("merchantStatus=${order.merchantStatus}")
                        appendLine("addressStatus=${order.addressStatus}")
                        appendLine("addressSource=${order.addressSource}")
                        appendLine("typeStatus=${order.typeStatus}")
                    }
                    appendLine("===== DISTANCE DEBUG =====")
                    appendLine(OrderParser.distanceDebugInfo())
                    appendLine("===== OCR PREPROCESS =====")
                    appendLine(regionText.ocrPreprocessDebugInfo)
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
            if (OcrFailureDebugStore.isIncomplete(order)) {
                OcrFailureDebugStore.recordFailure(context, regionFile, textFile, source)
            }
            OcrFailureDebugStore.recordLatest(context, regionFile, textFile, source)
            regionFile?.absolutePath.orEmpty()
        }.getOrDefault("")
    }

    private fun saveRegionOverlay(
        dir: File,
        fileName: String,
        source: Bitmap,
        regions: List<OcrHelper.DebugRegion>
    ): File? {
        if (regions.isEmpty()) return null
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

        regions.filter { it.rect.width() > 2 && it.rect.height() > 2 }.forEach { region ->
            val color = regionColor(region.name)
            val candidate = isCandidateRegion(region.name)
            stroke.color = color
            stroke.pathEffect = if (candidate) DashPathEffect(floatArrayOf(18f, 12f), 0f) else null
            fill.color = withAlpha(color, if (candidate) 14 else 34)
            canvas.drawRect(region.rect, fill)
            canvas.drawRect(region.rect, stroke)
            stroke.pathEffect = null
            labelBackground.color = withAlpha(color, if (candidate) 135 else 180)
            val label = OcrCalibrationStore.displayName(region.name)
            val padding = 8f
            val labelWidth = labelPaint.measureText(label) + padding * 2
            val labelHeight = labelPaint.textSize + padding * 2
            val left = region.rect.left.toFloat()
            val top = (region.rect.top - labelHeight).coerceAtLeast(0f)
            canvas.drawRect(left, top, left + labelWidth, top + labelHeight, labelBackground)
            canvas.drawText(label, left + padding, top + labelPaint.textSize + padding / 2, labelPaint)
        }

        val file = File(dir, fileName)
        file.outputStream().use { output ->
            overlay.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }
        return file
    }

    private fun acceptModeLabel(mode: com.example.gongderefuser.analyzer.RuleSettings.AcceptMode): String {
        return when (mode) {
            com.example.gongderefuser.analyzer.RuleSettings.AcceptMode.REWARD -> "趟獎模式"
            com.example.gongderefuser.analyzer.RuleSettings.AcceptMode.NORMAL -> "正常模式"
        }
    }

    private fun regionColor(name: String): Int {
        return when (name) {
            "actionButton" -> Color.rgb(255, 45, 85)
            "closeSearch" -> Color.rgb(175, 82, 222)
            "closeButton", "closeButtonDetected" -> Color.rgb(255, 45, 85)
            "deliveryAnchorSearch", "deliveryAnchorSearchActual" -> Color.rgb(130, 130, 130)
            "deliveryAnchor" -> Color.rgb(0, 122, 255)
            "pickupAnchor", "pickupAnchorActual", "pickupDetectedRect", "pickupAnchorShiftedReference" -> Color.rgb(90, 200, 250)
            "dropoffAnchor", "dropoffAnchorActual", "dropoffDetectedRect", "dropoffAnchorShiftedReference" -> Color.rgb(88, 86, 214)
            "pickupCircleSearchActual", "pickupSearchRect", "pickupNotDetected" -> Color.rgb(0, 180, 255)
            "dropoffSquareSearchActual", "dropoffSearchRect", "dropoffNotDetected" -> Color.rgb(175, 82, 222)
            "card", "cardActual" -> Color.rgb(0, 122, 255)
            "type", "typeActual" -> Color.rgb(128, 0, 255)
            "price", "priceActual" -> Color.rgb(255, 149, 0)
            "trip", "tripActual" -> Color.rgb(255, 214, 10)
            "sameDropoff", "sameDropoffActual" -> Color.rgb(48, 209, 88)
            "merchant", "merchantWide", "merchantActual" -> Color.rgb(52, 199, 89)
            "address", "addressWide", "addressLower", "addressActual", "addressWideActual" -> Color.rgb(255, 59, 48)
            else -> when {
                name.contains("ACCEPTED") -> Color.rgb(52, 199, 89)
                name.contains("SIZE_") -> Color.rgb(255, 59, 48)
                name.contains("ASPECT") || name.contains("EDGE") || name.contains("CIRCULARITY") || name.contains("RECTANGULARITY") -> Color.rgb(255, 149, 0)
                else -> Color.rgb(90, 200, 250)
            }
        }
    }

    private fun isCandidateRegion(name: String): Boolean {
        return name.contains("Candidate")
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun StringBuilder.appendAnchorDebug(info: OcrHelper.AnchorDebugInfo) {
        appendLine("anchorSource=${info.anchorSource}")
        appendLine("pickupDetected=${info.pickupDetected}")
        appendLine("dropoffDetected=${info.dropoffDetected}")
        appendLine("sameDropoffMatched=${info.sameDropoffMatched}")
        appendLine("merchantRows=${info.merchantRows ?: ""}")
        appendLine("addressRows=${info.addressRows ?: ""}")
        appendLine("selectedTemplate=${info.selectedTemplate ?: ""}")
        appendLine("lineHeight=${info.lineHeight ?: ""}")
        appendLine("pickupY=${info.pickupY ?: ""}")
        appendLine("dropoffY=${info.dropoffY ?: ""}")
        appendLine("baseGap=${info.baseGap ?: ""}")
        appendLine("detectedGap=${info.detectedGap ?: ""}")
        appendLine("dropoffShift=${info.dropoffShift ?: ""}")
        appendLine("gap=${info.detectedGap ?: ""}")
        appendLine("anchorConflict=${info.anchorConflict}")
        appendLine("anchorConflictReason=${info.anchorConflictReason ?: ""}")
        if (info.anchorConflict) {
            appendLine("ANCHOR_CONFLICT")
            appendLine("pickupCenter=${info.pickupY ?: ""}")
            appendLine("dropoffCenter=${info.dropoffY ?: ""}")
            appendLine("distance=${info.detectedGap ?: ""}")
            appendLine("reason=${info.anchorConflictReason ?: ""}")
        }
        info.pickupDetectDebug?.let { appendLine(it) }
        info.dropoffDetectDebug?.let { appendLine(it) }
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
        appendLine("pickupSearchRect=${formatRect(info.pickupSearchRect)}")
        appendLine("dropoffSearchRect=${formatRect(info.dropoffSearchRect)}")
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
