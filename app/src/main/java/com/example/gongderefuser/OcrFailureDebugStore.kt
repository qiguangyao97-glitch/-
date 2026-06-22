package com.example.gongderefuser

import android.content.Context
import com.example.gongderefuser.model.OrderData
import java.io.File

object OcrFailureDebugStore {
    private const val PREFS_NAME = "ocr_failure_debug_store"
    private const val KEY_REGION_IMAGE_PATH = "region_image_path"
    private const val KEY_TEXT_PATH = "text_path"
    private const val KEY_SOURCE = "source"
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_LATEST_REGION_IMAGE_PATH = "latest_region_image_path"
    private const val KEY_LATEST_TEXT_PATH = "latest_text_path"
    private const val KEY_LATEST_SOURCE = "latest_source"
    private const val KEY_LATEST_TIMESTAMP = "latest_timestamp"

    data class LatestFailure(
        val regionImageFile: File,
        val textFile: File?,
        val source: String,
        val timestamp: Long
    )

    fun isIncomplete(order: OrderData?): Boolean {
        if (order == null) return true
        return order.price <= 0 ||
            order.priceStatus != "OK" ||
            order.minutes <= 0 ||
            order.distance <= 0.0 ||
            order.tripStatus != "OK" ||
            order.storeName.isBlank() ||
            order.merchantStatus != "OK" ||
            order.address.isBlank() ||
            order.addressStatus != "OK" ||
            order.typeStatus != "OK"
    }

    fun recordFailure(
        context: Context,
        regionImageFile: File?,
        textFile: File?,
        source: String
    ) {
        if (regionImageFile == null || !regionImageFile.exists()) return
        prefs(context).edit()
            .putString(KEY_REGION_IMAGE_PATH, regionImageFile.absolutePath)
            .putString(KEY_TEXT_PATH, textFile?.absolutePath.orEmpty())
            .putString(KEY_SOURCE, source)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun recordLatest(
        context: Context,
        regionImageFile: File?,
        textFile: File?,
        source: String
    ) {
        if (regionImageFile == null || !regionImageFile.exists()) return
        prefs(context).edit()
            .putString(KEY_LATEST_REGION_IMAGE_PATH, regionImageFile.absolutePath)
            .putString(KEY_LATEST_TEXT_PATH, textFile?.absolutePath.orEmpty())
            .putString(KEY_LATEST_SOURCE, source)
            .putLong(KEY_LATEST_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun latestFailure(context: Context): LatestFailure? {
        return latestFromKeys(
            context = context,
            imageKey = KEY_REGION_IMAGE_PATH,
            textKey = KEY_TEXT_PATH,
            sourceKey = KEY_SOURCE,
            timestampKey = KEY_TIMESTAMP
        )
    }

    fun latestOcr(context: Context): LatestFailure? {
        return latestFromKeys(
            context = context,
            imageKey = KEY_LATEST_REGION_IMAGE_PATH,
            textKey = KEY_LATEST_TEXT_PATH,
            sourceKey = KEY_LATEST_SOURCE,
            timestampKey = KEY_LATEST_TIMESTAMP
        )
    }

    private fun latestFromKeys(
        context: Context,
        imageKey: String,
        textKey: String,
        sourceKey: String,
        timestampKey: String
    ): LatestFailure? {
        val prefs = prefs(context)
        val imagePath = prefs.getString(imageKey, "").orEmpty()
        if (imagePath.isBlank()) return null
        val imageFile = File(imagePath)
        if (!imageFile.exists()) return null

        val textPath = prefs.getString(textKey, "").orEmpty()
        val textFile = textPath
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() }

        return LatestFailure(
            regionImageFile = imageFile,
            textFile = textFile,
            source = prefs.getString(sourceKey, "").orEmpty(),
            timestamp = prefs.getLong(timestampKey, 0L)
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
