package com.example.gongderefuser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

object OcrHelper {

    data class OrderRegionText(
        val fullText: String,
        val isPairOffer: Boolean,
        val cardText: String,
        val typeText: String,
        val priceText: String,
        val tripText: String,
        val detailText: String,
        val merchantText: String,
        val addressText: String,
        val addressLowerText: String
    )

    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    fun run(bitmap: Bitmap, callback: (String) -> Unit) {

        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                callback(result.text)
            }
            .addOnFailureListener {
                callback("")
            }
    }

    fun runOrderRegions(context: Context, bitmap: Bitmap, callback: (OrderRegionText) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                runOrderRegions(context.applicationContext, bitmap, result.text, callback)
            }
            .addOnFailureListener {
                runOrderRegions(context.applicationContext, bitmap, "", callback)
            }
    }

    private fun runOrderRegions(
        context: Context,
        bitmap: Bitmap,
        fullText: String,
        callback: (OrderRegionText) -> Unit
    ) {
        val isPairOffer = fullText.contains("配對") || fullText.contains("配对")
        val profile = if (isPairOffer) RegionProfile.Pair else RegionProfile.Accept
        val regions = listOf(
            "card" to crop(bitmap, 0.03f, profile.cardTop, 0.97f, 0.97f),
            "type" to prepareForOcr(crop(bitmap, 0.06f, profile.typeTop, 0.56f, profile.typeBottom)),
            "price" to prepareForOcr(crop(bitmap, 0.05f, profile.priceTop, 0.45f, profile.priceBottom)),
            "trip" to prepareForOcr(crop(bitmap, 0.05f, profile.tripTop, 0.92f, profile.tripBottom)),
            "detail" to prepareForOcr(crop(bitmap, 0.00f, profile.merchantTop - 0.01f, 1.00f, profile.addressBottom + 0.04f)),
            "merchant" to prepareForOcr(crop(bitmap, 0.00f, profile.merchantTop, 1.00f, profile.merchantBottom)),
            "address" to prepareForOcr(crop(bitmap, 0.00f, profile.addressTop, 1.00f, profile.addressBottom)),
            "addressLower" to prepareForOcr(crop(bitmap, 0.00f, profile.addressSecondTop, 1.00f, profile.addressSecondBottom))
        )
        val results = mutableMapOf<String, String>()
        var remaining = regions.size
        val lock = Any()

        fun finishOne(key: String, text: String) {
            synchronized(lock) {
                results[key] = text
                remaining -= 1
                if (remaining == 0) {
                    callback(
                        OrderRegionText(
                            fullText = fullText,
                            isPairOffer = isPairOffer,
                            cardText = results["card"].orEmpty(),
                            typeText = results["type"].orEmpty(),
                            priceText = results["price"].orEmpty(),
                            tripText = results["trip"].orEmpty(),
                            detailText = results["detail"].orEmpty(),
                            merchantText = results["merchant"].orEmpty(),
                            addressText = results["address"].orEmpty(),
                            addressLowerText = results["addressLower"].orEmpty()
                        )
                    )
                }
            }
        }

        regions.forEach { (key, regionBitmap) ->
            if (key == "detail" || key == "merchant" || key == "address" || key == "addressLower") {
                Thread {
                    val paddleText = PaddleOcrEngine.recognize(context, regionBitmap)
                    if (!paddleText.isNullOrBlank()) {
                        finishOne(key, paddleText)
                    } else {
                        runMlKitRegion(regionBitmap) { text -> finishOne(key, text) }
                    }
                }.start()
            } else {
                runMlKitRegion(regionBitmap) { text -> finishOne(key, text) }
            }
        }
    }

    private fun runMlKitRegion(bitmap: Bitmap, callback: (String) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                callback(result.text)
            }
            .addOnFailureListener {
                callback("")
            }
    }

    private sealed class RegionProfile(
        val cardTop: Float,
        val typeTop: Float,
        val typeBottom: Float,
        val priceTop: Float,
        val priceBottom: Float,
        val tripTop: Float,
        val tripBottom: Float,
        val merchantTop: Float,
        val merchantBottom: Float,
        val addressTop: Float,
        val addressBottom: Float,
        val addressSecondTop: Float,
        val addressSecondBottom: Float
    ) {
        object Accept : RegionProfile(
            cardTop = 0.56f,
            typeTop = 0.58f,
            typeBottom = 0.64f,
            priceTop = 0.63f,
            priceBottom = 0.71f,
            tripTop = 0.73f,
            tripBottom = 0.79f,
            merchantTop = 0.80f,
            merchantBottom = 0.84f,
            addressTop = 0.84f,
            addressBottom = 0.93f,
            addressSecondTop = 0.855f,
            addressSecondBottom = 0.935f
        )

        object Pair : RegionProfile(
            cardTop = 0.54f,
            typeTop = 0.56f,
            typeBottom = 0.62f,
            priceTop = 0.61f,
            priceBottom = 0.69f,
            tripTop = 0.70f,
            tripBottom = 0.76f,
            merchantTop = 0.77f,
            merchantBottom = 0.81f,
            addressTop = 0.81f,
            addressBottom = 0.91f,
            addressSecondTop = 0.835f,
            addressSecondBottom = 0.915f
        )
    }

    private fun crop(
        bitmap: Bitmap,
        leftRatio: Float,
        topRatio: Float,
        rightRatio: Float,
        bottomRatio: Float
    ): Bitmap {
        val left = (bitmap.width * leftRatio).toInt().coerceIn(0, bitmap.width - 1)
        val top = (bitmap.height * topRatio).toInt().coerceIn(0, bitmap.height - 1)
        val right = (bitmap.width * rightRatio).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (bitmap.height * bottomRatio).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun prepareForOcr(bitmap: Bitmap): Bitmap {
        val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width * 3, bitmap.height * 3, true)
        val output = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply {
                    setSaturation(0f)
                    postConcat(ColorMatrix(floatArrayOf(
                        1.45f, 0f, 0f, 0f, -36f,
                        0f, 1.45f, 0f, 0f, -36f,
                        0f, 0f, 1.45f, 0f, -36f,
                        0f, 0f, 0f, 1f, 0f
                    )))
                }
            )
        }
        Canvas(output).drawBitmap(scaled, 0f, 0f, paint)
        return output
    }
}
