package com.example.gongderefuser

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticLogExporter {
    private val fileNameFormatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val metadataTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun suggestedFileName(context: Context): String {
        val appContext = context.applicationContext
        val channelName = if (appContext.packageName.endsWith(".beta")) "測試版" else "正式版"
        return "功德拒絕器-${channelName}-日誌-${fileNameFormatter.format(Date())}.zip"
    }

    fun createTempZip(context: Context): File {
        val appContext = context.applicationContext
        val channelName = if (appContext.packageName.endsWith(".beta")) "測試版" else "正式版"
        val fileName = suggestedFileName(appContext)
        val tempZip = File(appContext.cacheDir, fileName)
        createZip(appContext, tempZip, channelName)
        return tempZip
    }

    fun writeZipToUri(context: Context, tempZip: File, uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            tempZip.inputStream().use { input -> input.copyTo(output) }
        } ?: error("無法寫入選擇的位置")
    }

    private fun createZip(context: Context, outputFile: File, channelName: String) {
        outputFile.parentFile?.mkdirs()
        ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
            val metadata = buildString {
                appendLine("app=功德拒絕器")
                appendLine("channel=$channelName")
                appendLine("package=${context.packageName}")
                appendLine("generatedAt=${metadataTimeFormatter.format(Date())}")
            }
            zip.putNextEntry(ZipEntry("export_info.txt"))
            zip.write(metadata.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            val folders = listOf("diagnostic_logs", "debug_samples", "manual_ocr_debug")
            var fileCount = 0
            folders.forEach { folderName ->
                val folder = DebugFileDirs.resolveAppScoped(context, folderName)
                fileCount += addFolder(zip, folder, folderName)
            }
            if (fileCount == 0) {
                zip.putNextEntry(ZipEntry("NO_LOG_FILES.txt"))
                zip.write("目前沒有可匯出的日誌檔案。".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun addFolder(zip: ZipOutputStream, folder: File, entryRoot: String): Int {
        if (!folder.exists()) return 0
        var count = 0
        folder.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relativePath = file.relativeTo(folder).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry("$entryRoot/$relativePath"))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                count += 1
            }
        return count
    }

}
