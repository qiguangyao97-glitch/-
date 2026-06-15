package com.example.gongderefuser

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.gongderefuser.analyzer.OrderAnalyzer
import com.example.gongderefuser.analyzer.OrderAnalyzer.AnalysisResult
import com.example.gongderefuser.analyzer.RuleSettings
import com.example.gongderefuser.parser.OrderParser

class MyAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var collapseRunnable: Runnable? = null
    private var isTakingScreenshot = false
    private var isProcessingOrder = false
    private var lastShownOrderSignature: String = ""
    private var lastShownOrderTime: Long = 0L
    private var lastOcrAttemptTime: Long = 0L
    private var screenshotTimeoutRunnable: Runnable? = null
    private var ocrTimeoutRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        showStatusOverlayInternal()
        DiagnosticLogStore.append(this, "ACCESSIBILITY", "connected package=$packageName")
        Log.d("TARGET_SERVICE", "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!MonitoringState.isEnabled(this)) return

        val packageName = event?.packageName?.toString() ?: return
        if (packageName != "com.ubercab.driver") return
        val now = System.currentTimeMillis()

        if (AppSettings.isAccessibilityLogEnabled(this)) {
            Log.d(
                "TARGET_EVENT",
                "type=${event.eventType} class=${event.className} text=${event.text} desc=${event.contentDescription}"
            )
        }
        lastTargetEventSummary = "type=${event.eventType} class=${event.className} time=$now"

        if (now - CaptureTrigger.lastTriggerTime < 4_000) return

        CaptureTrigger.lastTriggerTime = now
        Log.d("TARGET_TRIGGER", "target event detected")

        CaptureTrigger.pendingCaptureCount = 2
        listOf(420L, 1200L).forEach { delay ->
            Handler(Looper.getMainLooper()).postDelayed({
                triggerCapture()
            }, delay)
        }
    }

    private fun triggerCapture() {
        if (!MonitoringState.isEnabled(this)) return
        val now = System.currentTimeMillis()
        if (now - lastShownOrderTime < 6_000) {
            Log.d("TARGET_TRIGGER", "skip, recent order shown")
            return
        }
        if (now - lastOcrAttemptTime < 650) {
            Log.d("TARGET_TRIGGER", "skip, recent OCR attempt")
            return
        }
        lastOcrAttemptTime = now

        Log.d("TARGET_TRIGGER", "set capture flag")
        tryAccessibilityScreenshot()
    }

    private fun reportAccessibilityScreenshotFailure(reason: String) {
        DiagnosticLogStore.append(this, "A11Y_SCREENSHOT", "failed_no_fallback reason=$reason")
        Toast.makeText(this, "无障碍截图失败：$reason", Toast.LENGTH_SHORT).show()
    }

    private fun tryAccessibilityScreenshot(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            reportAccessibilityScreenshotFailure("sdk_${Build.VERSION.SDK_INT}")
            return false
        }
        if (isTakingScreenshot) {
            Log.d("A11Y_SCREENSHOT", "skip, screenshot already running")
            DiagnosticLogStore.append(this, "A11Y_SCREENSHOT", "skip_already_running")
            return true
        }
        isTakingScreenshot = true
        DiagnosticLogStore.append(this, "A11Y_SCREENSHOT", "request lastEvent=$lastTargetEventSummary")
        armScreenshotTimeout()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    clearScreenshotTimeout()
                    isTakingScreenshot = false
                    val bitmap = screenshot.toBitmap()
                    if (bitmap == null) {
                        reportAccessibilityScreenshotFailure("bitmap_null")
                        return
                    }
                    DiagnosticLogStore.append(this@MyAccessibilityService, "A11Y_SCREENSHOT", "success ${bitmap.width}x${bitmap.height}")
                    processAccessibilityOrderBitmap(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    clearScreenshotTimeout()
                    isTakingScreenshot = false
                    Log.d("A11Y_SCREENSHOT", "failed code=$errorCode")
                    reportAccessibilityScreenshotFailure("error_$errorCode")
                }
            }
        )
        return true
    }

    private fun armScreenshotTimeout() {
        clearScreenshotTimeout()
        screenshotTimeoutRunnable = Runnable {
            if (isTakingScreenshot) {
                isTakingScreenshot = false
                DiagnosticLogStore.append(this, "A11Y_SCREENSHOT", "timeout_reset")
                Log.d("A11Y_SCREENSHOT", "timeout reset")
            }
        }.also {
            mainHandler.postDelayed(it, SCREENSHOT_TIMEOUT_MS)
        }
    }

    private fun clearScreenshotTimeout() {
        screenshotTimeoutRunnable?.let(mainHandler::removeCallbacks)
        screenshotTimeoutRunnable = null
    }

    private fun processAccessibilityOrderBitmap(bitmap: Bitmap) {
        if (!MonitoringState.isEnabled(this)) {
            bitmap.recycle()
            return
        }
        if (isProcessingOrder) {
            DiagnosticLogStore.append(this, "CAPTURE", "skip_processing_busy")
            bitmap.recycle()
            return
        }

        isProcessingOrder = true
        armOcrTimeout()
        DiagnosticLogStore.append(this, "CAPTURE", "process source=accessibility ${bitmap.width}x${bitmap.height}")
        OcrHelper.runOrderRegions(this, bitmap) { regionText ->
            runCatching {
                Log.d("OCR_RESULT", regionText.fullText)
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
                        merchantText = regionText.merchantText,
                        merchantWideText = regionText.merchantWideText,
                        addressText = regionText.addressText,
                        addressWideText = regionText.addressWideText,
                        addressLowerText = regionText.addressLowerText
                    )
                ) else null
                val foundOrder = handleAccessibilityOrder(order, bitmap)
                DebugSampleStore.saveCapture(this, bitmap, regionText, foundOrder)
                if (!foundOrder) {
                    DiagnosticLogStore.append(this, "CAPTURE", "no_order source=accessibility")
                }
            }.onFailure { throwable ->
                Log.e("CAPTURE", "accessibility OCR callback failed", throwable)
                CrashLogStore.save(this, "accessibility_ocr_callback", throwable)
            }
            clearOcrTimeout()
            isProcessingOrder = false
            bitmap.recycle()
        }
    }

    private fun armOcrTimeout() {
        clearOcrTimeout()
        ocrTimeoutRunnable = Runnable {
            if (isProcessingOrder) {
                isProcessingOrder = false
                DiagnosticLogStore.append(this, "CAPTURE", "ocr_timeout_reset")
                Log.d("CAPTURE", "OCR timeout reset")
            }
        }.also {
            mainHandler.postDelayed(it, OCR_TIMEOUT_MS)
        }
    }

    private fun clearOcrTimeout() {
        ocrTimeoutRunnable?.let(mainHandler::removeCallbacks)
        ocrTimeoutRunnable = null
    }

    private fun handleAccessibilityOrder(order: com.example.gongderefuser.model.OrderData?, bitmap: Bitmap?): Boolean {
        if (order == null) {
            Log.d("ORDER_ANALYSIS", "incomplete order ignored")
            return false
        }

        val signature = "${order.price}-${order.minutes}-${order.distance}-${order.deliveryCount}-${order.isAddOnOrder}"
        val now = System.currentTimeMillis()
        if (signature == lastShownOrderSignature && now - lastShownOrderTime < 30_000) {
            Log.d("ORDER_ANALYSIS", "duplicate order ignored")
            return true
        }

        lastShownOrderSignature = signature
        lastShownOrderTime = now

        val analysis = OrderAnalyzer.analyzeResult(this, order)
        val screenshotPath = bitmap?.takeIf {
            AppSettings.isOrderCaptureEnabled(this)
        }?.let {
            OrderCaptureStore.saveOrderCapture(this, it, analysis)
        }.orEmpty()
        OrderHistory.add(this, analysis, "实时", screenshotPath)

        Log.d("ORDER_ANALYSIS", OrderAnalyzer.analyze(order))
        mainHandler.post {
            showAnalysisOverlayInternal(analysis)
        }
        return true
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
        hideOverlay()
        if (activeService === this) {
            activeService = null
        }
        super.onDestroy()
    }

    private fun showStatusOverlayInternal() {
        clearCollapseTimer()
        hideOverlay()

        if (!MonitoringState.isEnabled(this)) return

        val density = resources.displayMetrics.density
        val overlaySize = (40 * density).toInt()
        val isWorking = isMonitoringWorking()
        val params = WindowManager.LayoutParams(
            overlaySize,
            overlaySize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = getOverlayX((12 * density).toInt())
            y = getOverlayY((56 * density).toInt())
        }

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(238, 255, 255, 255))
                cornerRadius = 12 * density
                setStroke((1 * density).toInt(), Color.argb(160, 148, 163, 184))
            }
            elevation = 8 * density
        }

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isWorking) COLOR_SUCCESS else COLOR_DANGER)
            }
        }

        container.addView(
            dot,
            FrameLayout.LayoutParams((13 * density).toInt(), (13 * density).toInt()).apply {
                gravity = Gravity.CENTER
            }
        )

        makeDraggable(container, params, windowManager, persistPosition = true)
        windowManager.addView(container, params)
        overlayView = container
    }

    private fun showAnalysisOverlayInternal(analysis: AnalysisResult) {
        clearCollapseTimer()
        hideOverlay()
        playAnalysisTone(analysis)

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

        collapseRunnable = Runnable {
            showStatusOverlayInternal()
        }.also {
            mainHandler.postDelayed(it, 30_000)
        }
    }

    private fun playAnalysisTone(analysis: AnalysisResult) {
        if (!AppSettings.isSoundEnabled(this)) return

        val soundRes = when (analysis.recommendation) {
            "建议接单" -> R.raw.whitelist_accept
            "慎重考虑" -> R.raw.normal_order
            else -> R.raw.blacklist_reject
        }

        runCatching {
            val player = MediaPlayer.create(this, soundRes) ?: return
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { mediaPlayer, _, _ ->
                mediaPlayer.release()
                true
            }
            player.start()
        }
    }

    private fun createAnalysisCard(analysis: AnalysisResult): LinearLayout {
        val density = resources.displayMetrics.density
        val accentColor = recommendationColor(analysis)

        val background = GradientDrawable().apply {
            setColor(Color.argb(246, 255, 255, 255))
            cornerRadius = 16 * density
            setStroke((2 * density).toInt(), accentColor)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
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
            setPadding(0, dp(8), 0, dp(8))
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
                showStatusOverlayInternal()
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
                bottomMargin = dp(8)
            }
        )

        addMerchantStatusBlock(card, analysis)
        addListMatchBlock(card, analysis)
        addResultLine(card, "类型", analysis.orderType)
        if (analysis.isSameLocationStack) {
            addResultLine(card, "爽单", "取货或配送地点相同")
        }
        addResultLine(card, "金额", "${analysis.price} 元")
        addResultLine(card, "时间", "${analysis.minutes} 分钟")
        addResultLine(card, "距离", "${OrderAnalyzer.formatDistance(analysis.distance)} 公里")
        addResultLine(card, "预计时薪", "${OrderAnalyzer.formatMoney(analysis.effectiveHourly)} 元/小时")

        return card
    }

    private fun recommendationColor(analysis: AnalysisResult): Int {
        return when (analysis.recommendation) {
            "建议接单" -> COLOR_SUCCESS
            "慎重考虑" -> COLOR_WARNING
            else -> COLOR_DANGER
        }
    }

    private fun showAddListEntryDialog(keyword: String, isWhitelist: Boolean) {
        val keywordInput = EditText(this).apply {
            hint = "商家名称或地址关键词"
            setText(keyword)
            setSingleLine(false)
            minLines = if (keyword.contains('\n')) 2 else 1
        }
        val noteInput = EditText(this).apply {
            hint = if (isWhitelist) {
                "备注：位置、出餐速度等"
            } else {
                "原因：不好取/不好送/难停车等"
            }
            setSingleLine(false)
            minLines = 2
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), 0)
            addView(keywordInput)
            addView(noteInput)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isWhitelist) "添加白名单" else "添加黑名单")
            .setView(content)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val keyword = keywordInput.text.toString().trim()
                val note = noteInput.text.toString().trim()
                if (keyword.length < 2) {
                    keywordInput.error = "至少两个字"
                    return@setOnClickListener
                }
                val saved = saveListEntry(keyword, note, isWhitelist)
                Toast.makeText(
                    this,
                    if (saved) {
                        "已添加到${if (isWhitelist) "白名单" else "黑名单"}"
                    } else {
                        "名单里已有相同商家或地址"
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
        if (cleanKeyword.length < 2 || cleanKeyword == "未识别") return

        val dialog = AlertDialog.Builder(this)
            .setTitle("添加名单")
            .setMessage(cleanKeyword)
            .setPositiveButton("加白名单") { _, _ ->
                showAddListEntryDialog(cleanKeyword, isWhitelist = true)
            }
            .setNegativeButton("加黑名单") { _, _ ->
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
            text = label
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
        }

        val valueView = TextView(this).apply {
            text = value
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            setTextColor(COLOR_TEXT_PRIMARY)
            maxLines = 2
        }

        row.addView(
            labelView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            valueView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f)
        )
        parent.addView(row)
    }

    private fun addMerchantStatusBlock(parent: LinearLayout, analysis: AnalysisResult) {
        val accentColor = when {
            analysis.isBlacklisted -> COLOR_DANGER
            analysis.isWhitelisted -> COLOR_SUCCESS
            else -> Color.rgb(218, 225, 233)
        }
        val fillColor = when {
            analysis.isBlacklisted -> Color.rgb(254, 202, 202)
            analysis.isWhitelisted -> Color.rgb(187, 247, 208)
            else -> Color.WHITE
        }

        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        }
        addColoredResultLine(
            block,
            "商家",
            analysis.storeName.ifBlank { "未识别" },
            fillColor,
            accentColor
        )
        addColoredResultLine(
            block,
            "地址",
            analysis.storeAddress.ifBlank { "未识别" },
            fillColor,
            accentColor
        )
        parent.addView(block)
    }

    private fun addListMatchBlock(parent: LinearLayout, analysis: AnalysisResult) {
        val isBlacklisted = analysis.isBlacklisted
        val isWhitelisted = analysis.isWhitelisted
        if (!isBlacklisted && !isWhitelisted) return

        val label = if (isBlacklisted) "命中黑名单" else "命中白名单"
        val keyword = if (isBlacklisted) analysis.matchedBlacklistKeyword else analysis.matchedWhitelistKeyword
        val note = if (isBlacklisted) analysis.blacklistNote else analysis.whitelistNote
        val fillColor = if (isBlacklisted) Color.rgb(220, 38, 38) else Color.rgb(22, 163, 74)

        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(fillColor)
                cornerRadius = 12 * resources.displayMetrics.density
            }
        }
        block.addView(TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        block.addView(TextView(this).apply {
            text = buildString {
                append("标签：").append(keyword.ifBlank { "已命中名单规则" })
                if (note.isNotBlank()) append("\n备注：").append(note)
            }
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(4), 0, 0)
        })
        parent.addView(block, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(6)
        })
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
            gravity = Gravity.END
            setTextColor(COLOR_TEXT_PRIMARY)
            maxLines = if (label == "地址") 4 else 2
            setLineSpacing(0f, 1.08f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (value != "未识别") {
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
        if (isBetaPackage()) {
            return MonitoringState.isEnabled(this) && activeService === this
        }
        val hasProjection = CaptureHolder.data != null &&
                CaptureHolder.resultCode == Activity.RESULT_OK
        return MonitoringState.isEnabled(this) && hasProjection && ScreenCaptureService.isRunning
    }

    private fun isBetaPackage(): Boolean {
        return packageName.endsWith(".beta")
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val OVERLAY_PREFS_NAME = "gongde_refuser_overlay"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        private const val SCREENSHOT_TIMEOUT_MS = 3_500L
        private const val OCR_TIMEOUT_MS = 8_000L

        private val COLOR_SUCCESS = Color.rgb(34, 197, 94)
        private val COLOR_DANGER = Color.rgb(239, 68, 68)
        private val COLOR_WARNING = Color.rgb(245, 158, 11)
        private val COLOR_TEXT_PRIMARY = Color.rgb(22, 27, 34)
        private val COLOR_TEXT_SECONDARY = Color.rgb(88, 96, 105)

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
                    service.showStatusOverlayInternal()
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

        fun isServiceActive(): Boolean {
            return activeService != null
        }
    }
}
