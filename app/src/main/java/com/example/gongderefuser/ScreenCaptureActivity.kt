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

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

        AlertDialog.Builder(this)
            .setTitle("请选择分享整个画面")
            .setMessage("实时监测需要读取当前手机画面。接下来的系统提示里，请选择“分享整个画面”，不要选择“分享单一应用程式”。")
            .setPositiveButton("继续授权") { _, _ ->
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

            // 保存权限数据
            CaptureHolder.resultCode = resultCode
            CaptureHolder.data = data
            MonitoringState.setEnabled(this, true)

            // 👉 启动截屏Service
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
