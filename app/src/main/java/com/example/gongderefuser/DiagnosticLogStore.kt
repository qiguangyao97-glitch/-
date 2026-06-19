package com.example.gongderefuser

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogStore {
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun append(context: Context, tag: String, message: String): File? {
        return runCatching {
            val dir = DebugFileDirs.resolve(context, "diagnostic_logs")
            val file = File(dir, "monitor-events.txt")
            file.appendText(
                "${formatter.format(Date())} [$tag] $message\n",
                Charsets.UTF_8
            )
            file
        }.getOrNull()
    }
}
