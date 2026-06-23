package com.example.gongderefuser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.RectF
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
import android.widget.ImageView
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
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.launch

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
    private lateinit var activationCodeInput: EditText
    private lateinit var activationStatusText: TextView
    private lateinit var activationResultText: TextView

    private lateinit var whitelistKeywordInput: EditText
    private lateinit var whitelistNoteInput: EditText
    private lateinit var blacklistInput: EditText
    private lateinit var blacklistNoteInput: EditText
    private lateinit var whitelistTagContainer: LinearLayout
    private lateinit var blacklistTagContainer: LinearLayout
    private val whitelistTags = mutableListOf<RuleSettings.ListEntry>()
    private val blacklistTags = mutableListOf<RuleSettings.ListEntry>()

    private enum class Screen {
        InitialSetup,
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
    private lateinit var calibrationHintText: TextView
    private var calibrationGuidedMode = false
    private var calibrationGuideIndex = 0
    private var pendingDiagnosticZip: File? = null

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

    private val createDiagnosticZipFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val zipFile = pendingDiagnosticZip
            pendingDiagnosticZip = null
            if (uri == null) {
                zipFile?.delete()
                return@registerForActivityResult
            }
            if (zipFile == null || !zipFile.exists()) {
                Toast.makeText(this, "日誌壓縮檔不存在，請重新匯出", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            val message = runCatching {
                DiagnosticLogExporter.writeZipToUri(this, zipFile, uri)
                "日誌 ZIP 已匯出到你選擇的位置"
            }.getOrElse { throwable ->
                "日誌匯出失敗：${throwable.javaClass.simpleName} ${throwable.message.orEmpty()}"
            }
            zipFile.delete()
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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

        ActivationLocalStore.clearActivationIfNeeded(this)
        if (shouldShowInitialSetup()) {
            showInitialSetup()
        } else {
            showHome()
        }
        if (currentScreen != Screen.InitialSetup && ActivationLocalStore.isLocalActive(this)) {
            promptAccessibilityIfNeeded()
        }
        restoreMonitoringIfPossible()
        if (currentScreen == Screen.Home && ::statusTitleText.isInitialized) {
            updateMonitoringUi()
        }
    }

    override fun onResume() {
        super.onResume()
        ActivationLocalStore.clearActivationIfNeeded(this)
        stopMonitoringIfActivationExpired(showToast = false)
        if (currentScreen == Screen.InitialSetup) {
            showInitialSetup()
            return
        }
        if (ActivationLocalStore.isLocalActive(this)) {
            promptAccessibilityIfNeeded()
        }
        restoreMonitoringIfPossible()
        if (currentScreen == Screen.Home && ::statusTitleText.isInitialized) {
            updateMonitoringUi()
            updateActivationUi()
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
            setOnClickListener {
                if (!ActivationLocalStore.isLocalActive(this@MainActivity)) {
                    showActivationSettings()
                } else {
                    toggleMonitoring()
                }
            }
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
        if (OcrDebugConfig.OCR_DEBUG_TOOLS_ENABLED) {
            val uploadCard = createActionCard(
                title = "截圖分析",
                detail = "",
                accentColor = COLOR_TEXT_PRIMARY,
                showDot = false
            ).apply {
                setOnClickListener {
                    if (!ensureActivationForMonitoring()) return@setOnClickListener
                    setAnalyzing(true, "請選擇訂單截圖", "從相簿選擇訂單截圖後，我會立即進行 OCR 分析。")
                    pickOrderImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            }
            actionRow.addView(uploadCard, rowItemParams(rightMargin = dp(7)))
        }
        actionRow.addView(
            createActionCard("手動計算", "", COLOR_WARNING, showDot = false).apply {
                setOnClickListener { showManualCalculator() }
            },
            rowItemParams(leftMargin = if (OcrDebugConfig.OCR_DEBUG_TOOLS_ENABLED) dp(7) else 0)
        )
        layout.addView(actionRow)
        layout.addView(createRecentOrderCard())

        val recordRow = createCardRow().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        recordRow.addView(
            createRecordSummaryCard("訂單記錄", realtimeRecords()) {
                showRecordDetail(Screen.RealtimeHistory, "訂單記錄", realtimeRecords(), "即時 OCR 自動識別訂單")
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                rightMargin = dp(7)
            }
        )
        recordRow.addView(
            createRecordSummaryCard("截圖記錄", screenshotRecords()) {
                showRecordDetail(Screen.ScreenshotHistory, "截圖記錄", screenshotRecords(), "截圖分析儲存記錄")
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
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
            Screen.InitialSetup -> {
                showHome()
                true
            }
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
                if (currentScreen == Screen.OcrCalibration && calibrationGuidedMode) {
                    showInitialSetup()
                } else {
                    showHome()
                }
                true
            }
        }
    }

    private fun showInitialSetup() {
        currentScreen = Screen.InitialSetup
        val layout = createSettingsBaseLayout()

        layout.addView(TextView(this).apply {
            text = "初始設定"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        layout.addView(TextView(this).apply {
            text = "首次使用前，按順序完成必要服務與 OCR 校準。"
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(6), 0, dp(16))
        })

        layout.addView(createSettingsGroup(
            "啟動檢查",
            listOf(
                SettingsEntry("1", "無障礙服務", if (isAccessibilityServiceEnabled()) "已啟用" else "未啟用") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                SettingsEntry("2", "OCR 引擎狀態", "正常") {
                    showOcrCalibration(guided = true)
                },
                SettingsEntry("3", "OCR 校準流程", if (OcrCalibrationStore.hasSaved(this)) "已保存" else "未保存") {
                    showOcrCalibration(guided = true)
                },
                SettingsEntry("4", "即時監測", if (MonitoringState.isEnabled(this)) "開啟" else "關閉") {
                    enableMonitoringFromInitialSetup()
                }
            )
        ))

        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(TextView(this).apply {
            text = initialSetupSummary()
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(0f, 1.16f)
            setPadding(0, 0, 0, dp(12))
        })
        card.addView(createButton(primary = true).apply {
            text = if (initialSetupMissingCount() == 0) "完成並進入首頁" else "先進入首頁"
            setOnClickListener {
                markInitialSetupDismissed()
                showHome()
            }
        })
        card.addView(createButton(primary = false).apply {
            text = "重新檢查"
            setOnClickListener { showInitialSetup() }
        })
        layout.addView(card)

        setBaseContent(layout)
    }

    private fun enableMonitoringFromInitialSetup() {
        if (!ensureActivationForMonitoring()) return
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "請先開啟無障礙服務", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        requestNotificationPermissionIfNeeded()
        MonitoringState.setEnabled(this, true)
        Toast.makeText(this, "即時監測已開啟", Toast.LENGTH_SHORT).show()
        MyAccessibilityService.refreshStatusOverlay()
        showInitialSetup()
    }

    private fun shouldShowInitialSetup(): Boolean {
        if (isInitialSetupDismissed()) return false
        return initialSetupMissingCount() > 0
    }

    private fun initialSetupMissingCount(): Int {
        var count = 0
        if (!isAccessibilityServiceEnabled()) count++
        if (!OcrCalibrationStore.hasSaved(this)) count++
        if (!MonitoringState.isEnabled(this)) count++
        return count
    }

    private fun initialSetupSummary(): String {
        val missing = initialSetupMissingCount()
        return if (missing == 0) {
            "必要流程已完成：無障礙服務已啟用、OCR 模板已保存、即時監測已開啟。"
        } else {
            "仍有 ${missing} 項未完成。建議依序處理：無障礙服務 → OCR 校準流程 → 即時監測。"
        }
    }

    private fun isInitialSetupDismissed(): Boolean {
        return getSharedPreferences(INITIAL_SETUP_PREFS, MODE_PRIVATE)
            .getBoolean(KEY_INITIAL_SETUP_DISMISSED, false)
    }

    private fun markInitialSetupDismissed() {
        getSharedPreferences(INITIAL_SETUP_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INITIAL_SETUP_DISMISSED, true)
            .apply()
    }

    private fun showHistory() {
        currentScreen = Screen.History

        val layout = createBaseLayout()
        addSubHeader(layout, "識別記錄", "即時監測和截圖分析的歷史結果。")
        layout.addView(createOrderHistoryCard())
        setBaseContent(layout)
    }

    private fun showRecentOrderDetail() {
        currentScreen = Screen.RecentOrderDetail
        val latest = realtimeRecords().firstOrNull()
        val layout = createBaseLayout()
        addSubHeader(layout, "最近訂單", "即時 OCR 自動識別的最新一筆。")
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        if (latest == null) {
            card.addView(TextView(this).apply {
                text = "還沒有即時訂單"
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
                text = "還沒有記錄。"
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
            addHistoryRow(
                parent = card,
                record = record,
                onDelete = {
                    OrderHistory.delete(this@MainActivity, record.timestamp)
                    refreshRecordDetail(screen)
                },
                onChanged = {
                    refreshRecordDetail(screen)
                }
            )
        }

        return card
    }

    private fun refreshRecordDetail(screen: Screen) {
        when (screen) {
            Screen.RealtimeHistory -> showRecordDetail(Screen.RealtimeHistory, "訂單記錄", realtimeRecords(), "即時 OCR 自動識別訂單")
            Screen.ScreenshotHistory -> showRecordDetail(Screen.ScreenshotHistory, "截圖記錄", screenshotRecords(), "截圖分析儲存記錄")
            else -> showHome()
        }
    }

    private fun confirmClearRecords(screen: Screen) {
        AlertDialog.Builder(this)
            .setTitle("確認清空記錄？")
            .setMessage("此操作無法恢復。")
            .setNegativeButton("取消", null)
            .setPositiveButton("確認清空") { _, _ ->
                when (screen) {
                    Screen.RealtimeHistory -> OrderHistory.clearSource(this, "即時")
                    Screen.ScreenshotHistory -> OrderHistory.clearSource(this, "截圖")
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
            text = "識別記錄"
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
                text = "還沒有識別記錄。即時監測或截圖分析成功後，會自動儲存在這裡。"
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
            addHistoryRow(
                parent = card,
                record = record,
                onDelete = {
                    OrderHistory.delete(this@MainActivity, record.timestamp)
                    showHistory()
                },
                onChanged = {
                    showHistory()
                }
            )
        }

        return card
    }

    private fun addHistoryRow(
        parent: LinearLayout,
        record: OrderHistory.Record,
        onDelete: () -> Unit,
        onChanged: () -> Unit
    ) {
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
            text = "刪除"
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
            if (record.isSameLocationStack) add("爽單")
            if (record.isWhitelisted) add("標籤備註")
            if (record.isBlacklisted) add("避雷標籤")
        }.joinToString(" / ")
        row.addView(TextView(this).apply {
            text = "${record.timeLabel()} · ${record.source} · $markers"
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(TextView(this).apply {
            text = "${formatRecordPrice(record)} · ${record.minutes} 分鐘 · ${
                OrderAnalyzer.formatDistance(record.distance)
            } 公里 · ${OrderAnalyzer.formatMoney(record.effectiveHourly)} 元/小時"
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
                text = "截圖：${record.screenshotPath}"
                textSize = 11f
                setTextColor(COLOR_TEXT_SECONDARY)
                setLineSpacing(0f, 1.12f)
                setPadding(0, dp(4), 0, 0)
            })
        }
        if (record.acceptedAt > 0L || record.completedAt > 0L) {
            row.addView(TextView(this).apply {
                text = buildOrderManualStatusText(record)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_ACCENT)
                setPadding(0, dp(6), 0, 0)
            })
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val keyword = buildHistoryListKeyword(record)
        actionRow.addView(
            createSmallActionButton(if (record.acceptedAt > 0L) "已接" else "接受", COLOR_ACCENT) {
                OrderHistory.markAccepted(this@MainActivity, record.timestamp)
                onChanged()
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                rightMargin = dp(4)
            }
        )
        actionRow.addView(
            createSmallActionButton(if (record.completedAt > 0L) "已完成" else "完成", COLOR_SUCCESS) {
                OrderHistory.markCompleted(this@MainActivity, record.timestamp)
                onChanged()
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
        )
        actionRow.addView(
            createSmallActionButton("加備註", COLOR_SUCCESS) {
                showAddListEntryDialog(keyword, "", isWhitelist = true)
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
        )
        actionRow.addView(
            createSmallActionButton("加避雷", COLOR_DANGER) {
                showAddListEntryDialog(keyword, "", isWhitelist = false)
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                leftMargin = dp(4)
            }
        )
        row.addView(actionRow)

        parent.addView(row)
    }

    private fun buildOrderManualStatusText(record: OrderHistory.Record): String {
        return buildList {
            if (record.acceptedAt > 0L) add("已接：${record.acceptedTimeLabel()}")
            if (record.completedAt > 0L) add("已完成：${record.completedTimeLabel()}")
        }.joinToString("　")
    }

    private fun createModeBadge(mode: String): TextView {
        val isReward = mode == "趟獎模式"
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
            .filter { it.isNotBlank() && it != "未識別商家" }
            .joinToString("\n")
            .ifBlank { record.storeName }
    }

    private fun showManualCalculator() {
        currentScreen = Screen.Manual

        val layout = createBaseLayout()
        addSubHeader(layout, "手動計算", "沒有截圖時，直接輸入訂單資訊。")

        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        incomeInput = addLabeledNumberInput(card, "單筆預期收入", "元")
        distanceInput = addLabeledNumberInput(card, "總跑單里程", "公里")
        minutesInput = addLabeledNumberInput(card, "預估總時間", "分鐘")
        card.addView(TextView(this).apply {
            val rule = RuleSettings.load(this@MainActivity).normal
            text = "使用規則頁目標：時薪 ${rule.targetHourly}，${OrderAnalyzer.formatDistance(rule.targetYuanPerKm)} 元/km，平均單價 ${OrderAnalyzer.formatMoney(rule.targetAveragePrice)}。"
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(4), 0, dp(12))
        })
        manualStoreNameInput = addLabeledTextInput(card, "商家名稱", "可選，用於加入標籤備註/避雷標籤")
        manualAddressInput = addLabeledTextInput(card, "配送地址", "可選，用於加入標籤備註/避雷標籤")
        card.addView(createButton(primary = true).apply {
            text = "計算是否划算"
            setOnClickListener { calculateManualOrder() }
        })
        layout.addView(card)

        setBaseContent(layout)
    }

    private fun showRuleSettings() {
        currentScreen = Screen.Rules

        val settings = RuleSettings.load(this)
        val layout = createBaseLayout()
        addSubHeader(layout, "規則", "點擊卡片進入對應項目修改。", showBackButton = false)

        layout.addView(
            createActionCard(
                "接單模式",
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
                "評分規則",
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
                "標籤備註",
                "已建立 ${settings.whitelistEntries.size} 條\n查看標籤库",
                COLOR_SUCCESS
            ).apply { setOnClickListener { showListEditor(isWhitelist = true) } },
            rowItemParams(rightMargin = dp(7))
        )
        listRow.addView(
            createActionCard(
                "避雷標籤",
                "已建立 ${settings.blacklistEntries.size} 條\n查看避雷库",
                COLOR_DANGER
            ).apply { setOnClickListener { showListEditor(isWhitelist = false) } },
            rowItemParams(leftMargin = dp(7))
        )
        layout.addView(listRow)

        setBaseContent(layout)
    }

    private fun ruleSummary(rule: RuleSettings.RuleConfig): String {
        return "時薪 ${rule.targetHourly}；${OrderAnalyzer.formatDistance(rule.targetYuanPerKm)} 元/km；均價 ${OrderAnalyzer.formatMoney(rule.targetAveragePrice)}；肥單 ${rule.fatOrderMinAmount} 元"
    }

    private fun acceptModeTitle(mode: RuleSettings.AcceptMode): String {
        return when (mode) {
            RuleSettings.AcceptMode.REWARD -> "趟獎模式"
            RuleSettings.AcceptMode.NORMAL -> "正常模式"
        }
    }

    private fun displayAcceptMode(mode: String): String {
        return when (mode) {
            "躺獎模式", "躺獎模式", "趟獎模式", "趟獎模式" -> "趟獎模式"
            "正常模式" -> "正常模式"
            else -> mode.ifBlank { "未記錄" }
        }
    }

    private fun showAcceptModeEditor() {
        currentScreen = Screen.RuleDetail

        val settings = RuleSettings.load(this)
        var selectedMode = settings.acceptMode
        val layout = createBaseLayout()
        addSubHeader(layout, "接單模式", "選擇目前跑單時段的判斷方式。")

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
            (rewardOption.getChildAt(0) as? TextView)?.text = "${if (selectedMode == RuleSettings.AcceptMode.REWARD) "●" else "○"} 趟獎模式"
        }
        normalOption = createModeOption(
            title = "正常模式",
            detail = "使用原始動態評分算法",
            selected = selectedMode == RuleSettings.AcceptMode.NORMAL,
            accentColor = COLOR_SUCCESS
        ) {
            selectedMode = RuleSettings.AcceptMode.NORMAL
            refreshOptions()
        }
        rewardOption = createModeOption(
            title = "趟獎模式",
            detail = "提高多單且不拖時間訂單的權重",
            selected = selectedMode == RuleSettings.AcceptMode.REWARD,
            accentColor = COLOR_ACCENT
        ) {
            selectedMode = RuleSettings.AcceptMode.REWARD
            refreshOptions()
        }
        card.addView(normalOption)
        card.addView(rewardOption)

        val rewardInput = addLabeledNumberInput(card, "每趟獎勵金額", "元").apply {
            setText(settings.rewardPerTrip.toString())
        }
        card.addView(TextView(this).apply {
            text = buildString {
                appendLine("趟獎模式不修改訂單金額。")
                appendLine("仅提高多單且高效率訂單權重。")
                append("避雷單與低效率訂單不會獲得加權。")
            }
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(2f * resources.displayMetrics.density, 1.08f)
            setPadding(0, dp(6), 0, dp(12))
        })
        card.addView(createButton(primary = true).apply {
            text = "儲存"
            setOnClickListener {
                val rewardPerTrip = rewardInput.text.toString().toIntOrNull()
                if (rewardPerTrip == null || rewardPerTrip < 0) {
                    Toast.makeText(this@MainActivity, "請輸入有效獎勵金額", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                RuleSettings.saveAcceptMode(this@MainActivity, selectedMode, rewardPerTrip)
                Toast.makeText(this@MainActivity, "已儲存接單模式", Toast.LENGTH_SHORT).show()
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
            "評分規則",
            "目標值代表公平價格，達到目標值為 80 分。"
        )

        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val targetHourlyInput = addLabeledNumberInput(card, "目標時薪", "元/小時").apply {
            setText(rule.targetHourly.toString())
        }
        val targetYuanPerKmInput = addLabeledNumberInput(card, "目標元/公里", "金額除以公里").apply {
            setText(OrderAnalyzer.formatDistance(rule.targetYuanPerKm))
        }
        val targetAveragePriceInput = addLabeledNumberInput(card, "目標平均單價", "金額除以配送數量").apply {
            setText(OrderAnalyzer.formatMoney(rule.targetAveragePrice))
        }
        val scoreBaseInput = addLabeledNumberInput(card, "評分基準分", "50 到 100").apply {
            setText(rule.scoreBase.toString())
        }
        val fatOrderMinAmountInput = addLabeledNumberInput(card, "肥單最低金額", "預設 100 元").apply {
            setText(rule.fatOrderMinAmount.toString())
        }
        card.addView(createButton(primary = true).apply {
            text = "儲存"
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
                    Toast.makeText(this@MainActivity, "請輸入有效目標，評分基準分需為 50 到 100", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                RuleSettings.save(
                    context = this@MainActivity,
                    normal = updatedRule,
                    blacklist = updatedRule,
                    whitelistText = RuleSettings.serializeEntries(settings.whitelistEntries),
                    blacklistText = RuleSettings.serializeEntries(settings.blacklistEntries)
                )
                Toast.makeText(this@MainActivity, "已儲存", Toast.LENGTH_SHORT).show()
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
            if (isWhitelist) "標籤備註" else "避雷標籤",
            if (isWhitelist) "命中後只顯示備註，不加分不扣分。" else "命中任意避雷標籤後扣 10 分。"
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
        val title = if (isWhitelist) "標籤列表" else "避雷列表"
        val detail = if (isWhitelist) "已建立 $count 個標籤" else "已建立 $count 個避雷標籤"
        return createActionCard(
            title = title,
            detail = "$detail\n點擊查看和管理",
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
            if (isWhitelist) "標籤管理" else "避雷管理",
            if (isWhitelist) "查詢、匯入、匯出和编辑標籤備註。" else "查詢、匯入、匯出和编辑避雷標籤。"
        )
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val searchInput = createNumberlessInput("查詢名稱或備註")
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
        actionRow.addView(createSmallActionButton("查詢", COLOR_ACCENT) {
            renderListTags(isWhitelist, searchInput.text.toString().trim())
        }, actionButtonParams(right = dp(6)))
        actionRow.addView(createSmallActionButton("匯入", COLOR_SUCCESS) {
            showImportListDialog(isWhitelist)
        }, actionButtonParams(left = dp(3), right = dp(3)))
        actionRow.addView(createSmallActionButton("匯出", COLOR_WARNING) {
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
            if (isWhitelist) "商家名稱或地址關鍵词" else "不好送商家/地址關鍵词"
        )
        val noteInput = createNumberlessInput(
            if (isWhitelist) "備註：位置、出餐速度等" else "原因：不好取/不好送/難停車等"
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
            text = if (isWhitelist) "新增標籤備註" else "新增避雷標籤"
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
            Toast.makeText(this, "請輸入至少兩個字的關鍵词", Toast.LENGTH_SHORT).show()
            return
        }

        val list = if (isWhitelist) whitelistTags else blacklistTags
        if (RuleSettings.containsMatchingEntry(whitelistTags + blacklistTags, keyword)) {
            Toast.makeText(this, "名單裡已有相同商家或地址", Toast.LENGTH_SHORT).show()
        } else {
            list.add(RuleSettings.ListEntry(keyword, note))
            persistListTags(isWhitelist)
            Toast.makeText(this, "標籤已儲存", Toast.LENGTH_SHORT).show()
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
            if (isWhitelist) "還沒有標籤備註" else "還沒有避雷標籤"
        } else {
            "沒有找到匹配標籤"
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
        val keywordInput = createNumberlessInput("商家名稱或地址關鍵词").apply {
            setText(entry.keyword)
        }
        val noteInput = createNumberlessInput(
            if (isWhitelist) "備註：位置、出餐速度等" else "原因：不好取/不好送/難停車等"
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
            .setTitle(if (isWhitelist) "编辑標籤備註" else "编辑避雷標籤")
            .setView(content)
            .setPositiveButton("儲存", null)
            .setNegativeButton("刪除", null)
            .setNeutralButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val keyword = keywordInput.text.toString().trim()
                val note = noteInput.text.toString().trim()
                if (keyword.length < 2) {
                    keywordInput.error = "至少兩個字"
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
                Toast.makeText(this@MainActivity, "標籤已儲存", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val list = if (isWhitelist) whitelistTags else blacklistTags
                list.remove(entry)
                persistListTags(isWhitelist)
                Toast.makeText(this@MainActivity, "標籤已刪除", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "讀取檔案失敗：${it.message.orEmpty()}", Toast.LENGTH_LONG).show()
            return
        }

        val imported = RuleSettings.parseEntries(text)
        if (imported.isEmpty()) {
            Toast.makeText(this, "沒有可匯入內容，請確認格式為：名稱|備註", Toast.LENGTH_LONG).show()
            return
        }

        val target = if (isWhitelist) whitelistTags else blacklistTags
        val beforeCount = target.size
        val merged = (target + imported).distinctBy { it.keyword }
        target.clear()
        target.addAll(merged)
        persistListTags(isWhitelist)
        Toast.makeText(this, "已匯入 ${target.size - beforeCount} 條，跳過 ${imported.size - (target.size - beforeCount)} 條重複", Toast.LENGTH_LONG).show()
    }

    private fun exportListEntries(isWhitelist: Boolean) {
        val entries = if (isWhitelist) whitelistTags else blacklistTags
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "功德拒絕器/名單匯出"
        )
        dir.mkdirs()
        val file = File(dir, if (isWhitelist) "標籤備註.txt" else "避雷標籤.txt")
        file.writeText(RuleSettings.serializeEntries(entries), Charsets.UTF_8)
        Toast.makeText(this, "已匯出：${file.absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun showAppSettings() {
        currentScreen = Screen.AppSettings

        val layout = createSettingsBaseLayout()
        addSettingsHeader(layout)

        layout.addView(createSettingsGroup(
            "權限與服務",
            listOf(
                SettingsEntry("♿", "無障礙服務", if (isAccessibilityServiceEnabled()) "已啟用" else "未啟用") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                SettingsEntry("OCR", "OCR 引擎狀態", "正常") {
                    showDebugSettings()
                },
                SettingsEntry("框", "失敗 OCR 截圖", if (OcrFailureDebugStore.latestFailure(this) != null) "有記錄" else "無記錄") {
                    showLastFailedOcrScreenshot()
                }
            )
        ))

        layout.addView(createSettingsGroup(
            "通知與提醒",
            listOf(
                SettingsEntry("♪", "提示音", if (AppSettings.isSoundEnabled(this)) "開啟" else "關閉") {
                    showSoundSettings()
                }
            )
        ))

        layout.addView(createSettingsGroup(
            "授權與設備",
            listOf(
                SettingsEntry("🔑", "啟用狀態", activationSummaryStatus()) {
                    if (ActivationLocalStore.isActivationRequired(this)) showActivationSettings()
                },
                SettingsEntry("⏱", "到期時間", activationExpirySummary()) {
                    if (ActivationLocalStore.isActivationRequired(this)) showActivationSettings()
                },
                SettingsEntry("◇", "設備綁定資訊", deviceBindingSummary()) {
                    showDeviceInfoDialog()
                }
            )
        ))

        layout.addView(createSettingsGroup(
            "系統資訊",
            listOf(
                SettingsEntry("↻", "更新日誌", "查看") {
                    showUpdateLogDialog()
                },
                SettingsEntry("⌘", "隱私政策", "查看") {
                    showPrivacyPolicyDialog()
                },
                SettingsEntry("⇩", "資料管理", "日誌匯出") {
                    showDataSettings()
                },
                SettingsEntry("ⓘ", "關於與版本", appVersionLabel()) {
                    showAboutSettings()
                }
            )
        ))

        setBaseContent(layout)
    }

    private data class SettingsEntry(
        val icon: String,
        val title: String,
        val status: String,
        val onClick: () -> Unit
    )

    private fun createSettingsBaseLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
            setBackgroundColor(COLOR_SETTINGS_BACKGROUND)
        }
    }

    private fun addSettingsHeader(layout: LinearLayout) {
        layout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
            addView(TextView(this@MainActivity).apply {
                text = "設定"
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_PRIMARY)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "版本 ${appVersionNameOnly()}"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_SECONDARY)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = roundedStroke(Color.WHITE, COLOR_BORDER, 999f)
            })
        })
    }

    private fun createSettingsGroup(title: String, entries: List<SettingsEntry>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }

            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_SECONDARY)
                setPadding(dp(4), 0, dp(4), dp(8))
            })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedFill(Color.WHITE, 16f)
                elevation = dp(1).toFloat()
                entries.forEachIndexed { index, entry ->
                    addView(createSettingsListRow(entry))
                    if (index != entries.lastIndex) addView(createSettingsDivider())
                }
            })
        }
    }

    private fun createSettingsListRow(entry: SettingsEntry): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
            minimumHeight = dp(58)
            isClickable = true
            isFocusable = true
            setOnClickListener { entry.onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
            )

            addView(TextView(this@MainActivity).apply {
                text = entry.icon
                textSize = if (entry.icon.length <= 2) 18f else 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(COLOR_ACCENT)
                background = roundedFill(Color.rgb(241, 245, 249), 999f)
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                rightMargin = dp(12)
            })

            addView(TextView(this@MainActivity).apply {
                text = entry.title
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_PRIMARY)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(this@MainActivity).apply {
                text = entry.status
                textSize = 13f
                setTextColor(settingsStatusColor(entry.status))
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT))

            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 24f
                setTextColor(COLOR_TEXT_HINT)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(18), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun createSettingsDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.rgb(238, 242, 246))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                leftMargin = dp(60)
            }
        }
    }

    private fun settingsStatusColor(status: String): Int {
        return when {
            status.contains("未") || status == "關閉" || status == "異常" -> COLOR_TEXT_HINT
            status.contains("已") || status == "開啟" || status == "正常" -> COLOR_SUCCESS
            else -> COLOR_TEXT_SECONDARY
        }
    }

    private fun notificationStatusLabel(): String {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            "開啟"
        } else {
            "關閉"
        }
    }

    private fun activationSummaryStatus(): String {
        if (!ActivationLocalStore.isActivationRequired(this)) return "測試版"
        return if (ActivationLocalStore.isLocalActive(this)) "已啟用" else "未啟用"
    }

    private fun activationExpirySummary(): String {
        if (!ActivationLocalStore.isActivationRequired(this)) return "不限制"
        val expiresAtMillis = ActivationLocalStore.getExpiresAtMillis(this)
        return if (expiresAtMillis > 0L) ActivationManager.formatDateTime(expiresAtMillis) else "--"
    }

    private fun deviceBindingSummary(): String {
        return if (DeviceIdManager.getDeviceId(this).isNotBlank()) "設備已綁定" else "未綁定"
    }

    private fun showDeviceInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("設備綁定資訊")
            .setMessage("設備 ID：${DeviceIdManager.getDeviceId(this)}")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showUpdateLogDialog() {
        AlertDialog.Builder(this)
            .setTitle("更新日誌")
            .setMessage(
                """
                目前版本：${appVersionLabel()}

                本次更新：
                1. 新增首次啟動設定流程，依序檢查無障礙服務、OCR 狀態、OCR 校準與即時監測。
                2. OCR 校準新增逐步引導：關閉按鈕、金額、時間距離、商家、地址、同地點配送。
                3. 設定頁整理系統資訊，合併版本號與關於頁，減少重複入口。
                4. 更新隱私政策與免責聲明。
                5. 保留現有 OCR、Parser、評分、Overlay 與訂單記錄邏輯。
                """.trimIndent()
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showPrivacyPolicyDialog() {
        AlertDialog.Builder(this)
            .setTitle("隱私政策")
            .setMessage(
                """
                功德拒絕器重視你的資料安全與使用自主權。

                一、資料處理範圍
                本 APP 主要在本機處理訂單畫面截圖、OCR 識別結果、規則設定、黑白名單、訂單記錄、校準模板與診斷日誌。

                二、本機分析
                OCR、訂單解析、評分與提醒邏輯均在裝置本機執行。除非你主動匯出、分享或提交診斷資料，APP 不會主動上傳訂單截圖、OCR 原文、訂單記錄或個人設定。

                三、診斷日誌
                診斷日誌僅用於排查識別、觸發、匯出或權限問題。日誌可能包含事件時間、識別狀態、OCR 統計、訂單核心數值與部分診斷文字。你可以在資料管理中打包匯出，也可以清除診斷資料。

                四、權限用途
                無障礙服務用於偵測目標平台訂單彈窗與取得系統截圖能力，以便觸發本機 OCR 分析。通知、音效或震動僅用於提醒識別結果。APP 不會替你自動接單、拒單或操作第三方平台。

                五、使用者控制
                你可以隨時關閉無障礙服務、停止即時監測、關閉提示音、清除診斷資料，或卸載 APP。

                六、免責聲明
                本 APP 僅作為訂單資訊輔助分析工具，分析結果、分數、提示音與建議僅供參考，不構成收入保證、接單建議承諾或任何法律、財務、營運決策保證。實際是否接受訂單、完成配送、遵守平台規範與當地法律，均由使用者自行判斷並承擔責任。

                七、第三方平台
                本 APP 與任何第三方平台沒有官方合作、授權或背書關係。第三方平台介面、規則或政策變更可能影響識別效果，使用者應自行確認其使用方式符合相關平台條款。
                """.trimIndent()
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showActivationSettings() {
        currentScreen = Screen.SettingsDetail
        val layout = createBaseLayout()
        addSubHeader(layout, "啟用碼", "輸入啟用碼或續期。")
        layout.addView(createActivationCard())
        setBaseContent(layout)
        updateActivationUi()
    }

    private fun showSoundSettings() {
        currentScreen = Screen.SettingsDetail
        val layout = createBaseLayout()
        addSubHeader(layout, "音效設定", "回饋音和測試播放。")
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(createSettingsToggleRow(
            title = "回饋音效",
            enabled = AppSettings.isSoundEnabled(this),
            onToggle = { enabled ->
                AppSettings.setSoundEnabled(this, enabled)
                showSoundSettings()
            }
        ))
        card.addView(TextView(this).apply {
            text = "訂單結果會按四檔播放對應提示音。"
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
        if (AppSettings.isAccessibilityLogEnabled(this)) {
            DiagnosticLogStore.writeSelfTest(this, "debug_settings_view")
        }
        val layout = createBaseLayout()
        addSubHeader(layout, "調試資訊", "識別問題排查入口。")
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(createSettingsToggleRow(
            title = "問題診斷模式",
            enabled = AppSettings.isDebugSamplesEnabled(this),
            onToggle = { enabled ->
                AppSettings.setDebugSamplesEnabled(this, enabled)
                showDebugSettings()
            }
        ))
        card.addView(TextView(this).apply {
            text = "儲存截圖、OCR結果和訂單分析資料，方便排查識別問題。\n路徑：${AppSettings.debugSamplePath(this@MainActivity)}"
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(8), 0, dp(12))
        })
        if (OcrDebugConfig.OCR_DEBUG_TOOLS_ENABLED) {
            card.addView(createActionCard(
                title = "OCR 校准",
                detail = "選擇截圖產生區域框圖",
                accentColor = COLOR_ACCENT
            ).apply {
                setOnClickListener { showOcrCalibration(guided = true) }
            })
            card.addView(createActionCard(
                title = "上一張失敗 OCR 截圖",
                detail = if (OcrFailureDebugStore.latestFailure(this) != null) "查看帶框截圖" else "目前無記錄",
                accentColor = COLOR_WARNING
            ).apply {
                setOnClickListener { showLastFailedOcrScreenshot() }
            })
            card.addView(createActionCard(
                title = "上一張 OCR 截圖",
                detail = if (OcrFailureDebugStore.latestOcr(this) != null) "查看帶框截圖" else "目前無記錄",
                accentColor = COLOR_ACCENT
            ).apply {
                setOnClickListener { showLatestOcrScreenshot() }
            })
        }
        if (isDebugBuild()) {
            card.addView(createSettingsToggleRow(
                title = "記錄無障礙事件日誌",
                enabled = AppSettings.isAccessibilityLogEnabled(this),
                onToggle = { enabled ->
                    AppSettings.setAccessibilityLogEnabled(this, enabled)
                    val file = DiagnosticLogStore.writeSelfTest(this, "accessibility_log_toggle_enabled_$enabled")
                    Toast.makeText(
                        this,
                        if (!enabled) {
                            "日誌已關閉"
                        } else if (file != null) {
                            "日誌已寫入：${file.name}"
                        } else {
                            "日誌寫入失敗"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                    showDebugSettings()
                }
            ))
            card.addView(TextView(this).apply {
                text = "無障礙事件日誌路徑：${AppSettings.diagnosticLogPath(this@MainActivity)}\n主日誌按小時輸出：yyyyMMdd-HH-monitor-events.txt\n排查時按問題發生時間傳對應小時的 TXT 即可。\n本次嘗試時間：${DiagnosticLogStore.lastAttemptTime()}\n最近寫入：${DiagnosticLogStore.lastWriteSummary()}"
                textSize = 12f
                setTextColor(COLOR_TEXT_SECONDARY)
                setLineSpacing(0f, 1.12f)
                setPadding(0, dp(8), 0, 0)
            })
        }
        layout.addView(card)
        setBaseContent(layout)
    }

    private fun showLastFailedOcrScreenshot() {
        showOcrDebugScreenshot(
            title = "上一張失敗 OCR 截圖",
            latest = OcrFailureDebugStore.latestFailure(this),
            emptyMessage = "目前沒有失敗 OCR 截圖"
        )
    }

    private fun showLatestOcrScreenshot() {
        showOcrDebugScreenshot(
            title = "上一張 OCR 截圖",
            latest = OcrFailureDebugStore.latestOcr(this),
            emptyMessage = "目前沒有 OCR 截圖"
        )
    }

    private fun showOcrDebugScreenshot(
        title: String,
        latest: OcrFailureDebugStore.LatestFailure?,
        emptyMessage: String
    ) {
        if (latest == null) {
            Toast.makeText(this, emptyMessage, Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = BitmapFactory.decodeFile(latest.regionImageFile.absolutePath)
        if (bitmap == null) {
            Toast.makeText(this, "截圖讀取失敗", Toast.LENGTH_SHORT).show()
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        content.addView(ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        content.addView(TextView(this).apply {
            text = buildString {
                append("來源：${latest.source.ifBlank { "未知" }}\n")
                append("路徑：${latest.regionImageFile.absolutePath}")
                latest.textFile?.let { append("\n文字診斷：${it.absolutePath}") }
            }
            textSize = 11f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(10), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showDataSettings() {
        currentScreen = Screen.SettingsDetail
        val layout = createBaseLayout()
        addSubHeader(layout, "資料管理", "診斷資料與調試檔案。")
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(createButton(primary = true).apply {
            text = "打包匯出日誌"
            setOnClickListener { exportDiagnosticLogs() }
        })
        card.addView(createButton(primary = false).apply {
            text = "清除診斷資料"
            setOnClickListener { showClearDiagnosticDataDialog() }
        })
        card.addView(TextView(this).apply {
            text = "日誌會先保存在 APP 內部可寫位置。\n需要查看時，點「打包匯出日誌」，選擇任意可訪問位置保存 ZIP。"
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(10), 0, 0)
        })
        layout.addView(card)
        setBaseContent(layout)
    }

    private fun exportDiagnosticLogs() {
        val zipFile = runCatching {
            DiagnosticLogExporter.createTempZip(this)
        }.getOrElse { throwable ->
            Toast.makeText(
                this,
                "日誌打包失敗：${throwable.javaClass.simpleName} ${throwable.message.orEmpty()}",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        pendingDiagnosticZip?.delete()
        pendingDiagnosticZip = zipFile
        createDiagnosticZipFile.launch(zipFile.name)
    }

    private fun showAboutSettings() {
        currentScreen = Screen.SettingsDetail
        val layout = createBaseLayout()
        addSubHeader(layout, "關於應用", "版本 ${appVersionNameOnly()}")
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(TextView(this).apply {
            text = "功德拒絕器\n版本：${appVersionLabel()}\n\n公平，公平，還是他媽的公平。"
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
            .setTitle("清除診斷資料")
            .setMessage("確定刪除所有診斷檔案嗎？")
            .setNegativeButton("取消", null)
            .setPositiveButton("確定") { _, _ ->
                DebugFileDirs.clearDiagnostics(this)
                Toast.makeText(this, "診斷資料已清除", Toast.LENGTH_SHORT).show()
                showAppSettings()
            }
            .show()
    }

    private fun isDebugBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun showOcrCalibration(guided: Boolean = false) {
        if (!OcrDebugConfig.OCR_DEBUG_TOOLS_ENABLED) {
            Toast.makeText(this, "OCR 調試工具已關閉", Toast.LENGTH_SHORT).show()
            showAppSettings()
            return
        }
        currentScreen = Screen.OcrCalibration
        calibrationGuidedMode = guided
        if (calibrationGuideIndex !in OCR_CALIBRATION_GUIDE_ORDER.indices) {
            calibrationGuideIndex = 0
        }
        loadLastCalibrationBitmapIfNeeded()
        val selectedRegion = if (calibrationGuidedMode) {
            OCR_CALIBRATION_GUIDE_ORDER[calibrationGuideIndex]
        } else {
            OcrCalibrationStore.editableRegionNames.first()
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        calibrationBitmap?.let { bitmap ->
            val activeTemplate = OcrTemplateRepository.getActiveTemplate(this)
            logTemplateLoadForEditor(activeTemplate)
            val initialRegions = activeTemplate.regions
            val view = OcrCalibrationView(this).apply {
                setImageAndRegions(bitmap, initialRegions)
                selectRegion(selectedRegion)
            }
            calibrationView = view
            root.addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            if (calibrationSavedPath.isNotBlank()) {
                root.addView(TextView(this).apply {
                    text = if (calibrationSavedPath.startsWith("內建")) {
                        "已載入內建 OCR 校準圖與保存模板"
                    } else {
                        "已載入上次 OCR 校準圖"
                    }
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
                text = "先點上方“選擇截圖”載入訂單截圖"
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
            text = "選擇"
            setOnClickListener { pickCalibrationScreenshot() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        })
        topRow.addView(createButton(primary = true).apply {
            text = "保存"
            setOnClickListener {
                val current = calibrationView?.currentRegions().orEmpty()
                saveOcrCalibrationTemplate(current)
                Toast.makeText(this@MainActivity, "OCR 模板已保存", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        })
        topRow.addView(createButton(primary = false).apply {
            text = "預設"
            setOnClickListener {
                OcrCalibrationStore.reset(this@MainActivity)
                DiagnosticLogStore.append(
                    this@MainActivity,
                    "OCR_TEMPLATE_SOURCE",
                    "templateSource=DEFAULT templateVersion=${OcrTemplateRepository.TEMPLATE_VERSION} loadedAt=${System.currentTimeMillis()} hasUserSavedTemplate=false fallbackReason=USER_RESET"
                )
                lastCalibrationImageFile().delete()
                calibrationBitmap = BitmapFactory.decodeResource(resources, R.drawable.default_ocr_calibration)
                calibrationSavedPath = "內建預設 OCR 校準圖"
                calibrationBitmap?.let { bitmap ->
                    calibrationView?.setImageAndRegions(bitmap, OcrCalibrationStore.defaultRegions())
                    calibrationView?.selectRegion(selectedRegion)
                }
                updateCalibrationHint(selectedRegion)
                Toast.makeText(this@MainActivity, "已恢復內建預設圖與模板", Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4)
        })
        toolbar.addView(topRow)

        val regionPicker = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val regionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        OcrCalibrationStore.editableRegionNames.forEachIndexed { index, name ->
            regionRow.addView(createCalibrationRegionButton(name), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = if (index == OcrCalibrationStore.editableRegionNames.lastIndex) 0 else dp(8)
            })
        }
        regionPicker.addView(regionRow)
        toolbar.addView(regionPicker, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })

        toolbar.addView(TextView(this).apply {
            text = "商家與地址現在使用一個四行總文字區識別，不需要分別調整商家一行、地址一行或地址兩行。"
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(0f, 1.12f)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(8)
        })

        if (OcrDebugConfig.SHOW_LEGACY_OCR_REGION_PICKER) {
            toolbar.addView(TextView(this).apply {
                text = "高級 Anchor 診斷"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT_SECONDARY)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(4)
            })

            val advancedPicker = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
            }
            val advancedRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            OcrCalibrationStore.debugRegionNames.forEachIndexed { index, name ->
                advancedRow.addView(createCalibrationRegionButton(name), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = if (index == OcrCalibrationStore.debugRegionNames.lastIndex) 0 else dp(8)
                })
            }
            advancedPicker.addView(advancedRow)
            toolbar.addView(advancedPicker, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            })
        }

        if (calibrationGuidedMode) {
            toolbar.addView(createCalibrationGuideRow(), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            })
        }

        calibrationHintText = TextView(this).apply {
            text = "請選擇上方任一模板。提示會顯示在這裡。"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            setLineSpacing(0f, 1.16f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedFill(Color.WHITE, 12f)
        }
        toolbar.addView(calibrationHintText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(toolbar, FrameLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ).apply {
            leftMargin = dp(8)
            topMargin = dp(8)
            rightMargin = dp(8)
        })

        updateCalibrationHint(selectedRegion)
        setContentView(root)
    }

    private fun pickCalibrationScreenshot() {
        setAnalyzing(true, "請選擇校准截圖", "產生 OCR 區域框圖後會提示儲存位置。")
        pickCalibrationImage.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun lastCalibrationImageFile(): File {
        return File(DebugFileDirs.resolveAppScoped(this, "manual_ocr_debug"), "last_calibration.png")
    }

    private fun loadLastCalibrationBitmapIfNeeded() {
        if (calibrationBitmap != null) return
        val savedFile = lastCalibrationImageFile()
        if (!AppSettings.isDebugSamplesEnabled(this)) {
            savedFile.delete()
        } else if (savedFile.exists()) {
            BitmapFactory.decodeFile(savedFile.absolutePath)?.let { bitmap ->
                calibrationBitmap = bitmap
                calibrationSavedPath = savedFile.absolutePath
                return
            }
        }
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.default_ocr_calibration) ?: return
        calibrationBitmap = bitmap
        calibrationSavedPath = "內建預設 OCR 校準圖"
    }

    private fun saveLastCalibrationBitmap(bitmap: Bitmap): String {
        if (!AppSettings.isDebugSamplesEnabled(this)) return ""
        val file = lastCalibrationImageFile()
        return runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            file.absolutePath
        }.getOrDefault("")
    }

    private fun createCalibrationRegionButton(name: String): TextView {
        return TextView(this).apply {
            text = OcrCalibrationStore.displayName(name)
            textSize = 13f
            typeface = if (name == "closeButton") {
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
                    else -> COLOR_MUTED
                },
                999f
            )
            setOnClickListener {
                val guideIndex = OCR_CALIBRATION_GUIDE_ORDER.indexOf(name)
                if (guideIndex >= 0) calibrationGuideIndex = guideIndex
                calibrationView?.selectRegion(name)
                updateCalibrationHint(name)
            }
        }
    }

    private fun createCalibrationGuideRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(createSmallActionButton("上一步", COLOR_MUTED) {
                moveCalibrationGuide(-1)
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                rightMargin = dp(5)
            })
            addView(createSmallActionButton("下一步", COLOR_ACCENT) {
                moveCalibrationGuide(1)
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                leftMargin = dp(5)
                rightMargin = dp(5)
            })
            addView(createSmallActionButton("保存完成", COLOR_SUCCESS) {
                val current = calibrationView?.currentRegions().orEmpty()
                saveOcrCalibrationTemplate(current)
                Toast.makeText(this@MainActivity, "OCR 校準已保存", Toast.LENGTH_SHORT).show()
                showInitialSetup()
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                leftMargin = dp(5)
            })
        }
    }

    private fun saveOcrCalibrationTemplate(regions: Map<String, RectF>) {
        val success = OcrTemplateRepository.save(this, regions)
        val active = OcrTemplateRepository.getActiveTemplate(this)
        DiagnosticLogStore.append(
            this,
            "OCR_TEMPLATE_SAVE",
            "saveSuccess=$success savePath=${OcrTemplateRepository.savePath(this)} ${OcrTemplateRepository.templateSummary(active.regions)}"
        )
    }

    private fun logTemplateLoadForEditor(activeTemplate: OcrTemplateRepository.ActiveTemplate) {
        DiagnosticLogStore.append(
            this,
            "OCR_TEMPLATE_LOAD_FOR_EDITOR",
            "templateSource=${activeTemplate.source.name} templateVersion=${activeTemplate.version} fallbackReason=${activeTemplate.fallbackReason} ${OcrTemplateRepository.templateSummary(activeTemplate.regions)}"
        )
    }

    private fun moveCalibrationGuide(delta: Int) {
        val lastIndex = OCR_CALIBRATION_GUIDE_ORDER.lastIndex
        calibrationGuideIndex = (calibrationGuideIndex + delta).coerceIn(0, lastIndex)
        val name = OCR_CALIBRATION_GUIDE_ORDER[calibrationGuideIndex]
        calibrationView?.selectRegion(name)
        updateCalibrationHint(name)
    }

    private fun updateCalibrationHint(name: String) {
        if (!::calibrationHintText.isInitialized) return
        val prefix = if (calibrationGuidedMode) {
            val step = OCR_CALIBRATION_GUIDE_ORDER.indexOf(name).takeIf { it >= 0 }?.plus(1)
                ?: (calibrationGuideIndex + 1)
            "第 $step/${OCR_CALIBRATION_GUIDE_ORDER.size} 步\n"
        } else {
            ""
        }
        calibrationHintText.text = "${prefix}目前調整：${OcrCalibrationStore.displayName(name)}\n${calibrationRegionHint(name)}"
    }

    private fun calibrationRegionHint(name: String): String {
        return when (name) {
            "card" -> "固定參考框：固定屏幕下半部大小，只作背景參考。"
            "closeSearch" -> "跟隨關閉按鈕模板：上下加大搜尋範圍，不單獨調整。"
            "closeButton" -> "關閉 X 放在小方塊正中心。"
            "price" -> "中心線放在金額和訂單數中心線。"
            "trip" -> "框住分鐘與公里區域，例如 12 分鐘、2.2 公里。"
            "type" -> "跟隨金額模板：與金額模板上下排並連動移動。"
            "merchant" -> "完整大框代表兩行商家；上半框代表一行商家。實際識別會依圓圈與方塊距離自動套用。"
            "address" -> "完整大框代表兩行地址；上半框代表一行地址。方塊位置用來決定地址起點。"
            "merchantAddressBlock" -> "商家地址總文字區：框住最多兩行商家與兩行地址，供解析器在小框失準時兜底拆分。"
            "addressWide" -> "已停用：目前主流程不使用此區域。"
            "pickupAnchor" -> "取餐圓圈定位框：用來判斷商家/地址行數，不直接讀文字。"
            "dropoffAnchor" -> "送達方塊定位框：用來判斷地址起點與行數，不直接讀文字。"
            "deliveryAnchorSearch" -> "舊定位搜尋框已停用；新幾何定位只使用取餐圓圈搜尋框與送達方塊搜尋框。"
            "sameDropoff" -> "框住同地點配送、相同送達點等提示文字。"
            else -> "拖曳框線上下調整位置，調好後記得點「儲存」。"
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
                createSmallActionButton("測試 ${left.first}", leftColor) {
                    playTestSound(left.second)
                },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    rightMargin = dp(6)
                }
            )
            addView(
                createSmallActionButton("測試 ${right.first}", rightColor) {
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
            Toast.makeText(this, "音效播放失敗", Toast.LENGTH_SHORT).show()
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
                text = "目前版本：四檔評分、首頁統計、標籤備註/避雷標籤、摺疊式音效測試與更清爽的設定頁。"
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
            text = "我們只為一件事！公平，公平，還是他妈的公平"
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
                currentScreen == Screen.InitialSetup ||
                currentScreen == Screen.AppSettings ||
                currentScreen == Screen.SettingsDetail ||
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
        nav.addView(createNavItem("首頁", isHomeSection()) { showHome() }, navItemParams())
        nav.addView(createNavItem("規則", isRuleSection()) { showRuleSettings() }, navItemParams())
        nav.addView(createNavItem("設定", isSettingsSection()) { showAppSettings() }, navItemParams())
        return nav
    }

    private fun isHomeSection(): Boolean {
        return currentScreen == Screen.Home || currentScreen == Screen.Manual || currentScreen == Screen.History
    }

    private fun isRuleSection(): Boolean {
        return currentScreen == Screen.Rules || currentScreen == Screen.RuleDetail || currentScreen == Screen.TagManage
    }

    private fun isSettingsSection(): Boolean {
        return currentScreen == Screen.InitialSetup ||
                currentScreen == Screen.AppSettings ||
                currentScreen == Screen.SettingsDetail
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
    }

    private fun toggleMonitoring() {
        val isEnabled = MonitoringState.isEnabled(this)
        if (isEnabled) {
            MonitoringState.setEnabled(this, false)
            Toast.makeText(this, "即時監測已暫停", Toast.LENGTH_SHORT).show()
            MyAccessibilityService.refreshStatusOverlay()
            updateMonitoringUi()
            return
        }

        if (!ensureActivationForMonitoring()) return

        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "請先開啟無障礙服務", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        requestNotificationPermissionIfNeeded()
        MonitoringState.setEnabled(this, true)
        Toast.makeText(this, "即時監測已開啟，不需要畫面分享授權", Toast.LENGTH_LONG).show()
        MyAccessibilityService.refreshStatusOverlay()
        updateMonitoringUi()
    }

    private fun restoreMonitoringIfPossible() {
        if (!ActivationLocalStore.isLocalActive(this)) {
            stopMonitoringIfActivationExpired(showToast = false)
            return
        }
        if (MonitoringState.isEnabled(this)) {
            MyAccessibilityService.refreshStatusOverlay()
        }
    }

    private fun updateMonitoringUi() {
        ActivationLocalStore.clearActivationIfNeeded(this)
        stopMonitoringIfActivationExpired(showToast = false)
        val isEnabled = MonitoringState.isEnabled(this)
        val isServiceRunning = isEnabled &&
            isAccessibilityServiceEnabled() &&
            MyAccessibilityService.isServiceActive()
        val records = realtimeRecords()
        val todayCount = todayRecords(records).size
        val successRate = if (todayCount > 0) "100%" else "--"
        val lastTime = records.firstOrNull()?.let { formatClock(it.timestamp) } ?: "--"
        val currentMode = acceptModeTitle(RuleSettings.load(this).acceptMode)
        val statusMetrics = "目前模式：$currentMode\n今日識別：${todayCount}單\n成功率：$successRate\n最後識別：$lastTime"

        if (::statusToggleButton.isInitialized) {
            statusToggleButton.text = when {
                !ActivationLocalStore.isLocalActive(this) -> "輸入啟用碼"
                isEnabled && isServiceRunning -> "暫停監測"
                else -> "啟用監測"
            }
        }

        when {
            !ActivationLocalStore.isLocalActive(this) -> setStatus(
                title = "未啟用",
                detail = activationBlockedMessage(),
                color = COLOR_WARNING
            )
            isEnabled && isServiceRunning -> setStatus(
                title = "監測中",
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
            card.addView(createNavigationTitle("最近訂單"))
            card.addView(TextView(this).apply {
                text = "還沒有即時訂單"
                textSize = 14f
                setTextColor(COLOR_TEXT_SECONDARY)
                setPadding(0, dp(6), 0, 0)
            })
        } else {
            val recommendation = normalizedRecommendation(latest)
            card.addView(
                createNavigationTitle(
                    title = "最近訂單",
                    trailingText = "${latest.score}分 · $recommendation",
                    trailingColor = recommendationColor(recommendation)
                )
            )
            addCompactStatRow(card, "商家", latest.storeName)
            addCompactStatRow(card, "金額", formatRecordPrice(latest))
            addCompactStatRow(card, "時間", "${latest.minutes} 分鐘")
            addCompactStatRow(card, "距離", "${OrderAnalyzer.formatDistance(latest.distance)} 公里")
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
            minimumHeight = dp(174)
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
        addCompactStatRow(parent, "地址", record.storeAddress.ifBlank { "未識別" })
        addCompactStatRow(parent, "接單模式", displayAcceptMode(record.acceptMode))
        addCompactStatRow(parent, "金額", formatRecordPrice(record))
        addCompactStatRow(parent, "時間", "${record.minutes} 分鐘")
        addCompactStatRow(parent, "距離", "${OrderAnalyzer.formatDistance(record.distance)} 公里")
        addCompactStatRow(parent, "評分", "${record.score} 分")
        addCompactStatRow(parent, "等級", recommendation)
        addCompactStatRow(parent, "預計時薪", "${OrderAnalyzer.formatMoney(record.effectiveHourly)} 元/小時")
        addCompactStatRow(parent, "訂單類型", record.orderType.ifBlank { "未識別" })
        addCompactStatRow(parent, "同地點配送", if (record.isSameLocationStack) "是" else "否")
        addCompactStatRow(parent, "標籤備註", if (record.isWhitelisted) "命中" else "未命中")
        addCompactStatRow(parent, "避雷標籤", if (record.isBlacklisted) "命中" else "未命中")
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
        val acceptedCounts = records.filter { it.acceptedAt > 0L }
            .groupingBy { normalizedRecommendation(it) }
            .eachCount()
        val averageScore = if (records.isEmpty()) 0 else records.map { it.score }.average().toInt()
        addCompactStatRow(parent, "總數", formatCountWithAccepted(records.size, records.count { it.acceptedAt > 0L }))
        if (includeAverage) {
            addCompactStatRow(parent, "平均評分", if (records.isEmpty()) "--" else "$averageScore 分")
        }
        addCompactStatRow(parent, "狗都不接", formatCountWithAccepted(counts["狗都不接"] ?: 0, acceptedCounts["狗都不接"] ?: 0))
        addCompactStatRow(parent, "跪著送", formatCountWithAccepted(counts["跪著送"] ?: 0, acceptedCounts["跪著送"] ?: 0))
        addCompactStatRow(parent, "站著掙", formatCountWithAccepted(counts["站著掙"] ?: 0, acceptedCounts["站著掙"] ?: 0))
        addCompactStatRow(parent, "掙他娘的", formatCountWithAccepted(counts["掙他娘的"] ?: 0, acceptedCounts["掙他娘的"] ?: 0))
    }

    private fun addRecordSummaryCompact(parent: LinearLayout, records: List<OrderHistory.Record>) {
        val counts = records.groupingBy { normalizedRecommendation(it) }.eachCount()
        val acceptedCounts = records.filter { it.acceptedAt > 0L }
            .groupingBy { normalizedRecommendation(it) }
            .eachCount()
        addCompactStatRow(parent, "總數", formatCountWithAccepted(records.size, records.count { it.acceptedAt > 0L }))
        addLevelSummaryRow(
            parent,
            "狗都不接", counts["狗都不接"] ?: 0, acceptedCounts["狗都不接"] ?: 0,
            "跪著送", counts["跪著送"] ?: 0, acceptedCounts["跪著送"] ?: 0
        )
        addLevelSummaryRow(
            parent,
            "站著掙", counts["站著掙"] ?: 0, acceptedCounts["站著掙"] ?: 0,
            "掙他娘的", counts["掙他娘的"] ?: 0, acceptedCounts["掙他娘的"] ?: 0
        )
    }

    private fun addLevelSummaryRow(
        parent: LinearLayout,
        leftLabel: String,
        leftCount: Int,
        leftAcceptedCount: Int,
        rightLabel: String,
        rightCount: Int,
        rightAcceptedCount: Int
    ) {
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(createLevelSummaryText(formatLevelSummary(leftLabel, leftCount, leftAcceptedCount)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(createLevelSummaryText(formatLevelSummary(rightLabel, rightCount, rightAcceptedCount)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(6)
            })
        })
    }

    private fun formatCountWithAccepted(count: Int, acceptedCount: Int): String {
        return if (acceptedCount > 0) {
            "$count 單（接受 $acceptedCount 單）"
        } else {
            "$count 單"
        }
    }

    private fun formatLevelSummary(label: String, count: Int, acceptedCount: Int): String {
        return if (acceptedCount > 0) {
            "$label $count（接受$acceptedCount）"
        } else {
            "$label $count"
        }
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
                text = if (expanded) "收起" else "展開"
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
        return recommendationForRecord(record)
    }

    private fun realtimeRecords(): List<OrderHistory.Record> {
        return OrderHistory.load(this).filter { it.source == "即時" || it.source == "实时" }
    }

    private fun screenshotRecords(): List<OrderHistory.Record> {
        return OrderHistory.load(this).filter { it.source == "截圖" || it.source == "截图" }
    }

    private fun recommendationForRecord(record: OrderHistory.Record): String {
        val rule = RuleSettings.load(this).normal
        return OrderAnalyzer.recommendationForScore(
            score = record.score,
            scoreBase = rule.scoreBase,
            money = record.price,
            fatOrderMinAmount = rule.fatOrderMinAmount
        )
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
        setAnalyzing(true, "正在識別截圖", "OCR 正在讀取訂單內容，完成後會顯示分析結果。")

        val bitmap = try {
            loadBitmap(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            setAnalyzing(false)
            Toast.makeText(this, "圖片讀取失敗", Toast.LENGTH_SHORT).show()
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
                        merchantAddressBlockText = regionText.merchantAddressBlockText,
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
                            appendLine("skipResultReason=${gate.skipResultReason}")
                            appendLine(buildMissingOrderFieldMessage(order, regionText.hasAnchoredCard, gate.skipResultReason))
                            appendLine()
                            appendLine("區域 OCR / 定位診斷：")
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
                    OrderHistory.add(this, analysis, "截圖")
                    showFloatingAnalysisResult(analysis)
                }
            }
        }
    }

    private fun buildMissingOrderFieldMessage(
        order: OrderData?,
        hasAnchoredCard: Boolean,
        skipResultReason: String
    ): String {
        val missingItems = mutableListOf<String>()
        if (!hasAnchoredCard || skipResultReason == "NO_ORDER_CARD") {
            missingItems.add("訂單卡片定位")
        }
        if (order == null) {
            if (hasAnchoredCard) {
                missingItems.add("金額")
                missingItems.add("分鐘")
                missingItems.add("公里")
            }
        } else {
            if (order.price <= 0 || order.priceStatus != "OK") missingItems.add("金額")
            if (order.minutes <= 0 || order.tripStatus != "OK") missingItems.add("分鐘")
            if (order.distance <= 0.0 || order.tripStatus != "OK") missingItems.add("公里")
            if (order.storeName.isBlank() || order.merchantStatus != "OK") missingItems.add("商家")
            if (order.address.isBlank() || order.addressStatus != "OK") missingItems.add("地址")
            if (order.typeStatus != "OK") missingItems.add("訂單類型")
        }
        return if (missingItems.isEmpty()) {
            "缺少：未知欄位（$skipResultReason）"
        } else {
            "缺少：${missingItems.distinct().joinToString("、")}"
        }
    }

    private fun analyzeCalibrationImage(uri: Uri) {
        setAnalyzing(true, "正在產生 OCR 框圖", "完成後會儲存到手動調試目錄。")

        val bitmap = try {
            loadBitmap(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            setAnalyzing(false)
            Toast.makeText(this, "圖片讀取失敗", Toast.LENGTH_SHORT).show()
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
                        merchantAddressBlockText = regionText.merchantAddressBlockText,
                        addressText = regionText.addressText,
                        addressWideText = regionText.addressWideText,
                        addressLowerText = regionText.addressLowerText
                    )
                ) else null
                val savedPath = ManualOcrDebugStore.save(this, bitmap, regionText, order, "calibration")
                val calibrationImagePath = saveLastCalibrationBitmap(bitmap)
                if (AppSettings.isDebugSamplesEnabled(this)) {
                    calibrationBitmap = null
                    calibrationSavedPath = calibrationImagePath
                } else {
                    calibrationBitmap = bitmap
                    calibrationSavedPath = "問題診斷模式關閉，未儲存圖片"
                }
                setAnalyzing(false)
                val message = if (!AppSettings.isDebugSamplesEnabled(this)) {
                    "已完成 OCR 校準分析，診斷模式關閉未儲存圖片"
                } else if (savedPath.isBlank() || calibrationImagePath.isBlank()) {
                    "OCR 校準圖儲存失敗"
                } else {
                    "已儲存 OCR 校準圖"
                }
                Toast.makeText(
                    this,
                    message,
                    Toast.LENGTH_LONG
                ).show()
                showOcrCalibration(guided = calibrationGuidedMode)
            }
        }
    }

    private fun showFloatingAnalysisResult(analysis: AnalysisResult) {
        val shown = MyAccessibilityService.showFeedback(analysis)
        if (!shown) {
            Toast.makeText(this, "請先開啟無障礙服務，用統一懸浮卡片顯示結果", Toast.LENGTH_LONG).show()
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

    private fun createActivationCard(): LinearLayout {
        val card = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(TextView(this).apply {
            text = "啟用碼"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        activationStatusText = TextView(this).apply {
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(6), 0, dp(10))
        }
        card.addView(activationStatusText)

        activationCodeInput = createNumberlessInput("請輸入啟用碼").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(ActivationLocalStore.getCurrentCode(this@MainActivity))
        }
        card.addView(activationCodeInput)

        val submitButton = createButton(primary = true).apply {
            text = "啟用 / 續期"
            setOnClickListener { submitActivationCode(this) }
        }
        card.addView(submitButton)

        activationResultText = TextView(this).apply {
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            setLineSpacing(0f, 1.12f)
        }
        card.addView(activationResultText)
        return card
    }

    private fun submitActivationCode(button: Button) {
        val code = activationCodeInput.text.toString()
        button.isEnabled = false
        activationResultText.text = "正在驗證啟用碼..."
        lifecycleScope.launch {
            val result = ActivationManager.activate(this@MainActivity, code)
            button.isEnabled = true
            activationResultText.text = result.message
            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
            if (result.success) {
                updateActivationUi()
                updateMonitoringUi()
                promptAccessibilityIfNeeded()
            }
        }
    }

    private fun updateActivationUi() {
        if (!ActivationLocalStore.isActivationRequired(this) || !::activationStatusText.isInitialized) return
        val currentCode = ActivationLocalStore.getCurrentCode(this)
        activationStatusText.text = activationStatusLabel()
        if (::activationCodeInput.isInitialized && activationCodeInput.text.isNullOrBlank() && currentCode.isNotBlank()) {
            activationCodeInput.setText(currentCode)
        }
    }

    private fun activationStatusLabel(): String {
        ActivationLocalStore.clearActivationIfNeeded(this)
        val expiresAtMillis = ActivationLocalStore.getExpiresAtMillis(this)
        return when {
            ActivationLocalStore.isLocalActive(this) ->
                "已啟用，有效期至 ${ActivationManager.formatDateTime(expiresAtMillis)}"
            expiresAtMillis > 0L ->
                "已過期，請輸入新的啟用碼"
            else ->
                "未啟用"
        }
    }

    private fun ensureActivationForMonitoring(): Boolean {
        if (ActivationLocalStore.isLocalActive(this)) return true
        stopMonitoringIfActivationExpired(showToast = false)
        Toast.makeText(this, activationBlockedMessage(), Toast.LENGTH_LONG).show()
        updateActivationUi()
        if (::statusTitleText.isInitialized) {
            updateMonitoringUi()
        }
        return false
    }

    private fun stopMonitoringIfActivationExpired(showToast: Boolean) {
        if (ActivationLocalStore.isLocalActive(this)) return
        if (MonitoringState.isEnabled(this)) {
            MonitoringState.setEnabled(this, false)
            if (showToast) {
                Toast.makeText(this, activationBlockedMessage(), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun activationBlockedMessage(): String {
        val expiresAtMillis = ActivationLocalStore.getExpiresAtMillis(this)
        return if (expiresAtMillis > 0L) {
            "啟用已過期，請輸入新的啟用碼"
        } else {
            "請先輸入啟用碼"
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
            Toast.makeText(this, "請輸入有效的收入、里程和時間", Toast.LENGTH_SHORT).show()
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
            text = "訂單分析結果"
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
        addResultRow(content, "配送數量", "${analysis.deliveryCount} 單")
        addResultRow(content, "金額", formatPriceWithSubsidy(analysis))
        addResultRow(content, "時間", "${analysis.minutes} 分鐘")
        addResultRow(content, "距離", "${OrderAnalyzer.formatDistance(analysis.distance)} 公里")
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
            "${analysis.price} 元（+${rewardTotal} 元趟獎）"
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
            "${record.price} 元（+${rewardTotal} 元趟獎）"
        } else {
            "${record.price} 元"
        }
    }

    private fun recordRewardTripTotal(record: OrderHistory.Record): Int {
        if (displayAcceptMode(record.acceptMode) != "趟獎模式" || record.rewardPerTrip <= 0) {
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

        addClickableResultRow(card, "商家", analysis.storeName.ifBlank { "未識別" })
        addClickableResultRow(card, "地址", analysis.storeAddress.ifBlank { "未識別" })
        parent.addView(card)
    }

    private fun addClickableResultRow(parent: LinearLayout, label: String, value: String) {
        addResultRow(parent, label, value)
        parent.getChildAt(parent.childCount - 1)?.setOnClickListener {
            if (value.isNotBlank() && value != "未識別") {
                showListActionChoice(value)
            }
        }
    }

    private fun showListActionChoice(keyword: String) {
        val cleanKeyword = keyword.trim()
        if (cleanKeyword.length < 2 || cleanKeyword == "未識別") return

        AlertDialog.Builder(this)
            .setTitle("新增標籤")
            .setMessage(cleanKeyword)
            .setPositiveButton("新增標籤備註") { _, _ ->
                showQuickAddListEntryDialog(cleanKeyword, isWhitelist = true)
            }
            .setNegativeButton("新增避雷標籤") { _, _ ->
                showQuickAddListEntryDialog(cleanKeyword, isWhitelist = false)
            }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun showQuickAddListEntryDialog(keyword: String, isWhitelist: Boolean) {
        val noteInput = createNumberlessInput(
            if (isWhitelist) "備註：位置、出餐速度等" else "原因：不好取/不好送/難停車等"
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
            .setTitle(if (isWhitelist) "新增標籤備註" else "新增避雷標籤")
            .setView(content)
            .setPositiveButton("儲存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val note = noteInput.text.toString().trim()
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
            else -> keyword.ifBlank { "已命中標籤規則" }
        }
        if (isBlacklisted) {
            addColoredResultRow(parent, "命中避雷標籤", hitTarget, Color.rgb(254, 226, 226), COLOR_DANGER)
        } else {
            addColoredResultRow(parent, "命中標籤備註", hitTarget, Color.rgb(220, 252, 231), COLOR_SUCCESS)
        }
        if (note.isNotBlank()) addResultRow(parent, "備註", twoLine(note))
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
            text = "未識別到完整訂單"
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
            createSmallActionButton("加標籤備註", COLOR_SUCCESS) {
                showAddListEntryDialog(analysis, isWhitelist = true)
            },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                rightMargin = dp(6)
            }
        )
        row.addView(
            createSmallActionButton("加避雷標籤", COLOR_DANGER) {
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
        val keywordInput = createNumberlessInput("商家名稱或地址關鍵词").apply {
            setText(keyword)
            setSingleLine(false)
            minLines = if (keyword.contains('\n')) 2 else 1
        }
        val noteInput = createNumberlessInput(
            if (isWhitelist) "備註：位置、出餐速度等" else "原因：不好取/不好送/難停車等"
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
            .setTitle(if (isWhitelist) "新增標籤備註" else "新增避雷標籤")
            .setView(content)
            .setPositiveButton("儲存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val keyword = keywordInput.text.toString().trim()
                val note = noteInput.text.toString().trim()
                if (keyword.length < 2) {
                    keywordInput.error = "至少兩個字"
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

    private fun promptAccessibilityIfNeeded() {
        if (hasPromptedAccessibility || isAccessibilityServiceEnabled()) return

        hasPromptedAccessibility = true
        AlertDialog.Builder(this)
            .setTitle("需要開啟無障礙服務")
            .setMessage("功德拒絕器 需要無障礙服務來監聽 目標平台 的訂單畫面變化。開啟後，即時監測才會自動觸發 OCR。")
            .setPositiveButton("去開啟") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("稍後", null)
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
        private const val INITIAL_SETUP_PREFS = "gongde_refuser_initial_setup"
        private const val KEY_INITIAL_SETUP_DISMISSED = "initial_setup_dismissed"
        private val OCR_CALIBRATION_GUIDE_ORDER = listOf(
            "closeButton",
            "price",
            "trip",
            "merchant",
            "address",
            "merchantAddressBlock",
            "sameDropoff"
        )
        private val COLOR_BACKGROUND = Color.rgb(246, 248, 250)
        private val COLOR_SETTINGS_BACKGROUND = Color.rgb(245, 246, 248)
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
