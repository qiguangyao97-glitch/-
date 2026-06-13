package com.example.gongderefuser

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GongdeRefuserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OcrCorrectionStore.load(this)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = File(getExternalFilesDir(null), "crash_logs").apply { mkdirs() }
                val name = "crash-${formatter.format(Date())}.txt"
                val stack = StringWriter().also { writer ->
                    PrintWriter(writer).use { printer ->
                        printer.println("thread=${thread.name}")
                        throwable.printStackTrace(printer)
                    }
                }.toString()
                File(dir, name).writeText(stack, Charsets.UTF_8)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private val formatter = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    }
}
