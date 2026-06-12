package com.example.gongderefuser

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.equationl.paddleocr4android.CpuPowerMode
import com.equationl.paddleocr4android.OCR
import com.equationl.paddleocr4android.OcrConfig
import com.equationl.paddleocr4android.Util.paddle.OcrResultModel

object PaddleOcrEngine {
    private const val TAG = "PADDLE_OCR"

    private var ocr: OCR? = null
    private var initAttempted = false

    @Synchronized
    fun recognize(context: Context, bitmap: Bitmap): String? {
        val engine = getOrInit(context) ?: return null
        return engine.runSync(bitmap).fold(
            onSuccess = { result ->
                sortTextLines(result.outputRawResult)
                    .joinToString("\n") { it.label.trim() }
                    .ifBlank { result.simpleText.trim() }
            },
            onFailure = { error ->
                Log.e(TAG, "run failed", error)
                null
            }
        )
    }

    private fun sortTextLines(lines: List<OcrResultModel>): List<OcrResultModel> {
        return lines.sortedWith(
            compareBy<OcrResultModel>(
                { line -> line.points.minOfOrNull { it.y } ?: 0 },
                { line -> line.points.minOfOrNull { it.x } ?: 0 }
            )
        )
    }

    @Synchronized
    private fun getOrInit(context: Context): OCR? {
        ocr?.let { return it }
        if (initAttempted) return null
        initAttempted = true

        val appContext = context.applicationContext
        val engine = OCR(appContext)
        val config = OcrConfig(
            modelPath = "models/ch_PP-OCRv4",
            labelPath = "labels/ppocr_keys_v1.txt",
            cpuThreadNum = 4,
            cpuPowerMode = CpuPowerMode.LITE_POWER_HIGH,
            scoreThreshold = 0.35f,
            detLongSize = 960,
            detModelFilename = "det.nb",
            recModelFilename = "rec.nb",
            clsModelFilename = "cls.nb",
            isRunDet = true,
            isRunCls = true,
            isRunRec = true,
            isUseOpencl = false,
            isDrwwTextPositionBox = false
        )

        return engine.initModelSync(config).fold(
            onSuccess = { success ->
                if (success) {
                    Log.i(TAG, "init success")
                    ocr = engine
                    engine
                } else {
                    Log.e(TAG, "init returned false")
                    null
                }
            },
            onFailure = { error ->
                Log.e(TAG, "init failed", error)
                null
            }
        )
    }
}
