package com.example.gongderefuser

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogStore {
    private const val DEBUG_MODE = false
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val hourlyFileNameFormatter = SimpleDateFormat("yyyyMMdd-HH", Locale.US)
    private val releasePersistedTags = setOf(
        "ACCESSIBILITY_SERVICE_RECOVERED",
        "ACCESSIBILITY_SERVICE_SUSPECTED_DEAD",
        "ACCESSIBILITY_WATCHDOG_CHECK",
        "A11Y_CLICK",
        "APP_ERROR",
        "APP_START",
        "DUPLICATE_ORDER_SUPPRESSED",
        "MAIN_UI_ERROR",
        "MAIN_UI_RECOVERY_ACTION",
        "MAIN_UI_RECOVERY_SHOWN",
        "MAIN_UI_START",
        "OCR_FAIL_FINAL",
        "OCR_SUCCESS",
        "ORDER_ACCEPT_CONFIRMED",
        "ORDER_ACCEPT_CONFIRMATION_TIMEOUT",
        "ORDER_ACCEPT_STATE_OBSERVED",
        "ORDER_ACCEPT_STATE_SCAN",
        "ORDER_ACTION_REGIONS_ACTIVE",
        "ORDER_ACTION_REGIONS_CLEARED",
        "ORDER_ACTION_REGIONS_MISSING",
        "ORDER_ACTION_REGION_CLICK",
        "ORDER_ACTION_REGION_ERROR",
        "ORDER_ACTION_REGION_FORWARD",
        "ORDER_ACTION_REGION_OVERLAY_ADDED",
        "ORDER_ANALYSIS_FINISH",
        "ORDER_DECLINED_MARKED",
        "PENDING_EVENT_COALESCED",
        "PENDING_EVENT_DROPPED_AFTER_STALE",
        "PENDING_UBER_EVENT_CLEARED",
        "PENDING_UBER_EVENT_PROCESS",
        "PENDING_UBER_EVENT_RECEIVED",
        "POPUP_SHOW",
        "POPUP_REPLACE",
        "REMINDER_FLOW_REPLACE",
        "REMINDER_FLOW_START",
        "REMINDER_FLOW_SUPPRESSED_DUPLICATE",
        "SESSION_END",
        "SESSION_STALE_COOLDOWN_END",
        "SESSION_STALE_COOLDOWN_START",
        "SOUND_PLAY_COMPLETE",
        "SOUND_PLAY_REQUEST",
        "SOUND_PLAY_SKIPPED_DUPLICATE",
        "SOUND_PLAY_START",
        "SOUND_STOP_CALLED"
    )
    private val debugPersistedTags = setOf(
        "ACCESSIBILITY",
        "ACCESSIBILITY_SERVICE_RECOVERED",
        "ACCESSIBILITY_SERVICE_SUSPECTED_DEAD",
        "ACCESSIBILITY_WATCHDOG_CHECK",
        "ACTIVATION",
        "A11Y_CLICK",
        "A11Y_SCREENSHOT",
        "CAPTURE",
        "DIAGNOSTIC_LOG_SELF_TEST",
        "DUPLICATE_ORDER_OBSERVED",
        "DUPLICATE_ORDER_SUPPRESSED",
        "EVENT_SOURCE_BOUNDS",
        "EVENT_STRUCTURE",
        "MAIN_NAV_PAGE_BYPASS",
        "MAIN_NAV_PAGE_CHECK",
        "MAIN_UI_ERROR",
        "MAIN_UI_RECOVERY_ACTION",
        "MAIN_UI_RECOVERY_SHOWN",
        "MAIN_UI_START",
        "MONITORING",
        "NEW_ORDER_REPLACES_OVERLAY",
        "OCR_ATTEMPT",
        "OCR_FAIL",
        "OCR_FAIL_FINAL",
        "OCR_FAILED",
        "OCR_FINISH",
        "OCR_MONEY_INVALID",
        "OCR_RETRY",
        "OCR_SCREENSHOT_SAVED",
        "OCR_START",
        "OCR_SUCCESS",
        "OCR_REGION_USAGE",
        "OCR_TEMPLATE_ACTIVE",
        "OCR_TEMPLATE_LOAD_FOR_EDITOR",
        "OCR_TEMPLATE_SAVE",
        "OCR_TEMPLATE_SOURCE",
        "ORDER_ANALYSIS_FINISH",
        "ORDER_ANALYSIS_START",
        "ORDER_ACCEPT_CONFIRMED",
        "ORDER_ACCEPT_CONFIRMATION_TIMEOUT",
        "ORDER_ACCEPT_STATE_OBSERVED",
        "ORDER_ACCEPT_STATE_SCAN",
        "ORDER_ACTION_REGIONS_ACTIVE",
        "ORDER_ACTION_REGIONS_CLEARED",
        "ORDER_ACTION_REGIONS_MISSING",
        "ORDER_ACTION_REGION_CLICK",
        "ORDER_ACTION_REGION_ERROR",
        "ORDER_ACTION_REGION_FORWARD",
        "ORDER_ACTION_REGION_OVERLAY_ADDED",
        "ORDER_DETECTED",
        "ORDER_DECLINED_MARKED",
        "ORDER_DETECTION_WAIT",
        "ORDER_EVENT_RECEIVED",
        "ORDER_LATENCY",
        "ORDER_POPUP_VALIDATION",
        "ORDER_VALIDATION_RESULT",
        "ORDER_REPLACED",
        "ORDER_SESSION_END",
        "ORDER_SESSION_FORCE_RESET",
        "ORDER_SESSION_START",
        "ORDER_UPDATED",
        "ORDER_BLOCKING_POPUP_CANDIDATE",
        "ORDER_TRIGGER_CANDIDATE",
        "ORDER_TRIGGER_REJECTED",
        "POPUP_REPLACE",
        "POPUP_SHOW",
        "POPUP_HIDE",
        "POPUP_STRUCTURE_SCAN",
        "PROJECTION",
        "SCREEN",
        "SCREENSHOT_FAILURE_DETAIL",
        "SECOND_CHECK",
        "SESSION_DECISION",
        "SESSION_END",
        "SERVICE",
        "TRIGGER_GATE_DECISION",
        "TRIGGER_GATE_BYPASS",
        "UBER_EVENT_FORCED_TO_OCR",
        "PENDING_UBER_EVENT_CLEARED",
        "PENDING_UBER_EVENT_PROCESS",
        "PENDING_UBER_EVENT_RECEIVED",
        "WINDOW_DEBUG"
    )
    private val persistedTags = if (DEBUG_MODE) {
        debugPersistedTags + releasePersistedTags
    } else {
        releasePersistedTags
    }
    @Volatile
    private var lastWriteSummary: String = "尚未寫入"
    @Volatile
    private var lastAttemptTime: String = "尚未嘗試"

    fun append(context: Context, tag: String, message: String): File? {
        if (tag !in persistedTags) return null
        if (!AppSettings.isAccessibilityLogEnabled(context)) {
            val displayTime = formatter.format(Date())
            lastAttemptTime = displayTime
            lastWriteSummary = "日誌開關關閉，未寫入"
            return null
        }

        val now = Date()
        val displayTime = formatter.format(now)
        lastAttemptTime = displayTime
        val line = "$displayTime [$tag] $message\n"
        val hourKey = hourlyFileNameFormatter.format(now)
        val targets = listOf(
            LogTarget(
                label = "主日誌",
                hourlyFile = File(DebugFileDirs.resolveAppScoped(context, "diagnostic_logs"), "$hourKey-monitor-events.txt")
            ),
            LogTarget(
                label = "備用日誌",
                hourlyFile = File(DebugFileDirs.resolveAppScoped(context, "debug_samples"), "$hourKey-diagnostic-monitor-events.txt")
            )
        )
        val successes = mutableListOf<File>()
        val failures = mutableListOf<String>()
        targets.forEach { target ->
            writeTarget(target, line, successes, failures)
        }
        lastWriteSummary = buildString {
            append("嘗試時間：")
            append(displayTime)
            append('\n')
            if (successes.isNotEmpty()) {
                append("寫入成功：")
                append(successes.joinToString("；") { it.absolutePath })
            } else {
                append("寫入失敗")
            }
            if (failures.isNotEmpty()) {
                append("\n失敗：")
                append(failures.joinToString("；"))
            }
        }
        return successes.firstOrNull()
    }

    fun add(context: Context, tag: String, message: String): File? {
        return append(context, tag, message)
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
                failures.add("${target.label} 小時檔 ${target.hourlyFile.absolutePath}: ${it.javaClass.simpleName} ${it.message.orEmpty()}")
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
