package com.example.gongderefuser

import android.content.Context
import android.graphics.Bitmap
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
                        appendLine("白名单=${analysis.isWhitelisted} ${analysis.matchedWhitelistKeyword} ${analysis.whitelistNote}")
                        appendLine("黑名单=${analysis.isBlacklisted} ${analysis.matchedBlacklistKeyword} ${analysis.blacklistNote}")
                    }
                    appendLine("isPairOffer=${regionText.isPairOffer}")
                    appendLine("hasAnchoredCard=${regionText.hasAnchoredCard}")
                    appendLine("===== FULL =====")
                    appendLine(regionText.fullText)
                    appendLine("===== CARD =====")
                    appendLine(regionText.cardText)
                    appendLine("===== TYPE =====")
                    appendLine(regionText.typeText)
                    appendLine("===== PRICE =====")
                    appendLine(regionText.priceText)
                    appendLine("===== TRIP =====")
                    appendLine(regionText.tripText)
                    appendLine("===== DETAIL =====")
                    appendLine(regionText.detailText)
                    appendLine("===== MERCHANT =====")
                    appendLine(regionText.merchantText)
                    appendLine("===== MERCHANT WIDE =====")
                    appendLine(regionText.merchantWideText)
                    appendLine("===== ADDRESS =====")
                    appendLine(regionText.addressText)
                    appendLine("===== ADDRESS WIDE =====")
                    appendLine(regionText.addressWideText)
                    appendLine("===== ADDRESS LOWER =====")
                    appendLine(regionText.addressLowerText)
                },
                Charsets.UTF_8
            )
        }
    }

    private fun parseOrder(regionText: OcrHelper.OrderRegionText) =
        OrderParser.parse(
            OrderParser.RegionInput(
                fullText = regionText.fullText,
                cardText = regionText.cardText,
                typeText = regionText.typeText,
                priceText = regionText.priceText,
                tripText = regionText.tripText,
                detailText = regionText.detailText,
                merchantText = regionText.merchantText,
                merchantWideText = regionText.merchantWideText,
                addressText = regionText.addressText,
                addressWideText = regionText.addressWideText,
                addressLowerText = regionText.addressLowerText
            )
        ) ?: OrderParser.parse(regionText.fullText)
}
