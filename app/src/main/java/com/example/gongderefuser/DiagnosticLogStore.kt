package com.example.gongderefuser

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogStore {
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileNameFormatter = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    @Volatile
    private var lastWriteSummary: String = "尚未写入"
    @Volatile
    private var lastAttemptTime: String = "尚未尝试"
    @Volatile
    private var activePrimaryFile: File? = null
    @Volatile
    private var activeMirrorFile: File? = null

    fun append(context: Context, tag: String, message: String): File? {
        val now = Date()
        val displayTime = formatter.format(now)
        lastAttemptTime = displayTime
        val line = "$displayTime [$tag] $message\n"
        val sessionFileTime = fileNameFormatter.format(now)
        val targets = listOf(
            LogTarget(
                label = "主日志",
                fixedFile = File(DebugFileDirs.resolve(context, "diagnostic_logs"), "monitor-events.txt"),
                activeFile = activePrimaryFile,
                fallbackFileName = "$sessionFileTime-monitor-events.txt"
            ),
            LogTarget(
                label = "备用日志",
                fixedFile = File(DebugFileDirs.resolve(context, "debug_samples"), "diagnostic-monitor-events.txt"),
                activeFile = activeMirrorFile,
                fallbackFileName = "$sessionFileTime-diagnostic-monitor-events.txt"
            )
        )
        val successes = mutableListOf<File>()
        val failures = mutableListOf<String>()
        targets.forEach { target ->
            val activeFile = writeTarget(target, line, successes, failures)
            when (target.label) {
                "主日志" -> activePrimaryFile = activeFile
                "备用日志" -> activeMirrorFile = activeFile
            }
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
        val fixedFile: File,
        val activeFile: File?,
        val fallbackFileName: String
    )

    private fun writeTarget(
        target: LogTarget,
        line: String,
        successes: MutableList<File>,
        failures: MutableList<String>
    ): File? {
        target.activeFile?.let { activeFile ->
            writeFile(activeFile, line)
                .onSuccess {
                    successes.add(it)
                    return it
                }
                .onFailure {
                    failures.add("${target.label} 既有檔 ${activeFile.absolutePath}: ${it.javaClass.simpleName} ${it.message.orEmpty()}")
                }
        }

        val fixedResult = writeFile(target.fixedFile, line)
        fixedResult
            .onSuccess {
                successes.add(it)
                return it
            }
            .onFailure {
                failures.add("${target.label} 固定檔 ${target.fixedFile.absolutePath}: ${it.javaClass.simpleName} ${it.message.orEmpty()}")
            }

        val fallbackFile = File(target.fixedFile.parentFile, target.fallbackFileName)
        writeFile(fallbackFile, line)
            .onSuccess {
                successes.add(it)
                return it
            }
            .onFailure {
                failures.add("${target.label} 时间戳檔 ${fallbackFile.absolutePath}: ${it.javaClass.simpleName} ${it.message.orEmpty()}")
            }
        return null
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
