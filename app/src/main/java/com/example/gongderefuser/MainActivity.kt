package com.example.gongderefuser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.gongderefuser.analyzer.OrderAnalyzer
import com.example.gongderefuser.analyzer.OrderAnalyzer.AnalysisResult
import com.example.gongderefuser.analyzer.RuleManager
import com.example.gongderefuser.analyzer.RuleSettings
import com.example.gongderefuser.model.OrderData
import com.example.gongderefuser.parser.OrderParser

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitleText: TextView
    private lateinit var statusDetailText: TextView
    private lateinit var statusToggleButton: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var incomeInput: EditText
    private lateinit var distanceInput: EditText
    private lateinit var minutesInput: EditText
    private lateinit var targetHourlyInput: EditText
    private lateinit var manualStoreNameInput: EditText
    private lateinit var manualAddressInput: EditText

    private lateinit var normalMinPriceInput: EditText
    private lateinit var normalMaxDistanceInput: EditText
    private lateinit var normalMaxMinutesInput: EditText
    private lateinit var normalTargetHourlyInput: EditText
    private lateinit var normalCostPerKmInput: EditText
    private lateinit var blackMinPriceInput: EditText
    private lateinit var blackMaxDistanceInput: EditText
    private lateinit var blackMaxMinutesInput: EditText
    private lateinit var blackTargetHourlyInput: EditText
    private lateinit var blackCostPerKmInput: EditText
    private lateinit var whitelistKeywordInput: EditText
    private lateinit var whitelistNoteInput: EditText
    private lateinit var blacklistInput: EditText
    private lateinit var blacklistNoteInput: EditText
    private lateinit var whitelistTagContainer: LinearLayout
    private lateinit var blacklistTagContainer: LinearLayout
    private val whitelistTags = mutableListOf<RuleSettings.ListEntry>()
    private val blacklistTags = mutableListOf<RuleSettings.ListEntry>()

    private enum class Screen {
        Home,
        Manual,
        Settings
    }

    private var currentScreen = Screen.Home
    private var hasPromptedAccessibility = false

    private val pickOrderImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) {
                setAnalyzing(false)
                return@registerForActivityResult
            }

            analyzeImage(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentScreen == Screen.Home) {
                    finish()
                } else {
                    showHome()
                }
            }
        })

        showHome()
        promptAccessibilityIfNeeded()
        restoreMonitoringIfPossible()
        updateMonitoringUi()
    }

    override fun onResume() {
        super.onResume()
        promptAccessibilityIfNeeded()
        restoreMonitoringIfPossible()
        if (currentScreen == Screen.Home && ::statusTitleText.isInitialized) {
            updateMonitoringUi()
        }
    }

    private fun showHome() {
        currentScreen = Screen.Home

        val layout = createBaseLayout()
        addHeader(layout)

        statusCard = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val statusHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                rightMargin = dp(10)
            }
        }
        statusTitleText = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        statusDetailText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(10), 0, 0)
        }
        statusToggleButton = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedFill(Color.WHITE, 999f)
            setOnClickListener { toggleMonitoring() }
        }
        progressBar = ProgressBar(this).apply {
            visibility = ProgressBar.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(14)
            }
        }
        statusHeader.addView(statusDot)
        statusHeader.addView(statusTitleText, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))
        statusHeader.addView(statusToggleButton)
        statusCard.addView(statusHeader)
        statusCard.addView(statusDetailText)
        statusCard.addView(progressBar)
        layout.addView(statusCard)

        val actionRow = createCardRow()
        val uploadCard = createActionCard(
            title = "截图分析",
            detail = "上传订单截图",
            accentColor = COLOR_TEXT_PRIMARY
        ).apply {
            setOnClickListener {
                setAnalyzing(true, "请选择订单截图", "从相册选择订单截图后，我会立即进行 OCR 分析。")
                pickOrderImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        }
        actionRow.addView(uploadCard, rowItemParams(rightMargin = dp(7)))
        actionRow.addView(
            createActionCard("手动计算", "输入金额/时间/距离", COLOR_WARNING).apply {
                setOnClickListener { showManualCalculator() }
            },
            rowItemParams(leftMargin = dp(7))
        )
        layout.addView(actionRow)
        layout.addView(createActionCard("规则设置", "规则与黑名单", COLOR_SUCCESS).apply {
            setOnClickListener { showRuleSettings() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(96)
            ).apply {
                bottomMargin = dp(14)
            }
        })
        layout.addView(createOrderHistoryCard())

        setBaseContent(layout)
        updateMonitoringUi()
    }

    private fun createOrderHistoryCard(): LinearLayout {
        val records = OrderHistory.load(this)
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "识别记录"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (records.isNotEmpty()) {
            header.addView(TextView(this).apply {
                text = "清空"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_DANGER)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = roundedStroke(Color.WHITE, COLOR_DANGER, 10f)
                setOnClickListener {
                    OrderHistory.clear(this@MainActivity)
                    showHome()
                }
            })
        }
        card.addView(header)

        if (records.isEmpty()) {
            card.addView(TextView(this).apply {
                text = "还没有识别记录。实时监测或截图分析成功后，会自动保存在这里。"
                textSize = 14f
                setTextColor(COLOR_TEXT_SECONDARY)
                setLineSpacing(0f, 1.12f)
                setPadding(0, dp(12), 0, 0)
            })
            return card
        }

        records.forEachIndexed { index, record ->
            if (index > 0) {
                card.addView(View(this).apply {
                    setBackgroundColor(0xFFE5E7EB.toInt())
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                ).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(10)
                })
            } else {
                card.addView(View(this), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(10)
                ))
            }
            addHistoryRow(card, record)
        }

        return card
    }

    private fun addHistoryRow(parent: LinearLayout, record: OrderHistory.Record) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = record.storeName
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(this).apply {
            text = if (record.shouldAccept) "接单" else "拒单"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundedFill(if (record.shouldAccept) COLOR_SUCCESS else COLOR_DANGER, 999f)
        })
        row.addView(titleRow)

        val markers = buildList {
            add(record.orderType)
            if (record.isSameLocationStack) add("爽单")
            if (record.isWhitelisted) add("白名单")
            if (record.isBlacklisted) add("黑名单")
        }.joinToString(" / ")
        row.addView(TextView(this).apply {
            text = "${record.timeLabel()} · ${record.source} · $markers"
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(TextView(this).apply {
            text = "${record.price} 元 · ${record.minutes} 分钟 · ${
                OrderAnalyzer.formatDistance(record.distance)
            } 公里 · ${OrderAnalyzer.formatMoney(record.effectiveHourly)} 元/小时"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(0, dp(4), 0, 0)
        })

        parent.addView(row)
    }

    private fun showManualCalculator() {
        currentScreen = Screen.Manual

        val layout = createBaseLayout()
        addSubHeader(layout, "手动计算", "没有截图时，直接输入订单信息。")

        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        incomeInput = addLabeledNumberInput(card, "单笔预期收入", "元")
        distanceInput = addLabeledNumberInput(card, "总跑单里程", "公里")
        minutesInput = addLabeledNumberInput(card, "预估总时间", "分钟")
        targetHourlyInput = addLabeledNumberInput(card, "个人目标时薪", "预设 ${RuleManager.DEFAULT_TARGET_HOURLY}").apply {
            setText(RuleManager.DEFAULT_TARGET_HOURLY.toString())
        }
        manualStoreNameInput = addLabeledTextInput(card, "商家名称", "可选，用于加入白名单/黑名单")
        manualAddressInput = addLabeledTextInput(card, "配送地址", "可选，用于加入白名单/黑名单")
        card.addView(createButton(primary = true).apply {
            text = "计算划算度"
            setOnClickListener { calculateManualOrder() }
        })
        layout.addView(card)

        setBaseContent(layout)
    }

    private fun showRuleSettings() {
        currentScreen = Screen.Settings

        val settings = RuleSettings.load(this)
        val layout = createBaseLayout()
        addSubHeader(layout, "规则设置", "命中黑名单关键词时，会使用黑名单规则。")

        val normalCard = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        normalCard.addView(createSectionTitle("正常单规则"))
        normalMinPriceInput = addLabeledNumberInput(normalCard, "最低金额", "元").apply {
            setText(settings.normal.minPrice.toString())
        }
        normalMaxDistanceInput = addLabeledNumberInput(normalCard, "最大公里", "公里").apply {
            setText(OrderAnalyzer.formatDistance(settings.normal.maxDistance))
        }
        normalMaxMinutesInput = addLabeledNumberInput(normalCard, "最大时间", "分钟").apply {
            setText(settings.normal.maxMinutes.toString())
        }
        normalTargetHourlyInput = addLabeledNumberInput(normalCard, "目标时薪", "元/小时").apply {
            setText(settings.normal.targetHourly.toString())
        }
        normalCostPerKmInput = addLabeledNumberInput(normalCard, "每公里成本", "元/公里").apply {
            setText(OrderAnalyzer.formatDistance(settings.normal.costPerKm))
        }
        layout.addView(normalCard)

        val blackCard = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        blackCard.addView(createSectionTitle("黑名单规则"))
        blackMinPriceInput = addLabeledNumberInput(blackCard, "最低金额", "元").apply {
            setText(settings.blacklist.minPrice.toString())
        }
        blackMaxDistanceInput = addLabeledNumberInput(blackCard, "最大公里", "公里").apply {
            setText(OrderAnalyzer.formatDistance(settings.blacklist.maxDistance))
        }
        blackMaxMinutesInput = addLabeledNumberInput(blackCard, "最大时间", "分钟").apply {
            setText(settings.blacklist.maxMinutes.toString())
        }
        blackTargetHourlyInput = addLabeledNumberInput(blackCard, "目标时薪", "元/小时").apply {
            setText(settings.blacklist.targetHourly.toString())
        }
        blackCostPerKmInput = addLabeledNumberInput(blackCard, "每公里成本", "元/公里").apply {
            setText(OrderAnalyzer.formatDistance(settings.blacklist.costPerKm))
        }
        blackCard.addView(createSectionTitle("白名单商家/地址"))
        blackCard.addView(createTagInputRow(isWhitelist = true))
        whitelistTagContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        blackCard.addView(whitelistTagContainer)
        whitelistTags.clear()
        whitelistTags.addAll(settings.whitelistEntries)
        renderListTags(isWhitelist = true)

        blackCard.addView(createSectionTitle("黑名单商家/地址"))
        blackCard.addView(createTagInputRow(isWhitelist = false))
        blacklistTagContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        blackCard.addView(blacklistTagContainer)
        blacklistTags.clear()
        blacklistTags.addAll(settings.blacklistEntries)
        renderListTags(isWhitelist = false)

        blackCard.addView(createButton(primary = true).apply {
            text = "保存规则设置"
            setOnClickListener { saveRuleSettings() }
        })
        layout.addView(blackCard)

        setBaseContent(layout)
    }

    private fun createTagInputRow(isWhitelist: Boolean): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val keywordInput = createNumberlessInput(
            if (isWhitelist) "商家名称或地址关键词" else "不好送商家/地址关键词"
        )
        val noteInput = createNumberlessInput(
            if (isWhitelist) "备注：位置、出餐速度等" else "原因：不好取/不好送/难停车等"
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        if (isWhitelist) {
            whitelistKeywordInput = keywordInput
            whitelistNoteInput = noteInput
        } else {
            blacklistInput = keywordInput
            blacklistNoteInput = noteInput
        }

        val addButton = Button(this).apply {
            text = if (isWhitelist) "添加白名单标签" else "添加黑名单标签"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = roundedFill(if (isWhitelist) COLOR_SUCCESS else COLOR_DANGER, 12f)
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { addListTagFromInput(isWhitelist) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(8)
            }
        }

        container.addView(keywordInput)
        container.addView(noteInput)
        container.addView(addButton)
        return container
    }

    private fun addListTagFromInput(isWhitelist: Boolean) {
        val keywordInput = if (isWhitelist) whitelistKeywordInput else blacklistInput
        val noteInput = if (isWhitelist) whitelistNoteInput else blacklistNoteInput
        val keyword = keywordInput.text.toString().trim()
        val note = noteInput.text.toString().trim()

        if (keyword.length < 2) {
            Toast.makeText(this, "请输入至少两个字的关键词", Toast.LENGTH_SHORT).show()
            return
        }

        val list = if (isWhitelist) whitelistTags else blacklistTags
        if (list.none { it.keyword == keyword }) {
            list.add(RuleSettings.ListEntry(keyword, note))
            renderListTags(isWhitelist)
        }
        keywordInput.setText("")
        noteInput.setText("")
    }

    private fun renderListTags(isWhitelist: Boolean) {
        val list = if (isWhitelist) whitelistTags else blacklistTags
        val container = if (isWhitelist) whitelistTagContainer else blacklistTagContainer
        val accentColor = if (isWhitelist) COLOR_SUCCESS else COLOR_DANGER
        val emptyText = if (isWhitelist) "还没有白名单标签" else "还没有黑名单标签"

        container.removeAllViews()
        if (list.isEmpty()) {
            container.addView(TextView(this).apply {
                text = emptyText
                textSize = 13f
                setTextColor(COLOR_TEXT_HINT)
                setPadding(0, dp(6), 0, dp(6))
            })
            return
        }

        list.forEach { entry ->
            container.addView(TextView(this).apply {
                text = if (entry.note.isBlank()) {
                    "${entry.keyword}  ×"
                } else {
                    "${entry.keyword}：${entry.note}  ×"
                }
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accentColor)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = roundedStroke(Color.WHITE, accentColor, 999f)
                setOnClickListener {
                    list.remove(entry)
                    renderListTags(isWhitelist)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                }
            })
        }
    }

    private fun addHeader(layout: LinearLayout) {
        layout.addView(TextView(this).apply {
            text = "功德拒絕器"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            gravity = Gravity.CENTER
        })
        layout.addView(TextView(this).apply {
            text = "实时识别 目標平台 订单，并给出接单参考"
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(18))
        })
    }

    private fun addSubHeader(layout: LinearLayout, title: String, subtitle: String) {
        layout.addView(createButton(primary = false).apply {
            text = "返回主页"
            setOnClickListener { showHome() }
        })
        layout.addView(TextView(this).apply {
            text = title
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        layout.addView(TextView(this).apply {
            text = subtitle
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(6), 0, dp(16))
        })
    }

    private fun setBaseContent(layout: LinearLayout) {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            isFillViewport = true
            addView(layout)
        }
        setContentView(scrollView)
    }

    private fun createBaseLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(24), dp(18), dp(28))
        }
    }

    private fun createCardRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
    }

    private fun rowItemParams(leftMargin: Int = 0, rightMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, dp(112), 1f).apply {
            this.leftMargin = leftMargin
            this.rightMargin = rightMargin
        }
    }

    private fun createActionCard(title: String, detail: String, accentColor: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedFill(Color.WHITE, 16f)
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true

            addView(View(this@MainActivity).apply {
                background = roundedFill(accentColor, 999f)
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                    bottomMargin = dp(10)
                }
            })
            addView(TextView(this@MainActivity).apply {
                tag = "title"
                text = title
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_PRIMARY)
            })
            addView(TextView(this@MainActivity).apply {
                tag = "detail"
                text = detail
                textSize = 13f
                setTextColor(COLOR_TEXT_SECONDARY)
                setPadding(0, dp(5), 0, 0)
            })
        }
    }

    private fun startAppFlow() {
        startActivity(Intent(this, ScreenCaptureActivity::class.java))
    }

    private fun toggleMonitoring() {
        val isEnabled = MonitoringState.isEnabled(this)

        if (isEnabled) {
            MonitoringState.setEnabled(this, false)
            CaptureHolder.clear()
            Toast.makeText(this, "实时监测已暂停", Toast.LENGTH_SHORT).show()
            updateMonitoringUi()
            return
        }

        Toast.makeText(this, "請授權螢幕分享，用於識別訂單", Toast.LENGTH_SHORT).show()
        startAppFlow()
    }

    private fun restoreMonitoringIfPossible() {
        if (
            MonitoringState.isEnabled(this) &&
            hasProjectionPermission() &&
            !ScreenCaptureService.isRunning
        ) {
            startForegroundService(Intent(this, ScreenCaptureService::class.java))
        }
    }

    private fun updateMonitoringUi() {
        val isEnabled = MonitoringState.isEnabled(this)
        val hasProjectionPermission = hasProjectionPermission()
        val isServiceRunning = ScreenCaptureService.isRunning

        if (::statusToggleButton.isInitialized) {
            statusToggleButton.text = when {
                isEnabled && !hasProjectionPermission && !isServiceRunning -> "重新授权"
                isEnabled -> "暂停监测"
                else -> "启用监测"
            }
        }

        when {
            isEnabled && isServiceRunning -> setStatus(
                title = "正在工作",
                detail = "后台 OCR 正在运行。收到订单后会自动弹出分析卡片。",
                color = COLOR_SUCCESS
            )
            isEnabled && hasProjectionPermission -> setStatus(
                title = "正在恢复",
                detail = "已授权屏幕分享，正在恢复后台识别服务。",
                color = COLOR_WARNING
            )
            isEnabled -> setStatus(
                title = "需要重新授权",
                detail = "监测开关已开启，但屏幕分享授权已失效。请重新授权整个画面。",
                color = COLOR_DANGER
            )
            hasProjectionPermission || isServiceRunning -> setStatus(
                title = "已暂停",
                detail = "OCR 暂时不会自动识别订单。点击启用后会继续后台监测。",
                color = COLOR_DANGER
            )
            else -> setStatus(
                title = "尚未启用",
                detail = "点击启用监测，授权时请选择分享整个画面。",
                color = COLOR_DANGER
            )
        }
    }

    private fun analyzeImage(uri: Uri) {
        setAnalyzing(true, "正在识别截图", "OCR 正在读取订单内容，完成后会显示分析结果。")

        val bitmap = try {
            loadBitmap(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            setAnalyzing(false)
            Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show()
            return
        }

        OcrHelper.runOrderRegions(this, bitmap) { regionText ->
            runOnUiThread {
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
                if (order == null) {
                    setAnalyzing(false)
                    showFailureResult(OrderParser.buildFailureMessage(regionText.fullText))
                } else {
                    setAnalyzing(false)
                    val analysis = OrderAnalyzer.analyzeResult(this, order)
                    OrderHistory.add(this, analysis, "截图")
                    showFloatingAnalysisResult(analysis)
                }
            }
        }
    }

    private fun showFloatingAnalysisResult(analysis: AnalysisResult) {
        val shown = MyAccessibilityService.showFeedback(analysis)
        if (!shown) {
            Toast.makeText(this, "请先开启无障碍服务，用统一悬浮卡片显示结果", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }
    }

    private fun setAnalyzing(isAnalyzing: Boolean, title: String = "", detail: String = "") {
        if (::progressBar.isInitialized) {
            progressBar.visibility = if (isAnalyzing) ProgressBar.VISIBLE else ProgressBar.GONE
        }
        if (currentScreen == Screen.Home && ::statusTitleText.isInitialized) {
            if (isAnalyzing) {
                setStatus(title, detail, COLOR_ACCENT)
            } else {
                updateMonitoringUi()
            }
        }
    }

    private fun calculateManualOrder() {
        val income = incomeInput.text.toString().toIntOrNull()
        val distance = distanceInput.text.toString().toDoubleOrNull()
        val minutes = minutesInput.text.toString().toIntOrNull()
        val targetHourly = targetHourlyInput.text.toString()
            .toIntOrNull()
            ?: RuleManager.DEFAULT_TARGET_HOURLY

        if (
            income == null ||
            distance == null ||
            minutes == null ||
            income <= 0 ||
            distance <= 0.0 ||
            minutes <= 0 ||
            targetHourly <= 0
        ) {
            Toast.makeText(this, "请输入有效的收入、里程、时间和目标时薪", Toast.LENGTH_SHORT).show()
            return
        }

        showAnalysisResult(
            OrderAnalyzer.analyzeResult(
                OrderData(
                    price = income,
                    distance = distance,
                    minutes = minutes,
                    isTargetOffer = false,
                    storeName = manualStoreNameInput.text.toString().trim(),
                    address = manualAddressInput.text.toString().trim()
                ),
                targetHourly
            )
        )
    }

    private fun saveRuleSettings() {
        val normal = readRuleConfig(
            minPriceInput = normalMinPriceInput,
            maxDistanceInput = normalMaxDistanceInput,
            maxMinutesInput = normalMaxMinutesInput,
            targetHourlyInput = normalTargetHourlyInput,
            costPerKmInput = normalCostPerKmInput
        )
        val blacklist = readRuleConfig(
            minPriceInput = blackMinPriceInput,
            maxDistanceInput = blackMaxDistanceInput,
            maxMinutesInput = blackMaxMinutesInput,
            targetHourlyInput = blackTargetHourlyInput,
            costPerKmInput = blackCostPerKmInput
        )

        if (normal == null || blacklist == null) {
            Toast.makeText(this, "请输入有效的规则数字", Toast.LENGTH_SHORT).show()
            return
        }

        RuleSettings.save(
            context = this,
            normal = normal,
            blacklist = blacklist,
            whitelistText = RuleSettings.serializeEntries(whitelistTags),
            blacklistText = RuleSettings.serializeEntries(blacklistTags)
        )
        Toast.makeText(this, "规则设置已保存", Toast.LENGTH_SHORT).show()
        showHome()
    }

    private fun readRuleConfig(
        minPriceInput: EditText,
        maxDistanceInput: EditText,
        maxMinutesInput: EditText,
        targetHourlyInput: EditText,
        costPerKmInput: EditText
    ): RuleSettings.RuleConfig? {
        val minPrice = minPriceInput.text.toString().toIntOrNull()
        val maxDistance = maxDistanceInput.text.toString().toDoubleOrNull()
        val maxMinutes = maxMinutesInput.text.toString().toIntOrNull()
        val targetHourly = targetHourlyInput.text.toString().toIntOrNull()
        val costPerKm = costPerKmInput.text.toString().toDoubleOrNull()

        if (
            minPrice == null ||
            maxDistance == null ||
            maxMinutes == null ||
            targetHourly == null ||
            costPerKm == null ||
            minPrice < 0 ||
            maxDistance <= 0.0 ||
            maxMinutes <= 0 ||
            targetHourly <= 0 ||
            costPerKm < 0.0
        ) {
            return null
        }

        return RuleSettings.RuleConfig(
            minPrice = minPrice,
            maxDistance = maxDistance,
            maxMinutes = maxMinutes,
            targetHourly = targetHourly,
            costPerKm = costPerKm
        )
    }

    private fun showAnalysisResult(analysis: AnalysisResult) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(10))
        }

        content.addView(TextView(this).apply {
            text = "订单分析结果"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        addMerchantStatusCard(content, analysis)
        content.addView(TextView(this).apply {
            text = if (analysis.shouldAccept) "推荐接单" else "建议拒单"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(12))
            background = roundedFill(if (analysis.shouldAccept) COLOR_SUCCESS else COLOR_DANGER, 14f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(12)
            }
        })
        if (analysis.isSameLocationStack) {
            addResultRow(content, "爽单提示", "取货或配送地点相同")
        }
        addResultRow(content, "订单类型", analysis.orderType)
        addResultRow(content, "金额", "${analysis.price} 元")
        addResultRow(content, "时间", "${analysis.minutes} 分钟")
        addResultRow(content, "距离", "${OrderAnalyzer.formatDistance(analysis.distance)} 公里")
        addResultRow(content, "预计时薪", "${OrderAnalyzer.formatMoney(analysis.effectiveHourly)} 元 / 小时")
        content.addView(createListActionRow(analysis))

        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton("知道了", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                InsetDrawable(roundedFill(Color.WHITE, 18f), dp(18))
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(COLOR_ACCENT)
                isAllCaps = false
                textSize = 15f
            }
        }

        dialog.show()
    }

    private fun addMerchantStatusCard(parent: LinearLayout, analysis: AnalysisResult) {
        val accentColor = when {
            analysis.isBlacklisted -> COLOR_DANGER
            analysis.isWhitelisted -> COLOR_SUCCESS
            else -> COLOR_BORDER
        }
        val fillColor = when {
            analysis.isBlacklisted -> Color.rgb(254, 202, 202)
            analysis.isWhitelisted -> Color.rgb(187, 247, 208)
            else -> Color.WHITE
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(2)
            }
        }

        addColoredResultRow(card, "商家", analysis.storeName.ifBlank { "未识别" }, fillColor, accentColor)
        addColoredResultRow(card, "地址", analysis.storeAddress.ifBlank { "未识别" }, fillColor, accentColor)
        parent.addView(card)
    }

    private fun showFailureResult(message: String) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(8))
        }

        content.addView(TextView(this).apply {
            text = "未识别到完整订单"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        content.addView(TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(3f * resources.displayMetrics.density, 1.08f)
            setPadding(0, dp(12), 0, dp(2))
        })

        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton("知道了", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                InsetDrawable(roundedFill(Color.WHITE, 18f), dp(18))
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(COLOR_ACCENT)
                isAllCaps = false
                textSize = 15f
            }
        }

        dialog.show()
    }

    private fun addResultRow(parent: LinearLayout, label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = value
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            setTextColor(COLOR_TEXT_PRIMARY)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
        parent.addView(row)
    }

    private fun addColoredResultRow(
        parent: LinearLayout,
        label: String,
        value: String,
        fillColor: Int,
        strokeColor: Int
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedStroke(fillColor, strokeColor, 12f)
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.75f))
        row.addView(TextView(this).apply {
            text = value
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            setTextColor(COLOR_TEXT_PRIMARY)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.45f))
        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(6)
        })
    }

    private fun createListActionRow(analysis: AnalysisResult): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        row.addView(
            createSmallActionButton("加白名单", COLOR_SUCCESS) {
                showAddListEntryDialog(analysis, isWhitelist = true)
            },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                rightMargin = dp(6)
            }
        )
        row.addView(
            createSmallActionButton("加黑名单", COLOR_DANGER) {
                showAddListEntryDialog(analysis, isWhitelist = false)
            },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                leftMargin = dp(6)
            }
        )
        return row
    }

    private fun createSmallActionButton(label: String, color: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedFill(color, 12f)
            setOnClickListener { onClick() }
        }
    }

    private fun showAddListEntryDialog(analysis: AnalysisResult, isWhitelist: Boolean) {
        val keywordInput = createNumberlessInput("商家名称或地址关键词").apply {
            setText(analysis.storeName.ifBlank {
                if (isWhitelist) analysis.matchedWhitelistKeyword else analysis.matchedBlacklistKeyword
            })
        }
        val noteInput = createNumberlessInput(
            if (isWhitelist) "备注：位置、出餐速度等" else "原因：不好取/不好送/难停车等"
        ).apply {
            setText(if (isWhitelist) analysis.whitelistNote else analysis.blacklistNote)
            setSingleLine(false)
            minLines = 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
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
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val keyword = keywordInput.text.toString().trim()
                val note = noteInput.text.toString().trim()
                if (keyword.length < 2) {
                    keywordInput.error = "至少两个字"
                    return@setOnClickListener
                }
                saveListEntry(keyword, note, isWhitelist)
                Toast.makeText(this, "已添加到${if (isWhitelist) "白名单" else "黑名单"}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun saveListEntry(keyword: String, note: String, isWhitelist: Boolean) {
        val settings = RuleSettings.load(this)
        val whitelist = settings.whitelistEntries.toMutableList()
        val blacklist = settings.blacklistEntries.toMutableList()
        val target = if (isWhitelist) whitelist else blacklist

        target.removeAll { it.keyword == keyword }
        target.add(RuleSettings.ListEntry(keyword, note))

        RuleSettings.save(
            context = this,
            normal = settings.normal,
            blacklist = settings.blacklist,
            whitelistText = RuleSettings.serializeEntries(whitelist),
            blacklistText = RuleSettings.serializeEntries(blacklist)
        )
    }

    private fun addLabeledNumberInput(parent: LinearLayout, label: String, unit: String): EditText {
        parent.addView(TextView(this).apply {
            text = "$label（$unit）"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(10), 0, dp(5))
        })
        return createNumberInput("").also(parent::addView)
    }

    private fun addLabeledTextInput(parent: LinearLayout, label: String, hint: String): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(10), 0, dp(5))
        })
        return createNumberlessInput(hint).also(parent::addView)
    }

    private fun createNumberInput(hintText: String): EditText {
        return createNumberlessInput(hintText).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
    }

    private fun createNumberlessInput(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            textSize = 15f
            setTextColor(COLOR_TEXT_PRIMARY)
            setHintTextColor(COLOR_TEXT_HINT)
            setSingleLine(true)
            background = roundedStroke(COLOR_INPUT, COLOR_BORDER, 12f)
            setPadding(dp(14), 0, dp(14), 0)
            minHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun createSectionTitle(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(0, dp(4), 0, dp(6))
        }
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            background = roundedFill(Color.WHITE, 18f)
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
    }

    private fun createButton(primary: Boolean): Button {
        return Button(this).apply {
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(if (primary) Color.WHITE else COLOR_ACCENT)
            background = if (primary) {
                roundedFill(COLOR_ACCENT, 14f)
            } else {
                roundedStroke(Color.WHITE, COLOR_BORDER, 14f)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                bottomMargin = dp(12)
            }
        }
    }

    private fun setStatus(title: String, detail: String, color: Int) {
        statusTitleText.text = title
        statusDetailText.text = detail
        statusTitleText.setTextColor(Color.WHITE)
        statusDetailText.setTextColor(Color.WHITE)
        if (::statusCard.isInitialized) {
            statusCard.background = roundedFill(color, 18f)
        }
        if (::statusToggleButton.isInitialized) {
            statusToggleButton.setTextColor(color)
        }
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
    }

    private fun hasProjectionPermission(): Boolean {
        return CaptureHolder.resultCode == Activity.RESULT_OK && CaptureHolder.data != null
    }

    private fun promptAccessibilityIfNeeded() {
        if (hasPromptedAccessibility || isAccessibilityServiceEnabled()) return

        hasPromptedAccessibility = true
        AlertDialog.Builder(this)
            .setTitle("需要开启无障碍服务")
            .setMessage("功德拒絕器 需要无障碍服务来监听 目標平台 的订单画面变化。开启后，实时监测才会自动触发 OCR。")
            .setPositiveButton("去开启") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val serviceName = "$packageName/${MyAccessibilityService::class.java.name}"
        return enabledServices.split(':').any { enabledService ->
            enabledService.equals(serviceName, ignoreCase = true)
        }
    }

    private fun roundedFill(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
        }
    }

    private fun roundedStroke(fillColor: Int, strokeColor: Int, radiusDp: Float): GradientDrawable {
        return roundedFill(fillColor, radiusDp).apply {
            setStroke(dp(1), strokeColor)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private val COLOR_BACKGROUND = Color.rgb(246, 248, 250)
        private val COLOR_TEXT_PRIMARY = Color.rgb(22, 27, 34)
        private val COLOR_TEXT_SECONDARY = Color.rgb(88, 96, 105)
        private val COLOR_TEXT_HINT = Color.rgb(139, 148, 158)
        private val COLOR_BORDER = Color.rgb(218, 225, 233)
        private val COLOR_INPUT = Color.rgb(250, 251, 252)
        private val COLOR_ACCENT = Color.rgb(13, 148, 136)
        private val COLOR_SUCCESS = Color.rgb(34, 197, 94)
        private val COLOR_DANGER = Color.rgb(239, 68, 68)
        private val COLOR_WARNING = Color.rgb(245, 158, 11)
        private val COLOR_MUTED = Color.rgb(148, 163, 184)
    }
}
