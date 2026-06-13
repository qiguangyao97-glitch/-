package com.example.gongderefuser

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogStore {
    private val formatter = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    fun save(context: Context, label: String, throwable: Throwable) {
        runCatching {
            val dir = DebugFileDirs.resolve(context, "crash_logs")
            val stack = StringWriter().also { writer ->
                PrintWriter(writer).use { printer ->
                    printer.println("label=$label")
                    throwable.printStackTrace(printer)
                }
            }.toString()
            File(dir, "${formatter.format(Date())}-$label.txt").writeText(stack, Charsets.UTF_8)
        }
    }
}
