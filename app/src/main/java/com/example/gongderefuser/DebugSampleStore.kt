package com.example.gongderefuser

import android.content.Context
import android.graphics.Bitmap
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

        runCatching {
            val dir = File(context.getExternalFilesDir(null), "debug_samples").apply {
                mkdirs()
            }
            val name = "${formatter.format(Date())}-${if (parsed) "order" else "miss"}"
            File(dir, "$name.jpg").outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            }
            File(dir, "$name.txt").writeText(
                buildString {
                    appendLine("parsed=$parsed")
                    appendLine("isPairOffer=${regionText.isPairOffer}")
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
                    appendLine("===== ADDRESS =====")
                    appendLine(regionText.addressText)
                    appendLine("===== ADDRESS LOWER =====")
                    appendLine(regionText.addressLowerText)
                },
                Charsets.UTF_8
            )
        }
    }
}
