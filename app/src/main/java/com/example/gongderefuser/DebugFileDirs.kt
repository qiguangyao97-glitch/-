package com.example.gongderefuser

import android.content.Context
import android.os.Environment
import java.io.File

object DebugFileDirs {
    private const val ROOT_NAME = "功德拒絕器"
    private val diagnosticFolders = listOf(
        "debug_samples",
        "manual_ocr_debug",
        "order_captures",
        "accessibility-click-debug",
        "diagnostic_logs"
    )

    fun resolve(context: Context, folderName: String): File {
        val channelName = if (context.applicationContext.packageName.endsWith(".beta")) {
            "測試版"
        } else {
            "正式版"
        }
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$ROOT_NAME/$channelName/$folderName"
        )
        return runCatching {
            publicDir.mkdirs()
            val probe = File(publicDir, ".probe")
            probe.writeText("ok")
            probe.delete()
            publicDir
        }.getOrElse {
            File(context.applicationContext.getExternalFilesDir(null), folderName).apply {
                mkdirs()
            }
        }
    }

    fun resolveAppScoped(context: Context, folderName: String): File {
        return File(context.applicationContext.getExternalFilesDir(null), folderName).apply {
            mkdirs()
        }
    }

    fun clearDiagnostics(context: Context) {
        diagnosticFolders.forEach { folderName ->
            runCatching {
                resolveAppScoped(context, folderName)
                    .takeIf { it.exists() }
                    ?.deleteRecursively()
            }
        }
    }
}
