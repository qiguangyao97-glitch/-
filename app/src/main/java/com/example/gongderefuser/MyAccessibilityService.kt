package com.example.gongderefuser

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.os.Build
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.gongderefuser.analyzer.OrderAnalyzer
import com.example.gongderefuser.analyzer.OrderAnalyzer.AnalysisResult
import com.example.gongderefuser.analyzer.RuleSettings
import com.example.gongderefuser.model.OrderData
import com.example.gongderefuser.parser.OrderParser
import java.io.FileOutputStream
import java.util.ArrayDeque

class MyAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var collapseRunnable: Runnable? = null
    private var isTakingScreenshot = false
    private var isProcessingOrder = false
    private var lastShownOrderSignature: String = ""
    private var lastShownOrderTime: Long = 0L
    private var lastCoreOrderSignature: String = ""
    private var lastCoreOrderTimestamp: Long = 0L
    private var lastOcrAttemptTime: Long = 0L
    private var screenshotTimeoutRunnable: Runnable? = null
    private var ocrTimeoutRunnable: Runnable? = null
    private var sessionTimeoutRunnable: Runnable? = null
    private var secondCheckSignature: String = ""
    private var lastPendingOrder: OrderData? = null
    private var pendingOrderEventRunnable: Runnable? = null
    private var consecutiveNoOrderCount: Int = 0
    private var currentOverlayOrderLabel: String = ""
    private var isDetectionScheduled: Boolean = false
    private var pendingDetectionAfterCurrent: Boolean = false
    private var currentBurstDetectCount: Int = 0
    private var lastDetectionEventTime: Long = 0L
    private var lastScreenshotRequestTime: Long = 0L
    private var popupCandidateUntilTime: Long = 0L
    private var isOrderDetectionSessionActive: Boolean = false
    private var orderDetectionSessionId: Long = 0L
    private var orderDetectionSessionStartTime: Long = 0L
    private var orderDetectionSessionNextAttemptIndex: Int = 0
    private var orderDetectionSessionFailureCount: Int = 0
    private var orderDetectionSessionLastHadAnchor: Boolean = false
    private var currentOcrAttemptNumber: Int = 0
    private var currentOcrAttemptElapsedMs: Long = 0L
    private var lastPopupStructureScanTime: Long = 0L
    private var lastPopupCandidateLogTime: Long = 0L
    private var currentSessionEventType: String = ""
    private var currentSessionPackageName: String = ""
    private var currentSessionClassName: String = ""
    private var currentSessionTriggerReason: String = ""
    private var currentSessionNoOrderStructureLogged: Boolean = false
    private var currentSessionOcrSuccessStructureLogged: Boolean = false
    private var lastEventStructureSnapshot: EventStructureSnapshot? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        syncForegroundNotification()
        showStatusOverlayInternal()
        DiagnosticLogStore.append(this, "ACCESSIBILITY", "connected package=$packageName")
        Log.d("TARGET_SERVICE", "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!MonitoringState.isEnabled(this)) return
        if (!isActivationActiveForMonitoring()) return

        val packageName = event?.packageName?.toString() ?: return
        logUberWindowDebug(event, packageName, "EVENT_RECEIVED")
        if (packageName != "com.ubercab.driver") return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            logTargetClickEvent(event, packageName)
        }
        val now = System.currentTimeMillis()

        if (AppSettings.isAccessibilityLogEnabled(this)) {
            Log.d(
                "TARGET_EVENT",
                "type=${event.eventType} class=${event.className} text=${event.text} desc=${event.contentDescription}"
            )
        }
        lastTargetEventSummary = "type=${event.eventType} class=${event.className} time=$now"
        lastEventStructureSnapshot = buildEventStructureSnapshot(event, packageName)
        logEventSourceBounds(event, packageName)

        Log.i("ORDER_EVENT_RECEIVED", "type=${event.eventType} class=${event.className} time=$now")
        DiagnosticLogStore.append(this, "ORDER_EVENT_RECEIVED", "type=${event.eventType} class=${event.className} time=$now")
        logUberWindowDebug(event, packageName, "AFTER_ORDER_EVENT_RECEIVED")
        val popupScan = logPopupStructureScan(event, packageName, now)
        resetStaleSessionIfNeeded(now)
        scheduleEventDrivenCapture(event, now, popupScan)
    }

    private fun scheduleEventDrivenCapture(event: AccessibilityEvent, now: Long, popupScan: PopupStructureScan?) {
        val triggerDecision = shouldTriggerOrderDetection(event, now, popupScan)
        if (!triggerDecision.shouldTrigger) {
            logOrderTriggerRejected(event, triggerDecision, popupScan)
            logSessionDecision(event, "REJECTED", triggerDecision.reason)
            logObservation(
                "ORDER_DETECTION_WAIT",
                "ignoredEvent reason=${triggerDecision.reason} type=${eventTypeName(event.eventType)} class=${event.className}"
            )
            return
        }
        logOrderTriggerCandidate(event, triggerDecision)
        if (now - lastDetectionEventTime > DETECTION_BURST_RESET_MS) {
            currentBurstDetectCount = 0
            pendingDetectionAfterCurrent = false
        }
        lastDetectionEventTime = now

        if (isDetectionScheduled || isTakingScreenshot || isProcessingOrder) {
            pendingDetectionAfterCurrent = true
            logSessionDecision(event, "SESSION_ALREADY_RUNNING", triggerDecision.reason)
            logObservation(
                "ORDER_DETECTION_WAIT",
                "pendingAfterCurrent=true reason=${triggerDecision.reason} count=$currentBurstDetectCount type=${eventTypeName(event.eventType)} class=${event.className}"
            )
            return
        }
        if (currentBurstDetectCount >= maxDetectionsForCurrentWindow()) {
            logOrderTriggerRejected(event, triggerDecision.copy(reason = "BURST_LIMIT_${triggerDecision.reason}"), popupScan)
            logSessionDecision(event, "IGNORED", "BURST_LIMIT_${triggerDecision.reason}")
            logObservation(
                "ORDER_DETECTION_WAIT",
                "skipBurstLimit count=$currentBurstDetectCount type=${eventTypeName(event.eventType)} class=${event.className}"
            )
            return
        }
        startOrderDetectionSession(now, event, triggerDecision)
    }

    private fun startOrderDetectionSession(
        now: Long,
        event: AccessibilityEvent,
        triggerDecision: TriggerDecision
    ) {
        if (isOrderDetectionSessionActive) {
            pendingDetectionAfterCurrent = true
            logObservation("ORDER_DETECTION_WAIT", "pendingAfterCurrent=true reason=SESSION_ACTIVE")
            logSessionDecision(event, "SESSION_ALREADY_RUNNING", "SESSION_ACTIVE")
            return
        }
        logEventStructure("BEFORE_SESSION_START", buildEventStructureSnapshot(event, event.packageName?.toString().orEmpty()))
        logUberWindowDebug(event, event.packageName?.toString().orEmpty(), "BEFORE_SESSION_START")
        isOrderDetectionSessionActive = true
        orderDetectionSessionId += 1
        orderDetectionSessionStartTime = now
        orderDetectionSessionNextAttemptIndex = 0
        orderDetectionSessionFailureCount = 0
        orderDetectionSessionLastHadAnchor = false
        currentSessionNoOrderStructureLogged = false
        currentSessionOcrSuccessStructureLogged = false
        currentSessionEventType = eventTypeName(event.eventType)
        currentSessionPackageName = event.packageName?.toString().orEmpty()
        currentSessionClassName = event.className?.toString().orEmpty()
        currentSessionTriggerReason = triggerDecision.reason
        DiagnosticLogStore.append(
            this,
            "ORDER_SESSION_START",
            "sessionId=$orderDetectionSessionId reason=${triggerDecision.reason} eventType=$currentSessionEventType packageName=$currentSessionPackageName className=$currentSessionClassName"
        )
        logSessionDecision(event, "START_SESSION", triggerDecision.reason)
        armSessionTimeout(orderDetectionSessionId)
        scheduleNextSessionCapture()
    }

    private fun scheduleNextSessionCapture() {
        if (!isOrderDetectionSessionActive) return
        if (orderDetectionSessionNextAttemptIndex >= MAX_ORDER_DETECTION_ATTEMPTS) return
        if (isDetectionScheduled) return
        val attemptIndex = orderDetectionSessionNextAttemptIndex
        val delayMs = if (attemptIndex == 0) FIRST_OCR_ATTEMPT_DELAY_MS else OCR_SESSION_RETRY_DELAY_MS
        isDetectionScheduled = true
        pendingOrderEventRunnable = Runnable {
            pendingOrderEventRunnable = null
            isDetectionScheduled = false
            val attemptNumber = attemptIndex + 1
            orderDetectionSessionNextAttemptIndex = maxOf(orderDetectionSessionNextAttemptIndex, attemptNumber)
            triggerCapture(attemptNumber)
        }
        logObservation(
            "ORDER_DETECTION_WAIT",
            "scheduled delayMs=$delayMs attempt=${attemptIndex + 1}/$MAX_ORDER_DETECTION_ATTEMPTS count=${currentBurstDetectCount + 1}"
        )
        mainHandler.postDelayed(pendingOrderEventRunnable!!, delayMs)
    }

    private fun triggerCapture(sessionAttemptNumber: Int = 0, forceScreenshotInterval: Boolean = false) {
        if (!MonitoringState.isEnabled(this)) return
        if (!isActivationActiveForMonitoring()) return
        val now = System.currentTimeMillis()
        val waitMs = MIN_SCREENSHOT_INTERVAL_MS - (now - lastScreenshotRequestTime)
        if (!forceScreenshotInterval && !isOrderDetectionSessionActive && waitMs > 0) {
            logObservation("ORDER_DETECTION_WAIT", "reschedule reason=SCREENSHOT_INTERVAL waitMs=$waitMs")
            scheduleNextSessionCapture()
            return
        }
        currentBurstDetectCount += 1
        lastOcrAttemptTime = now
        lastScreenshotRequestTime = now
        currentOcrAttemptNumber = sessionAttemptNumber.coerceAtLeast(1)
        currentOcrAttemptElapsedMs = eventElapsedMs(now)
        logOcrAttempt(currentOcrAttemptNumber, currentOcrAttemptElapsedMs)

        Log.d("TARGET_TRIGGER", "set capture flag")
        Log.i("ORDER_ANALYSIS_START", "source=accessibility_event attempt=$sessionAttemptNumber")
        DiagnosticLogStore.append(this, "ORDER_ANALYSIS_START", "source=accessibility_event attempt=$sessionAttemptNumber")
        tryAccessibilityScreenshot(sessionId = orderDetectionSessionId)
    }

    private fun reportAccessibilityScreenshotFailure(reason: String) {
        DiagnosticLogStore.append(this, "A11Y_SCREENSHOT", "failed_no_fallback reason=$reason")
        Toast.makeText(this, "無障礙截圖失敗：$reason", Toast.LENGTH_SHORT).show()
    }

    private fun logScreenshotFailureDetail(reason: String, errorCode: String = "") {
        DiagnosticLogStore.append(
            this,
            "SCREENSHOT_FAILURE_DETAIL",
            "sessionId=$orderDetectionSessionId attempt=$currentOcrAttemptNumber elapsedMs=${eventElapsedMs()} " +
                    "eventType=$currentSessionEventType packageName=$currentSessionPackageName className=$currentSessionClassName " +
                    "errorCode=$errorCode reason=$reason"
        )
    }

    private fun tryAccessibilityScreenshot(isSecondCheck: Boolean = false, sessionId: Long = orderDetectionSessionId): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            logScreenshotFailureDetail("SDK_TOO_LOW", "sdk_${Build.VERSION.SDK_INT}")
            reportAccessibilityScreenshotFailure("sdk_${Build.VERSION.SDK_INT}")
            if (!isSecondCheck) resetAnalysisSession("SCREENSHOT_FAILURE")
            return false
        }
        if (isTakingScreenshot) {
            Log.d("A11Y_SCREENSHOT", "skip, screenshot already running")
            DiagnosticLogStore.append(this, "A11Y_SCREENSHOT", "skip_already_running")
            return true
        }
        isTakingScreenshot = true
        DiagnosticLogStore.append(this, "A11Y_SCREENSHOT", "request secondCheckRunning=$isSecondCheck lastEvent=$lastTargetEventSummary")
        armScreenshotTimeout()
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        clearScreenshotTimeout()
                        if (!isSecondCheck && !isCurrentSession(sessionId)) {
                            closeStaleScreenshot(screenshot)
                            return
                        }
                        isTakingScreenshot = false
                        val bitmap = screenshot.toBitmap()
                        if (bitmap == null) {
                            logScreenshotFailureDetail("BITMAP_NULL", "bitmap_null")
                            reportAccessibilityScreenshotFailure("bitmap_null")
                            if (!isSecondCheck) resetAnalysisSession("SCREENSHOT_FAILURE")
                            return
                        }
                        DiagnosticLogStore.append(this@MyAccessibilityService, "A11Y_SCREENSHOT", "success ${bitmap.width}x${bitmap.height}")
                        processAccessibilityOrderBitmap(bitmap, isSecondCheck)
                    }

                    override fun onFailure(errorCode: Int) {
                        clearScreenshotTimeout()
                        if (!isSecondCheck && !isCurrentSession(sessionId)) return
                        isTakingScreenshot = false
                        Log.d("A11Y_SCREENSHOT", "failed code=$errorCode")
                        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                            logScreenshotFailureDetail("INTERVAL_TOO_SHORT", errorCode.toString())
                            DiagnosticLogStore.append(this@MyAccessibilityService, "A11Y_SCREENSHOT", "failed_interval_too_short_no_toast code=$errorCode")
                            if (!isSecondCheck) resetAnalysisSession("SCREENSHOT_FAILURE")
                            return
                        }
                        logScreenshotFailureDetail("TAKE_SCREENSHOT_FAILURE", errorCode.toString())
                        reportAccessibilityScreenshotFailure("error_$errorCode")
                        if (!isSecondCheck) resetAnalysisSession("SCREENSHOT_FAILURE")
                    }
                }
            )
        } catch (throwable: Throwable) {
            clearScreenshotTimeout()
            isTakingScreenshot = false
            logScreenshotFailureDetail("EXCEPTION_${throwable.javaClass.simpleName}", throwable.message.orEmpty())
            DiagnosticLogStore.append(this, "A11Y_SCREENSHOT", "exception=${throwable.javaClass.simpleName}:${throwable.message.orEmpty()}")
            if (!isSecondCheck) resetAnalysisSession("SCREENSHOT_FAILURE")
            return false
        }
        return true
    }

    private fun armScreenshotTimeout() {
        clearScreenshotTimeout()
        screenshotTimeoutRunnable = Runnable {
            if (isTakingScreenshot) {
                isTakingScreenshot = false
                logScreenshotFailureDetail("TIMEOUT", "timeout")
                DiagnosticLogStore.append(this, "A11Y_SCREENSHOT", "timeout_reset")
                Log.d("A11Y_SCREENSHOT", "timeout reset")
                resetAnalysisSession("SCREENSHOT_FAILURE")
            }
        }.also {
            mainHandler.postDelayed(it, SCREENSHOT_TIMEOUT_MS)
        }
    }

    private fun clearScreenshotTimeout() {
        screenshotTimeoutRunnable?.let(mainHandler::removeCallbacks)
        screenshotTimeoutRunnable = null
    }

    private fun processAccessibilityOrderBitmap(bitmap: Bitmap, isSecondCheck: Boolean = false) {
        if (!MonitoringState.isEnabled(this)) {
            bitmap.recycle()
            return
        }
        if (!isActivationActiveForMonitoring()) {
            bitmap.recycle()
            return
        }
        if (isProcessingOrder) {
            DiagnosticLogStore.append(this, "CAPTURE", "skip_processing_busy")
            bitmap.recycle()
            return
        }

        isProcessingOrder = true
        val ocrStartMs = System.currentTimeMillis()
        armOcrTimeout()
        DiagnosticLogStore.append(this, "CAPTURE", "process source=accessibility ${bitmap.width}x${bitmap.height}")
        saveOcrAttemptScreenshot(bitmap, isSecondCheck)
        logObservation("OCR_START", "source=accessibility width=${bitmap.width} height=${bitmap.height} secondCheck=$isSecondCheck")
        OcrHelper.runOrderRegions(this, bitmap) { regionText ->
            runCatching {
                Log.d("OCR_RESULT", regionText.fullText)
                val ocrTimeMs = System.currentTimeMillis() - ocrStartMs
                val cropRect = regionText.anchorDebugInfo.cardRect
                logObservation(
                    "OCR_FINISH",
                    "OCR_TIME_MS=$ocrTimeMs hasAnchoredCard=${regionText.hasAnchoredCard} textLength=${regionText.fullText.length} " +
                            "OCR_TEXT_LENGTH=${regionText.fullText.length} ANCHOR_FOUND=${regionText.hasAnchoredCard} " +
                            "CROP_WIDTH=${cropRect?.width() ?: 0} CROP_HEIGHT=${cropRect?.height() ?: 0}"
                )
                if (!regionText.hasAnchoredCard) {
                    DiagnosticLogStore.append(this, "CAPTURE", "skip_no_anchor source=accessibility")
                }
                val order = if (regionText.hasAnchoredCard) OrderParser.parse(
                    OrderParser.RegionInput(
                        fullText = regionText.fullText,
                        cardText = regionText.cardText,
                        typeText = regionText.typeText,
                        priceText = regionText.priceText,
                        tripText = regionText.tripText,
                        detailText = regionText.detailText,
                        sameDropoffText = regionText.sameDropoffText,
                        merchantText = regionText.merchantText,
                        merchantWideText = regionText.merchantWideText,
                        merchantAddressBlockText = regionText.merchantAddressBlockText,
                        addressText = regionText.addressText,
                        addressWideText = regionText.addressWideText,
                        addressLowerText = regionText.addressLowerText
                    )
                ) else null
                val foundOrder = handleAccessibilityOrder(
                    order = order,
                    bitmap = bitmap,
                    isSecondCheck = isSecondCheck,
                    tripText = regionText.tripText,
                    hasAnchoredCard = regionText.hasAnchoredCard,
                    textLength = regionText.fullText.length,
                    cropWidth = cropRect?.width() ?: 0,
                    cropHeight = cropRect?.height() ?: 0
                )
                if (!isSecondCheck) {
                    DebugSampleStore.saveCapture(this, bitmap, regionText, foundOrder)
                }
                if (!foundOrder) {
                    DiagnosticLogStore.append(this, "CAPTURE", "no_order source=accessibility")
                    if (isSecondCheck) {
                        DiagnosticLogStore.append(this, "SECOND_CHECK", "secondCheckIgnoredReason=no_order")
                        logObservation("OCR_RETRY", "secondCheckIgnoredReason=no_order")
                    }
                }
                finishDetectionCycle(foundOrder, isSecondCheck, regionText.hasAnchoredCard)
            }.onFailure { throwable ->
                val ocrTimeMs = System.currentTimeMillis() - ocrStartMs
                logObservation("OCR_FAILED", "OCR_TIME_MS=$ocrTimeMs error=${throwable.javaClass.simpleName}:${throwable.message.orEmpty()}")
                Log.e("CAPTURE", "accessibility OCR callback failed", throwable)
                CrashLogStore.save(this, "accessibility_ocr_callback", throwable)
                finishDetectionCycle(foundOrder = false, isSecondCheck = isSecondCheck, hadOrderAnchor = false)
            }
            clearOcrTimeout()
            isProcessingOrder = false
            bitmap.recycle()
        }
    }

    private fun isActivationActiveForMonitoring(): Boolean {
        if (ActivationLocalStore.isLocalActive(this)) return true
        ActivationLocalStore.clearActivationIfNeeded(this)
        Log.d("ACTIVATION", "Not active, skip monitoring")
        DiagnosticLogStore.append(this, "ACTIVATION", "skip_monitoring expiresAt=${ActivationLocalStore.getExpiresAtMillis(this)}")
        if (MonitoringState.isEnabled(this)) {
            MonitoringState.setEnabled(this, false)
        }
        mainHandler.post {
            Toast.makeText(this, "啟用已過期，請輸入新的啟用碼", Toast.LENGTH_LONG).show()
        }
        return false
    }

    private fun finishDetectionCycle(foundOrder: Boolean, isSecondCheck: Boolean, hadOrderAnchor: Boolean) {
        if (isSecondCheck) return
        if (foundOrder) {
            endOrderDetectionSession("OCR_SUCCESS")
            logObservation("ORDER_DETECTION_WAIT", "state=WAITING reason=ORDER_HANDLED")
            return
        }
        if (isOrderDetectionSessionActive) {
            orderDetectionSessionFailureCount += 1
            orderDetectionSessionLastHadAnchor = hadOrderAnchor
            if (orderDetectionSessionNextAttemptIndex < MAX_ORDER_DETECTION_ATTEMPTS) {
                logObservation(
                    "OCR_RETRY",
                    "reason=SESSION_RETRY delayMs=$OCR_SESSION_RETRY_DELAY_MS attemptFailed=$orderDetectionSessionFailureCount hasAnchoredCard=$hadOrderAnchor"
                )
                scheduleNextSessionCapture()
                return
            }
            logOcrFinalFail(currentOcrAttemptNumber, eventElapsedMs(), "MAX_ATTEMPTS_REACHED")
            logObservation(
                "ORDER_DETECTION_WAIT",
                "state=WAITING reason=OCR_FAIL failures=$orderDetectionSessionFailureCount"
            )
            endOrderDetectionSession("OCR_FAIL")
            return
        }
        if (pendingDetectionAfterCurrent && currentBurstDetectCount < maxDetectionsForCurrentWindow()) {
            pendingDetectionAfterCurrent = false
            logObservation("ORDER_DETECTION_WAIT", "dropPending reason=PENDING_EVENT_REQUIRES_FRESH_GATE")
        }
        pendingDetectionAfterCurrent = false
        logObservation("ORDER_DETECTION_WAIT", "state=WAITING reason=NO_ORDER")
    }

    private fun armOcrTimeout() {
        clearOcrTimeout()
        ocrTimeoutRunnable = Runnable {
            if (isProcessingOrder) {
                isProcessingOrder = false
                DiagnosticLogStore.append(this, "CAPTURE", "ocr_timeout_reset")
                Log.d("CAPTURE", "OCR timeout reset")
                resetAnalysisSession("TIMEOUT")
            }
        }.also {
            mainHandler.postDelayed(it, OCR_TIMEOUT_MS)
        }
    }

    private fun clearOcrTimeout() {
        ocrTimeoutRunnable?.let(mainHandler::removeCallbacks)
        ocrTimeoutRunnable = null
    }

    private fun armSessionTimeout(sessionId: Long) {
        clearSessionTimeout()
        sessionTimeoutRunnable = Runnable {
            if (isCurrentSession(sessionId) && isSessionTimedOut()) {
                resetAnalysisSession("SESSION_TIMEOUT")
            }
        }.also {
            mainHandler.postDelayed(it, MAX_SESSION_DURATION_MS)
        }
    }

    private fun clearSessionTimeout() {
        sessionTimeoutRunnable?.let(mainHandler::removeCallbacks)
        sessionTimeoutRunnable = null
    }

    private fun isCurrentSession(sessionId: Long): Boolean {
        return isOrderDetectionSessionActive && sessionId == orderDetectionSessionId
    }

    private fun isSessionTimedOut(now: Long = System.currentTimeMillis()): Boolean {
        return isOrderDetectionSessionActive &&
                orderDetectionSessionStartTime > 0L &&
                now - orderDetectionSessionStartTime >= MAX_SESSION_DURATION_MS
    }

    private fun resetStaleSessionIfNeeded(now: Long): Boolean {
        if (!isSessionTimedOut(now)) return false
        resetAnalysisSession("STALE_SESSION")
        return true
    }

    private fun endOrderDetectionSession(reason: String) {
        if (isOrderDetectionSessionActive) {
            DiagnosticLogStore.append(this, "ORDER_SESSION_END", "reason=$reason sessionId=$orderDetectionSessionId")
            DiagnosticLogStore.append(this, "SESSION_END", "reason=$reason sessionId=$orderDetectionSessionId")
        }
        clearSessionTimeout()
        pendingOrderEventRunnable?.let(mainHandler::removeCallbacks)
        pendingOrderEventRunnable = null
        isDetectionScheduled = false
        isOrderDetectionSessionActive = false
        orderDetectionSessionNextAttemptIndex = 0
        orderDetectionSessionFailureCount = 0
        orderDetectionSessionLastHadAnchor = false
        currentSessionNoOrderStructureLogged = false
        currentSessionOcrSuccessStructureLogged = false
        pendingDetectionAfterCurrent = false
    }

    private fun resetAnalysisSession(reason: String) {
        val sessionId = orderDetectionSessionId
        DiagnosticLogStore.append(this, "ORDER_SESSION_FORCE_RESET", "reason=$reason sessionId=$sessionId")
        Log.i("ORDER_SESSION_FORCE_RESET", "reason=$reason sessionId=$sessionId")
        clearSessionTimeout()
        pendingOrderEventRunnable?.let(mainHandler::removeCallbacks)
        pendingOrderEventRunnable = null
        clearScreenshotTimeout()
        clearOcrTimeout()
        isDetectionScheduled = false
        isTakingScreenshot = false
        isProcessingOrder = false
        isOrderDetectionSessionActive = false
        orderDetectionSessionStartTime = 0L
        orderDetectionSessionNextAttemptIndex = 0
        orderDetectionSessionFailureCount = 0
        orderDetectionSessionLastHadAnchor = false
        currentSessionNoOrderStructureLogged = false
        currentSessionOcrSuccessStructureLogged = false
        pendingDetectionAfterCurrent = false
        orderDetectionSessionId += 1
        DiagnosticLogStore.append(this, "ORDER_SESSION_END", "reason=$reason sessionId=$sessionId")
        DiagnosticLogStore.append(this, "SESSION_END", "reason=$reason sessionId=$sessionId")
    }

    private fun handleAccessibilityOrder(
        order: com.example.gongderefuser.model.OrderData?,
        bitmap: Bitmap?,
        isSecondCheck: Boolean = false,
        tripText: String = "",
        hasAnchoredCard: Boolean = true,
        textLength: Int = 0,
        cropWidth: Int = 0,
        cropHeight: Int = 0
    ): Boolean {
        val gate = OrderResultGate.evaluate(order, hasAnchoredCard = hasAnchoredCard, tripText = tripText)
        Log.i("ORDER_POPUP_VALIDATION", gate.debugLog)
        DiagnosticLogStore.append(this, "ORDER_POPUP_VALIDATION", gate.debugLog)
        if (!gate.shouldShow) {
            val finalSkipReason = gate.skipResultReason
            logOcrFail(currentOcrAttemptNumber, eventElapsedMs(), finalSkipReason)
            Log.d("ORDER_ANALYSIS", "invalid order ignored $finalSkipReason")
            DiagnosticLogStore.append(this, "CAPTURE", "skipResultReason=$finalSkipReason")
            Log.i("ORDER_ANALYSIS_FINISH", "shown=false reason=$finalSkipReason")
            DiagnosticLogStore.append(this, "ORDER_ANALYSIS_FINISH", "shown=false reason=$finalSkipReason")
            if (finalSkipReason == "OCR_MONEY_INVALID") {
                Log.i("OCR_MONEY_INVALID", "price=${order?.price ?: 0} status=${order?.priceStatus ?: "MISSING"}")
                DiagnosticLogStore.append(this, "OCR_MONEY_INVALID", "price=${order?.price ?: 0} status=${order?.priceStatus ?: "MISSING"}")
            }
            if (finalSkipReason == "NO_ORDER_CARD" && !currentSessionNoOrderStructureLogged) {
                logEventStructure("NO_ORDER_CARD", lastEventStructureSnapshot)
                currentSessionNoOrderStructureLogged = true
            }
            handleInvalidOrderLifecycle(finalSkipReason, hasAnchoredCard, textLength)
            return false
        }
        val validOrder = order ?: return false
        consecutiveNoOrderCount = 0

        val signature = orderSignature(validOrder)
        val coreSignature = coreOrderSignature(validOrder)
        val now = System.currentTimeMillis()
        val ocrSuccessAttempt = currentOcrAttemptNumber
        val ocrSuccessElapsedMs = eventElapsedMs(now)
        val coreSignatureAgeMs = now - lastCoreOrderTimestamp
        if (
            lastCoreOrderSignature == coreSignature &&
            coreSignatureAgeMs in 0..DUPLICATE_CORE_ORDER_WINDOW_MS
        ) {
            lastPendingOrder = validOrder
            val message = "signature=$coreSignature ageMs=$coreSignatureAgeMs reason=SAME_CORE_ORDER"
            Log.i("DUPLICATE_ORDER_SUPPRESSED", message)
            DiagnosticLogStore.append(this, "DUPLICATE_ORDER_SUPPRESSED", message)
            logCurrentSessionDecision("DUPLICATE", "SAME_CORE_ORDER")
            return true
        }
        if (isSecondCheck) {
            DiagnosticLogStore.append(this, "SECOND_CHECK", "secondCheckRunning=true")
            if (signature == secondCheckSignature) {
                DiagnosticLogStore.append(this, "SECOND_CHECK", "secondCheckChanged=false secondCheckIgnoredReason=same_result")
                return true
            }
            val analysis = OrderAnalyzer.analyzeResult(this, validOrder)
            DiagnosticLogStore.append(this, "SECOND_CHECK", "secondCheckChanged=true")
            lastShownOrderSignature = signature
            lastShownOrderTime = now
            secondCheckSignature = signature
            val newOrderLabel = orderLabel(validOrder)
            logObservation("ORDER_UPDATED", orderDetails(validOrder))
            mainHandler.post {
                logPopupShowOrReplace(analysis, newOrderLabel)
                showAnalysisOverlayInternal(analysis, playTone = false)
                currentOverlayOrderLabel = newOrderLabel
            }
            return true
        }
        if (signature == lastShownOrderSignature && now - lastShownOrderTime < 30_000) {
            Log.d("ORDER_ANALYSIS", "duplicate order ignored")
            DiagnosticLogStore.append(this, "CAPTURE", "skipResultReason=DUPLICATE_RESULT")
            Log.i("ORDER_ANALYSIS_FINISH", "shown=false reason=DUPLICATE_RESULT")
            DiagnosticLogStore.append(this, "ORDER_ANALYSIS_FINISH", "shown=false reason=DUPLICATE_RESULT")
            logCurrentSessionDecision("DUPLICATE", "DUPLICATE_RESULT")
            return false
        }
        if (!currentSessionOcrSuccessStructureLogged) {
            logEventStructure("OCR_SUCCESS", lastEventStructureSnapshot)
            currentSessionOcrSuccessStructureLogged = true
        }
        logOcrSuccess(ocrSuccessAttempt, ocrSuccessElapsedMs)
        if (lastShownOrderSignature.isNotBlank() && signature != lastShownOrderSignature) {
            logObservation(
                "ORDER_REPLACED",
                "old=${currentOverlayOrderLabel.ifBlank { lastShownOrderSignature }} new=${orderLabel(validOrder)}"
            )
        } else {
            logObservation("ORDER_DETECTED", orderDetails(validOrder))
        }

        lastShownOrderSignature = signature
        lastShownOrderTime = now
        lastCoreOrderSignature = coreSignature
        lastCoreOrderTimestamp = now

        lastPendingOrder = validOrder
        val analysis = OrderAnalyzer.analyzeResult(this, validOrder)
        OrderHistory.add(this, analysis, "即時", "")

        Log.d("ORDER_ANALYSIS", OrderAnalyzer.analyze(this, validOrder))
        mainHandler.post {
            val newOrderLabel = orderLabel(validOrder)
            logPopupShowOrReplace(analysis, newOrderLabel)
            showAnalysisOverlayInternal(analysis)
            logOrderLatency(eventElapsedMs(), ocrSuccessElapsedMs, ocrSuccessAttempt)
            currentOverlayOrderLabel = newOrderLabel
        }
        Log.i("ORDER_ANALYSIS_FINISH", "shown=true signature=$signature")
        DiagnosticLogStore.append(this, "ORDER_ANALYSIS_FINISH", "shown=true signature=$signature")
        return true
    }

    private fun handleInvalidOrderLifecycle(reason: String, hasAnchoredCard: Boolean, textLength: Int) {
        if (isOrderDetectionSessionActive) return
        val isAnchoredBlankOrEmptyCore = hasAnchoredCard && (textLength == 0 || reason == "ALL_CORE_FIELDS_EMPTY")
        if (isAnchoredBlankOrEmptyCore) {
            return
        }
        if (reason == "NO_ORDER_CARD" || reason == "ALL_CORE_FIELDS_EMPTY") {
            if (lastShownOrderSignature.isBlank() && overlayView == null) {
                consecutiveNoOrderCount = 0
                return
            }
            consecutiveNoOrderCount += 1
            if (consecutiveNoOrderCount >= ORDER_END_INVALID_COUNT) {
                Log.i("ORDER_SESSION_END", "reason=$reason")
                DiagnosticLogStore.append(this, "ORDER_SESSION_END", "reason=$reason")
                logObservation("ORDER_LOST", "reason=$reason")
                lastShownOrderSignature = ""
                secondCheckSignature = ""
                lastPendingOrder = null
                mainHandler.post {
                    logObservation("POPUP_HIDE", "reason=ORDER_LOST")
                    hideOverlay()
                }
            }
        } else {
            consecutiveNoOrderCount = 0
        }
    }

    private fun endOrderSessionAsLost(reason: String) {
        endOrderDetectionSession(reason)
        if (lastShownOrderSignature.isBlank() && overlayView == null) {
            logObservation("ORDER_DETECTION_WAIT", "state=WAITING reason=$reason")
            return
        }
        logObservation("ORDER_LOST", "reason=$reason")
        lastShownOrderSignature = ""
        secondCheckSignature = ""
        lastPendingOrder = null
        mainHandler.post {
            logObservation("POPUP_HIDE", "reason=ORDER_LOST")
            hideOverlay()
        }
    }

    private fun shouldTriggerOrderDetection(
        event: AccessibilityEvent,
        now: Long,
        popupScan: PopupStructureScan?
    ): TriggerDecision {
        return when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> popupCandidateDecision(event, now, popupScan)
            else -> TriggerDecision(false, "UNSUPPORTED_EVENT")
        }
    }

    private fun popupCandidateDecision(
        event: AccessibilityEvent,
        now: Long,
        popupScan: PopupStructureScan?
    ): TriggerDecision {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return TriggerDecision(false, "USER_CLICK_EVENT")
        }
        val scan = popupScan ?: rootInActiveWindow?.let(::inspectRootForPopupStructure)
            ?: return TriggerDecision(false, "NO_ROOT_FOR_POPUP_SCAN")
        if (scan.isOpportunityPage) {
            return popupTriggerDecision(false, "OPPORTUNITY_PAGE", scan)
        }
        if (scan.isMainNavPage) {
            return popupTriggerDecision(false, "MAIN_NAV_PAGE", scan)
        }
        if (scan.isLikelyBlockingPopup) {
            popupCandidateUntilTime = now + POPUP_CANDIDATE_WINDOW_MS
            return popupTriggerDecision(true, "POPUP_SCAN_BLOCKING", scan)
        }
        popupCandidateUntilTime = now + POPUP_CANDIDATE_WINDOW_MS
        logTriggerGateBypass("UBER_EVENT_DIRECT_OCR", scan)
        return popupTriggerDecision(true, "UBER_EVENT_FALLBACK_OCR", scan)
    }

    private fun popupTriggerDecision(
        shouldTrigger: Boolean,
        reason: String,
        scan: PopupStructureScan
    ): TriggerDecision {
        return TriggerDecision(
            shouldTrigger = shouldTrigger,
            reason = reason,
            score = scan.score,
            likelyBlockingPopup = scan.isLikelyBlockingPopup,
            features = scan.featureSummary(),
            samples = scan.samples.joinToString(" | ")
        )
    }

    private fun maxDetectionsForCurrentWindow(): Int {
        return if (System.currentTimeMillis() <= popupCandidateUntilTime) {
            MAX_DETECTIONS_PER_POPUP_CANDIDATE
        } else {
            MAX_DETECTIONS_PER_BURST
        }
    }

    private fun logObservation(tag: String, message: String) {
        Log.i(tag, message)
        DiagnosticLogStore.append(this, tag, message)
    }

    private fun logSessionDecision(event: AccessibilityEvent, decision: String, reason: String) {
        DiagnosticLogStore.append(
            this,
            "SESSION_DECISION",
            "eventType=${eventTypeName(event.eventType)} className=${event.className?.toString().orEmpty()} " +
                    "decision=$decision reason=$reason sessionId=$orderDetectionSessionId"
        )
    }

    private fun logCurrentSessionDecision(decision: String, reason: String) {
        DiagnosticLogStore.append(
            this,
            "SESSION_DECISION",
            "eventType=$currentSessionEventType className=$currentSessionClassName " +
                    "decision=$decision reason=$reason sessionId=$orderDetectionSessionId"
        )
    }

    private fun logTriggerGateBypass(reason: String, scan: PopupStructureScan) {
        val message = "reason=$reason score=${scan.score} likelyBlockingPopup=${scan.isLikelyBlockingPopup} features=${scan.featureSummary()} samples=${scan.samples.joinToString(" | ")}"
        Log.i("TRIGGER_GATE_BYPASS", message)
        DiagnosticLogStore.append(this, "TRIGGER_GATE_BYPASS", message)
    }

    private fun logTargetClickEvent(event: AccessibilityEvent, packageName: String) {
        val viewId = runCatching {
            event.source?.viewIdResourceName.orEmpty()
        }.getOrDefault("")
        val message = buildString {
            append("eventType=")
            append(eventTypeName(event.eventType))
            append(" package=")
            append(packageName)
            append(" class=")
            append(event.className?.toString().orEmpty())
            append(" viewId=")
            append(viewId)
            append(" text=")
            append(formatEventText(event))
            append(" contentDescription=")
            append(formatBracketed(event.contentDescription?.toString().orEmpty()))
        }
        Log.i("A11Y_CLICK", message)
        DiagnosticLogStore.append(this, "A11Y_CLICK", message)
    }

    private fun logPopupStructureScan(event: AccessibilityEvent, targetPackageName: String, now: Long): PopupStructureScan? {
        if (now - lastPopupStructureScanTime < POPUP_STRUCTURE_SCAN_MIN_INTERVAL_MS) return null
        lastPopupStructureScanTime = now

        val root = rootInActiveWindow
        if (root == null) {
            DiagnosticLogStore.append(
                this,
                "POPUP_STRUCTURE_SCAN",
                "eventType=${eventTypeName(event.eventType)} package=$targetPackageName class=${event.className} root=null"
            )
            return null
        }

        val eventSourceInfo = inspectEventSource(event)
        val scan = inspectRootForPopupStructure(root)
        val summary = buildString {
            append("eventType=")
            append(eventTypeName(event.eventType))
            append(" package=")
            append(targetPackageName)
            append(" class=")
            append(event.className?.toString().orEmpty())
            append(" source=")
            append(eventSourceInfo)
            append(" nodes=")
            append(scan.nodeCount)
            append(" clickable=")
            append(scan.clickableCount)
            append(" textNodes=")
            append(scan.textNodeCount)
            append(" features=")
            append(scan.featureSummary())
            append(" score=")
            append(scan.score)
            append(" likelyBlockingPopup=")
            append(scan.isLikelyBlockingPopup)
            append(" samples=")
            append(scan.samples.joinToString(" | "))
            append(" ids=")
            append(scan.viewIds.joinToString("|"))
        }
        DiagnosticLogStore.append(this, "POPUP_STRUCTURE_SCAN", summary)

        if (!scan.isNonOrderPage &&
            scan.hasTriggerableOrderSignal &&
            now - lastPopupCandidateLogTime >= POPUP_CANDIDATE_LOG_MIN_INTERVAL_MS
        ) {
            lastPopupCandidateLogTime = now
            DiagnosticLogStore.append(
                this,
                "ORDER_TRIGGER_CANDIDATE",
                "score=${scan.score} likelyBlockingPopup=${scan.isLikelyBlockingPopup} features=${scan.featureSummary()} samples=${scan.samples.joinToString(" | ")}"
            )
        }
        if (!scan.isOpportunityPage && scan.isLikelyBlockingPopup) {
            DiagnosticLogStore.append(
                this,
                "ORDER_BLOCKING_POPUP_CANDIDATE",
                "score=${scan.score} features=${scan.featureSummary()} source=$eventSourceInfo samples=${scan.samples.joinToString(" | ")}"
            )
        }
        return scan
    }

    private fun logOrderTriggerCandidate(event: AccessibilityEvent, decision: TriggerDecision) {
        DiagnosticLogStore.append(
            this,
            "ORDER_TRIGGER_CANDIDATE",
            "reason=${decision.reason} eventType=${eventTypeName(event.eventType)} packageName=${event.packageName?.toString().orEmpty()} " +
                    "className=${event.className?.toString().orEmpty()} score=${decision.score} likelyBlockingPopup=${decision.likelyBlockingPopup} features=${decision.features} samples=${decision.samples}"
        )
    }

    private fun logOrderTriggerRejected(
        event: AccessibilityEvent,
        decision: TriggerDecision,
        popupScan: PopupStructureScan?
    ) {
        logEventStructure("BEFORE_TRIGGER_REJECT", buildEventStructureSnapshot(event, event.packageName?.toString().orEmpty()))
        val score = if (decision.features.isNotBlank()) decision.score else popupScan?.score ?: 0
        val likelyBlockingPopup = if (decision.features.isNotBlank()) decision.likelyBlockingPopup else popupScan?.isLikelyBlockingPopup ?: false
        val features = decision.features.ifBlank { popupScan?.featureSummary().orEmpty() }
        val samples = decision.samples.ifBlank { popupScan?.samples?.joinToString(" | ").orEmpty() }
        DiagnosticLogStore.append(
            this,
            "ORDER_TRIGGER_REJECTED",
            "reason=${decision.reason} eventType=${eventTypeName(event.eventType)} packageName=${event.packageName?.toString().orEmpty()} " +
                    "className=${event.className?.toString().orEmpty()} score=$score likelyBlockingPopup=$likelyBlockingPopup features=$features samples=$samples"
        )
    }

    private fun buildEventStructureSnapshot(event: AccessibilityEvent, packageName: String): EventStructureSnapshot {
        val source = runCatching { event.source }.getOrNull()
        val root = rootInActiveWindow
        val counters = root?.let(::countAccessibilityStructure) ?: StructureCounters()
        return EventStructureSnapshot(
            eventType = eventTypeName(event.eventType),
            packageName = packageName,
            className = event.className?.toString().orEmpty(),
            windowId = source?.windowId ?: root?.windowId ?: -1,
            childCount = source?.childCount ?: -1,
            nodeCount = counters.nodeCount,
            textNodeCount = counters.textNodeCount,
            sourceViewId = source?.viewIdResourceName?.takeIf { it.isNotBlank() } ?: "NULL",
            contentDescription = sanitizeForLog(source?.contentDescription?.toString().orEmpty(), EVENT_STRUCTURE_TEXT_LIMIT),
            eventTime = event.eventTime
        )
    }

    private fun countAccessibilityStructure(root: AccessibilityNodeInfo): StructureCounters {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        var nodeCount = 0
        var textNodeCount = 0
        queue.add(root)
        while (queue.isNotEmpty() && nodeCount < EVENT_STRUCTURE_NODE_LIMIT) {
            val node = queue.removeFirst()
            nodeCount += 1
            if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) {
                textNodeCount += 1
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return StructureCounters(nodeCount = nodeCount, textNodeCount = textNodeCount)
    }

    private fun logEventStructure(stage: String, snapshot: EventStructureSnapshot?) {
        val data = snapshot ?: EventStructureSnapshot.empty()
        DiagnosticLogStore.append(
            this,
            "EVENT_STRUCTURE",
            "stage=$stage eventType=${data.eventType} packageName=${data.packageName} className=${data.className} " +
                    "windowId=${data.windowId} childCount=${data.childCount} nodeCount=${data.nodeCount} " +
                    "textNodeCount=${data.textNodeCount} sourceViewId=${data.sourceViewId} " +
                    "contentDescription=${formatBracketed(data.contentDescription)} eventTime=${data.eventTime}"
        )
    }

    private fun logEventSourceBounds(event: AccessibilityEvent, packageName: String) {
        if (packageName != "com.ubercab.driver") return
        val source = runCatching { event.source }.getOrNull()
        val parent = runCatching { source?.parent }.getOrNull()
        val root = rootInActiveWindow
        val sourceBounds = Rect()
        val parentBounds = Rect()
        source?.let { runCatching { it.getBoundsInScreen(sourceBounds) } }
        parent?.let { runCatching { it.getBoundsInScreen(parentBounds) } }
        DiagnosticLogStore.append(
            this,
            "EVENT_SOURCE_BOUNDS",
            "eventType=${eventTypeName(event.eventType)} className=${event.className?.toString().orEmpty()} " +
                    "packageName=$packageName windowId=${source?.windowId ?: root?.windowId ?: -1} " +
                    "rootPackage=${root?.packageName?.toString().orEmpty()} " +
                    "sourceBounds=${formatRectForLog(sourceBounds, source != null)} " +
                    "parentBounds=${formatRectForLog(parentBounds, parent != null)} " +
                    "parentClass=${parent?.className?.toString().orEmpty()} " +
                    "childCount=${source?.childCount ?: -1} clickable=${source?.isClickable ?: false} " +
                    "focusable=${source?.isFocusable ?: false} visibleToUser=${source?.isVisibleToUser ?: false}"
        )
    }

    private fun formatRectForLog(rect: Rect, available: Boolean): String {
        return if (available) rect.toShortString() else "[]"
    }

    private fun logUberWindowDebug(event: AccessibilityEvent, packageName: String, stage: String) {
        val activeRoot = rootInActiveWindow
        val currentWindows = runCatching { windows.orEmpty() }.getOrDefault(emptyList())
        val hasUberWindow = currentWindows.any { window ->
            window.root?.packageName?.toString() == "com.ubercab.driver"
        }
        if (packageName != "com.ubercab.driver" && !hasUberWindow) return

        logWindowDebug(
            "stage=$stage eventType=${eventTypeName(event.eventType)} packageName=$packageName className=${event.className?.toString().orEmpty()} " +
                    "windowId=${runCatching { event.source?.windowId }.getOrNull() ?: -1} " +
                    "rootPackage=${activeRoot?.packageName?.toString().orEmpty()} rootClass=${activeRoot?.className?.toString().orEmpty()}"
        )
        logWindowDebug("stage=$stage windowCount=${currentWindows.size}")
        currentWindows.forEachIndexed { index, window ->
            val root = window.root
            logWindowDebug(
                "stage=$stage windowIndex=$index windowType=${windowTypeName(window.type)} windowLayer=${window.layer} " +
                        "windowTitle=${sanitizeForLog(window.title?.toString().orEmpty(), EVENT_STRUCTURE_TEXT_LIMIT)} " +
                        "rootPackage=${root?.packageName?.toString().orEmpty()} rootClass=${root?.className?.toString().orEmpty()}"
            )
        }
    }

    private fun logWindowDebug(message: String) {
        Log.d("WINDOW_DEBUG", message)
        DiagnosticLogStore.add(this, "WINDOW_DEBUG", message)
    }

    private fun saveOcrAttemptScreenshot(bitmap: Bitmap, isSecondCheck: Boolean) {
        if (!AppSettings.isDebugSamplesEnabled(this)) return
        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) return
        val attempt = currentOcrAttemptNumber.coerceAtLeast(1)
        val elapsedMs = currentOcrAttemptElapsedMs
        val fileName = "ocr-attempt-session-${orderDetectionSessionId}-attempt-$attempt-${System.currentTimeMillis()}.png"
        val targetFile = DebugFileDirs.resolveAppScoped(this, "debug_samples").resolve(fileName)
        runCatching {
            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            DiagnosticLogStore.append(
                this,
                "OCR_SCREENSHOT_SAVED",
                "sessionId=$orderDetectionSessionId attempt=$attempt elapsedMs=$elapsedMs " +
                        "secondCheck=$isSecondCheck fileName=$fileName size=${bitmap.width}x${bitmap.height}"
            )
        }.onFailure { throwable ->
            DiagnosticLogStore.append(
                this,
                "OCR_SCREENSHOT_SAVED",
                "sessionId=$orderDetectionSessionId attempt=$attempt elapsedMs=$elapsedMs " +
                        "secondCheck=$isSecondCheck fileName=$fileName size=${bitmap.width}x${bitmap.height} " +
                        "saveSuccess=false error=${throwable.javaClass.simpleName}:${throwable.message.orEmpty()}"
            )
        }
    }

    private fun windowTypeName(type: Int): String {
        return when (type) {
            android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "TYPE_APPLICATION"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "TYPE_INPUT_METHOD"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "TYPE_SYSTEM"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "TYPE_ACCESSIBILITY_OVERLAY"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "TYPE_SPLIT_SCREEN_DIVIDER"
            else -> type.toString()
        }
    }

    private fun inspectEventSource(event: AccessibilityEvent): String {
        val source = runCatching { event.source }.getOrNull() ?: return "null"
        val rect = Rect()
        runCatching { source.getBoundsInScreen(rect) }
        return buildString {
            append(source.className?.toString().orEmpty())
            append("#")
            append(source.viewIdResourceName.orEmpty())
            append("@")
            append(rect.toShortString())
        }
    }

    private fun inspectRootForPopupStructure(root: AccessibilityNodeInfo): PopupStructureScan {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val classCounts = mutableMapOf<String, Int>()
        val samples = mutableListOf<String>()
        val viewIds = mutableListOf<String>()
        var nodeCount = 0
        var clickableCount = 0
        var textNodeCount = 0
        var hasAcceptAction = false
        var hasMatchAction = false
        var hasCloseAction = false
        var hasMoney = false
        var hasMinute = false
        var hasDistance = false
        var hasTotal = false
        var hasDelivery = false
        var hasFoodDelivery = false
        var hasOpportunityTitle = false
        var hasExtraEarn = false
        var hasCompleteTrips = false
        var hasStartTime = false
        var hasReward = false
        var hasHomeNav = false
        var hasExploreNav = false
        var hasInboxNav = false
        var hasTripFare = false
        var hasMenuNav = false
        var hasSettingsNav = false

        queue.add(root)
        while (queue.isNotEmpty() && nodeCount < POPUP_STRUCTURE_SCAN_NODE_LIMIT) {
            val node = queue.removeFirst()
            nodeCount += 1
            val className = node.className?.toString().orEmpty()
            if (className.isNotBlank()) {
                classCounts[className] = (classCounts[className] ?: 0) + 1
            }
            if (node.isClickable) clickableCount += 1

            val id = node.viewIdResourceName.orEmpty()
            if (id.isNotBlank() && viewIds.size < POPUP_STRUCTURE_SCAN_SAMPLE_LIMIT && id !in viewIds) {
                viewIds.add(sanitizeForLog(id, 80))
            }

            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val combined = "$text $desc".trim()
            if (combined.isNotBlank()) {
                textNodeCount += 1
                updatePopupTextFeatures(
                    combined,
                    onAccept = { hasAcceptAction = true },
                    onMatch = { hasMatchAction = true },
                    onClose = { hasCloseAction = true },
                    onMoney = { hasMoney = true },
                    onMinute = { hasMinute = true },
                    onDistance = { hasDistance = true },
                    onTotal = { hasTotal = true },
                    onDelivery = { hasDelivery = true },
                    onFoodDelivery = { hasFoodDelivery = true },
                    onOpportunityTitle = { hasOpportunityTitle = true },
                    onExtraEarn = { hasExtraEarn = true },
                    onCompleteTrips = { hasCompleteTrips = true },
                    onStartTime = { hasStartTime = true },
                    onReward = { hasReward = true },
                    onHomeNav = { hasHomeNav = true },
                    onExploreNav = { hasExploreNav = true },
                    onInboxNav = { hasInboxNav = true },
                    onTripFare = { hasTripFare = true },
                    onMenuNav = { hasMenuNav = true },
                    onSettingsNav = { hasSettingsNav = true }
                )
                if (samples.size < POPUP_STRUCTURE_SCAN_SAMPLE_LIMIT) {
                    val rect = Rect()
                    runCatching { node.getBoundsInScreen(rect) }
                    samples.add("${sanitizeForLog(combined, 60)}@${rect.toShortString()}")
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }

        val score = listOf(
            hasMoney,
            hasMinute,
            hasDistance,
            hasTotal,
            hasDelivery,
            hasFoodDelivery,
            hasAcceptAction || hasMatchAction,
            hasCloseAction,
            clickableCount >= 2,
            textNodeCount >= 4
        ).count { it }
        val isLikelyBlockingPopup =
            (hasMoney && hasMinute && hasDistance && (hasAcceptAction || hasMatchAction || hasCloseAction)) ||
                    (score >= 5 && (hasAcceptAction || hasMatchAction))

        return PopupStructureScan(
            nodeCount = nodeCount,
            clickableCount = clickableCount,
            textNodeCount = textNodeCount,
            classCounts = classCounts,
            samples = samples,
            viewIds = viewIds,
            hasAcceptAction = hasAcceptAction,
            hasMatchAction = hasMatchAction,
            hasCloseAction = hasCloseAction,
            hasMoney = hasMoney,
            hasMinute = hasMinute,
            hasDistance = hasDistance,
            hasTotal = hasTotal,
            hasDelivery = hasDelivery,
            hasFoodDelivery = hasFoodDelivery,
            hasOpportunityTitle = hasOpportunityTitle,
            hasExtraEarn = hasExtraEarn,
            hasCompleteTrips = hasCompleteTrips,
            hasStartTime = hasStartTime,
            hasReward = hasReward,
            hasHomeNav = hasHomeNav,
            hasExploreNav = hasExploreNav,
            hasInboxNav = hasInboxNav,
            hasTripFare = hasTripFare,
            hasMenuNav = hasMenuNav,
            hasSettingsNav = hasSettingsNav,
            score = score,
            isLikelyBlockingPopup = isLikelyBlockingPopup
        )
    }

    private fun updatePopupTextFeatures(
        value: String,
        onAccept: () -> Unit,
        onMatch: () -> Unit,
        onClose: () -> Unit,
        onMoney: () -> Unit,
        onMinute: () -> Unit,
        onDistance: () -> Unit,
        onTotal: () -> Unit,
        onDelivery: () -> Unit,
        onFoodDelivery: () -> Unit,
        onOpportunityTitle: () -> Unit,
        onExtraEarn: () -> Unit,
        onCompleteTrips: () -> Unit,
        onStartTime: () -> Unit,
        onReward: () -> Unit,
        onHomeNav: () -> Unit,
        onExploreNav: () -> Unit,
        onInboxNav: () -> Unit,
        onTripFare: () -> Unit,
        onMenuNav: () -> Unit,
        onSettingsNav: () -> Unit
    ) {
        val normalized = value.replace(" ", "")
        if (normalized.contains("接受") || normalized.contains("接單") || normalized.contains("Accept", ignoreCase = true)) onAccept()
        if (normalized.contains("匹配") || normalized.contains("配對") || normalized.contains("Match", ignoreCase = true)) onMatch()
        if (normalized == "×" || normalized.equals("x", ignoreCase = true) || normalized.contains("關閉")) onClose()
        if (Regex("""[$＄]\s*\d+""").containsMatchIn(value) || Regex("""\bNT\s*\$?\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(value)) onMoney()
        if (normalized.contains("分鐘") || Regex("""\b\d+\s*min\b""", RegexOption.IGNORE_CASE).containsMatchIn(value)) onMinute()
        if (normalized.contains("公里") || Regex("""\b\d+(?:\.\d+)?\s*km\b""", RegexOption.IGNORE_CASE).containsMatchIn(value)) onDistance()
        if (normalized.contains("總計") || normalized.contains("總共") || normalized.contains("total", ignoreCase = true)) onTotal()
        if (normalized.contains("單") || normalized.contains("趟")) onDelivery()
        if (normalized.contains("外送") || normalized.contains("獨享")) onFoodDelivery()
        if (normalized == "機會" || normalized.equals("opportunities", ignoreCase = true)) onOpportunityTitle()
        if (normalized.contains("額外獲得") || normalized.contains("额外获得")) onExtraEarn()
        if (Regex("""完成\d+趟行程""").containsMatchIn(normalized)) onCompleteTrips()
        if (normalized.contains("開始時間") || normalized.contains("开始时间")) onStartTime()
        if (normalized.contains("獎勵") || normalized.contains("奖励")) onReward()
        if (normalized == "首頁" || normalized.equals("home", ignoreCase = true)) onHomeNav()
        if (normalized == "探索" || normalized.equals("explore", ignoreCase = true)) onExploreNav()
        if (normalized == "收件匣" || normalized.equals("inbox", ignoreCase = true)) onInboxNav()
        if (normalized.contains("行程費用") || normalized.contains("行程费用")) onTripFare()
        if (normalized == "選單" || normalized == "菜单" || normalized.equals("menu", ignoreCase = true)) onMenuNav()
        if (normalized == "設定" || normalized == "设置" || normalized.equals("settings", ignoreCase = true)) onSettingsNav()
    }

    private fun sanitizeForLog(value: String, maxLength: Int): String {
        val compact = value.replace(Regex("""\s+"""), " ").trim()
        return if (compact.length <= maxLength) compact else compact.take(maxLength) + "..."
    }

    private data class TriggerDecision(
        val shouldTrigger: Boolean,
        val reason: String,
        val score: Int = 0,
        val likelyBlockingPopup: Boolean = false,
        val features: String = "",
        val samples: String = ""
    )

    private data class EventStructureSnapshot(
        val eventType: String,
        val packageName: String,
        val className: String,
        val windowId: Int,
        val childCount: Int,
        val nodeCount: Int,
        val textNodeCount: Int,
        val sourceViewId: String,
        val contentDescription: String,
        val eventTime: Long
    ) {
        companion object {
            fun empty(): EventStructureSnapshot {
                return EventStructureSnapshot(
                    eventType = "UNKNOWN",
                    packageName = "",
                    className = "",
                    windowId = -1,
                    childCount = -1,
                    nodeCount = 0,
                    textNodeCount = 0,
                    sourceViewId = "NULL",
                    contentDescription = "",
                    eventTime = 0L
                )
            }
        }
    }

    private data class StructureCounters(
        val nodeCount: Int = 0,
        val textNodeCount: Int = 0
    )

    private data class PopupStructureScan(
        val nodeCount: Int,
        val clickableCount: Int,
        val textNodeCount: Int,
        val classCounts: Map<String, Int>,
        val samples: List<String>,
        val viewIds: List<String>,
        val hasAcceptAction: Boolean,
        val hasMatchAction: Boolean,
        val hasCloseAction: Boolean,
        val hasMoney: Boolean,
        val hasMinute: Boolean,
        val hasDistance: Boolean,
        val hasTotal: Boolean,
        val hasDelivery: Boolean,
        val hasFoodDelivery: Boolean,
        val hasOpportunityTitle: Boolean,
        val hasExtraEarn: Boolean,
        val hasCompleteTrips: Boolean,
        val hasStartTime: Boolean,
        val hasReward: Boolean,
        val hasHomeNav: Boolean,
        val hasExploreNav: Boolean,
        val hasInboxNav: Boolean,
        val hasTripFare: Boolean,
        val hasMenuNav: Boolean,
        val hasSettingsNav: Boolean,
        val score: Int,
        val isLikelyBlockingPopup: Boolean
    ) {
        val hasOrderTextSignal: Boolean
            get() = hasMoney || hasMinute || hasDistance || hasAcceptAction || hasMatchAction

        val coreOrderFeatureCount: Int
            get() = listOf(
                hasMoney || hasTotal,
                hasMinute,
                hasDistance
            ).count { it }

        val hasTriggerableOrderSignal: Boolean
            get() = isLikelyBlockingPopup || coreOrderFeatureCount >= 2

        val isSparsePopupShell: Boolean
            get() {
                val frameCount = classCounts["android.widget.FrameLayout"] ?: 0
                val viewGroupCount = classCounts["android.view.ViewGroup"] ?: 0
                return textNodeCount <= 1 &&
                        nodeCount in 24..80 &&
                        clickableCount in 1..12 &&
                        frameCount >= 5 &&
                        viewGroupCount >= 5
            }

        val isRenderedPopupShell: Boolean
            get() = isSparsePopupShell && textNodeCount == 0

        val opportunityScore: Int
            get() = listOf(
                hasOpportunityTitle,
                hasExtraEarn,
                hasCompleteTrips,
                hasStartTime,
                hasReward
            ).count { it }

        val isOpportunityPage: Boolean
            get() = hasOpportunityTitle && opportunityScore >= 2

        val mainNavScore: Int
            get() = listOf(
                hasHomeNav,
                hasExploreNav,
                hasInboxNav,
                hasTripFare,
                hasMenuNav,
                hasSettingsNav
            ).count { it }

        val isMainNavPage: Boolean
            get() = mainNavScore >= 2

        val isNonOrderPage: Boolean
            get() = isOpportunityPage || isMainNavPage || (!isLikelyBlockingPopup && coreOrderFeatureCount < 2)

        fun featureSummary(): String {
            val topClasses = classCounts.entries
                .sortedByDescending { it.value }
                .take(4)
                .joinToString("|") { "${it.key.substringAfterLast('.')}:${it.value}" }
            return "money=$hasMoney minute=$hasMinute distance=$hasDistance total=$hasTotal delivery=$hasDelivery food=$hasFoodDelivery " +
                    "accept=$hasAcceptAction match=$hasMatchAction close=$hasCloseAction " +
                    "coreOrderFeatures=$coreOrderFeatureCount opportunityScore=$opportunityScore opportunity=$hasOpportunityTitle extraEarn=$hasExtraEarn completeTrips=$hasCompleteTrips startTime=$hasStartTime reward=$hasReward " +
                    "mainNavScore=$mainNavScore home=$hasHomeNav explore=$hasExploreNav inbox=$hasInboxNav tripFare=$hasTripFare menu=$hasMenuNav settings=$hasSettingsNav classes=$topClasses"
        }
    }

    private fun formatEventText(event: AccessibilityEvent): String {
        return formatBracketed(event.text.joinToString(",") { it?.toString().orEmpty() })
    }

    private fun formatBracketed(value: String): String {
        return "[$value]"
    }

    private fun logOcrAttempt(attempt: Int, elapsedMs: Long) {
        logObservation("OCR_ATTEMPT", "attempt=$attempt elapsedMs=$elapsedMs")
    }

    private fun logOcrSuccess(attempt: Int, elapsedMs: Long) {
        logObservation("OCR_SUCCESS", "attempt=$attempt elapsedMs=$elapsedMs")
    }

    private fun logOcrFail(attempt: Int, elapsedMs: Long, reason: String) {
        logObservation("OCR_FAIL", "attempt=$attempt elapsedMs=$elapsedMs reason=$reason")
    }

    private fun logOcrFinalFail(attempt: Int, elapsedMs: Long, reason: String) {
        logObservation("OCR_FAIL_FINAL", "attempt=$attempt elapsedMs=$elapsedMs reason=$reason")
    }

    private fun logOrderLatency(eventToPopupMs: Long, eventToOcrSuccessMs: Long, ocrAttempt: Int) {
        logObservation(
            "ORDER_LATENCY",
            "eventToPopupMs=$eventToPopupMs eventToOcrSuccessMs=$eventToOcrSuccessMs ocrAttempt=$ocrAttempt"
        )
    }

    private fun eventElapsedMs(now: Long = System.currentTimeMillis()): Long {
        val start = orderDetectionSessionStartTime
        return if (start > 0L) {
            (now - start).coerceAtLeast(0L)
        } else {
            0L
        }
    }

    private fun logPopupShowOrReplace(analysis: AnalysisResult, newOrderLabel: String) {
        val oldOrderLabel = currentOverlayOrderLabel
        if (overlayView != null && oldOrderLabel.isNotBlank() && oldOrderLabel != newOrderLabel) {
            logObservation("POPUP_REPLACE", "oldOrder=$oldOrderLabel newOrder=$newOrderLabel")
        } else {
            logObservation("POPUP_SHOW", "score=${analysis.score} recommendation=${analysis.recommendation}")
        }
    }

    private fun orderDetails(order: OrderData): String {
        return "money=${order.price} distance=${OrderAnalyzer.formatDistance(order.distance)} minutes=${order.minutes} orders=${order.deliveryCount}"
    }

    private fun orderLabel(order: OrderData): String {
        return "${order.price}元/${order.deliveryCount}單"
    }

    private fun coreOrderSignature(order: OrderData): String {
        return listOf(
            order.price,
            order.minutes,
            normalizedDistanceForSignature(order.distance),
            order.deliveryCount
        ).joinToString("|")
    }

    private fun normalizedDistanceForSignature(distance: Double): String {
        return java.math.BigDecimal.valueOf(distance)
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    private fun orderSignature(order: com.example.gongderefuser.model.OrderData): String {
        return listOf(
            order.price,
            order.minutes,
            order.distance,
            order.deliveryCount,
            order.isAddOnOrder,
            order.isSameLocationStack,
            order.storeName.trim(),
            order.address.trim()
        ).joinToString("|")
    }

    private fun closeStaleScreenshot(screenshot: ScreenshotResult) {
        runCatching {
            screenshot.hardwareBuffer.close()
        }
        DiagnosticLogStore.append(this, "A11Y_SCREENSHOT", "stale_callback_ignored")
    }

    private fun ScreenshotResult.toBitmap(): Bitmap? {
        return runCatching {
            val source = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace) ?: return null
            source.copy(Bitmap.Config.ARGB_8888, false).also {
                hardwareBuffer.close()
            }
        }.onFailure {
            DiagnosticLogStore.append(this@MyAccessibilityService, "A11Y_SCREENSHOT", "bitmap_error=${it.javaClass.simpleName}:${it.message}")
        }.getOrNull()
    }

    override fun onInterrupt() {
        Log.d("TARGET_SERVICE", "interrupted")
    }

    override fun onDestroy() {
        DiagnosticLogStore.append(this, "ACCESSIBILITY", "destroyed package=$packageName")
        clearCollapseTimer()
        pendingOrderEventRunnable?.let(mainHandler::removeCallbacks)
        pendingOrderEventRunnable = null
        clearSessionTimeout()
        isDetectionScheduled = false
        pendingDetectionAfterCurrent = false
        currentBurstDetectCount = 0
        hideOverlay()
        stopForegroundCompat()
        if (activeService === this) {
            activeService = null
        }
        super.onDestroy()
    }

    private fun showStatusOverlayInternal() {
        clearCollapseTimer()
        hideOverlay()
    }

    private fun showAnalysisOverlayInternal(analysis: AnalysisResult, playTone: Boolean = true) {
        clearCollapseTimer()
        hideOverlay()

        val density = resources.displayMetrics.density
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        val params = WindowManager.LayoutParams(
            (displayWidth * 0.86f).toInt(),
            (displayHeight * 0.46f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (48 * density).toInt()
        }

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val card = createAnalysisCard(analysis)
        val scrollContainer = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(card)
        }

        makeDraggable(scrollContainer, params, windowManager, persistPosition = false)
        windowManager.addView(scrollContainer, params)
        overlayView = scrollContainer
        if (playTone) {
            mainHandler.post { playAnalysisTone(analysis) }
        }

        collapseRunnable = Runnable {
            hideOverlay()
        }.also {
            mainHandler.postDelayed(it, 30_000)
        }
    }

    private fun playAnalysisTone(analysis: AnalysisResult) {
        if (!AppSettings.isSoundEnabled(this)) return

        val soundRes = when (analysis.recommendation) {
            "掙他娘的" -> R.raw.sound_level_4
            "站著掙" -> R.raw.sound_level_3
            "跪著送" -> R.raw.sound_level_2
            else -> R.raw.sound_level_1
        }

        runCatching {
            val descriptor = resources.openRawResourceFd(soundRes) ?: return
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            descriptor.close()
            player.setOnCompletionListener { mediaPlayer ->
                mediaPlayer.release()
            }
            player.setOnErrorListener { mediaPlayer, _, _ ->
                mediaPlayer.release()
                true
            }
            player.prepare()
            player.start()
        }
    }

    private fun syncForegroundNotification() {
        if (MonitoringState.isEnabled(this)) {
            startForeground(
                MonitorNotificationHelper.NOTIFICATION_ID,
                MonitorNotificationHelper.createRunningNotification(this)
            )
        } else {
            stopForegroundCompat()
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createAnalysisCard(analysis: AnalysisResult): LinearLayout {
        val density = resources.displayMetrics.density
        val accentColor = recommendationColor(analysis)

        val background = GradientDrawable().apply {
            setColor(Color.argb(150, 255, 255, 255))
            cornerRadius = 16 * density
            setStroke((2 * density).toInt(), accentColor)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            this.background = background
            elevation = 10 * density
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = TextView(this).apply {
            text = "${analysis.score}分 · ${analysis.recommendation}"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(7), 0, dp(7))
            setBackground(GradientDrawable().apply {
                setColor(accentColor)
                cornerRadius = 12 * density
            })
        }

        val collapseButton = TextView(this).apply {
            text = "×"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(2))
            setBackground(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(241, 245, 249))
            })
            setOnClickListener {
                hideOverlay()
            }
        }

        header.addView(
            badge,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                rightMargin = dp(8)
            }
        )
        header.addView(
            collapseButton,
            LinearLayout.LayoutParams(dp(36), dp(36))
        )
        card.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(5)
            }
        )

        addMerchantStatusBlock(card, analysis)
        addListMatchBlock(card, analysis)
        addResultLine(card, "配送數量", "${analysis.deliveryCount} 單")
        addResultLine(card, "金額", formatPriceWithSubsidy(analysis))
        addResultLine(card, "時間", "${analysis.minutes} 分鐘")
        addResultLine(card, "距離", "${OrderAnalyzer.formatDistance(analysis.distance)} 公里")
        addResultLine(card, "預計時薪", "${formatOriginalHourly(analysis)} 元/小時")
        addResultLine(card, "元/公里", "${formatOriginalYuanPerKm(analysis)} 元/km")
        addResultLine(card, "平均單價", "${formatOriginalAveragePrice(analysis)} 元")

        return card
    }

    private fun acceptModeLabel(mode: RuleSettings.AcceptMode): String {
        return when (mode) {
            RuleSettings.AcceptMode.REWARD -> "趟獎模式"
            RuleSettings.AcceptMode.NORMAL -> "正常模式"
        }
    }

    private fun formatPriceWithSubsidy(analysis: AnalysisResult): String {
        val rewardTotal = rewardTripTotal(analysis)
        return if (rewardTotal > 0) {
            "${analysis.originalPrice} 元（+趟獎 ${rewardTotal} 元）"
        } else {
            "${analysis.originalPrice} 元"
        }
    }

    private fun formatOriginalHourly(analysis: AnalysisResult): String {
        val hourly = if (analysis.minutes > 0) {
            analysis.originalPrice.toDouble() / analysis.minutes * 60.0
        } else {
            0.0
        }
        return OrderAnalyzer.formatMoney(hourly)
    }

    private fun formatOriginalYuanPerKm(analysis: AnalysisResult): String {
        val yuanPerKm = if (analysis.distance > 0.0) {
            analysis.originalPrice.toDouble() / analysis.distance
        } else {
            0.0
        }
        return OrderAnalyzer.formatMoney(yuanPerKm)
    }

    private fun formatOriginalAveragePrice(analysis: AnalysisResult): String {
        val averagePrice = analysis.originalPrice.toDouble() / analysis.deliveryCount.coerceAtLeast(1)
        return OrderAnalyzer.formatMoney(averagePrice)
    }

    private fun rewardTripTotal(analysis: AnalysisResult): Int {
        if (analysis.acceptMode != RuleSettings.AcceptMode.REWARD || analysis.rewardPerTrip <= 0) {
            return 0
        }
        return analysis.rewardPerTrip * analysis.deliveryCount.coerceAtLeast(1)
    }

    private fun recommendationColor(analysis: AnalysisResult): Int {
        return when (analysis.recommendation) {
            "掙他娘的" -> COLOR_GOLD
            "站著掙" -> COLOR_SUCCESS
            "跪著送" -> COLOR_WARNING
            else -> COLOR_DANGER
        }
    }

    private fun showAddListEntryDialog(keyword: String, isWhitelist: Boolean) {
        val noteInput = EditText(this).apply {
            hint = if (isWhitelist) {
                "備註：位置、出餐速度等"
            } else {
                "原因：不好取/不好送/難停車等"
            }
            setSingleLine(false)
            minLines = 2
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), 0)
            addView(TextView(this@MyAccessibilityService).apply {
                text = keyword
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_PRIMARY)
                setPadding(0, 0, 0, dp(10))
            })
            addView(noteInput)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isWhitelist) "新增標籤備註" else "新增避雷標籤")
            .setView(content)
            .setPositiveButton("儲存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val note = noteInput.text.toString().trim()
                if (keyword.length < 2) {
                    Toast.makeText(this, "標籤名稱太短", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val saved = saveListEntry(keyword, note, isWhitelist)
                Toast.makeText(
                    this,
                    if (saved) {
                        "已新增到${if (isWhitelist) "標籤備註" else "避雷標籤"}"
                    } else {
                        "名單裡已有相同商家或地址"
                    },
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
        }
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.show()
    }

    private fun showListActionChoice(keyword: String) {
        val cleanKeyword = keyword.trim()
        if (cleanKeyword.length < 2 || cleanKeyword == "未識別") return

        val dialog = AlertDialog.Builder(this)
            .setTitle("新增標籤")
            .setMessage(cleanKeyword)
            .setPositiveButton("加標籤備註") { _, _ ->
                showAddListEntryDialog(cleanKeyword, isWhitelist = true)
            }
            .setNegativeButton("加避雷標籤") { _, _ ->
                showAddListEntryDialog(cleanKeyword, isWhitelist = false)
            }
            .setNeutralButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        }
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.show()
    }

    private fun saveListEntry(keyword: String, note: String, isWhitelist: Boolean): Boolean {
        val settings = RuleSettings.load(this)
        val whitelist = settings.whitelistEntries.toMutableList()
        val blacklist = settings.blacklistEntries.toMutableList()
        val target = if (isWhitelist) whitelist else blacklist
        val allEntries = whitelist + blacklist

        if (RuleSettings.containsMatchingEntry(allEntries, keyword)) {
            return false
        }

        target.removeAll { it.keyword == keyword }
        target.add(RuleSettings.ListEntry(keyword, note))

        RuleSettings.save(
            context = this,
            normal = settings.normal,
            blacklist = settings.blacklist,
            whitelistText = RuleSettings.serializeEntries(whitelist),
            blacklistText = RuleSettings.serializeEntries(blacklist)
        )
        return true
    }

    private fun addResultLine(parent: LinearLayout, label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }

        val labelView = TextView(this).apply {
            text = "$label："
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_RESULT_LABEL)
            setSingleLine(true)
        }

        val valueView = TextView(this).apply {
            text = value
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            setTextColor(COLOR_TEXT_PRIMARY)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setSingleLine(false)
            setLineSpacing(0f, 1.08f)
        }

        row.addView(
            labelView,
            LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        row.addView(
            valueView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        parent.addView(row)
    }

    private fun addMerchantStatusBlock(parent: LinearLayout, analysis: AnalysisResult) {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(3)
            }
        }
        addClickableResultLine(block, "商家", analysis.storeName.ifBlank { "未識別" })
        addClickableResultLine(block, "地址", analysis.storeAddress.ifBlank { "未識別" })
        parent.addView(block)
    }

    private fun addClickableResultLine(parent: LinearLayout, label: String, value: String) {
        addResultLine(parent, label, value)
        val row = parent.getChildAt(parent.childCount - 1)
        row?.setOnClickListener {
            if (value.isNotBlank() && value != "未識別") {
                showListActionChoice(value)
            }
        }
    }

    private fun addListMatchBlock(parent: LinearLayout, analysis: AnalysisResult) {
        val isBlacklisted = analysis.isBlacklisted
        val isWhitelisted = analysis.isWhitelisted
        if (!isBlacklisted && !isWhitelisted) return

        val keyword = if (isBlacklisted) analysis.matchedBlacklistKeyword else analysis.matchedWhitelistKeyword
        val note = if (isBlacklisted) analysis.blacklistNote else analysis.whitelistNote
        val hitTarget = when {
            analysis.isAddressBlacklisted -> "地址"
            analysis.isMerchantBlacklisted -> "商家"
            analysis.isAddressWhitelisted -> "地址"
            analysis.isMerchantWhitelisted -> "商家"
            else -> keyword.ifBlank { "已命中標籤規則" }
        }
        addHighlightedResultLine(
            parent = parent,
            label = if (isBlacklisted) "命中避雷標籤" else "命中標籤備註",
            value = hitTarget,
            fillColor = if (isBlacklisted) Color.rgb(254, 226, 226) else Color.rgb(220, 252, 231),
            strokeColor = if (isBlacklisted) COLOR_DANGER else COLOR_SUCCESS
        )
        if (note.isNotBlank()) addResultLine(parent, "備註", twoLine(note))
    }

    private fun addHighlightedResultLine(
        parent: LinearLayout,
        label: String,
        value: String,
        fillColor: Int,
        strokeColor: Int
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                setColor(fillColor)
                cornerRadius = 10 * resources.displayMetrics.density
                setStroke(dp(1), strokeColor)
            }
        }
        row.addView(TextView(this).apply {
            text = "$label："
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            setTextColor(strokeColor)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = value
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_TEXT_PRIMARY)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(3)
            bottomMargin = dp(3)
        })
    }

    private fun twoLine(value: String): String {
        return value.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("\n")
            .ifBlank { value.take(42) }
    }

    private fun addColoredResultLine(
        parent: LinearLayout,
        label: String,
        value: String,
        fillColor: Int,
        strokeColor: Int
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(fillColor)
                setStroke(dp(1), strokeColor)
                cornerRadius = 10 * resources.displayMetrics.density
            }
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
        })
        row.addView(TextView(this).apply {
            text = value
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = if (label == "商家" || label == "地址") Gravity.START else Gravity.END
            setTextColor(COLOR_TEXT_PRIMARY)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setSingleLine(false)
            setLineSpacing(0f, 1.08f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (value != "未識別") {
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener {
                showListActionChoice(value)
            }
        }
        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(4)
        })
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        overlayView = null
        currentOverlayOrderLabel = ""
        runCatching {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.removeView(view)
        }
    }

    private fun makeDraggable(
        view: View,
        params: WindowManager.LayoutParams,
        windowManager: WindowManager,
        persistPosition: Boolean
    ) {
        var startX = 0
        var startY = 0
        var startRawX = 0f
        var startRawY = 0f
        var moved = false

        view.setOnTouchListener { touchedView, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - startRawX).toInt()
                    val deltaY = (event.rawY - startRawY).toInt()
                    if (kotlin.math.abs(deltaX) > 6 || kotlin.math.abs(deltaY) > 6) {
                        moved = true
                    }
                    params.x = startX + deltaX
                    params.y = startY + deltaY
                    runCatching {
                        windowManager.updateViewLayout(touchedView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (persistPosition && moved) {
                        saveOverlayPosition(params.x, params.y)
                    }
                    if (!moved) {
                        touchedView.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun clearCollapseTimer() {
        collapseRunnable?.let(mainHandler::removeCallbacks)
        collapseRunnable = null
    }

    private fun isMonitoringWorking(): Boolean {
        return MonitoringState.isEnabled(this) && activeService === this
    }

    private fun getPrefs() =
        getSharedPreferences(OVERLAY_PREFS_NAME, MODE_PRIVATE)

    private fun getOverlayX(defaultValue: Int): Int {
        return getPrefs().getInt(KEY_OVERLAY_X, defaultValue)
    }

    private fun getOverlayY(defaultValue: Int): Int {
        return getPrefs().getInt(KEY_OVERLAY_Y, defaultValue)
    }

    private fun saveOverlayPosition(x: Int, y: Int) {
        getPrefs()
            .edit()
            .putInt(KEY_OVERLAY_X, x)
            .putInt(KEY_OVERLAY_Y, y)
            .apply()
    }

    private fun eventTypeName(eventType: Int): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "TYPE_WINDOWS_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
            else -> eventType.toString()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val OVERLAY_PREFS_NAME = "gongde_refuser_overlay"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        private const val SCREENSHOT_TIMEOUT_MS = 3_500L
        private const val OCR_TIMEOUT_MS = 8_000L
        private const val MAX_SESSION_DURATION_MS = 10_000L
        private const val FIRST_OCR_ATTEMPT_DELAY_MS = 800L
        private const val OCR_SESSION_RETRY_DELAY_MS = 400L
        private const val MAX_ORDER_DETECTION_ATTEMPTS = 2
        private const val ORDER_END_INVALID_COUNT = 5
        private const val DETECTION_BURST_RESET_MS = 1_500L
        private const val MAX_DETECTIONS_PER_BURST = 5
        private const val MAX_DETECTIONS_PER_POPUP_CANDIDATE = 5
        private const val POPUP_CANDIDATE_WINDOW_MS = 2_000L
        private const val MIN_SCREENSHOT_INTERVAL_MS = 1_000L
        private const val DUPLICATE_CORE_ORDER_WINDOW_MS = 10_000L
        private const val POPUP_STRUCTURE_SCAN_MIN_INTERVAL_MS = 800L
        private const val POPUP_CANDIDATE_LOG_MIN_INTERVAL_MS = 3_000L
        private const val POPUP_STRUCTURE_SCAN_NODE_LIMIT = 140
        private const val POPUP_STRUCTURE_SCAN_SAMPLE_LIMIT = 10
        private const val EVENT_STRUCTURE_NODE_LIMIT = 160
        private const val EVENT_STRUCTURE_TEXT_LIMIT = 80
        private val NON_ORDER_TRIGGER_REASONS = setOf(
            "OPPORTUNITY_PAGE",
            "MAIN_NAV_PAGE"
        )

        private val COLOR_SUCCESS = Color.rgb(34, 197, 94)
        private val COLOR_DANGER = Color.rgb(239, 68, 68)
        private val COLOR_WARNING = Color.rgb(245, 158, 11)
        private val COLOR_GOLD = Color.rgb(126, 87, 194)
        private val COLOR_TEXT_PRIMARY = Color.rgb(22, 27, 34)
        private val COLOR_TEXT_SECONDARY = Color.rgb(88, 96, 105)
        private val COLOR_RESULT_LABEL = Color.rgb(45, 55, 72)
        @Volatile
        private var activeService: MyAccessibilityService? = null
        @Volatile
        private var lastTargetEventSummary: String = ""

        fun showFeedback(analysis: AnalysisResult): Boolean {
            val service = activeService ?: return false
            service.mainHandler.post {
                runCatching {
                    service.showAnalysisOverlayInternal(analysis)
                }.onFailure {
                    service.hideOverlay()
                }
            }
            return true
        }

        fun refreshStatusOverlay() {
            val service = activeService ?: return
            service.mainHandler.post {
                service.showStatusOverlayInternal()
            }
        }

        fun refreshForegroundNotification() {
            val service = activeService ?: return
            service.mainHandler.post {
                service.syncForegroundNotification()
            }
        }

        fun isServiceActive(): Boolean {
            return activeService != null
        }
    }
}
