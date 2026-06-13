package com.example.gongderefuser

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.widget.Toast
import com.example.gongderefuser.analyzer.OrderAnalyzer
import com.example.gongderefuser.parser.OrderParser

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var projectionCallbackRegistered = false

    private var isProcessing = false
    private var lastShownOrderSignature: String = ""
    private var lastShownOrderTime: Long = 0L
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            MonitoringState.setEnabled(this@ScreenCaptureService, false)
            stopCapture()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        MyAccessibilityService.refreshStatusOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1, createNotification())
        }

        val resultCode = CaptureHolder.resultCode
        val data = CaptureHolder.data

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e("CAPTURE", "no permission data")
            MonitoringState.setEnabled(this, false)
            return START_NOT_STICKY
        }

        if (mediaProjection != null) {
            if (intent?.action == ACTION_CAPTURE_PULSE) {
                startCapture()
            }
            return START_STICKY
        }

        val manager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

        try {
            mediaProjection =
                manager.getMediaProjection(resultCode, data)
        } catch (e: SecurityException) {
            Log.e("CAPTURE", "projection token invalid", e)
            clearProjectionPermission()
            MonitoringState.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CAPTURE_PULSE) {
            startCapture()
        }
        MyAccessibilityService.refreshStatusOverlay()

        return START_STICKY
    }

    private fun startCapture() {

        val projection = mediaProjection ?: return
        if (virtualDisplay != null || imageReader != null) return
        if (!projectionCallbackRegistered) {
            projection.registerCallback(projectionCallback, mainHandler)
            projectionCallbackRegistered = true
        }

        val metrics = Resources.getSystem().displayMetrics

        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            android.graphics.PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = projection.createVirtualDisplay(
            "gongde-screen",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        imageReader?.setOnImageAvailableListener({ reader ->

            if (!MonitoringState.isEnabled(this) || !CaptureTrigger.shouldCapture) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            // 防止OCR重复并发（关键）
            if (isProcessing) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            isProcessing = true
            CaptureTrigger.shouldCapture = false
            if (CaptureTrigger.pendingCaptureCount > 0) {
                CaptureTrigger.pendingCaptureCount -= 1
            }

            val image = reader.acquireLatestImage()
            if (image == null) {
                isProcessing = false
                return@setOnImageAvailableListener
            }

            val planes = image.planes
            val buffer = planes[0].buffer

            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride

            val width = Resources.getSystem().displayMetrics.widthPixels
            val height = Resources.getSystem().displayMetrics.heightPixels

            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )

            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            Log.d("CAPTURE", "bitmap ready")

            // OCR处理
            OcrHelper.runOrderRegions(this, bitmap) { regionText ->

                Log.d("OCR_RESULT", regionText.fullText)
                val foundOrder = handleOcrText(regionText)
                DebugSampleStore.saveCapture(this, bitmap, regionText, foundOrder)
                if (!foundOrder && CaptureTrigger.pendingCaptureCount > 0) {
                    mainHandler.postDelayed({
                        CaptureTrigger.shouldCapture = true
                        Log.d("CAPTURE", "retry capture, remaining=${CaptureTrigger.pendingCaptureCount}")
                    }, 650)
                } else if (CaptureTrigger.pendingCaptureCount <= 0 || foundOrder) {
                    mainHandler.postDelayed({ stopCaptureSession() }, 250)
                }

                isProcessing = false
            }

        }, mainHandler)

        mainHandler.postDelayed({
            if (!isProcessing && CaptureTrigger.pendingCaptureCount <= 0) {
                stopCaptureSession()
            }
        }, 3_500)
    }

    private fun handleOcrText(regionText: OcrHelper.OrderRegionText): Boolean {
        val order = OrderParser.parse(
            OrderParser.RegionInput(
                fullText = regionText.fullText,
                cardText = regionText.cardText,
                typeText = regionText.typeText,
                priceText = regionText.priceText,
                tripText = regionText.tripText,
                detailText = regionText.detailText,
                merchantText = regionText.merchantText,
                addressText = regionText.addressText,
                addressLowerText = regionText.addressLowerText
            )
        ) ?: OrderParser.parse(regionText.fullText)
        return handleOrder(order)
    }

    private fun handleOcrText(text: String): Boolean {
        val order = OrderParser.parse(text)
        return handleOrder(order)
    }

    private fun handleOrder(order: com.example.gongderefuser.model.OrderData?): Boolean {
        if (order == null) {
            Log.d("ORDER_ANALYSIS", "incomplete order ignored")
            return false
        }

        val signature = "${order.price}-${order.minutes}-${order.distance}-${order.deliveryCount}-${order.isExclusive}"
        val now = System.currentTimeMillis()
        if (signature == lastShownOrderSignature && now - lastShownOrderTime < 30_000) {
            Log.d("ORDER_ANALYSIS", "duplicate order ignored")
            return true
        }

        lastShownOrderSignature = signature
        lastShownOrderTime = now

        val analysis = OrderAnalyzer.analyzeResult(this, order)
        OrderHistory.add(this, analysis, "实时")

        Log.d("ORDER_ANALYSIS", OrderAnalyzer.analyze(order))

        val shownByAccessibility = MyAccessibilityService.showFeedback(analysis)
        if (!shownByAccessibility) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    this,
                    "${analysis.score}分 · ${analysis.recommendation}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        return true
    }

    private fun stopCaptureSession() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        CaptureTrigger.shouldCapture = false
        CaptureTrigger.pendingCaptureCount = 0
    }

    private fun stopCapture() {
        stopCaptureSession()
        if (projectionCallbackRegistered) {
            mediaProjection?.unregisterCallback(projectionCallback)
            projectionCallbackRegistered = false
        }
        mediaProjection = null
        isProcessing = false
    }

    private fun clearProjectionPermission() {
        CaptureHolder.clear()
        CaptureTrigger.shouldCapture = false
        CaptureTrigger.pendingCaptureCount = 0
    }

    private fun createNotification(): Notification {

        val channelId = "capture"

        val channel = NotificationChannel(
            channelId,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        )

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager

        manager.createNotificationChannel(channel)

        return Notification.Builder(this, channelId)
            .setContentTitle("功德拒絕器運行中")
            .setContentText("后台实时监测 目標平台 订单")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        isRunning = false
        stopCapture()
        MyAccessibilityService.refreshStatusOverlay()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        MonitoringState.setEnabled(this, false)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        const val ACTION_CAPTURE_PULSE = "com.example.gongderefuser.CAPTURE_PULSE"

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
