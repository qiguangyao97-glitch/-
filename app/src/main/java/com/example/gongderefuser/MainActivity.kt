package com.example.gongderefuser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.example.gongderefuser.analyzer.OrderAnalyzer
import com.example.gongderefuser.analyzer.OrderAnalyzer.AnalysisResult
import com.example.gongderefuser.analyzer.RuleSettings
import com.example.gongderefuser.model.OrderData
import com.example.gongderefuser.parser.OrderParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    private lateinit var manualStoreNameInput: EditText
    private lateinit var manualAddressInput: EditText

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
        SettingsDetail,
        TagManage,
        OcrCalibration,
        History,
        RecentOrderDetail,
        RealtimeHistory,
        ScreenshotHistory
    }

    private var currentScreen = Screen.Home
    private var hasPromptedAccessibility = false
    private var soundTestExpanded = false
    private var debugInfoExpanded = false
    private var currentListIsWhitelist = true
    private var pendingImportIsWhitelist = true
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

    private val pickTagImportFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            importListEntriesFromFile(uri, pendingImportIsWhitelist)
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
            setPadding(dp(16), dp(14), dp(16), dp(14))
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
            detail = "",
            accentColor = COLOR_TEXT_PRIMARY,
            showDot = false
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
            createActionCard("手动计算", "", COLOR_WARNING, showDot = false).apply {
                setOnClickListener { showManualCalculator() }
            },
            rowItemParams(leftMargin = dp(7))
        )
        layout.addView(actionRow)
        layout.addView(createRecentOrderCard())

        val recordRow = createCardRow()
        recordRow.addView(
            createRecordSummaryCard("订单记录", realtimeRecords()) {
                showRecordDetail(Screen.RealtimeHistory, "订单记录", realtimeRecords(), "实时 OCR 自动识别订单")
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(7)
            }
        )
        recordRow.addView(
            createRecordSummaryCard("截图记录", screenshotRecords()) {
                showRecordDetail(Screen.ScreenshotHistory, "截图记录", screenshotRecords(), "截图分析保存记录")
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(7)
            }
        )
        layout.addView(recordRow)

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
            Screen.TagManage -> {
                showListEditor(currentListIsWhitelist)
                true
            }
            Screen.SettingsDetail -> {
                showAppSettings()
                true
            }
            Screen.Rules,
            Screen.AppSettings,
            Screen.OcrCalibration,
            Screen.Manual,
            Screen.History,
            Screen.RecentOrderDetail,
            Screen.RealtimeHistory,
            Screen.ScreenshotHistory -> {
                showHome()
                true
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

    private fun showRecentOrderDetail() {
        currentScreen = Screen.RecentOrderDetail
        val latest = realtimeRecords().firstOrNull()
        val layout = createBaseLayout()
        addSubHeader(layout, "最近订单", "实时 OCR 自动识别的最新一笔。")
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        if (latest == null) {
            card.addView(TextView(this).apply {
                text = "还没有实时订单"
                textSize = 14f
                setTextColor(COLOR_TEXT_SECONDARY)
            })
        } else {
            addRecordDetailRows(card, latest)
        }
        layout.addView(card)
        setBaseContent(layout)
    }

    private fun showRecordDetail(
        screen: Screen,
        title: String,
        records: List<OrderHistory.Record>,
        subtitle: String
    ) {
        currentScreen = screen
        val layout = createBaseLayout()
        addSubHeader(layout, title, subtitle)
        layout.addView(createRecordDetailCard(title, records, screen))
        setBaseContent(layout)
    }

    private fun createRecordDetailCard(
        title: String,
        records: List<OrderHistory.Record>,
        screen: Screen
    ): LinearLayout {
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = title
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
                setOnClickListener { confirmClearRecords(screen) }
            })
        }
        card.addView(header)
        addRecordStats(card, records, includeAverage = true)

        if (records.isEmpty()) {
            card.addView(TextView(this).apply {
                text = "还没有记录。"
                textSize = 14f
                setTextColor(COLOR_TEXT_SECONDARY)
                setPadding(0, dp(12), 0, 0)
            })
            return card
        }

        records.forEachIndexed { index, record ->
            card.addView(View(this).apply {
                setBackgroundColor(0xFFE5E7EB.toInt())
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = if (index == 0) dp(14) else dp(10)
                bottomMargin = dp(10)
            })
            addHistoryRow(card, record) {
                OrderHistory.delete(this@MainActivity, record.timestamp)
                refreshRecordDetail(screen)
            }
        }

        return card
    }

    private fun refreshRecordDetail(screen: Screen) {
        when (screen) {
            Screen.RealtimeHistory -> showRecordDetail(Screen.RealtimeHistory, "订单记录", realtimeRecords(), "实时 OCR 自动识别订单")
            Screen.ScreenshotHistory -> showRecordDetail(Screen.ScreenshotHistory, "截图记录", screenshotRecords(), "截图分析保存记录")
            else -> showHome()
        }
    }

    private fun confirmClearRecords(screen: Screen) {
        AlertDialog.Builder(this)
            .setTitle("确认清空记录？")
            .setMessage("此操作无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清空") { _, _ ->
                when (screen) {
                    Screen.RealtimeHistory -> OrderHistory.clearSource(this, "实时")
                    Screen.ScreenshotHistory -> OrderHistory.clearSource(this, "截图")
                    else -> Unit
                }
                refreshRecordDetail(screen)
            }
            .show()
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
            addHistoryRow(card, record) {
                OrderHistory.delete(this@MainActivity, record.timestamp)
                showHistory()
            }
        }

        return card
    }

    private fun addHistoryRow(parent: LinearLayout, record: OrderHistory.Record, onDelete: () -> Unit) {
        val recommendation = normalizedRecommendation(record)
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
            text = "${record.score}分 · $recommendation"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundedFill(recommendationColor(recommendation), 999f)
        })
        titleRow.addView(createModeBadge(displayAcceptMode(record.acceptMode)), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp(6)
        })
        titleRow.addView(TextView(this).apply {
            text = "删除"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_DANGER)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundedStroke(Color.WHITE, COLOR_DANGER, 999f)
            setOnClickListener { onDelete() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp(8)
        })
        row.addView(titleRow)

        val markers = buildList {
            add(displayAcceptMode(record.acceptMode))
            add(record.orderType)
            if (record.isSameLocationStack) add("爽单")
            if (record.isWhitelisted) add("标签备注")
            if (record.isBlacklisted) add("避雷标签")
        }.joinToString(" / ")
        row.addView(TextView(this).apply {
            text = "${record.timeLabel()} · ${record.source} · $markers"
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(TextView(this).apply {
            text = "${formatRecordPrice(record)} · ${record.minutes} 分钟 · ${
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
            createSmallActionButton("加标签备注", COLOR_SUCCESS) {
                showAddListEntryDialog(keyword, "", isWhitelist = true)
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                rightMargin = dp(6)
            }
        )
        actionRow.addView(
            createSmallActionButton("加避雷标签", COLOR_DANGER) {
                showAddListEntryDialog(keyword, "", isWhitelist = false)
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                leftMargin = dp(6)
            }
        )
        row.addView(actionRow)

        parent.addView(row)
    }

    private fun createModeBadge(mode: String): TextView {
        val isReward = mode == "趟奖模式"
        return TextView(this).apply {
            text = mode
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (isReward) COLOR_ACCENT else COLOR_TEXT_SECONDARY)
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = roundedStroke(
                if (isReward) Color.rgb(240, 253, 250) else Color.WHITE,
                if (isReward) COLOR_ACCENT else COLOR_BORDER,
                999f
            )
        }
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
        card.addView(TextView(this).apply {
            val rule = RuleSettings.load(this@MainActivity).normal
            text = "使用规则页目标：时薪 ${rule.targetHourly}，${OrderAnalyzer.formatDistance(rule.targetYuanPerKm)} 元/km，平均单价 ${OrderAnalyzer.formatMoney(rule.targetAveragePrice)}。"
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(4), 0, dp(12))
        })
        manualStoreNameInput = addLabeledTextInput(card, "商家名称", "可选，用于加入标签备注/避雷标签")
        manualAddressInput = addLabeledTextInput(card, "配送地址", "可选，用于加入标签备注/避雷标签")
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
        addSubHeader(layout, "规则", "点击卡片进入对应项目修改。", showBackButton = false)

        layout.addView(
            createActionCard(
                "接单模式",
                "目前：${acceptModeTitle(settings.acceptMode)}",
                COLOR_ACCENT
            ).apply { setOnClickListener { showAcceptModeEditor() } },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        )

        layout.addView(
            createActionCard(
                "评分规则",
                ruleSummary(settings.normal),
                COLOR_SUCCESS
            ).apply { setOnClickListener { showRuleConfigEditor() } },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        )

        val listRow = createCardRow()
        listRow.addView(
            createActionCard(
                "标签备注",
                "已建立 ${settings.whitelistEntries.size} 条\n查看标签库",
                COLOR_SUCCESS
            ).apply { setOnClickListener { showListEditor(isWhitelist = true) } },
            rowItemParams(rightMargin = dp(7))
        )
        listRow.addView(
            createActionCard(
                "避雷标签",
                "已建立 ${settings.blacklistEntries.size} 条\n查看避雷库",
                COLOR_DANGER
            ).apply { setOnClickListener { showListEditor(isWhitelist = false) } },
            rowItemParams(leftMargin = dp(7))
        )
        layout.addView(listRow)

        setBaseContent(layout)
    }

    private fun ruleSummary(rule: RuleSettings.RuleConfig): String {
        return "时薪 ${rule.targetHourly}；${OrderAnalyzer.formatDistance(rule.targetYuanPerKm)} 元/km；均价 ${OrderAnalyzer.formatMoney(rule.targetAveragePrice)}；肥单 ${rule.fatOrderMinAmount} 元"
    }

    private fun acceptModeTitle(mode: RuleSettings.AcceptMode): String {
        return when (mode) {
            RuleSettings.AcceptMode.REWARD -> "趟奖模式"
            RuleSettings.AcceptMode.NORMAL -> "正常模式"
        }
    }

    private fun displayAcceptMode(mode: String): String {
        return when (mode) {
            "躺奖模式", "躺獎模式", "趟獎模式", "趟奖模式" -> "趟奖模式"
            "正常模式" -> "正常模式"
            else -> mode.ifBlank { "未记录" }
        }
    }

    private fun showAcceptModeEditor() {
        currentScreen = Screen.RuleDetail

        val settings = RuleSettings.load(this)
        var selectedMode = settings.acceptMode
        val layout = createBaseLayout()
        addSubHeader(layout, "接单模式", "选择当前跑单时段的判断方式。")

        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        lateinit var normalOption: LinearLayout
        lateinit var rewardOption: LinearLayout
        fun refreshOptions() {
            normalOption.background = modeOptionBackground(selectedMode == RuleSettings.AcceptMode.NORMAL, COLOR_SUCCESS)
            rewardOption.background = modeOptionBackground(selectedMode == RuleSettings.AcceptMode.REWARD, COLOR_ACCENT)
            (normalOption.getChildAt(0) as? TextView)?.text = "${if (selectedMode == RuleSettings.AcceptMode.NORMAL) "●" else "○"} 正常模式"
            (rewardOption.getChildAt(0) as? TextView)?.text = "${if (selectedMode == RuleSettings.AcceptMode.REWARD) "●" else "○"} 趟奖模式"
        }
        normalOption = createModeOption(
            title = "正常模式",
            detail = "使用原始动态评分算法",
            selected = selectedMode == RuleSettings.AcceptMode.NORMAL,
            accentColor = COLOR_SUCCESS
        ) {
            selectedMode = RuleSettings.AcceptMode.NORMAL
            refreshOptions()
        }
        rewardOption = createModeOption(
            title = "趟奖模式",
            detail = "提高多单且不拖时间订单的权重",
            selected = selectedMode == RuleSettings.AcceptMode.REWARD,
            accentColor = COLOR_ACCENT
        ) {
            selectedMode = RuleSettings.AcceptMode.REWARD
            refreshOptions()
        }
        card.addView(normalOption)
        card.addView(rewardOption)

        val rewardInput = addLabeledNumberInput(card, "每趟奖励金额", "元").apply {
            setText(settings.rewardPerTrip.toString())
        }
        card.addView(TextView(this).apply {
            text = buildString {
                appendLine("趟奖模式不修改订单金额。")
                appendLine("仅提高多单且高效率订单权重。")
                append("避雷单与低效率订单不会获得加权。")
            }
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(2f * resources.displayMetrics.density, 1.08f)
            setPadding(0, dp(6), 0, dp(12))
        })
        card.addView(createButton(primary = true).apply {
            text = "保存"
            setOnClickListener {
                val rewardPerTrip = rewardInput.text.toString().toIntOrNull()
                if (rewardPerTrip == null || rewardPerTrip < 0) {
                    Toast.makeText(this@MainActivity, "请输入有效奖励金额", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                RuleSettings.saveAcceptMode(this@MainActivity, selectedMode, rewardPerTrip)
                Toast.makeText(this@MainActivity, "已保存接单模式", Toast.LENGTH_SHORT).show()
                showRuleSettings()
            }
        })
        layout.addView(card)
        setBaseContent(layout)
    }

    private fun createModeOption(
        title: String,
        detail: String,
        selected: Boolean,
        accentColor: Int,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = modeOptionBackground(selected, accentColor)
            setOnClickListener { onClick() }
            addView(TextView(this@MainActivity).apply {
                text = "${if (selected) "●" else "○"} $title"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_PRIMARY)
            })
            addView(TextView(this@MainActivity).apply {
                text = detail
                textSize = 13f
                setTextColor(COLOR_TEXT_SECONDARY)
                setPadding(0, dp(4), 0, 0)
            })
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun modeOptionBackground(selected: Boolean, accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(if (selected) Color.argb(26, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)) else Color.WHITE)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(if (selected) 2 else 1), if (selected) accentColor else COLOR_BORDER)
        }
    }

    private fun showRuleConfigEditor() {
        currentScreen = Screen.RuleDetail

        val settings = RuleSettings.load(this)
        val rule = settings.normal
        val layout = createBaseLayout()
        addSubHeader(
            layout,
            "评分规则",
            "目标值代表公平价格，达到目标值为 80 分。"
        )

        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val targetHourlyInput = addLabeledNumberInput(card, "目标时薪", "元/小时").apply {
            setText(rule.targetHourly.toString())
        }
        val targetYuanPerKmInput = addLabeledNumberInput(card, "目标元/公里", "金额除以公里").apply {
            setText(OrderAnalyzer.formatDistance(rule.targetYuanPerKm))
        }
        val targetAveragePriceInput = addLabeledNumberInput(card, "目标平均单价", "金额除以配送数量").apply {
            setText(OrderAnalyzer.formatMoney(rule.targetAveragePrice))
        }
        val scoreBaseInput = addLabeledNumberInput(card, "评分基准分", "50 到 100").apply {
            setText(rule.scoreBase.toString())
        }
        val fatOrderMinAmountInput = addLabeledNumberInput(card, "肥单最低金额", "默认 100 元").apply {
            setText(rule.fatOrderMinAmount.toString())
        }
        card.addView(createButton(primary = true).apply {
            text = "保存"
            setOnClickListener {
                val updatedRule = readScoreRuleConfig(
                    targetHourlyInput,
                    targetYuanPerKmInput,
                    targetAveragePriceInput,
                    scoreBaseInput,
                    fatOrderMinAmountInput,
                    rule
                )
                if (updatedRule == null) {
                    Toast.makeText(this@MainActivity, "请输入有效目标，评分基准分需为 50 到 100", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                RuleSettings.save(
                    context = this@MainActivity,
                    normal = updatedRule,
                    blacklist = updatedRule,
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
        currentListIsWhitelist = isWhitelist

        val settings = RuleSettings.load(this)
        val layout = createBaseLayout()
        addSubHeader(
            layout,
            if (isWhitelist) "标签备注" else "避雷标签",
            if (isWhitelist) "命中后只显示备注，不加分不扣分。" else "命中任意避雷标签后扣 10 分。"
        )

        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(createTagInputRow(isWhitelist))
        if (isWhitelist) {
            whitelistTags.clear()
            whitelistTags.addAll(settings.whitelistEntries)
        } else {
            blacklistTags.clear()
            blacklistTags.addAll(settings.blacklistEntries)
        }
        card.addView(createTagLibraryCard(isWhitelist))
        layout.addView(card)
        setBaseContent(layout)
    }

    private fun createTagLibraryCard(isWhitelist: Boolean): LinearLayout {
        val count = if (isWhitelist) whitelistTags.size else blacklistTags.size
        val title = if (isWhitelist) "标签列表" else "避雷列表"
        val detail = if (isWhitelist) "已建立 $count 个标签" else "已建立 $count 个避雷标签"
        return createActionCard(
            title = title,
            detail = "$detail\n点击查看和管理",
            accentColor = if (isWhitelist) COLOR_SUCCESS else COLOR_DANGER
        ).apply {
            setOnClickListener { showListManagement(isWhitelist) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }
    }

    private fun showListManagement(isWhitelist: Boolean) {
        currentScreen = Screen.TagManage
        currentListIsWhitelist = isWhitelist

        val settings = RuleSettings.load(this)
        if (isWhitelist) {
            whitelistTags.clear()
            whitelistTags.addAll(settings.whitelistEntries)
        } else {
            blacklistTags.clear()
            blacklistTags.addAll(settings.blacklistEntries)
        }

        val layout = createBaseLayout()
        addSubHeader(
            layout,
            if (isWhitelist) "标签管理" else "避雷管理",
            if (isWhitelist) "查询、导入、导出和编辑标签备注。" else "查询、导入、导出和编辑避雷标签。"
        )
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val searchInput = createNumberlessInput("查询名称或备注")
        card.addView(searchInput)
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        fun actionButtonParams(left: Int = 0, right: Int = 0): LinearLayout.LayoutParams {
            return LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                leftMargin = left
                rightMargin = right
            }
        }
        actionRow.addView(createSmallActionButton("查询", COLOR_ACCENT) {
            renderListTags(isWhitelist, searchInput.text.toString().trim())
        }, actionButtonParams(right = dp(6)))
        actionRow.addView(createSmallActionButton("导入", COLOR_SUCCESS) {
            showImportListDialog(isWhitelist)
        }, actionButtonParams(left = dp(3), right = dp(3)))
        actionRow.addView(createSmallActionButton("导出", COLOR_WARNING) {
            exportListEntries(isWhitelist)
        }, actionButtonParams(left = dp(6)))
        card.addView(actionRow)
        if (isWhitelist) {
            whitelistTagContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            card.addView(whitelistTagContainer)
        } else {
            blacklistTagContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            card.addView(blacklistTagContainer)
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
            text = if (isWhitelist) "添加标签备注" else "添加避雷标签"
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

    private fun renderListTags(isWhitelist: Boolean, query: String = "") {
        val sourceList = if (isWhitelist) whitelistTags else blacklistTags
        val normalizedQuery = query.trim()
        val list = if (normalizedQuery.isBlank()) {
            sourceList
        } else {
            sourceList.filter {
                it.keyword.contains(normalizedQuery, ignoreCase = true) ||
                        it.note.contains(normalizedQuery, ignoreCase = true)
            }
        }
        val container = if (isWhitelist) whitelistTagContainer else blacklistTagContainer
        val accentColor = if (isWhitelist) COLOR_SUCCESS else COLOR_DANGER
        val emptyText = if (normalizedQuery.isBlank()) {
            if (isWhitelist) "还没有标签备注" else "还没有避雷标签"
        } else {
            "没有找到匹配标签"
        }

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
            .setTitle(if (isWhitelist) "编辑标签备注" else "编辑避雷标签")
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
            blacklist = latest.normal,
            whitelistText = RuleSettings.serializeEntries(if (isWhitelist) whitelistTags else latest.whitelistEntries),
            blacklistText = RuleSettings.serializeEntries(if (isWhitelist) latest.blacklistEntries else blacklistTags)
        )
        if (currentScreen == Screen.TagManage) {
            val canRender = if (isWhitelist) {
                ::whitelistTagContainer.isInitialized
            } else {
                ::blacklistTagContainer.isInitialized
            }
            if (canRender) renderListTags(isWhitelist)
        } else {
            showListEditor(isWhitelist)
        }
    }

    private fun showImportListDialog(isWhitelist: Boolean) {
        pendingImportIsWhitelist = isWhitelist
        pickTagImportFile.launch(arrayOf("text/plain", "text/*", "application/octet-stream", "*/*"))
    }

    private fun importListEntriesFromFile(uri: Uri, isWhitelist: Boolean) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        }.getOrElse {
            Toast.makeText(this, "读取文件失败：${it.message.orEmpty()}", Toast.LENGTH_LONG).show()
            return
        }

        val imported = RuleSettings.parseEntries(text)
        if (imported.isEmpty()) {
            Toast.makeText(this, "没有可导入内容，请确认格式为：名称|备注", Toast.LENGTH_LONG).show()
            return
        }

        val target = if (isWhitelist) whitelistTags else blacklistTags
        val beforeCount = target.size
        val merged = (target + imported).distinctBy { it.keyword }
        target.clear()
        target.addAll(merged)
        persistListTags(isWhitelist)
        Toast.makeText(this, "已导入 ${target.size - beforeCount} 条，跳过 ${imported.size - (target.size - beforeCount)} 条重复", Toast.LENGTH_LONG).show()
    }

    private fun exportListEntries(isWhitelist: Boolean) {
        val entries = if (isWhitelist) whitelistTags else blacklistTags
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "UberHelper")
        dir.mkdirs()
        val file = File(dir, if (isWhitelist) "tags.txt" else "blacklist.txt")
        file.writeText(RuleSettings.serializeEntries(entries), Charsets.UTF_8)
        Toast.makeText(this, "已导出：${file.absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun showAppSettings() {
        currentScreen = Screen.AppSettings

        val layout = createBaseLayout()
        addSubHeader(layout, "设置", "应用偏好。", trailingText = "版本 ${appVersionNameOnly()}")
        layout.addView(createActionCard(
            "音效设置",
            if (AppSettings.isSoundEnabled(this)) "当前：已开启" else "当前：已关闭",
            COLOR_SUCCESS
        ).apply { setOnClickListener { showSoundSettings() } })
        layout.addView(createActionCard(
            "调试信息",
            if (AppSettings.isDebugSamplesEnabled(this)) "当前：已开启" else "当前：已关闭",
            COLOR_ACCENT
        ).apply { setOnClickListener { showDebugSettings() } })
        layout.addView(createActionCard(
            "数据管理",
            "清除诊断数据",
            COLOR_WARNING
        ).apply { setOnClickListener { showDataSettings() } })
        layout.addView(createActionCard(
            "关于应用",
            "版本 ${appVersionNameOnly()}",
            COLOR_TEXT_PRIMARY
        ).apply { setOnClickListener { showAboutSettings() } })

        setBaseContent(layout)
    }

    private fun showSoundSettings() {
        currentScreen = Screen.SettingsDetail
        val layout = createBaseLayout()
        addSubHeader(layout, "音效设置", "反馈音和测试播放。")
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(createSettingsToggleRow(
            title = "反馈音效",
            enabled = AppSettings.isSoundEnabled(this),
            onToggle = { enabled ->
                AppSettings.setSoundEnabled(this, enabled)
                showSoundSettings()
            }
        ))
        card.addView(TextView(this).apply {
            text = "订单结果会按四档播放对应提示音。"
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(10), 0, dp(12))
        })
        card.addView(createSoundTestRow(
            "狗都不接" to R.raw.sound_level_1,
            "跪著送" to R.raw.sound_level_2,
            leftColor = COLOR_DANGER,
            rightColor = COLOR_WARNING
        ))
        card.addView(createSoundTestRow(
            "站著掙" to R.raw.sound_level_3,
            "掙他娘的" to R.raw.sound_level_4,
            leftColor = COLOR_SUCCESS,
            rightColor = COLOR_GOLD
        ))
        layout.addView(card)
        setBaseContent(layout)
    }

    private fun showDebugSettings() {
        currentScreen = Screen.SettingsDetail
        val layout = createBaseLayout()
        addSubHeader(layout, "调试信息", "识别问题排查入口。")
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(createSettingsToggleRow(
            title = "问题诊断模式",
            enabled = AppSettings.isDebugSamplesEnabled(this),
            onToggle = { enabled ->
                AppSettings.setDebugSamplesEnabled(this, enabled)
                showDebugSettings()
            }
        ))
        card.addView(TextView(this).apply {
            text = "保存截图、OCR结果和订单分析数据，方便排查识别问题。\n路径：${AppSettings.debugSamplePath(this@MainActivity)}"
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(8), 0, dp(12))
        })
        card.addView(createActionCard(
            title = "OCR 校准",
            detail = "选择截图生成区域框图",
            accentColor = COLOR_ACCENT
        ).apply {
            setOnClickListener { showOcrCalibration() }
        })
        if (isDebugBuild()) {
            card.addView(createSettingsToggleRow(
                title = "记录无障碍事件日志",
                enabled = AppSettings.isAccessibilityLogEnabled(this),
                onToggle = { enabled ->
                    AppSettings.setAccessibilityLogEnabled(this, enabled)
                    showDebugSettings()
                }
            ))
        }
        layout.addView(card)
        setBaseContent(layout)
    }

    private fun showDataSettings() {
        currentScreen = Screen.SettingsDetail
        val layout = createBaseLayout()
        addSubHeader(layout, "数据管理", "诊断数据与调试文件。")
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(createButton(primary = false).apply {
            text = "清除诊断数据"
            setOnClickListener { showClearDiagnosticDataDialog() }
        })
        card.addView(TextView(this).apply {
            text = "诊断路径：${AppSettings.debugSamplePath(this@MainActivity)}\n手动截图调试路径：${AppSettings.manualOcrDebugPath(this@MainActivity)}"
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(10), 0, 0)
        })
        layout.addView(card)
        setBaseContent(layout)
    }

    private fun showAboutSettings() {
        currentScreen = Screen.SettingsDetail
        val layout = createBaseLayout()
        addSubHeader(layout, "关于应用", "版本 ${appVersionNameOnly()}")
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(TextView(this).apply {
            text = "功德拒绝器\n公平，公平，还是他妈的公平。"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            setLineSpacing(0f, 1.16f)
        })
        layout.addView(card)
        setBaseContent(layout)
    }

    private fun showClearDiagnosticDataDialog() {
        AlertDialog.Builder(this)
            .setTitle("清除诊断数据")
            .setMessage("确定删除所有诊断文件吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                DebugFileDirs.clearDiagnostics(this)
                Toast.makeText(this, "诊断数据已清除", Toast.LENGTH_SHORT).show()
                showAppSettings()
            }
            .show()
    }

    private fun isDebugBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun showOcrCalibration() {
        currentScreen = Screen.OcrCalibration

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        calibrationBitmap?.let { bitmap ->
            val view = OcrCalibrationView(this).apply {
                setImageAndRegions(bitmap, OcrCalibrationStore.load(this@MainActivity))
            }
            calibrationView = view
            root.addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            if (calibrationSavedPath.isNotBlank()) {
                root.addView(TextView(this).apply {
                    text = "最近生成：manual_ocr_debug"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = roundedFill(Color.argb(150, 0, 0, 0), 8f)
                }, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START
                ).apply {
                    leftMargin = dp(10)
                    bottomMargin = dp(10)
                })
            }
        } ?: run {
            calibrationView = null
            root.addView(TextView(this).apply {
                text = "先点上方“选择截图”载入订单截图"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedFill(Color.argb(205, 248, 250, 252), 18f)
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(createButton(primary = false).apply {
            text = "返回"
            setOnClickListener { navigateBackOneLevel() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(4)
        })
        topRow.addView(createButton(primary = false).apply {
            text = "选择"
            setOnClickListener { pickCalibrationScreenshot() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        })
        topRow.addView(createButton(primary = true).apply {
            text = "保存"
            setOnClickListener {
                val current = calibrationView?.currentRegions().orEmpty()
                OcrCalibrationStore.save(this@MainActivity, current)
                showOcrCalibration()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("保存成功")
                    .setMessage("OCR 模板已保存。以后点“默认”会恢复到这一次保存的模板。")
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        })
        topRow.addView(createButton(primary = false).apply {
            text = "默认"
            setOnClickListener {
                calibrationBitmap?.let { bitmap ->
                    calibrationView?.setImageAndRegions(bitmap, OcrCalibrationStore.load(this@MainActivity))
                }
                Toast.makeText(this@MainActivity, "已恢复到上一次保存的 OCR 模板", Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4)
        })
        toolbar.addView(topRow)

        toolbar.addView(TextView(this).apply {
            text = "图片全屏显示。先调按钮定位、取送定位、取货圆点、送达方块；商家/地址文字框会按圆点和方块动态跟随。"
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(0f, 1.08f)
            setPadding(0, dp(6), 0, dp(6))
        })

        val regionPicker = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val regionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        OcrCalibrationStore.regionNames.forEachIndexed { index, name ->
            regionRow.addView(createCalibrationRegionButton(name), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = if (index == OcrCalibrationStore.regionNames.lastIndex) 0 else dp(8)
            })
        }
        regionPicker.addView(regionRow)
        toolbar.addView(regionPicker)

        root.addView(toolbar, FrameLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ).apply {
            leftMargin = dp(8)
            topMargin = dp(8)
            rightMargin = dp(8)
        })

        setContentView(root)
    }

    private fun pickCalibrationScreenshot() {
        setAnalyzing(true, "请选择校准截图", "生成 OCR 区域框图后会提示保存位置。")
        pickCalibrationImage.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun createCalibrationRegionButton(name: String): TextView {
        return TextView(this).apply {
            text = OcrCalibrationStore.displayName(name)
            textSize = 13f
            typeface = if (name == "closeButton" || name == "deliveryAnchorSearch") {
                Typeface.DEFAULT_BOLD
            } else {
                Typeface.DEFAULT
            }
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = roundedFill(
                when (name) {
                    "closeButton" -> COLOR_DANGER
                    "deliveryAnchorSearch", "pickupAnchor", "dropoffAnchor" -> COLOR_ACCENT
                    else -> COLOR_MUTED
                },
                999f
            )
            setOnClickListener {
                calibrationView?.selectRegion(name)
                Toast.makeText(this@MainActivity, "当前调整：${OcrCalibrationStore.displayName(name)}", Toast.LENGTH_SHORT).show()
            }
        }
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
            addView(SwitchCompat(this@MainActivity).apply {
                isChecked = enabled
                thumbTintList = switchThumbTint()
                trackTintList = switchTrackTint()
                setOnCheckedChangeListener { _, checked -> onToggle(checked) }
            })
        }
    }

    private fun createSoundTestRow(
        left: Pair<String, Int>,
        right: Pair<String, Int>,
        leftColor: Int,
        rightColor: Int
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(8))
            addView(
                createSmallActionButton("测试 ${left.first}", leftColor) {
                    playTestSound(left.second)
                },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    rightMargin = dp(6)
                }
            )
            addView(
                createSmallActionButton("测试 ${right.first}", rightColor) {
                    playTestSound(right.second)
                },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    leftMargin = dp(6)
                }
            )
        }
    }

    private fun playTestSound(soundRes: Int) {
        runCatching {
            val afd = resources.openRawResourceFd(soundRes) ?: return@runCatching
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
                setOnCompletionListener { player -> player.release() }
                setOnErrorListener { player, _, _ ->
                    player.release()
                    true
                }
                start()
            }
        }.onFailure {
            Toast.makeText(this, "音效播放失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchThumbTint(): ColorStateList {
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
            intArrayOf(COLOR_SUCCESS, COLOR_DANGER)
        )
    }

    private fun switchTrackTint(): ColorStateList {
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
            intArrayOf(Color.rgb(187, 247, 208), Color.rgb(254, 202, 202))
        )
    }

    private fun appVersionNameOnly(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")
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

    private fun createVersionNoteCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "当前版本：四档评分、首页统计、标签备注/避雷标签、折叠式音效测试与更清爽的设置页。"
                textSize = 13f
                setTextColor(COLOR_TEXT_SECONDARY)
                setLineSpacing(0f, 1.16f)
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
            text = "我们只为一件事！公平，公平，还是他妈的公平"
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(18))
        })
    }

    private fun addSubHeader(
        layout: LinearLayout,
        title: String,
        subtitle: String,
        showBackButton: Boolean = false,
        trailingText: String = ""
    ) {
        if (showBackButton) {
            layout.addView(createButton(primary = false).apply {
                text = "返回"
                setOnClickListener { navigateBackOneLevel() }
            })
        }
        layout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_PRIMARY)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (trailingText.isNotBlank()) {
                addView(TextView(this@MainActivity).apply {
                    text = trailingText
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLOR_TEXT_SECONDARY)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = roundedStroke(Color.WHITE, COLOR_BORDER, 999f)
                })
            }
        })
        layout.addView(TextView(this).apply {
            text = subtitle
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(6), 0, dp(16))
        })
    }

    private fun setBaseContent(layout: LinearLayout) {
        val contentView: View = if (shouldAllowPageScroll()) {
            ScrollView(this).apply {
                setBackgroundColor(COLOR_BACKGROUND)
                isFillViewport = true
                addView(layout)
            }
        } else {
            layout.apply {
                setBackgroundColor(COLOR_BACKGROUND)
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            addView(contentView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            addView(createBottomNavigation())
        }
        setContentView(root)
    }

    private fun shouldAllowPageScroll(): Boolean {
        return currentScreen == Screen.TagManage ||
                currentScreen == Screen.History ||
                currentScreen == Screen.RecentOrderDetail ||
                currentScreen == Screen.RealtimeHistory ||
                currentScreen == Screen.ScreenshotHistory
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
        nav.addView(createNavItem("设置", isSettingsSection()) { showAppSettings() }, navItemParams())
        return nav
    }

    private fun isHomeSection(): Boolean {
        return currentScreen == Screen.Home || currentScreen == Screen.Manual || currentScreen == Screen.History
    }

    private fun isRuleSection(): Boolean {
        return currentScreen == Screen.Rules || currentScreen == Screen.RuleDetail || currentScreen == Screen.TagManage
    }

    private fun isSettingsSection(): Boolean {
        return currentScreen == Screen.AppSettings || currentScreen == Screen.SettingsDetail
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

    private fun createActionCard(
        title: String,
        detail: String,
        accentColor: Int,
        showDot: Boolean = true
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedFill(Color.WHITE, 16f)
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true

            if (showDot) {
                addView(View(this@MainActivity).apply {
                    background = roundedFill(accentColor, 999f)
                    layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                        bottomMargin = dp(10)
                    }
                })
            }
            addView(TextView(this@MainActivity).apply {
                tag = "title"
                text = title
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_PRIMARY)
            })
            if (detail.isNotBlank()) {
                addView(TextView(this@MainActivity).apply {
                    tag = "detail"
                    text = detail
                    textSize = 13f
                    setTextColor(COLOR_TEXT_SECONDARY)
                    setPadding(0, dp(5), 0, 0)
                })
            }
        }
    }

    private fun startAppFlow() {
        startActivity(Intent(this, ScreenCaptureActivity::class.java))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
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
        requestNotificationPermissionIfNeeded()
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

        requestNotificationPermissionIfNeeded()
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
        val isServiceRunning = if (isBetaPackage()) {
            isEnabled && isAccessibilityServiceEnabled() && MyAccessibilityService.isServiceActive()
        } else {
            ScreenCaptureService.isRunning
        }
        val records = realtimeRecords()
        val todayCount = todayRecords(records).size
        val successRate = if (todayCount > 0) "100%" else "--"
        val lastTime = records.firstOrNull()?.let { formatClock(it.timestamp) } ?: "--"
        val currentMode = acceptModeTitle(RuleSettings.load(this).acceptMode)
        val statusMetrics = "当前模式：$currentMode\n今日识别：${todayCount}单\n成功率：$successRate\n最后识别：$lastTime"

        if (::statusToggleButton.isInitialized) {
            statusToggleButton.text = when {
                isEnabled && isServiceRunning -> "暂停监测"
                else -> "启用监测"
            }
        }

        when {
            isEnabled && isServiceRunning -> setStatus(
                title = "监测中",
                detail = statusMetrics,
                color = COLOR_SUCCESS
            )
            else -> setStatus(
                title = "已停止",
                detail = statusMetrics,
                color = COLOR_DANGER
            )
        }
    }

    private fun createRecentOrderCard(): LinearLayout {
        val latest = realtimeRecords().firstOrNull()
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener {
                showRecentOrderDetail()
            }
        }
        if (latest == null) {
            card.addView(createNavigationTitle("最近订单"))
            card.addView(TextView(this).apply {
                text = "还没有实时订单"
                textSize = 14f
                setTextColor(COLOR_TEXT_SECONDARY)
                setPadding(0, dp(6), 0, 0)
            })
        } else {
            val recommendation = normalizedRecommendation(latest)
            card.addView(
                createNavigationTitle(
                    title = "最近订单",
                    trailingText = "${latest.score}分 · $recommendation",
                    trailingColor = recommendationColor(recommendation)
                )
            )
            addCompactStatRow(card, "商家", latest.storeName)
            addCompactStatRow(card, "金额", formatRecordPrice(latest))
            addCompactStatRow(card, "时间", "${latest.minutes} 分钟")
            addCompactStatRow(card, "距离", "${OrderAnalyzer.formatDistance(latest.distance)} 公里")
        }
        return card
    }

    private fun createRecordSummaryCard(
        title: String,
        records: List<OrderHistory.Record>,
        onClick: () -> Unit
    ): LinearLayout {
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }
        card.addView(createNavigationTitle(title))
        addRecordSummaryCompact(card, records)
        return card
    }

    private fun addMiniRecordRow(parent: LinearLayout, record: OrderHistory.Record) {
        val recommendation = normalizedRecommendation(record)
        parent.addView(TextView(this).apply {
            text = "${record.score}分 · $recommendation"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(recommendationColor(recommendation))
        })
        parent.addView(TextView(this).apply {
            val mode = displayAcceptMode(record.acceptMode)
            text = "$mode · ${formatRecordPrice(record)} · ${record.minutes}分 · ${OrderAnalyzer.formatDistance(record.distance)}公里"
            textSize = 12f
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(0, dp(2), 0, 0)
        })
        parent.addView(TextView(this).apply {
            text = record.storeName
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun addRecordDetailRows(parent: LinearLayout, record: OrderHistory.Record) {
        val recommendation = normalizedRecommendation(record)
        addCompactStatRow(parent, "商家", record.storeName)
        addCompactStatRow(parent, "地址", record.storeAddress.ifBlank { "未识别" })
        addCompactStatRow(parent, "接单模式", displayAcceptMode(record.acceptMode))
        addCompactStatRow(parent, "金额", formatRecordPrice(record))
        addCompactStatRow(parent, "时间", "${record.minutes} 分钟")
        addCompactStatRow(parent, "距离", "${OrderAnalyzer.formatDistance(record.distance)} 公里")
        addCompactStatRow(parent, "评分", "${record.score} 分")
        addCompactStatRow(parent, "等级", recommendation)
        addCompactStatRow(parent, "预计时薪", "${OrderAnalyzer.formatMoney(record.effectiveHourly)} 元/小时")
        addCompactStatRow(parent, "订单类型", record.orderType.ifBlank { "未识别" })
        addCompactStatRow(parent, "同地點配送", if (record.isSameLocationStack) "是" else "否")
        addCompactStatRow(parent, "标签备注", if (record.isWhitelisted) "命中" else "未命中")
        addCompactStatRow(parent, "避雷标签", if (record.isBlacklisted) "命中" else "未命中")
    }

    private fun createMetricChip(textValue: String, textColor: Int): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(textColor)
            background = roundedStroke(Color.rgb(250, 251, 252), COLOR_BORDER, 14f)
        }
    }

    private fun addCompactStatRow(parent: LinearLayout, label: String, value: String) {
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(2))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 14f
                setTextColor(COLOR_TEXT_SECONDARY)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
                setTextColor(COLOR_TEXT_PRIMARY)
            })
        })
    }

    private fun addRecordStats(
        parent: LinearLayout,
        records: List<OrderHistory.Record>,
        includeAverage: Boolean
    ) {
        val counts = records.groupingBy { normalizedRecommendation(it) }.eachCount()
        val averageScore = if (records.isEmpty()) 0 else records.map { it.score }.average().toInt()
        addCompactStatRow(parent, "总数", "${records.size} 单")
        if (includeAverage) {
            addCompactStatRow(parent, "平均评分", if (records.isEmpty()) "--" else "$averageScore 分")
        }
        addCompactStatRow(parent, "狗都不接", "${counts["狗都不接"] ?: 0} 单")
        addCompactStatRow(parent, "跪著送", "${counts["跪著送"] ?: 0} 单")
        addCompactStatRow(parent, "站著掙", "${counts["站著掙"] ?: 0} 单")
        addCompactStatRow(parent, "掙他娘的", "${counts["掙他娘的"] ?: 0} 单")
    }

    private fun addRecordSummaryCompact(parent: LinearLayout, records: List<OrderHistory.Record>) {
        val counts = records.groupingBy { normalizedRecommendation(it) }.eachCount()
        addCompactStatRow(parent, "总数", "${records.size} 单")
        addLevelSummaryRow(
            parent,
            "狗都不接", counts["狗都不接"] ?: 0,
            "跪著送", counts["跪著送"] ?: 0
        )
        addLevelSummaryRow(
            parent,
            "站著掙", counts["站著掙"] ?: 0,
            "掙他娘的", counts["掙他娘的"] ?: 0
        )
    }

    private fun addLevelSummaryRow(
        parent: LinearLayout,
        leftLabel: String,
        leftCount: Int,
        rightLabel: String,
        rightCount: Int
    ) {
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(createLevelSummaryText("$leftLabel $leftCount"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(createLevelSummaryText("$rightLabel $rightCount"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(6)
            })
        })
    }

    private fun createLevelSummaryText(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_TEXT_SECONDARY)
        }
    }

    private fun createCollapsibleTitle(title: String, expanded: Boolean): LinearLayout {
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
                text = if (expanded) "收起" else "展开"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_ACCENT)
            })
        }
    }

    private fun createNavigationTitle(
        title: String,
        trailingText: String? = null,
        trailingColor: Int = COLOR_TEXT_SECONDARY
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
            if (!trailingText.isNullOrBlank()) {
                addView(TextView(this@MainActivity).apply {
                    text = trailingText
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(trailingColor)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(8)
                })
            }
            addView(TextView(this@MainActivity).apply {
                text = ">"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_SECONDARY)
            })
        }
    }

    private fun normalizedRecommendation(record: OrderHistory.Record): String {
        return normalizeRecommendation(record.recommendation, record.score)
    }

    private fun realtimeRecords(): List<OrderHistory.Record> {
        return OrderHistory.load(this).filter { it.source == "实时" }
    }

    private fun screenshotRecords(): List<OrderHistory.Record> {
        return OrderHistory.load(this).filter { it.source == "截图" }
    }

    private fun normalizeRecommendation(recommendation: String, score: Int): String {
        return recommendationForScore(score)
    }

    private fun recommendationForScore(score: Int): String {
        val scoreBase = RuleSettings.load(this).normal.scoreBase
        return OrderAnalyzer.recommendationForScore(score, scoreBase)
    }

    private fun todayRecords(records: List<OrderHistory.Record>): List<OrderHistory.Record> {
        val now = Calendar.getInstance()
        return records.filter { record ->
            val then = Calendar.getInstance().apply { timeInMillis = record.timestamp }
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        }
    }

    private fun formatClock(timestamp: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
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
                        addressText = regionText.addressText,
                        addressWideText = regionText.addressWideText,
                        addressLowerText = regionText.addressLowerText
                    )
                ) else null
                ManualOcrDebugStore.save(this, bitmap, regionText, order, "manual-analysis")
                val gate = OrderResultGate.evaluate(order, regionText.hasAnchoredCard, regionText.tripText)
                Log.i("ORDER_POPUP_VALIDATION", gate.debugLog)
                DiagnosticLogStore.append(this, "ORDER_POPUP_VALIDATION", gate.debugLog)
                if (!gate.shouldShow) {
                    setAnalyzing(false)
                    showFailureResult(
                        buildString {
                            appendLine("未识别到完整订单")
                            appendLine("skipResultReason=${gate.skipResultReason}")
                            appendLine(if (regionText.hasAnchoredCard) "模板定位成功，但订单字段不完整。" else "模板定位失败。")
                            appendLine()
                            appendLine("区域 OCR / 定位诊断：")
                            append(
                                listOf(
                                    regionText.fullText,
                                    regionText.cardText,
                                    regionText.typeText,
                                    regionText.priceText,
                                    regionText.tripText,
                                    regionText.sameDropoffText,
                                    regionText.merchantText,
                                    regionText.addressText
                                ).filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { "空白" }
                            )
                        }
                    )
                } else {
                    setAnalyzing(false)
                    val analysis = OrderAnalyzer.analyzeResult(this, order!!)
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
                        addressText = regionText.addressText,
                        addressWideText = regionText.addressWideText,
                        addressLowerText = regionText.addressLowerText
                    )
                ) else null
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
        if (
            income == null ||
            distance == null ||
            minutes == null ||
            income <= 0 ||
            distance <= 0.0 ||
            minutes <= 0
        ) {
            Toast.makeText(this, "请输入有效的收入、里程和时间", Toast.LENGTH_SHORT).show()
            return
        }

        showAnalysisResult(
            OrderAnalyzer.analyzeResult(
                this,
                OrderData(
                    price = income,
                    distance = distance,
                    minutes = minutes,
                    isTargetOffer = false,
                    storeName = manualStoreNameInput.text.toString().trim(),
                    address = manualAddressInput.text.toString().trim()
                )
            )
        )
    }

    private fun readScoreRuleConfig(
        targetHourlyInput: EditText,
        targetYuanPerKmInput: EditText,
        targetAveragePriceInput: EditText,
        scoreBaseInput: EditText,
        fatOrderMinAmountInput: EditText,
        current: RuleSettings.RuleConfig
    ): RuleSettings.RuleConfig? {
        val targetHourly = targetHourlyInput.text.toString().toIntOrNull()
        val targetYuanPerKm = targetYuanPerKmInput.text.toString().toDoubleOrNull()
        val targetAveragePrice = targetAveragePriceInput.text.toString().toDoubleOrNull()
        val scoreBase = scoreBaseInput.text.toString().toIntOrNull()
        val fatOrderMinAmount = fatOrderMinAmountInput.text.toString().toIntOrNull()
        if (
            targetHourly == null ||
            targetYuanPerKm == null ||
            targetAveragePrice == null ||
            scoreBase == null ||
            fatOrderMinAmount == null ||
            targetHourly <= 0 ||
            targetYuanPerKm <= 0.0 ||
            targetAveragePrice <= 0.0 ||
            scoreBase !in 50..100 ||
            fatOrderMinAmount < 0
        ) {
            return null
        }

        return current.copy(
            targetHourly = targetHourly,
            targetYuanPerKm = targetYuanPerKm,
            targetAveragePrice = targetAveragePrice,
            scoreBase = scoreBase,
            fatOrderMinAmount = fatOrderMinAmount,
            subsidyPerOrder = 0
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
        addMerchantStatusCard(content, analysis)
        addListMatchCard(content, analysis)
        addResultRow(content, "配送数量", "${analysis.deliveryCount} 单")
        addResultRow(content, "金额", formatPriceWithSubsidy(analysis))
        addResultRow(content, "时间", "${analysis.minutes} 分钟")
        addResultRow(content, "距离", "${OrderAnalyzer.formatDistance(analysis.distance)} 公里")
        content.addView(createListActionRow(analysis))

        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton("知道了", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                InsetDrawable(roundedFill(Color.argb(150, 255, 255, 255), 18f), dp(18))
            )
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(COLOR_ACCENT)
                isAllCaps = false
                textSize = 15f
            }
        }

        dialog.show()
    }

    private fun formatPriceWithSubsidy(analysis: AnalysisResult): String {
        val rewardTotal = rewardTripTotal(analysis)
        return if (rewardTotal > 0) {
            "${analysis.price} 元（+${rewardTotal} 元趟奖）"
        } else {
            "${analysis.price} 元"
        }
    }

    private fun rewardTripTotal(analysis: AnalysisResult): Int {
        if (analysis.acceptMode != RuleSettings.AcceptMode.REWARD || analysis.rewardPerTrip <= 0) {
            return 0
        }
        return analysis.rewardPerTrip * analysis.deliveryCount.coerceAtLeast(1)
    }

    private fun formatRecordPrice(record: OrderHistory.Record): String {
        val rewardTotal = recordRewardTripTotal(record)
        return if (rewardTotal > 0) {
            "${record.price} 元（+${rewardTotal} 元趟奖）"
        } else {
            "${record.price} 元"
        }
    }

    private fun recordRewardTripTotal(record: OrderHistory.Record): Int {
        if (displayAcceptMode(record.acceptMode) != "趟奖模式" || record.rewardPerTrip <= 0) {
            return 0
        }
        return record.rewardPerTrip * record.deliveryCount.coerceAtLeast(1)
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

        addClickableResultRow(card, "商家", analysis.storeName.ifBlank { "未识别" })
        addClickableResultRow(card, "地址", analysis.storeAddress.ifBlank { "未识别" })
        parent.addView(card)
    }

    private fun addClickableResultRow(parent: LinearLayout, label: String, value: String) {
        addResultRow(parent, label, value)
        parent.getChildAt(parent.childCount - 1)?.setOnClickListener {
            if (value.isNotBlank() && value != "未识别") {
                showListActionChoice(value)
            }
        }
    }

    private fun showListActionChoice(keyword: String) {
        val cleanKeyword = keyword.trim()
        if (cleanKeyword.length < 2 || cleanKeyword == "未识别") return

        AlertDialog.Builder(this)
            .setTitle("添加标签")
            .setMessage(cleanKeyword)
            .setPositiveButton("添加标签备注") { _, _ ->
                showQuickAddListEntryDialog(cleanKeyword, isWhitelist = true)
            }
            .setNegativeButton("添加避雷标签") { _, _ ->
                showQuickAddListEntryDialog(cleanKeyword, isWhitelist = false)
            }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun showQuickAddListEntryDialog(keyword: String, isWhitelist: Boolean) {
        val noteInput = createNumberlessInput(
            if (isWhitelist) "备注：位置、出餐速度等" else "原因：不好取/不好送/难停车等"
        ).apply {
            setSingleLine(false)
            minLines = 2
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), 0)
            addView(TextView(this@MainActivity).apply {
                text = keyword
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_PRIMARY)
                setPadding(0, 0, 0, dp(10))
            })
            addView(noteInput)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isWhitelist) "添加标签备注" else "添加避雷标签")
            .setView(content)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val note = noteInput.text.toString().trim()
                val saved = saveListEntry(keyword, note, isWhitelist)
                Toast.makeText(
                    this,
                    if (saved) {
                        "已添加到${if (isWhitelist) "标签备注" else "避雷标签"}"
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

    private fun addListMatchCard(parent: LinearLayout, analysis: AnalysisResult) {
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
            else -> keyword.ifBlank { "已命中标签规则" }
        }
        if (isBlacklisted) {
            addColoredResultRow(parent, "命中避雷标签", hitTarget, Color.rgb(254, 226, 226), COLOR_DANGER)
        } else {
            addColoredResultRow(parent, "命中标签备注", hitTarget, Color.rgb(220, 252, 231), COLOR_SUCCESS)
        }
        if (note.isNotBlank()) addResultRow(parent, "备注", twoLine(note))
    }

    private fun recommendationColor(recommendation: String): Int {
        return when (recommendation) {
            "掙他娘的" -> COLOR_GOLD
            "站著掙" -> COLOR_SUCCESS
            "跪著送" -> COLOR_WARNING
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
            text = "$label："
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_RESULT_LABEL)
            setSingleLine(true)
        }, LinearLayout.LayoutParams(dp(82), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(this).apply {
            text = value
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            setTextColor(COLOR_TEXT_PRIMARY)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setSingleLine(false)
            setLineSpacing(0f, 1.08f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row)
    }

    private fun twoLine(value: String): String {
        return value.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("\n")
            .ifBlank { value.take(42) }
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
            gravity = if (label == "商家" || label == "地址") Gravity.START else Gravity.END
            setTextColor(COLOR_TEXT_PRIMARY)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setSingleLine(false)
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
            createSmallActionButton("加标签备注", COLOR_SUCCESS) {
                showAddListEntryDialog(analysis, isWhitelist = true)
            },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                rightMargin = dp(6)
            }
        )
        row.addView(
            createSmallActionButton("加避雷标签", COLOR_DANGER) {
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
            .setTitle(if (isWhitelist) "添加标签备注" else "添加避雷标签")
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
                        "已添加到${if (isWhitelist) "标签备注" else "避雷标签"}"
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
            blacklist = settings.normal,
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
        private val COLOR_RESULT_LABEL = Color.rgb(45, 55, 72)
        private val COLOR_TEXT_HINT = Color.rgb(139, 148, 158)
        private val COLOR_BORDER = Color.rgb(218, 225, 233)
        private val COLOR_INPUT = Color.rgb(250, 251, 252)
        private val COLOR_ACCENT = Color.rgb(13, 148, 136)
        private val COLOR_SUCCESS = Color.rgb(34, 197, 94)
        private val COLOR_DANGER = Color.rgb(239, 68, 68)
        private val COLOR_WARNING = Color.rgb(245, 158, 11)
        private val COLOR_GOLD = Color.rgb(126, 87, 194)
        private val COLOR_MUTED = Color.rgb(148, 163, 184)
        private const val REQUEST_POST_NOTIFICATIONS = 6101
    }
}
