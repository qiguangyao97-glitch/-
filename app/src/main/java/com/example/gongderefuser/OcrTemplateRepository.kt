package com.example.gongderefuser

import android.content.Context
import android.graphics.RectF

object OcrTemplateRepository {
    const val TEMPLATE_VERSION = 2
    private const val PREF_NAME = "gongde_refuser_ocr_calibration"

    enum class Source {
        USER_SAVED,
        DEFAULT,
        LOAD_FAILED
    }

    data class ActiveTemplate(
        val regions: Map<String, RectF>,
        val source: Source,
        val version: Int = TEMPLATE_VERSION,
        val hasUserSavedTemplate: Boolean,
        val fallbackReason: String = "",
        val loadedAt: Long = System.currentTimeMillis()
    )

    fun getActiveTemplate(context: Context): ActiveTemplate {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val hasSaved = prefs.contains("${OcrCalibrationStore.regionNames.first()}_left")
        if (!hasSaved) {
            return ActiveTemplate(
                regions = OcrCalibrationStore.defaultRegions(),
                source = Source.DEFAULT,
                hasUserSavedTemplate = false,
                fallbackReason = "NO_USER_SAVED_TEMPLATE"
            )
        }
        return runCatching {
            val loaded = OcrCalibrationStore.regionNames.associateWith { name ->
                val fallback = OcrCalibrationStore.defaultRegions().getValue(name)
                RectF(
                    prefs.getFloat("${name}_left", fallback.left),
                    prefs.getFloat("${name}_top", fallback.top),
                    prefs.getFloat("${name}_right", fallback.right),
                    prefs.getFloat("${name}_bottom", fallback.bottom)
                ).let { OcrCalibrationStore.sanitizeRect(name, it) }
            }
            val missing = OcrCalibrationStore.regionNames.filterNot { name ->
                prefs.contains("${name}_left") &&
                    prefs.contains("${name}_top") &&
                    prefs.contains("${name}_right") &&
                    prefs.contains("${name}_bottom")
            }
            if (missing.isNotEmpty()) {
                ActiveTemplate(
                    regions = loaded,
                    source = Source.USER_SAVED,
                    hasUserSavedTemplate = true,
                    fallbackReason = "MISSING_FIELDS_FILLED_DEFAULT:${missing.joinToString(",")}"
                )
            } else {
                ActiveTemplate(
                    regions = loaded,
                    source = Source.USER_SAVED,
                    hasUserSavedTemplate = true
                )
            }
        }.getOrElse { throwable ->
            ActiveTemplate(
                regions = OcrCalibrationStore.defaultRegions(),
                source = Source.LOAD_FAILED,
                hasUserSavedTemplate = hasSaved,
                fallbackReason = "${throwable.javaClass.simpleName}:${throwable.message.orEmpty()}"
            )
        }
    }

    fun save(context: Context, regions: Map<String, RectF>): Boolean {
        return runCatching {
            val editor = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            OcrCalibrationStore.regionNames.forEach { name ->
                val rect = OcrCalibrationStore.sanitizeRect(
                    name,
                    regions[name] ?: OcrCalibrationStore.defaultRegions().getValue(name)
                )
                editor
                    .putFloat("${name}_left", rect.left)
                    .putFloat("${name}_top", rect.top)
                    .putFloat("${name}_right", rect.right)
                    .putFloat("${name}_bottom", rect.bottom)
            }
            editor.apply()
            true
        }.getOrDefault(false)
    }

    fun reset(context: Context) {
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun savePath(context: Context): String {
        return "/data/data/${context.applicationContext.packageName}/shared_prefs/$PREF_NAME.xml"
    }

    fun templateSummary(regions: Map<String, RectF>): String {
        val merchantLong = regions["merchant"]
        val addressLong = regions["address"]
        return listOf(
            "templateVersion=$TEMPLATE_VERSION",
            "closeButtonTemplate=${format(regions["closeButton"])}",
            "typeRectTemplate=${format(regions["type"])}",
            "priceRectTemplate=${format(regions["price"])}",
            "tripRectTemplate=${format(regions["trip"])}",
            "pickupSearchRect=${format(regions["pickupCircleSearch"])}",
            "dropoffSearchRect=${format(regions["dropoffSquareSearch"])}",
            "merchantLong=${format(merchantLong)}",
            "merchantShort=${format(topHalf(merchantLong))}",
            "addressLong=${format(addressLong)}",
            "addressShort=${format(topHalf(addressLong))}",
            "merchantAddressBlock=${format(regions["merchantAddressBlock"])}",
            "sameDropoffRect=${format(regions["sameDropoff"])}"
        ).joinToString(" ")
    }

    private fun topHalf(rect: RectF?): RectF? {
        rect ?: return null
        return RectF(rect.left, rect.top, rect.right, rect.top + rect.height() / 2f)
    }

    private fun format(rect: RectF?): String {
        rect ?: return "null"
        return "%.6f,%.6f,%.6f,%.6f".format(
            java.util.Locale.US,
            rect.left,
            rect.top,
            rect.right,
            rect.bottom
        )
    }
}
