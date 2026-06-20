package com.example.gongderefuser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

class ScreenCaptureActivity : Activity() {

    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ActivationLocalStore.isLocalActive(this)) {
            Log.d("ACTIVATION", "ScreenCaptureActivity blocked: not active")
            finish()
            return
        }

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

        AlertDialog.Builder(this)
            .setTitle("請選擇分享整個畫面")
            .setMessage("即時監測需要讀取目前手機畫面。接下來的系統提示裡，請選擇“分享整個畫面”，不要選擇“分享單一應用程式”。")
            .setPositiveButton("繼續授權") { _, _ ->
                startProjectionRequest()
            }
            .setNegativeButton("取消") { _, _ ->
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .show()
    }

    private fun startProjectionRequest() {
        val intent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_CODE)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {

            Log.d("CAPTURE", "permission granted")

            // 儲存權限資料
            CaptureHolder.resultCode = resultCode
            CaptureHolder.data = data
            MonitoringState.setEnabled(this, true)

            // 👉 啟動截圖Service
            startService()

            finish()
        } else {
            finish()
        }
    }

    private fun startService() {

        val intent = Intent(this, ScreenCaptureService::class.java)
        startForegroundService(intent)
    }

    companion object {
        const val REQUEST_CODE = 1001
    }
}
