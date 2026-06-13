package com.example.gongderefuser

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AccessibilityScreenshotTestStore {
    private val formatter = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    fun save(context: Context, bitmap: Bitmap): File {
        val dir = DebugFileDirs.resolve(context, "accessibility_screenshot_tests")
        val file = File(dir, "a11y-test-${formatter.format(Date())}.jpg")
        file.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
        }
        return file
    }
}
