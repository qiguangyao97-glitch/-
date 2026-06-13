package com.example.gongderefuser

import android.content.Context
import android.graphics.Bitmap
import com.example.gongderefuser.analyzer.OrderAnalyzer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OrderCaptureStore {
    private val formatter = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    fun saveOrderCapture(
        context: Context,
        bitmap: Bitmap,
        analysis: OrderAnalyzer.AnalysisResult
    ): String {
        return runCatching {
            val dir = File(context.applicationContext.getExternalFilesDir(null), "order_captures").apply {
                mkdirs()
            }
            val merchant = analysis.storeName
                .ifBlank { "order" }
                .replace(Regex("[^\\p{IsHan}A-Za-z0-9_-]+"), "_")
                .trim('_')
                .take(24)
                .ifBlank { "order" }
            val name = "${formatter.format(Date())}-${analysis.price}元-${merchant}.jpg"
            val file = File(dir, name)
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
            }
            file.absolutePath
        }.getOrDefault("")
    }
}
