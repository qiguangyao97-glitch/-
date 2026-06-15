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
        Rules,
        RuleDetail,
        AppSettings,
        OcrCalibration,
        History
    }

    private var currentScreen = Screen.Home
    private var hasPromptedAccessibility = false
    private var calibrationBitmap: Bitmap? = null
    private var calibrationSavedPath: String = ""
    private var calibrationView: OcrCalibrationView? = null

    private val pickOrderImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) {
                setAnalyzing(false)
                return@registerForActivityResult
            }

            analyzeImage(uri)
        }

    private val pickCalibrationImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) {
                setAnalyzing(false)
                return@registerForActivityResult
            }

            analyzeCalibrationImage(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!navigateBackOneLevel()) {
                    finish()
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
        layout.addView(createHistorySummaryCard())

        setBaseContent(layout)
        updateMonitoringUi()
    }

    private fun navigateBackOneLevel(): Boolean {
        return when (currentScreen) {
            Screen.Home -> false
            Screen.RuleDetail -> {
                showRuleSettings()
                true
            }
            Screen.Rules,
            Screen.AppSettings,
            Screen.OcrCalibration,
            Screen.Manual,
            Screen.History -> {
                showHome()
                true
            }
        }
    }

    private fun createHistorySummaryCard(): LinearLayout {
        val records = OrderHistory.load(this)
        return createActionCard(
            title = "识别记录",
            detail = if (records.isEmpty()) "还没有记录" else "已保存 ${records.size} 条，点击查看",
            accentColor = COLOR_ACCENT
        ).apply {
            setOnClickListener { showHistory() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(96)
            ).apply {
                bottomMargin = dp(14)
            }
        }
    }

    private fun showHistory() {
        currentScreen = Screen.History

        val layout = createBaseLayout()
        addSubHeader(layout, "识别记录", "实时监测和截图分析的历史结果。")
        layout.addView(createOrderHistoryCard())
        setBaseContent(layout)
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
            text = "${record.score}分"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundedFill(recommendationColor(record.recommendation), 999f)
        })
        titleRow.addView(TextView(this).apply {
            text = "删除"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_DANGER)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundedStroke(Color.WHITE, COLOR_DANGER, 999f)
            setOnClickListener {
                OrderHistory.delete(this@MainActivity, record.timestamp)
                showHistory()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp(8)
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
        if (record.storeAddress.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = record.storeAddress
                textSize = 13f
                setTextColor(COLOR_TEXT_SECONDARY)
                setLineSpacing(0f, 1.12f)
                setPadding(0, dp(4), 0, 0)
            })
        }
        if (record.screenshotPath.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = "截图：${record.screenshotPath}"
                textSize = 11f
                setTextColor(COLOR_TEXT_SECONDARY)
                setLineSpacing(0f, 1.12f)
                setPadding(0, dp(4), 0, 0)
            })
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val keyword = buildHistoryListKeyword(record)
        actionRow.addView(
            createSmallActionButton("加白名单", COLOR_SUCCESS) {
                showAddListEntryDialog(keyword, "", isWhitelist = true)
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                rightMargin = dp(6)
            }
        )
        actionRow.addView(
            createSmallActionButton("加黑名单", COLOR_DANGER) {
                showAddListEntryDialog(keyword, "", isWhitelist = false)
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                leftMargin = dp(6)
            }
        )
        row.addView(actionRow)

        parent.addView(row)
    }

    private fun buildHistoryListKeyword(record: OrderHistory.Record): String {
        return listOf(record.storeName, record.storeAddress)
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "未识别商家" }
            .joinToString("\n")
            .ifBlank { record.storeName }
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
        currentScreen = Screen.Rules

        val settings = RuleSettings.load(this)
        val layout = createBaseLayout()
        addSubHeader(layout, "规则", "点击卡片进入对应项目修改。")

        val ruleRow = createCardRow()
        ruleRow.addView(
            createActionCard(
                "正常单规则",
                ruleSummary(settings.normal),
                COLOR_SUCCESS
            ).apply { setOnClickListener { showRuleConfigEditor(isBlacklistRule = false) } },
            rowItemParams(rightMargin = dp(7))
        )
        ruleRow.addView(
            createActionCard(
                "黑名单规则",
                ruleSummary(settings.blacklist),
                COLOR_DANGER
            ).apply { setOnClickListener { showRuleConfigEditor(isBlacklistRule = true) } },
            rowItemParams(leftMargin = dp(7))
        )
        layout.addView(ruleRow)

        val listRow = createCardRow()
        listRow.addView(
            createActionCard(
                "白名单标签",
                "${settings.whitelistEntries.size} 个关键词",
                COLOR_SUCCESS
            ).apply { setOnClickListener { showListEditor(isWhitelist = true) } },
            rowItemParams(rightMargin = dp(7))
        )
        listRow.addView(
            createActionCard(
                "黑名单标签",
                "${settings.blacklistEntries.size} 个关键词",
                COLOR_DANGER
            ).apply { setOnClickListener { showListEditor(isWhitelist = false) } },
            rowItemParams(leftMargin = dp(7))
        )
        layout.addView(listRow)

        setBaseContent(layout)
    }

    private fun ruleSummary(rule: RuleSettings.RuleConfig): String {
        return "${rule.minPrice}元 / ${OrderAnalyzer.formatDistance(rule.maxDistance)}公里 / ${rule.maxMinutes}分"
    }

    private fun showRuleConfigEditor(isBlacklistRule: Boolean) {
        currentScreen = Screen.RuleDetail

        val settings = RuleSettings.load(this)
        val rule = if (isBlacklistRule) settings.blacklist else settings.normal
        val layout = createBaseLayout()
        addSubHeader(
            layout,
            if (isBlacklistRule) "黑名单规则" else "正常单规则",
            "按比例评分：金额越高越加分，时间和距离越低越加分；目标时薪只限制最高建议等级。"
        )

        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val minPriceInput = addLabeledNumberInput(card, "目标金额", "元，低于按比例扣分，高于按比例加分").apply {
            setText(rule.minPrice.toString())
        }
        val maxDistanceInput = addLabeledNumberInput(card, "目标最大公里", "公里，低于加分，超过扣分").apply {
            setText(OrderAnalyzer.formatDistance(rule.maxDistance))
        }
        val maxMinutesInput = addLabeledNumberInput(card, "目标最大时间", "分钟，低于加分，超过扣分").apply {
            setText(rule.maxMinutes.toString())
        }
        val targetHourlyInput = addLabeledNumberInput(card, "目标时薪", "元/小时，低于时最高只显示慎重考虑").apply {
            setText(rule.targetHourly.toString())
        }
        val costPerKmInput = addLabeledNumberInput(card, "每公里成本", "元/公里").apply {
            setText(OrderAnalyzer.formatDistance(rule.costPerKm))
        }
        card.addView(createButton(primary = true).apply {
            text = "保存"
            setOnClickListener {
                val updatedRule = readRuleConfig(
                    minPriceInput = minPriceInput,
                    maxDistanceInput = maxDistanceInput,
                    maxMinutesInput = maxMinutesInput,
                    targetHourlyInput = targetHourlyInput,
                    costPerKmInput = costPerKmInput
                )
                if (updatedRule == null) {
                    Toast.makeText(this@MainActivity, "请输入有效的规则数字", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                RuleSettings.save(
                    context = this@MainActivity,
                    normal = if (isBlacklistRule) settings.normal else updatedRule,
                    blacklist = if (isBlacklistRule) updatedRule else settings.blacklist,
                    whitelistText = RuleSettings.serializeEntries(settings.whitelistEntries),
                    blacklistText = RuleSettings.serializeEntries(settings.blacklistEntries)
                )
                Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show()
                showRuleSettings()
            }
        })
        layout.addView(card)
        setBaseContent(layout)
    }

    private fun showListEditor(isWhitelist: Boolean) {
        currentScreen = Screen.RuleDetail

        val settings = RuleSettings.load(this)
        val layout = createBaseLayout()
        addSubHeader(
            layout,
            if (isWhitelist) "白名单标签" else "黑名单标签",
            if (isWhitelist) "命中后评分加分。" else "命中后使用黑名单规则并扣分。"
        )

        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(createTagInputRow(isWhitelist))
        if (isWhitelist) {
            whitelistTagContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(4))
            }
            card.addView(whitelistTagContainer)
            whitelistTags.clear()
            whitelistTags.addAll(settings.whitelistEntries)
        } else {
            blacklistTagContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(4))
            }
            card.addView(blacklistTagContainer)
            blacklistTags.clear()
            blacklistTags.addAll(settings.blacklistEntries)
        }
        renderListTags(isWhitelist)
        layout.addView(card)
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
        if (RuleSettings.containsMatchingEntry(whitelistTags + blacklistTags, keyword)) {
            Toast.makeText(this, "名单里已有相同商家或地址", Toast.LENGTH_SHORT).show()
        } else {
            list.add(RuleSettings.ListEntry(keyword, note))
            persistListTags(isWhitelist)
            Toast.makeText(this, "标签已保存", Toast.LENGTH_SHORT).show()
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
                    entry.keyword
                } else {
                    "${entry.keyword}：${entry.note}"
                }
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accentColor)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = roundedStroke(Color.WHITE, accentColor, 999f)
                setOnClickListener {
                    showEditListTagDialog(entry, isWhitelist)
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

    private fun showEditListTagDialog(entry: RuleSettings.ListEntry, isWhitelist: Boolean) {
        val keywordInput = createNumberlessInput("商家名称或地址关键词").apply {
            setText(entry.keyword)
        }
        val noteInput = createNumberlessInput(
            if (isWhitelist) "备注：位置、出餐速度等" else "原因：不好取/不好送/难停车等"
        ).apply {
            setText(entry.note)
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
            .setTitle(if (isWhitelist) "编辑白名单标签" else "编辑黑名单标签")
            .setView(content)
            .setPositiveButton("保存", null)
            .setNegativeButton("删除", null)
            .setNeutralButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val keyword = keywordInput.text.toString().trim()
                val note = noteInput.text.toString().trim()
                if (keyword.length < 2) {
                    keywordInput.error = "至少两个字"
                    return@setOnClickListener
                }
                val list = if (isWhitelist) whitelistTags else blacklistTags
                val index = list.indexOf(entry)
                val updatedEntry = RuleSettings.ListEntry(keyword, note)
                if (index >= 0) {
                    list[index] = updatedEntry
                } else {
                    list.add(updatedEntry)
                }
                list.distinctBy { it.keyword }.also {
                    list.clear()
                    list.addAll(it)
                }
                persistListTags(isWhitelist)
                Toast.makeText(this@MainActivity, "标签已保存", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val list = if (isWhitelist) whitelistTags else blacklistTags
                list.remove(entry)
                persistListTags(isWhitelist)
                Toast.makeText(this@MainActivity, "标签已删除", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun persistListTags(isWhitelist: Boolean) {
        val latest = RuleSettings.load(this)
        RuleSettings.save(
            context = this,
            normal = latest.normal,
            blacklist = latest.blacklist,
            whitelistText = RuleSettings.serializeEntries(if (isWhitelist) whitelistTags else latest.whitelistEntries),
            blacklistText = RuleSettings.serializeEntries(if (isWhitelist) latest.blacklistEntries else blacklistTags)
        )
        renderListTags(isWhitelist)
    }

    private fun showAppSettings() {
        currentScreen = Screen.AppSettings

        val layout = createBaseLayout()
        addSubHeader(layout, "设置", "应用偏好和版本信息。")

        val soundCard = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val soundEnabled = AppSettings.isSoundEnabled(this)
        val soundRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        soundRow.addView(TextView(this).apply {
            text = "反馈音效"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        soundRow.addView(TextView(this).apply {
            text = if (soundEnabled) "已开启" else "已关闭"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedFill(if (soundEnabled) COLOR_SUCCESS else COLOR_MUTED, 999f)
            setOnClickListener {
                AppSettings.setSoundEnabled(this@MainActivity, !soundEnabled)
                showAppSettings()
            }
        })
        soundCard.addView(soundRow)
        soundCard.addView(TextView(this).apply {
            text = "建议、慎重考虑、不建议会播放三种不同提示音。"
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(10), 0, 0)
        })
        layout.addView(soundCard)

        val debugCard = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        debugCard.addView(createSettingsToggleRow(
            title = "保存调试样本",
            enabled = AppSettings.isDebugSamplesEnabled(this),
            onToggle = { enabled ->
                AppSettings.setDebugSamplesEnabled(this, enabled)
                showAppSettings()
            }
        ))
        debugCard.addView(TextView(this).apply {
            text = "路径：${AppSettings.debugSamplePath(this@MainActivity)}\n每笔会保存原图、OCR 文本和带框图（文件名含 -regions），带框图用于查看当前 OCR 裁切区域。"
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(8), 0, dp(12))
        })
        debugCard.addView(createSettingsToggleRow(
            title = "保存实时订单截图",
            enabled = AppSettings.isOrderCaptureEnabled(this),
            onToggle = { enabled ->
                AppSettings.setOrderCaptureEnabled(this, enabled)
                showAppSettings()
            }
        ))
        debugCard.addView(TextView(this).apply {
            text = "仅调试时开启。识别到真实订单后保存完整截图，路径：${AppSettings.orderCapturePath(this@MainActivity)}"
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(8), 0, dp(12))
        })
        debugCard.addView(createActionCard(
            title = "OCR 校准",
            detail = "选择截图生成区域框图",
            accentColor = COLOR_ACCENT
        ).apply {
            setOnClickListener { showOcrCalibration() }
        })
        debugCard.addView(TextView(this).apply {
            text = "手动截图调试路径：${AppSettings.manualOcrDebugPath(this@MainActivity)}"
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(8), 0, dp(12))
        })
        debugCard.addView(createSettingsToggleRow(
            title = "记录无障碍事件日志",
            enabled = AppSettings.isAccessibilityLogEnabled(this),
            onToggle = { enabled ->
                AppSettings.setAccessibilityLogEnabled(this, enabled)
                showAppSettings()
            }
        ))
        debugCard.addView(TextView(this).apply {
            text = "用于确认订单弹窗是否能触发低功耗 OCR。"
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(8), 0, 0)
        })
        layout.addView(debugCard)

        val versionCard = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        versionCard.addView(createSectionTitle("版本信息"))
        versionCard.addView(TextView(this).apply {
            text = "版本：${appVersionLabel()}"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(0, dp(4), 0, dp(10))
        })
        versionCard.addView(createUpdateHistoryCard())
        layout.addView(versionCard)

        setBaseContent(layout)
    }

    private fun showOcrCalibration() {
        currentScreen = Screen.OcrCalibration

        val layout = createBaseLayout()
        addSubHeader(layout, "OCR 校准", "拖动框后保存；无锚点时会用这套框进行 OCR。")

        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(createSectionTitle("设置 OCR 框"))
        card.addView(TextView(this).apply {
            text = "选择截图后可拖动框，点右下角附近可调整大小。保存后，手动截图和实时识别在没有命中按钮锚点时，会使用这套 fallback 框。\n路径：${AppSettings.manualOcrDebugPath(this@MainActivity)}"
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(0f, 1.14f)
            setPadding(0, dp(8), 0, dp(12))
        })
        card.addView(createButton(primary = true).apply {
            text = "选择截图生成 OCR 框图"
            setOnClickListener {
                setAnalyzing(true, "请选择校准截图", "生成 OCR 区域框图后会提示保存位置。")
                pickCalibrationImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        })
        calibrationBitmap?.let { bitmap ->
            val view = OcrCalibrationView(this).apply {
                setImageAndRegions(bitmap, OcrCalibrationStore.load(this@MainActivity))
            }
            calibrationView = view
            card.addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(12)
            })
            if (calibrationSavedPath.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = "最近生成：$calibrationSavedPath"
                    textSize = 12f
                    setTextColor(COLOR_TEXT_SECONDARY)
                    setLineSpacing(0f, 1.12f)
                    setPadding(0, 0, 0, dp(10))
                })
            }
            val buttonRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            buttonRow.addView(createButton(primary = true).apply {
                text = "保存框设置"
                setOnClickListener {
                    val current = calibrationView?.currentRegions().orEmpty()
                    OcrCalibrationStore.save(this@MainActivity, current)
                    Toast.makeText(this@MainActivity, "OCR 框设置已保存", Toast.LENGTH_SHORT).show()
                    showOcrCalibration()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            })
            buttonRow.addView(createButton(primary = false).apply {
                text = "恢复默认"
                setOnClickListener {
                    OcrCalibrationStore.reset(this@MainActivity)
                    Toast.makeText(this@MainActivity, "已恢复默认 OCR 框", Toast.LENGTH_SHORT).show()
                    showOcrCalibration()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(6)
            })
            card.addView(buttonRow)
        } ?: run {
            calibrationView = null
            card.addView(TextView(this).apply {
                text = "还没有载入截图。先点上面的按钮选择一张订单截图。"
                textSize = 13f
                setTextColor(COLOR_TEXT_SECONDARY)
                setPadding(0, dp(12), 0, 0)
            })
        }
        layout.addView(card)
        setBaseContent(layout)
    }

    private fun createSettingsToggleRow(
        title: String,
        enabled: Boolean,
        onToggle: (Boolean) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_PRIMARY)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = if (enabled) "已开启" else "已关闭"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = roundedFill(if (enabled) COLOR_SUCCESS else COLOR_MUTED, 999f)
                setOnClickListener { onToggle(!enabled) }
            })
        }
    }

    private fun appVersionLabel(): String {
        return runCatching {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toString()
            }
            "${packageInfo.versionName} ($versionCode)"
        }.getOrDefault("未知")
    }

    private fun createUpdateHistoryCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, 0)
            updateHistoryItems().forEachIndexed { index, item ->
                addView(TextView(this@MainActivity).apply {
                    text = item
                    textSize = 13f
                    setTextColor(if (index == 0) COLOR_TEXT_PRIMARY else COLOR_TEXT_SECONDARY)
                    setLineSpacing(0f, 1.16f)
                    setPadding(0, if (index == 0) 0 else dp(10), 0, 0)
                    if (index == 0) {
                        typeface = Typeface.DEFAULT_BOLD
                    }
                })
            }
        }
    }

    private fun updateHistoryItems(): List<String> {
        return listOf(
            "1.0.19：OCR 校准页支持在截图上拖动和缩放 OCR 框并保存；保存后的框会作为无锚点 fallback 识别区域，影响手动截图和实时识别。",
            "1.0.18：设置页新增 OCR 校准入口，可选择截图生成原图、OCR 文本和 -regions 区域框图；手动截图分析也会自动保存 OCR 框图到 manual_ocr_debug。",
            "1.0.17：修复手动截图分析时 OCR 裁切函数递归导致闪退的问题；设置页补充说明调试样本中的 -regions 图片就是当前 OCR 裁切区域图。",
            "1.0.16：实时识别改为宽进严出，无锚点时允许备用区域解析但必须通过订单核心字段校验；调试样本新增带框 OCR 区域图；评分改为比例式，白名单只提示备注不加分；新增外送订单可独立显示。",
            "1.0.15：实时识别改为必须命中真实订单卡片锚点才解析；取消实时整屏文字回退，避免在相册、笔记旧截图或导航地图中误触发。",
            "1.0.14：商家和地址 OCR 改为固定 X 文字起点，并新增宽/安全双区域识别；圆形、方块只用于上下定位，减少图标噪声同时保留开头字母数字。",
            "1.0.13：黑白名单改为同时判断；黑名单命中时优先使用黑名单规则，白名单加分仍保留，避免好商家覆盖风险配送地址。",
            "1.0.12：基于测试版真实订单截图优化 OCR；启用取货圆点、配送方块作为商家和地址独立识别锚点，并过滤独享、类型等界面噪声，避免商家被识别成独享。",
            "1.0.11：订单类型不再使用“独享/叠单”文字判断；只按外送标签数量显示，一般外送为一单，外送带数字则显示对应数字单。",
            "1.0.10：修复无障碍截图偶发无回调导致后续订单不再响应的问题；新增截图和 OCR 超时自动复位，并在实时 OCR 完成后释放截图内存。",
            "1.0.09：实时调试样本只保存有效订单，并在 OCR 文本顶部加入可读摘要；黑白名单命中块显示标签和备注；减少同一订单弹窗的重复 OCR 触发。",
            "1.0.08：调试样本改为只保存成功识别出订单的截图和 OCR 文本；未识别到订单的画面不再保存，减少存储占用。",
            "1.0.07：确认无障碍截图可用后，移除测试版截图测试悬浮按钮；测试版保留无画面分享授权的实时订单识别流程。",
            "1.0.06：测试版开启监测不再请求画面分享授权；实时订单改为由无障碍截图直接进入 OCR 处理，首页文案同步改为无障碍监听状态。",
            "1.0.05：测试版新增无障碍截图测试悬浮按钮；点按后只验证当前界面能否截图，不跑订单识别，成功图片保存到 Download/功德拒絕器/accessibility_screenshot_tests。",
            "1.0.04：测试版改为只使用无障碍截图，不再失败回退录屏；截图失败会写入诊断日志并提示，方便直观看出新逻辑是否能触发。",
            "1.0.03：测试版优先使用无障碍截图抓取订单画面，失败才回退到原录屏截屏；新增监测诊断日志，记录锁屏、解锁、录屏中断和截图来源。",
            "1.0.02：新增可与正式版同时安装的测试版包名；测试版显示为功德拒絕器 測試版，方便外出测试新功能时保留旧版备用。",
            "1.0.01：建立三级版本号规则；设置页更新说明改为历史卡片，最新更新固定显示在第一行，并保留旧版本说明。",
            "1.0.0：订单数量改为只读取外送标签，外送为一单，外送带括号数字才判定多单；商家词库加入索引，降低截图分析时的模糊匹配耗时。",
            "1.0.0：状态卡片简化为工作绿色、停止红色；录屏或截图中断时发送通知提醒；调试样本和实时订单截图路径显示为 Download/功德拒絕器 下的可见目录。",
            "1.0.0：扩展林口龜山餐饮商家词库，支持品牌根词和分店后缀自动组合；地址区域左侧边界右移，并清理地址行开头图标噪声。",
            "1.0.0：新增商家词库 OCR 归一化，黑白名单匹配使用纠错后的商家名和地址；修正独享文字误当商家、订单类型误判等问题。",
            "1.0.0：优化三段式反馈音效、规则卡片、识别记录管理，并支持从结果卡片添加黑白名单。"
        )
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
            text = "返回"
            setOnClickListener { navigateBackOneLevel() }
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            addView(scrollView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            addView(createBottomNavigation())
        }
        setContentView(root)
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

    private fun createBottomNavigation(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = roundedStroke(Color.WHITE, COLOR_BORDER, 18f)
        }
        nav.addView(createNavItem("主页", isHomeSection()) { showHome() }, navItemParams())
        nav.addView(createNavItem("规则", isRuleSection()) { showRuleSettings() }, navItemParams())
        nav.addView(createNavItem("设置", currentScreen == Screen.AppSettings) { showAppSettings() }, navItemParams())
        return nav
    }

    private fun isHomeSection(): Boolean {
        return currentScreen == Screen.Home || currentScreen == Screen.Manual || currentScreen == Screen.History
    }

    private fun isRuleSection(): Boolean {
        return currentScreen == Screen.Rules || currentScreen == Screen.RuleDetail
    }

    private fun navItemParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        }
    }

    private fun createNavItem(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (selected) Color.WHITE else COLOR_TEXT_SECONDARY)
            background = if (selected) {
                roundedFill(COLOR_ACCENT, 999f)
            } else {
                roundedFill(Color.WHITE, 999f)
            }
            setOnClickListener { onClick() }
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
        if (isBetaPackage()) {
            toggleBetaMonitoring()
            return
        }

        val isEnabled = MonitoringState.isEnabled(this)
        val isWorking = isEnabled && ScreenCaptureService.isRunning

        if (isWorking) {
            MonitoringState.setEnabled(this, false)
            CaptureHolder.clear()
            Toast.makeText(this, "实时监测已暂停", Toast.LENGTH_SHORT).show()
            updateMonitoringUi()
            return
        }

        Toast.makeText(this, "請授權螢幕分享，用於識別訂單", Toast.LENGTH_SHORT).show()
        startAppFlow()
    }

    private fun toggleBetaMonitoring() {
        val isEnabled = MonitoringState.isEnabled(this)
        if (isEnabled) {
            MonitoringState.setEnabled(this, false)
            Toast.makeText(this, "实时监测已暂停", Toast.LENGTH_SHORT).show()
            MyAccessibilityService.refreshStatusOverlay()
            updateMonitoringUi()
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "请先开启测试版无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        MonitoringState.setEnabled(this, true)
        Toast.makeText(this, "测试版实时监测已开启，不需要画面分享授权", Toast.LENGTH_LONG).show()
        MyAccessibilityService.refreshStatusOverlay()
        updateMonitoringUi()
    }

    private fun restoreMonitoringIfPossible() {
        if (isBetaPackage()) {
            if (MonitoringState.isEnabled(this)) {
                MyAccessibilityService.refreshStatusOverlay()
            }
            return
        }

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
        val isServiceRunning = if (isBetaPackage()) {
            isEnabled && isAccessibilityServiceEnabled() && MyAccessibilityService.isServiceActive()
        } else {
            ScreenCaptureService.isRunning
        }

        if (::statusToggleButton.isInitialized) {
            statusToggleButton.text = when {
                isEnabled && isServiceRunning -> "暂停监测"
                else -> "启用监测"
            }
        }

        when {
            isEnabled && isServiceRunning -> setStatus(
                title = "正在工作",
                detail = if (isBetaPackage()) {
                    "测试版无障碍监听已开启。目标应用弹出订单后会直接调用无障碍截图。"
                } else {
                    "低功耗监听已开启。目标应用弹出订单后才会短时 OCR。"
                },
                color = COLOR_SUCCESS
            )
            else -> setStatus(
                title = "已停止",
                detail = if (isBetaPackage()) {
                    "测试版没有在工作。点击启用监测，只需要确认无障碍服务已开启。"
                } else if (hasProjectionPermission) {
                    "实时监测没有在工作。点击启用监测后，请重新确认屏幕分享。"
                } else {
                    "实时监测没有在工作。点击启用监测，授权时请选择分享整个画面。"
                },
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
                        merchantWideText = regionText.merchantWideText,
                        addressText = regionText.addressText,
                        addressWideText = regionText.addressWideText,
                        addressLowerText = regionText.addressLowerText
                    )
                ) ?: OrderParser.parse(regionText.fullText)
                ManualOcrDebugStore.save(this, bitmap, regionText, order, "manual-analysis")
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

    private fun analyzeCalibrationImage(uri: Uri) {
        setAnalyzing(true, "正在生成 OCR 框图", "完成后会保存到手动调试目录。")

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
                        merchantWideText = regionText.merchantWideText,
                        addressText = regionText.addressText,
                        addressWideText = regionText.addressWideText,
                        addressLowerText = regionText.addressLowerText
                    )
                ) ?: OrderParser.parse(regionText.fullText)
                val savedPath = ManualOcrDebugStore.save(this, bitmap, regionText, order, "calibration")
                calibrationBitmap = bitmap
                calibrationSavedPath = savedPath
                setAnalyzing(false)
                Toast.makeText(
                    this,
                    if (savedPath.isBlank()) "OCR 框图保存失败" else "OCR 框图已保存：manual_ocr_debug",
                    Toast.LENGTH_LONG
                ).show()
                showOcrCalibration()
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
        addListMatchCard(content, analysis)
        content.addView(TextView(this).apply {
            text = "${analysis.score}分 · ${analysis.recommendation}"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(12))
            background = roundedFill(recommendationColor(analysis.recommendation), 14f)
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

    private fun addListMatchCard(parent: LinearLayout, analysis: AnalysisResult) {
        val isBlacklisted = analysis.isBlacklisted
        val isWhitelisted = analysis.isWhitelisted
        if (!isBlacklisted && !isWhitelisted) return

        val label = if (isBlacklisted) "命中黑名单" else "命中白名单"
        val keyword = if (isBlacklisted) analysis.matchedBlacklistKeyword else analysis.matchedWhitelistKeyword
        val note = if (isBlacklisted) analysis.blacklistNote else analysis.whitelistNote
        val color = if (isBlacklisted) COLOR_DANGER else COLOR_SUCCESS

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedFill(color, 12f)
        }
        card.addView(TextView(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        card.addView(TextView(this).apply {
            text = buildString {
                append(keyword.ifBlank { "已命中名单规则" })
                if (note.isNotBlank()) append("\n").append(note)
            }
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(4), 0, 0)
        })
        parent.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(8)
        })
    }

    private fun recommendationColor(recommendation: String): Int {
        return when (recommendation) {
            "建议接单" -> COLOR_SUCCESS
            "慎重考虑" -> COLOR_WARNING
            else -> COLOR_DANGER
        }
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
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedStroke(fillColor, strokeColor, 12f)
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
        })
        row.addView(TextView(this).apply {
            text = value
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            setTextColor(COLOR_TEXT_PRIMARY)
            maxLines = if (label == "地址") 4 else 2
            setLineSpacing(0f, 1.08f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
        showAddListEntryDialog(
            keyword = analysis.storeName.ifBlank {
                if (isWhitelist) analysis.matchedWhitelistKeyword else analysis.matchedBlacklistKeyword
            },
            note = if (isWhitelist) analysis.whitelistNote else analysis.blacklistNote,
            isWhitelist = isWhitelist
        )
    }

    private fun showAddListEntryDialog(keyword: String, note: String, isWhitelist: Boolean) {
        val keywordInput = createNumberlessInput("商家名称或地址关键词").apply {
            setText(keyword)
            setSingleLine(false)
            minLines = if (keyword.contains('\n')) 2 else 1
        }
        val noteInput = createNumberlessInput(
            if (isWhitelist) "备注：位置、出餐速度等" else "原因：不好取/不好送/难停车等"
        ).apply {
            setText(note)
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

    private fun isBetaPackage(): Boolean {
        return packageName.endsWith(".beta")
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
