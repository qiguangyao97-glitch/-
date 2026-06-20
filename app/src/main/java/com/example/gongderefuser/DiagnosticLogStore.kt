package com.example.gongderefuser

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogStore {
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val hourlyFileNameFormatter = SimpleDateFormat("yyyyMMdd-HH", Locale.US)
    private val persistedTags = setOf(
        "A11Y_CLICK",
        "A11Y_SCREENSHOT",
        "DIAGNOSTIC_LOG_SELF_TEST",
        "DUPLICATE_ORDER_SUPPRESSED",
        "OCR_ATTEMPT",
        "OCR_FAIL",
        "OCR_SUCCESS",
        "ORDER_ANALYSIS_FINISH",
        "ORDER_EVENT_RECEIVED",
        "ORDER_LATENCY",
        "ORDER_SESSION_END",
        "ORDER_SESSION_FORCE_RESET",
        "ORDER_SESSION_START"
    )
    @Volatile
    private var lastWriteSummary: String = "尚未写入"
    @Volatile
    private var lastAttemptTime: String = "尚未尝试"

    fun append(context: Context, tag: String, message: String): File? {
        if (tag !in persistedTags) return null

        val now = Date()
        val displayTime = formatter.format(now)
        lastAttemptTime = displayTime
        val line = "$displayTime [$tag] $message\n"
        val hourKey = hourlyFileNameFormatter.format(now)
        val targets = listOf(
            LogTarget(
                label = "主日志",
                hourlyFile = File(DebugFileDirs.resolve(context, "diagnostic_logs"), "$hourKey-monitor-events.txt")
            ),
            LogTarget(
                label = "备用日志",
                hourlyFile = File(DebugFileDirs.resolve(context, "debug_samples"), "$hourKey-diagnostic-monitor-events.txt")
            )
        )
        val successes = mutableListOf<File>()
        val failures = mutableListOf<String>()
        targets.forEach { target ->
            writeTarget(target, line, successes, failures)
        }
        lastWriteSummary = buildString {
            append("尝试时间：")
            append(displayTime)
            append('\n')
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

    private data class LogTarget(
        val label: String,
        val hourlyFile: File
    )

    private fun writeTarget(
        target: LogTarget,
        line: String,
        successes: MutableList<File>,
        failures: MutableList<String>
    ) {
        writeFile(target.hourlyFile, line)
            .onSuccess { successes.add(it) }
            .onFailure {
                failures.add("${target.label} 小时檔 ${target.hourlyFile.absolutePath}: ${it.javaClass.simpleName} ${it.message.orEmpty()}")
            }
    }

    private fun writeFile(file: File, line: String): Result<File> {
        return runCatching {
            prepareLogFile(file)
            file.appendText(line, Charsets.UTF_8)
            file
        }
    }

    private fun prepareLogFile(file: File) {
        val parent = file.parentFile
        if (parent != null && parent.exists() && !parent.isDirectory) {
            val backup = File(parent.parentFile, "${parent.name}.file-backup-${System.currentTimeMillis()}")
            parent.renameTo(backup)
        }
        file.parentFile?.mkdirs()
        if (file.exists() && file.isDirectory) {
            val backup = File(file.parentFile, "${file.name}.folder-backup-${System.currentTimeMillis()}")
            if (!file.renameTo(backup)) {
                file.deleteRecursively()
            }
        }
    }

    fun lastWriteSummary(): String = lastWriteSummary

    fun lastAttemptTime(): String = lastAttemptTime

    fun writeSelfTest(context: Context, reason: String): File? {
        return append(
            context,
            "DIAGNOSTIC_LOG_SELF_TEST",
            "reason=$reason package=${context.packageName}"
        )
    }

    fun appendPrimaryOnly(context: Context, tag: String, message: String): File? {
        return append(context, tag, message)
    }
}
