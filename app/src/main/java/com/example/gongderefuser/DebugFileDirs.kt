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
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$ROOT_NAME/$folderName"
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

    fun clearDiagnostics(context: Context) {
        diagnosticFolders.forEach { folderName ->
            runCatching {
                resolve(context, folderName)
                    .takeIf { it.exists() }
                    ?.deleteRecursively()
            }
        }
    }
}
