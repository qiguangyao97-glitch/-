package com.example.gongderefuser

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogStore {
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile
    private var lastWriteSummary: String = "尚未写入"

    fun append(context: Context, tag: String, message: String): File? {
        val line = "${formatter.format(Date())} [$tag] $message\n"
        val targets = listOf(
            "主日志" to File(DebugFileDirs.resolve(context, "diagnostic_logs"), "monitor-events.txt"),
            "备用日志" to File(DebugFileDirs.resolve(context, "debug_samples"), "diagnostic-monitor-events.txt")
        )
        val successes = mutableListOf<File>()
        val failures = mutableListOf<String>()
        targets.forEach { (label, file) ->
            val result = runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line, Charsets.UTF_8)
                file
            }
            result
                .onSuccess { successes.add(it) }
                .onFailure { failures.add("$label ${file.absolutePath}: ${it.javaClass.simpleName} ${it.message.orEmpty()}") }
        }
        lastWriteSummary = buildString {
            if (successes.isNotEmpty()) {
                append("写入成功：")
                append(successes.joinToString("；") { it.absolutePath })
            } else {
                append("写入失败")
            }
            if (failures.isNotEmpty()) {
                append("\n失败：")
                append(failures.joinToString("；"))
            }
        }
        return successes.firstOrNull()
    }

    fun lastWriteSummary(): String = lastWriteSummary

    fun writeSelfTest(context: Context, reason: String): File? {
        return append(
            context,
            "DIAGNOSTIC_LOG_SELF_TEST",
            "reason=$reason package=${context.packageName}"
        )
    }

    fun appendPrimaryOnly(context: Context, tag: String, message: String): File? {
        return runCatching {
            val file = File(DebugFileDirs.resolve(context, "diagnostic_logs"), "monitor-events.txt")
            file.appendText(
                "${formatter.format(Date())} [$tag] $message\n",
                Charsets.UTF_8
            )
            file
        }.getOrNull()
    }
}
