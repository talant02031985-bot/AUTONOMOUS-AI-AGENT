package kg.autonomous.agent

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Page {
        HOME,
        TASKS,
        MEMORY,
        HISTORY,
        DIAGNOSTICS,
        SETTINGS
    }

    private lateinit var contentContainer: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private var homeStateTitle:
        TextView? = null
    private lateinit var cancelCommandButton: Button
    private lateinit var stopButton: Button

    private lateinit var textModeButton: TextView
    private lateinit var textPanel: LinearLayout
    private lateinit var textInput: EditText
    private lateinit var textAnswer: TextView
    private lateinit var answerCard: LinearLayout

    private val navButtons =
        mutableMapOf<Page, TextView>()

    private var currentPage =
        Page.HOME

    private var receiverRegistered =
        false

    private var textModeVisible =
        false

    private val memoryStore by lazy {
        AyanaMemoryStore(
            applicationContext
        )
    }

    private val taskStore by lazy {
        AyanaTaskStore(
            applicationContext
        )
    }

    private val taskScheduler by lazy {
        AyanaTaskScheduler(
            applicationContext
        )
    }

    private val ayanaPreferences by lazy {
        AyanaPreferences(
            applicationContext
        )
    }

    private val commandHistoryStore by lazy {
        AyanaCommandHistoryStore(
            applicationContext
        )
    }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val microphoneGranted =
                permissions[
                    Manifest.permission.RECORD_AUDIO
                ] == true ||
                    checkSelfPermissionCompat(
                        Manifest.permission.RECORD_AUDIO
                    )

            if (microphoneGranted) {
                startAyanaService()
            } else {
                setStatus(
                    "Нужен доступ к микрофону",
                    AyanaVoiceService.STATE_ERROR
                )
            }

            renderCurrentPage()
        }

    private val statusReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                val text =
                    intent
                        ?.getStringExtra(
                            AyanaVoiceService.EXTRA_STATUS_TEXT
                        )
                        ?: return

                val state =
                    intent.getStringExtra(
                        AyanaVoiceService.EXTRA_STATUS_STATE
                    )
                        ?: AyanaVoiceService.STATE_LISTENING

                if (
                    state ==
                    AyanaVoiceService.STATE_TEXT
                ) {
                    showTextAnswer(
                        text
                    )
                }

                setStatus(
                    text,
                    state
                )

                if (
                    currentPage ==
                    Page.HISTORY &&
                    state in setOf(
                        AyanaVoiceService.STATE_SUCCESS,
                        AyanaVoiceService.STATE_ERROR,
                        AyanaVoiceService.STATE_TEXT,
                        AyanaVoiceService.STATE_SPEAKING,
                        AyanaVoiceService.STATE_CANCELLED,
                        AyanaVoiceService.STATE_STOPPED
                    )
                ) {
                    renderHistory()
                }
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )

        window.statusBarColor =
            Color.parseColor(
                "#060910"
            )

        window.navigationBarColor =
            Color.parseColor(
                "#060910"
            )

        window.setSoftInputMode(
            WindowManager.LayoutParams
                .SOFT_INPUT_ADJUST_RESIZE
        )

        // UI v5.3: exactly ONE global floating Orb is enabled.
        // It is owned by AyanaVoiceService/AyanaMiniOrbController, not by this Activity,
        // so it remains visible above other apps and the launcher.
        ayanaPreferences.miniOrbEnabled = true

        buildAyanaInterface()

        requestNeededPermissionsAndStart()
    }

    override fun onStart() {
        super.onStart()

        if (!receiverRegistered) {

            val filter =
                IntentFilter(
                    AyanaVoiceService.ACTION_STATUS
                )

            if (
                Build.VERSION.SDK_INT >= 33
            ) {

                registerReceiver(
                    statusReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )

            } else {

                @Suppress("DEPRECATION")
                registerReceiver(
                    statusReceiver,
                    filter
                )
            }

            receiverRegistered =
                true
        }

        if (
            AyanaVoiceService.isRunning
        ) {

            setStatus(
                AyanaVoiceService.currentStatusText,
                AyanaVoiceService.currentStatusState
            )

        } else {

            setStatus(
                "AYANA остановлена",
                AyanaVoiceService.STATE_STOPPED
            )
        }

        renderCurrentPage()
    }

    override fun onResume() {
        super.onResume()

        ayanaPreferences.miniOrbEnabled = true

        // The VoiceService is the only lifecycle owner of the global Orb.
        // Resuming the Activity must never create/refresh an overlay instance.
        renderCurrentPage()
    }

    override fun onStop() {

        if (
            receiverRegistered
        ) {

            try {

                unregisterReceiver(
                    statusReceiver
                )

            } catch (_: Exception) {
            }

            receiverRegistered =
                false
        }

        super.onStop()
    }

    private fun buildAyanaInterface() {

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#020409"))
            }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                )
            val ime =
                insets.getInsets(
                    WindowInsetsCompat.Type.ime()
                )

            view.setPadding(
                bars.left + dp(16),
                bars.top + dp(10),
                bars.right + dp(16),
                maxOf(
                    bars.bottom + dp(12),
                    ime.bottom + dp(8)
                )
            )
            insets
        }

        root.addView(buildTopBar())

        val body =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    ).apply {
                        topMargin = dp(10)
                    }
            }

        body.addView(buildSidebar())

        val rightColumn =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f
                    ).apply {
                        marginStart = dp(12)
                    }
            }

        buildTextPanel()

        rightColumn.addView(
            textPanel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )

        contentScroll =
            ScrollView(this).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
            }

        contentContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, dp(2), 0)
            }

        contentScroll.addView(contentContainer)
        rightColumn.addView(contentScroll)
        body.addView(rightColumn)
        root.addView(body)

        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        renderCurrentPage()
    }


    private fun buildTopBar(): View {

        val bar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dp(16),
                    dp(9),
                    dp(10),
                    dp(9)
                )
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(
                            Color.parseColor("#070C14"),
                            Color.parseColor("#0A101B"),
                            Color.parseColor("#0C0E18")
                        )
                    ).apply {
                        cornerRadius = dp(18).toFloat()
                        setStroke(
                            dp(1),
                            Color.parseColor("#202A3C")
                        )
                    }
            }

        val mark =
            TextView(this).apply {
                text = "A"
                textSize = 21f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.parseColor("#4328A8"),
                            Color.parseColor("#2458C7"),
                            Color.parseColor("#087D9B")
                        )
                    ).apply {
                        cornerRadius = dp(13).toFloat()
                        setStroke(
                            dp(1),
                            Color.parseColor("#5DB9E8")
                        )
                    }
            }

        bar.addView(
            mark,
            LinearLayout.LayoutParams(
                dp(44),
                dp(44)
            ).apply {
                marginEnd = dp(13)
            }
        )

        val brand =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
            }

        brand.addView(
            TextView(this).apply {
                text = "AYANA AI"
                textSize = 23f
                setTextColor(Color.WHITE)
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
                letterSpacing = 0.01f
            }
        )

        brand.addView(
            TextView(this).apply {
                text = "Персональный ИИ-агент  •  Agent Core"
                textSize = 15f
                setTextColor(
                    Color.parseColor("#8290A5")
                )
                setPadding(0, dp(1), 0, 0)
            }
        )

        bar.addView(brand)

        textModeButton =
            topIconButton("⌨").apply {
                contentDescription = "Текстовый режим"
                setOnClickListener {
                    toggleTextMode()
                }
            }

        bar.addView(
            textModeButton,
            LinearLayout.LayoutParams(
                dp(46),
                dp(46)
            ).apply {
                marginStart = dp(8)
            }
        )

        val settingsButton =
            topIconButton("⚙").apply {
                contentDescription = "Настройки"
                setOnClickListener {
                    switchPage(Page.SETTINGS)
                }
            }

        bar.addView(
            settingsButton,
            LinearLayout.LayoutParams(
                dp(46),
                dp(46)
            ).apply {
                marginStart = dp(7)
            }
        )

        return bar
    }


    private fun buildSidebar(): View {

        val side =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dp(8),
                    dp(12),
                    dp(8),
                    dp(10)
                )
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.parseColor("#070C14"),
                            Color.parseColor("#09111D"),
                            Color.parseColor("#0A0D16")
                        )
                    ).apply {
                        cornerRadius = dp(20).toFloat()
                        setStroke(
                            dp(1),
                            Color.parseColor("#202A3B")
                        )
                    }
                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(172),
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
            }

        side.addView(
            TextView(this).apply {
                text = "AYANA"
                textSize = 14f
                setTextColor(
                    Color.parseColor("#6F7F95")
                )
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
                letterSpacing = 0.09f
                setPadding(
                    dp(10),
                    dp(2),
                    0,
                    dp(10)
                )
            }
        )

        side.addView(navButton(Page.HOME, "⌂  Главная"))
        side.addView(navButton(Page.TASKS, "◷  Задачи"))
        side.addView(navButton(Page.MEMORY, "◇  Память"))
        side.addView(navButton(Page.HISTORY, "≡  История"))
        side.addView(navButton(Page.DIAGNOSTICS, "⌁  Система"))
        side.addView(navButton(Page.SETTINGS, "⚙  Настройки"))

        side.addView(
            Space(this),
            LinearLayout.LayoutParams(
                1,
                0,
                1f
            )
        )

        val serviceLine =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dp(10),
                    dp(9),
                    dp(10),
                    dp(9)
                )
                background =
                    softDrawable(
                        "#09131C",
                        "#1D384D",
                        14
                    )
            }

        serviceLine.addView(
            View(this).apply {
                background =
                    circleDrawable(
                        if (AyanaVoiceService.isRunning) {
                            Color.parseColor("#22C55E")
                        } else {
                            Color.parseColor("#64748B")
                        }
                    )
            },
            LinearLayout.LayoutParams(
                dp(8),
                dp(8)
            ).apply {
                marginEnd = dp(8)
            }
        )

        serviceLine.addView(
            TextView(this).apply {
                text = "Голосовой сервис"
                textSize = 14f
                setTextColor(
                    Color.parseColor("#AAB7C8")
                )
            }
        )

        side.addView(
            serviceLine,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(9)
            }
        )

        cancelCommandButton =
            Button(this).apply {
                text = "Стоп команды"
                textSize = 14f
                isAllCaps = false
                setTextColor(
                    Color.parseColor("#FCD34D")
                )
                background =
                    softDrawable(
                        "#1C1609",
                        "#6B4D18",
                        14
                    )
                isEnabled =
                    isCommandBusyState(
                        AyanaVoiceService.currentStatusState
                    )
                alpha =
                    if (
                        isEnabled
                    ) {
                        1.0f
                    } else {
                        0.45f
                    }
                setOnClickListener {
                    cancelCurrentCommand()
                }
            }

        side.addView(
            cancelCommandButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {
                bottomMargin =
                    dp(7)
            }
        )

        stopButton =
            Button(this).apply {
                text = "Остановить AYANA"
                textSize = 14.5f
                isAllCaps = false
                setTextColor(
                    Color.parseColor("#F2A7AE")
                )
                background =
                    softDrawable(
                        "#1B0E12",
                        "#56303A",
                        14
                    )
                isEnabled =
                    AyanaVoiceService.isRunning
                alpha =
                    if (
                        isEnabled
                    ) {
                        1.0f
                    } else {
                        0.45f
                    }
                setOnClickListener {
                    stopAyanaService()
                }
            }

        side.addView(
            stopButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        return side
    }


    private fun navButton(
        page: Page,
        label: String
    ): TextView {

        return TextView(this).apply {

            text =
                label

            textSize =
                    16.5f

            gravity =
                Gravity.CENTER_VERTICAL

            setTextColor(
                Color.parseColor(
                    "#B6C2D4"
                )
            )

            setPadding(
                dp(12),
                0,
                dp(10),
                0
            )

            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(54)
                ).apply {

                    bottomMargin =
                        dp(6)
                }

            setOnClickListener {
                switchPage(
                    page
                )
            }

            navButtons[
                page
            ] =
                this
        }
    }

    private fun switchPage(
        page: Page
    ) {

        currentPage =
            page

        renderCurrentPage()
    }

    private fun renderCurrentPage() {

        if (!::contentContainer.isInitialized) {
            return
        }

        updateNavigation()

        if (::contentScroll.isInitialized) {
            contentScroll.isVerticalScrollBarEnabled =
                currentPage != Page.HOME

            if (currentPage == Page.HOME) {
                contentScroll.scrollTo(0, 0)
            }
        }

        when (currentPage) {
            Page.HOME -> renderHome()
            Page.TASKS -> renderTasks()
            Page.MEMORY -> renderMemory()
            Page.HISTORY -> renderHistory()
            Page.DIAGNOSTICS -> renderDiagnostics()
            Page.SETTINGS -> renderSettings()
        }
    }


    private fun updateNavigation() {

        navButtons
            .forEach {
                entry ->

                val selected =
                    entry.key ==
                        currentPage

                entry.value.background =
                    if (
                        selected
                    ) {
                        GradientDrawable(
                            GradientDrawable
                                .Orientation
                                .LEFT_RIGHT,
                            intArrayOf(
                                Color.parseColor(
                                    "#3A1C78"
                                ),
                                Color.parseColor(
                                    "#1D4773"
                                )
                            )
                        ).apply {

                            cornerRadius =
                                dp(14)
                                    .toFloat()

                            setStroke(
                                dp(1),
                                Color.parseColor(
                                    "#745CFF"
                                )
                            )
                        }
                    } else {
                        ColorDrawableCompat(
                            Color.TRANSPARENT
                        )
                    }

                entry.value
                    .setTextColor(
                        Color.parseColor(
                            if (
                                selected
                            ) {
                                "#FFFFFF"
                            } else {
                                "#9CAAC0"
                            }
                        )
                    )
            }
    }

    private fun renderHome() {

        contentContainer.removeAllViews()
        contentContainer.setPadding(0, 0, dp(2), 0)

        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dp(2),
                    0,
                    dp(2),
                    dp(10)
                )
            }

        val title =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

        title.addView(
            TextView(this).apply {
                text = "Главная"
                textSize = 28f
                setTextColor(Color.WHITE)
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }
        )

        title.addView(
            TextView(this).apply {
                text = "Текущая сессия AYANA"
                textSize = 16f
                setTextColor(
                    Color.parseColor("#77879D")
                )
                setPadding(0, dp(2), 0, 0)
            }
        )

        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        header.addView(
            statusPill(
                stateTitle(
                    AyanaVoiceService.currentStatusState
                )
            )
        )

        contentContainer.addView(header)

        val primaryRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }

        primaryRow.addView(
            aiPresenceCard(),
            LinearLayout.LayoutParams(
                0,
                dp(310),
                1.72f
            )
        )

        primaryRow.addView(
            aiSystemCard(),
            LinearLayout.LayoutParams(
                0,
                dp(310),
                0.88f
            ).apply {
                marginStart = dp(12)
            }
        )

        contentContainer.addView(primaryRow)

        contentContainer.addView(
            homeCommandBar(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(88)
            ).apply {
                topMargin = dp(12)
            }
        )
    }

    private fun aiPresenceCard(): View {

        val card =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dp(24),
                    dp(22),
                    dp(20),
                    dp(22)
                )
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.parseColor("#080D15"),
                            Color.parseColor("#0B1220"),
                            Color.parseColor("#101023")
                        )
                    ).apply {
                        cornerRadius = dp(24).toFloat()
                        setStroke(
                            dp(1),
                            Color.parseColor("#2B3850")
                        )
                    }
            }

        val copy =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }

        copy.addView(
            TextView(this).apply {
                text = "AGENT CORE"
                textSize = 15f
                setTextColor(
                    Color.parseColor("#8A7CFF")
                )
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
                letterSpacing = 0.08f
            }
        )

        val stateTitleView =
            TextView(this).apply {
                text =
                    stateTitle(
                        AyanaVoiceService.currentStatusState
                    )
                textSize = 38f
                setTextColor(Color.WHITE)
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
                setPadding(0, dp(8), 0, 0)
            }

        homeStateTitle = stateTitleView
        copy.addView(
            stateTitleView
        )

        val stateRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }

        statusDot =
            View(this).apply {
                background =
                    circleDrawable(
                        stateColor(
                            AyanaVoiceService.currentStatusState
                        )
                    )
            }

        stateRow.addView(
            statusDot,
            LinearLayout.LayoutParams(
                dp(10),
                dp(10)
            ).apply {
                marginEnd = dp(9)
            }
        )

        statusText =
            TextView(this).apply {
                text = AyanaVoiceService.currentStatusText
                textSize = 17f
                maxLines = 3
                setTextColor(
                    Color.parseColor("#D4DEEA")
                )
            }

        stateRow.addView(
            statusText,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        copy.addView(stateRow)

        copy.addView(
            TextView(this).apply {
                text = "Голос  •  Экран  •  Agent Core"
                textSize = 15f
                setTextColor(
                    Color.parseColor("#718198")
                )
                setPadding(0, dp(16), 0, 0)
            }
        )

        card.addView(
            copy,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1.0f
            )
        )

        val neural =
            AyanaPulseView(this).apply {
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.parseColor("#060B12"),
                            Color.parseColor("#09111D")
                        )
                    ).apply {
                        cornerRadius = dp(20).toFloat()
                        setStroke(
                            dp(1),
                            Color.parseColor("#1E3B54")
                        )
                    }
            }

        card.addView(
            neural,
            LinearLayout.LayoutParams(
                0,
                dp(248),
                1.12f
            ).apply {
                marginStart = dp(22)
            }
        )

        return card
    }

    private fun aiSystemCard(): View {

        val card = panel(22)
        card.setPadding(
            dp(20),
            dp(20),
            dp(20),
            dp(18)
        )

        card.addView(
            TextView(this).apply {
                text = "Система"
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }
        )

        card.addView(
            TextView(this).apply {
                text = "Готовность основных модулей"
                textSize = 15f
                setTextColor(
                    Color.parseColor("#77879D")
                )
                setPadding(0, dp(4), 0, dp(14))
            }
        )

        card.addView(
            primarySystemRow(
                "Голос",
                checkSelfPermissionCompat(
                    Manifest.permission.RECORD_AUDIO
                )
            )
        )

        card.addView(
            primarySystemRow(
                "Экран",
                isAccessibilityEnabled()
            )
        )

        card.addView(
            primarySystemRow(
                "Agent Core",
                AyanaVoiceService.isRunning
            )
        )

        val spacer = Space(this)
        card.addView(
            spacer,
            LinearLayout.LayoutParams(
                1,
                0,
                1f
            )
        )

        card.addView(
            TextView(this).apply {
                text = "Диагностика  ›"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(
                    Color.parseColor("#D4D8FF")
                )
                background =
                    softDrawable(
                        "#0D1424",
                        "#313D62",
                        14
                    )
                setOnClickListener {
                    switchPage(Page.DIAGNOSTICS)
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        )

        return card
    }

    private fun primarySystemRow(
        label: String,
        ok: Boolean
    ): View {

        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dp(2),
                    dp(9),
                    dp(2),
                    dp(9)
                )
            }

        row.addView(
            View(this).apply {
                background =
                    circleDrawable(
                        Color.parseColor(
                            if (ok) {
                                "#22C55E"
                            } else {
                                "#EF4444"
                            }
                        )
                    )
            },
            LinearLayout.LayoutParams(
                dp(9),
                dp(9)
            ).apply {
                marginEnd = dp(10)
            }
        )

        row.addView(
            TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(
                    Color.parseColor("#D5DEEA")
                )
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        row.addView(
            TextView(this).apply {
                text =
                    if (ok) {
                        "Готово"
                    } else {
                        "Внимание"
                    }
                textSize = 15f
                setTextColor(
                    Color.parseColor(
                        if (ok) {
                            "#86EFAC"
                        } else {
                            "#FCA5A5"
                        }
                    )
                )
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }
        )

        return row
    }

    private fun homeCommandBar(): View {

        val bar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dp(20),
                    dp(14),
                    dp(14),
                    dp(14)
                )
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(
                            Color.parseColor("#080D15"),
                            Color.parseColor("#0A1220"),
                            Color.parseColor("#0D0F1B")
                        )
                    ).apply {
                        cornerRadius = dp(20).toFloat()
                        setStroke(
                            dp(1),
                            Color.parseColor("#27344B")
                        )
                    }
            }

        bar.addView(
            View(this).apply {
                background =
                    circleDrawable(
                        stateColor(
                            AyanaVoiceService.currentStatusState
                        )
                    )
            },
            LinearLayout.LayoutParams(
                dp(11),
                dp(11)
            ).apply {
                marginEnd = dp(12)
            }
        )

        val textBlock =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

        textBlock.addView(
            TextView(this).apply {
                text = "Команда"
                textSize = 16f
                setTextColor(
                    Color.parseColor("#8C9BB0")
                )
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }
        )

        textBlock.addView(
            TextView(this).apply {
                text = "Скажите: «Аяна, открой YouTube»"
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(0, dp(2), 0, 0)
            }
        )

        bar.addView(
            textBlock,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        bar.addView(
            TextView(this).apply {
                text = "⌨  Текст"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(
                    Color.parseColor("#D9D5FF")
                )
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
                background =
                    softDrawable(
                        "#121426",
                        "#3B3B68",
                        14
                    )
                setOnClickListener {
                    toggleTextMode()
                }
            },
            LinearLayout.LayoutParams(
                dp(128),
                dp(48)
            )
        )

        return bar
    }


    private fun controlHeroCard():
        View {

        val card =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dp(20),
                    dp(16),
                    dp(20),
                    dp(16)
                )
                minimumHeight = dp(286)
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.parseColor("#09101A"),
                            Color.parseColor("#0D1524"),
                            Color.parseColor("#151326")
                        )
                    ).apply {
                        cornerRadius = dp(24).toFloat()
                        setStroke(
                            dp(1),
                            Color.parseColor("#33405A")
                        )
                    }
                elevation = dp(3).toFloat()
            }

        val top =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        top.addView(
            smallSectionTitle("AGENT CORE"),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        top.addView(
            statusPill(
                stateTitle(
                    AyanaVoiceService.currentStatusState
                )
            )
        )

        card.addView(top)

        val coreRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    0,
                    dp(10),
                    0,
                    0
                )
            }

        val stateColumn =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        0.92f
                    )
            }

        stateColumn.addView(
            TextView(this).apply {
                text =
                    stateTitle(
                        AyanaVoiceService.currentStatusState
                    )
                textSize = 34f
                setTextColor(Color.WHITE)
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }
        )

        val statusRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    0,
                    dp(9),
                    0,
                    0
                )
            }

        statusDot =
            View(this).apply {
                background =
                    circleDrawable(
                        stateColor(
                            AyanaVoiceService.currentStatusState
                        )
                    )
            }

        statusRow.addView(
            statusDot,
            LinearLayout.LayoutParams(
                dp(10),
                dp(10)
            ).apply {
                marginEnd = dp(9)
            }
        )

        statusText =
            TextView(this).apply {
                text = AyanaVoiceService.currentStatusText
                textSize = 17f
                maxLines = 3
                setTextColor(
                    Color.parseColor("#D7E0ED")
                )
            }

        statusRow.addView(
            statusText,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        stateColumn.addView(statusRow)

        stateColumn.addView(
            TextView(this).apply {
                text = "Голос • Экран • Agent Core"
                textSize = 14f
                setTextColor(
                    Color.parseColor("#75869F")
                )
                setPadding(
                    0,
                    dp(11),
                    0,
                    0
                )
            }
        )

        coreRow.addView(stateColumn)

        val liveView =
            AyanaPulseView(this).apply {
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.parseColor("#07101A"),
                            Color.parseColor("#0A1422")
                        )
                    ).apply {
                        cornerRadius = dp(18).toFloat()
                        setStroke(
                            dp(1),
                            Color.parseColor("#223A54")
                        )
                    }
            }

        coreRow.addView(
            liveView,
            LinearLayout.LayoutParams(
                0,
                dp(122),
                1.08f
            ).apply {
                marginStart = dp(18)
            }
        )

        card.addView(coreRow)

        val chips =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                setPadding(
                    0,
                    dp(10),
                    0,
                    0
                )
            }

        listOf(
            "ГОЛОС",
            "ЭКРАН",
            "AGENT CORE"
        ).forEachIndexed {
            index,
            label ->
            chips.addView(
                statusChip(label),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(40)
                ).apply {
                    if (index > 0) {
                        marginStart = dp(8)
                    }
                }
            )
        }

        card.addView(chips)

        return card
    }

    private fun executionCard():
        View {

        val card = panel(22)
        card.minimumHeight = dp(286)

        val head =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        head.addView(
            smallSectionTitle("ТЕКУЩАЯ СЕССИЯ"),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        head.addView(
            TextView(this).apply {
                text = "●  АКТИВНА"
                textSize = 13.5f
                setTextColor(
                    Color.parseColor("#67E8F9")
                )
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }
        )

        card.addView(head)

        card.addView(
            TextView(this).apply {
                text = AyanaVoiceService.currentStatusText
                textSize = 19f
                maxLines = 3
                setTextColor(Color.WHITE)
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
                setPadding(
                    0,
                    dp(13),
                    0,
                    dp(10)
                )
            }
        )

        val stage =
            when (AyanaVoiceService.currentStatusState) {
                AyanaVoiceService.STATE_COMMAND -> 1
                AyanaVoiceService.STATE_THINKING -> 2
                AyanaVoiceService.STATE_SPEAKING -> 4
                AyanaVoiceService.STATE_ERROR -> 4
                else -> 0
            }

        listOf(
            "Команда",
            "План",
            "Действие",
            "Проверка"
        ).forEachIndexed {
            index,
            label ->
            card.addView(
                timelineRow(
                    index + 1,
                    label,
                    index <= stage,
                    index == stage
                )
            )
        }

        val lower =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    0,
                    dp(12),
                    0,
                    0
                )
            }

        lower.addView(
            TextView(this).apply {
                text = "Анализ экрана"
                textSize = 14f
                setTextColor(
                    Color.parseColor("#8291A8")
                )
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        lower.addView(
            TextView(this).apply {
                text =
                    if (isAccessibilityEnabled()) {
                        "ГОТОВО"
                    } else {
                        "ВЫКЛ"
                    }
                textSize = 13.5f
                setTextColor(
                    Color.parseColor(
                        if (isAccessibilityEnabled()) {
                            "#86EFAC"
                        } else {
                            "#FCA5A5"
                        }
                    )
                )
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }
        )

        card.addView(lower)

        return card
    }

    private fun servicesCompactCard():
        View {

        val card = panel(22)
        card.minimumHeight = dp(286)

        val titleRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        titleRow.addView(
            smallSectionTitle("СИСТЕМА"),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        titleRow.addView(
            statusPill("Agent Core")
        )

        card.addView(titleRow)

        card.addView(
            TextView(this).apply {
                text = "Ключевые контуры устройства"
                textSize = 14f
                setTextColor(
                    Color.parseColor("#75869F")
                )
                setPadding(
                    0,
                    dp(9),
                    0,
                    dp(8)
                )
            }
        )

        listOf(
            Triple(
                "Микрофон",
                checkSelfPermissionCompat(
                    Manifest.permission.RECORD_AUDIO
                ),
                "ГОЛОС"
            ),
            Triple(
                "Спец. возможности",
                isAccessibilityEnabled(),
                "ЭКРАН"
            ),
            Triple(
                "Agent Core",
                AyanaVoiceService.isRunning,
                "ИИ"
            ),
            Triple(
                "Напоминания",
                taskScheduler.canScheduleExact(),
                "ЗАДАЧИ"
            )
        ).forEach {
            item ->
            card.addView(
                compactServiceRow(
                    item.first,
                    item.second,
                    item.third
                )
            )
        }

        card.addView(
            TextView(this).apply {
                text = "Открыть диагностику  ›"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(
                    Color.parseColor("#C5CCFF")
                )
                background =
                    softDrawable(
                        "#101529",
                        "#343D69",
                        14
                    )
                setOnClickListener {
                    switchPage(Page.DIAGNOSTICS)
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply {
                topMargin = dp(12)
            }
        )

        return card
    }

    private fun activityOverviewCard():
        View {

        val card =
            panel(
                20
            )

        card.addView(
            smallSectionTitle(
                "АКТИВНОСТЬ"
            )
        )

        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                setPadding(
                    0,
                    dp(10),
                    0,
                    0
                )
            }

        row.addView(
            compactMetric(
                taskStore.getFutureTasks().size.toString(),
                "задач"
            ),
            equalCardParams()
        )

        row.addView(
            compactMetric(
                memoryStore.count().toString(),
                "память"
            ),
            equalCardParams(
                left =
                    7
            )
        )

        row.addView(
            compactMetric(
                diagnosticsPassed().toString(),
                "сервисов"
            ),
            equalCardParams(
                left =
                    7
            )
        )

        card.addView(
            row
        )

        val shortcutRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                setPadding(
                    0,
                    dp(11),
                    0,
                    0
                )
            }

        shortcutRow.addView(
            iconAction(
                "◷",
                "Задачи"
            ) {
                switchPage(
                    Page.TASKS
                )
            },
            equalCardParams()
        )

        shortcutRow.addView(
            iconAction(
                "◇",
                "Память"
            ) {
                switchPage(
                    Page.MEMORY
                )
            },
            equalCardParams(
                left =
                    7
            )
        )

        card.addView(
            shortcutRow
        )

        return card
    }

    private fun controlFooterStrip():
        View {

        val card =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dp(16),
                    dp(11),
                    dp(16),
                    dp(11)
                )
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(
                            Color.parseColor("#080D15"),
                            Color.parseColor("#0B1220"),
                            Color.parseColor("#0E0E1A")
                        )
                    ).apply {
                        cornerRadius = dp(16).toFloat()
                        setStroke(
                            dp(1),
                            Color.parseColor("#253047")
                        )
                    }
            }

        card.addView(
            View(this).apply {
                background =
                    circleDrawable(
                        stateColor(
                            AyanaVoiceService.currentStatusState
                        )
                    )
            },
            LinearLayout.LayoutParams(
                dp(10),
                dp(10)
            ).apply {
                marginEnd = dp(10)
            }
        )

        card.addView(
            TextView(this).apply {
                text =
                    "AYANA  •  " +
                        stateTitle(
                            AyanaVoiceService.currentStatusState
                        ).uppercase(Locale.getDefault())
                textSize = 14f
                setTextColor(
                    Color.parseColor("#D7E0ED")
                )
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }
        )

        card.addView(
            TextView(this).apply {
                text = "ГОЛОС  •  ЭКРАН  •  AGENT CORE"
                textSize = 13.5f
                setTextColor(
                    Color.parseColor("#788AA3")
                )
                setPadding(
                    dp(18),
                    0,
                    0,
                    0
                )
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        card.addView(
            TextView(this).apply {
                text = "ГОТОВО"
                textSize = 13.5f
                setTextColor(
                    Color.parseColor("#86EFAC")
                )
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }
        )

        return card
    }

    private fun headerBadge(
        label: String,
        fill: String,
        stroke: String,
        textColor: String
    ): TextView {

        return TextView(this).apply {
            text =
                label
            textSize =
                    14f
            gravity =
                Gravity.CENTER
            setTextColor(
                Color.parseColor(textColor)
            )
            setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
            setPadding(
                dp(11),
                0,
                dp(11),
                0
            )
            background =
                softDrawable(
                    fill,
                    stroke,
                    14
                )
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(40)
                )
        }
    }

    private fun statusPill(
        label: String
    ): TextView {

        return TextView(this).apply {
            text =
                label.uppercase(
                    Locale.getDefault()
                )
            textSize =
                    13.5f
            gravity =
                Gravity.CENTER
            setTextColor(
                Color.parseColor("#D8D3FF")
            )
            setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
            setPadding(
                dp(9),
                0,
                dp(9),
                0
            )
            background =
                softDrawable(
                    "#17132B",
                    "#4C3B79",
                    13
                )
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(34)
                )
        }
    }

    private fun waveformRail(): View {

        val rail =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.CENTER
            }

        val heights =
            listOf(
                6,
                10,
                15,
                22,
                12,
                27,
                17,
                30,
                20,
                12,
                24,
                16,
                9,
                6
            )

        heights.forEachIndexed {
            index,
            h ->
            rail.addView(
                View(this).apply {
                    background =
                        GradientDrawable().apply {
                            cornerRadius =
                                dp(2).toFloat()
                            setColor(
                                Color.parseColor(
                                    if (
                                        index % 3 == 0
                                    ) {
                                        "#8B5CF6"
                                    } else {
                                        "#38BDF8"
                                    }
                                )
                            )
                        }
                    alpha =
                        if (
                            AyanaVoiceService.currentStatusState ==
                            AyanaVoiceService.STATE_LISTENING ||
                            AyanaVoiceService.currentStatusState ==
                            AyanaVoiceService.STATE_COMMAND
                        ) {
                            0.95f
                        } else {
                            0.42f
                        }
                },
                LinearLayout.LayoutParams(
                    dp(4),
                    dp(h)
                ).apply {
                    if (
                        index > 0
                    ) {
                        marginStart =
                            dp(4)
                    }
                }
            )
        }

        return rail
    }

    private fun timelineRow(
        number: Int,
        label: String,
        done: Boolean,
        active: Boolean
    ): View {

        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.CENTER_VERTICAL
                setPadding(
                    0,
                    dp(6),
                    0,
                    dp(6)
                )
            }

        row.addView(
            TextView(this).apply {
                text =
                    number.toString()
                textSize =
                    14f
                gravity =
                    Gravity.CENTER
                setTextColor(
                    Color.parseColor(
                        if (
                            done
                        ) {
                            "#FFFFFF"
                        } else {
                            "#66758B"
                        }
                    )
                )
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
                background =
                    GradientDrawable().apply {
                        shape =
                            GradientDrawable.OVAL
                        setColor(
                            Color.parseColor(
                                if (
                                    active
                                ) {
                                    "#7C3AED"
                                } else if (
                                    done
                                ) {
                                    "#24386D"
                                } else {
                                    "#111827"
                                }
                            )
                        )
                        setStroke(
                            dp(1),
                            Color.parseColor(
                                if (
                                    active
                                ) {
                                    "#A78BFA"
                                } else {
                                    "#2D3A51"
                                }
                            )
                        )
                    }
            },
            LinearLayout.LayoutParams(
                dp(26),
                dp(26)
            ).apply {
                marginEnd =
                    dp(10)
            }
        )

        row.addView(
            TextView(this).apply {
                text =
                    label
                textSize =
                    14.5f
                setTextColor(
                    Color.parseColor(
                        if (
                            done
                        ) {
                            "#DCE6F5"
                        } else {
                            "#68778D"
                        }
                    )
                )
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        row.addView(
            TextView(this).apply {
                text =
                    if (
                        active
                    ) {
                        "СЕЙЧАС"
                    } else if (
                        done
                    ) {
                        "✓"
                    } else {
                        "—"
                    }
                textSize =
                    13.5f
                setTextColor(
                    Color.parseColor(
                        if (
                            active
                        ) {
                            "#C4B5FD"
                        } else if (
                            done
                        ) {
                            "#67E8F9"
                        } else {
                            "#475569"
                        }
                    )
                )
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }
        )

        return row
    }

    private fun commandConsoleCard(): View {

        val card =
            panel(
                20
            )

        val title =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.CENTER_VERTICAL
            }

        title.addView(
            smallSectionTitle(
                "КОМАНДНАЯ ПАНЕЛЬ"
            ),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        title.addView(
            TextView(this).apply {
                text =
                    "⌨  ТЕКСТ"
                textSize =
                    13.5f
                setTextColor(
                    Color.parseColor("#BDB4FF")
                )
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
                setOnClickListener {
                    if (
                        !textModeVisible
                    ) {
                        toggleTextMode()
                    }
                }
            }
        )

        card.addView(
            title
        )

        val prompt =
            TextView(this).apply {
                text =
                    "›  Скажите «Аяна» и дайте задачу"
                textSize =
                    15f
                setTextColor(
                    Color.parseColor("#D4DEEC")
                )
                setPadding(
                    dp(12),
                    dp(10),
                    dp(12),
                    dp(10)
                )
                background =
                    softDrawable(
                        "#070D17",
                        "#27344D",
                        13
                    )
            }

        card.addView(
            prompt,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin =
                    dp(10)
            }
        )

        card.addView(
            TextView(this).apply {
                text = "Готова к голосовой или текстовой команде"
                textSize = 15.5f
                setTextColor(Color.parseColor("#8291A8"))
                setPadding(dp(3), dp(9), 0, 0)
            }
        )

        return card
    }

    private fun iconAction(
        symbol: String,
        label: String,
        action: () -> Unit
    ): View {

        val item =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                gravity =
                    Gravity.CENTER
                setPadding(
                    dp(8),
                    dp(8),
                    dp(8),
                    dp(8)
                )
                background =
                    softDrawable(
                        "#0B1322",
                        "#273A55",
                        14
                    )
                setOnClickListener {
                    action()
                }
            }

        item.addView(
            TextView(this).apply {
                text =
                    symbol
                textSize =
                    18f
                gravity =
                    Gravity.CENTER
                setTextColor(
                    Color.parseColor("#9F8DFF")
                )
            }
        )

        item.addView(
            TextView(this).apply {
                text =
                    label
                textSize =
                    14f
                gravity =
                    Gravity.CENTER
                setTextColor(
                    Color.parseColor("#B9C5D7")
                )
                setPadding(
                    0,
                    dp(3),
                    0,
                    0
                )
            }
        )

        return item
    }

    private fun stateMatchesLabel(
        label: String
    ): Boolean {

        return when (
            label
        ) {
            "СЛУШАЮ" ->
                AyanaVoiceService.currentStatusState ==
                    AyanaVoiceService.STATE_LISTENING ||
                    AyanaVoiceService.currentStatusState ==
                    AyanaVoiceService.STATE_COMMAND
            "ДУМАЮ" ->
                AyanaVoiceService.currentStatusState ==
                    AyanaVoiceService.STATE_THINKING
            "ВЫПОЛНЯЮ" ->
                AyanaVoiceService.currentStatusState ==
                    AyanaVoiceService.STATE_THINKING
            "ГОВОРЮ" ->
                AyanaVoiceService.currentStatusState ==
                    AyanaVoiceService.STATE_SPEAKING
            "ОШИБКА" ->
                AyanaVoiceService.currentStatusState ==
                    AyanaVoiceService.STATE_ERROR
            else ->
                false
        }
    }

    private fun stateRailItem(
        label: String,
        color: String,
        active: Boolean
    ): View {

        val item =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.CENTER_VERTICAL
                setPadding(
                    dp(8),
                    0,
                    dp(8),
                    0
                )
                background =
                    if (
                        active
                    ) {
                        softDrawable(
                            "#17132A",
                            "#4C447A",
                            12
                        )
                    } else {
                        ColorDrawableCompat(
                            Color.TRANSPARENT
                        )
                    }
            }

        item.addView(
            View(this).apply {
                background =
                    circleDrawable(
                        Color.parseColor(color)
                    )
            },
            LinearLayout.LayoutParams(
                dp(7),
                dp(7)
            ).apply {
                marginEnd =
                    dp(6)
            }
        )

        item.addView(
            TextView(this).apply {
                text =
                    label
                textSize =
                    13.5f
                setTextColor(
                    Color.parseColor(
                        if (
                            active
                        ) {
                            "#EAE7FF"
                        } else {
                            "#64748B"
                        }
                    )
                )
                setTypeface(
                    Typeface.DEFAULT,
                    if (
                        active
                    ) {
                        Typeface.BOLD
                    } else {
                        Typeface.NORMAL
                    }
                )
            }
        )

        return item
    }

    private fun statusChip(
        label: String
    ): View {

        return TextView(this).apply {

            text =
                "✓  $label"

            textSize =
                    14.5f

            gravity =
                Gravity.CENTER

            setTextColor(
                Color.parseColor("#BEE9FF")
            )

            setPadding(
                dp(10),
                0,
                dp(10),
                0
            )

            background =
                softDrawable(
                    "#0C1A2A",
                    "#244F76",
                    14
                )

            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(34)
                )
        }
    }

    private fun agentStepRow(
        label: String,
        active: Boolean
    ): View {

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    0,
                    dp(5),
                    0,
                    dp(5)
                )
            }

        row.addView(
            View(this).apply {

                background =
                    circleDrawable(
                        Color.parseColor(
                            if (
                                active
                            ) {
                                "#8B5CF6"
                            } else {
                                "#334155"
                            }
                        )
                    )
            },
            LinearLayout.LayoutParams(
                dp(8),
                dp(8)
            ).apply {
                marginEnd =
                    dp(8)
            }
        )

        row.addView(
            TextView(this).apply {

                text =
                    label

                textSize =
                    14.5f

                setTextColor(
                    Color.parseColor(
                        if (
                            active
                        ) {
                            "#E9E5FF"
                        } else {
                            "#8795AA"
                        }
                    )
                )
            }
        )

        return row
    }

    private fun compactServiceRow(
        name: String,
        ok: Boolean,
        caption: String
    ): View {

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    0,
                    dp(6),
                    0,
                    dp(6)
                )
            }

        row.addView(
            TextView(this).apply {

                text =
                    name

                textSize =
                    14.5f

                setTextColor(
                    Color.parseColor("#CED8E7")
                )
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        row.addView(
            TextView(this).apply {

                text =
                    caption

                textSize =
                    14f

                setTextColor(
                    Color.parseColor("#66758B")
                )
            }
        )

        row.addView(
            View(this).apply {

                background =
                    circleDrawable(
                        Color.parseColor(
                            if (
                                ok
                            ) {
                                "#22C55E"
                            } else {
                                "#EF4444"
                            }
                        )
                    )
            },
            LinearLayout.LayoutParams(
                dp(9),
                dp(9)
            ).apply {
                marginStart =
                    dp(10)
            }
        )

        return row
    }

    private fun compactMetric(
        number: String,
        caption: String
    ): View {

        val box =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    dp(8),
                    dp(8),
                    dp(8),
                    dp(8)
                )

                background =
                    softDrawable(
                        "#0B1220",
                        "#26334A",
                        14
                    )
            }

        box.addView(
            TextView(this).apply {

                text =
                    number

                textSize =
                    22f

                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )

                setTextColor(
                    Color.WHITE
                )
            }
        )

        box.addView(
            TextView(this).apply {

                text =
                    caption

                textSize =
                    14f

                setTextColor(
                    Color.parseColor("#75859D")
                )
            }
        )

        return box
    }

    private fun renderTasks() {

        contentContainer
            .removeAllViews()

        contentContainer.addView(
            pageTitle(
                "Задачи",
                "Напоминания и повторяющиеся задачи"
            )
        )

        val tasks =
            taskStore
                .getFutureTasks()

        if (
            tasks.isEmpty()
        ) {

            contentContainer.addView(
                emptyCard(
                    "Активных напоминаний нет",
                    "Скажите: «Аяна, завтра в 9 напомни позвонить»."
                ),
                sectionParams()
            )

            return
        }

        tasks.forEach {
            task ->

            val card =
                panel(
                    20
                )

            val recurrence =
                when (
                    task.recurrence
                ) {

                    AyanaTaskStore
                        .RECURRENCE_DAILY ->
                        "Каждый день"

                    AyanaTaskStore
                        .RECURRENCE_WEEKLY ->
                        "Каждую неделю"

                    AyanaTaskStore
                        .RECURRENCE_MONTHLY ->
                        "Каждый месяц"

                    else ->
                        "Однократно"
                }

            card.addView(
                TextView(this).apply {

                    text =
                        task.title

                    textSize =
                    18f

                    setTypeface(
                        Typeface.DEFAULT,
                        Typeface.BOLD
                    )

                    setTextColor(
                        Color.WHITE
                    )
                }
            )

            card.addView(
                TextView(this).apply {

                    text =
                        formatTaskTime(
                            task.triggerAtMillis
                        ) +
                            "  •  " +
                            recurrence

                    textSize =
                    15f

                    setTextColor(
                        Color.parseColor(
                            "#A78BFA"
                        )
                    )

                    setPadding(
                        0,
                        dp(7),
                        0,
                        0
                    )
                }
            )

            card.addView(
                TextView(this).apply {

                    text =
                        task.message

                    textSize =
                    15.5f

                    setTextColor(
                        Color.parseColor(
                            "#A8B5C7"
                        )
                    )

                    setPadding(
                        0,
                        dp(7),
                        0,
                        0
                    )
                }
            )

            contentContainer.addView(
                card,
                sectionParams(
                    top =
                        8
                )
            )
        }
    }

    private fun renderMemory() {

        contentContainer
            .removeAllViews()

        contentContainer.addView(
            pageTitle(
                "Память",
                "Что AYANA хранит для будущих разговоров"
            )
        )

        val memories =
            memoryStore
                .getAll(
                    100
                )

        if (
            memories.isEmpty()
        ) {

            contentContainer.addView(
                emptyCard(
                    "Память пока пуста",
                    "Скажите: «Аяна, запомни…»"
                ),
                sectionParams()
            )

            return
        }

        val grouped =
            memories
                .groupBy {
                    it.category
                }

        val order =
            listOf(
                "preference",
                "person",
                "project",
                "task",
                "place",
                "general"
            )

        order.forEach {
            category ->

            val items =
                grouped[
                    category
                ]
                    ?: return@forEach

            contentContainer.addView(
                smallSectionTitle(
                    memoryCategoryTitle(
                        category
                    )
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {

                    topMargin =
                        dp(12)

                    bottomMargin =
                        dp(5)
                }
            )

            items.forEach {
                memory ->

                val card =
                    panel(
                        18
                    )

                card.addView(
                    TextView(this).apply {

                        text =
                            memory.text

                        textSize =
                    16f

                        setTextColor(
                            Color.parseColor(
                                "#D7E0ED"
                            )
                        )
                    }
                )

                contentContainer.addView(
                    card,
                    sectionParams(
                        top =
                            6
                    )
                )
            }
        }
    }

    private fun renderHistory() {

        contentContainer
            .removeAllViews()

        contentContainer.addView(
            pageTitle(
                "История команд",
                "Что AYANA услышала → что решила → что выполнила → результат или ошибка"
            )
        )

        val tools =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        tools.addView(
            TextView(this).apply {
                text = "Копировать диагностику"
                textSize = 14.5f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#E0E7FF"))
                background = softDrawable("#11182A", "#38436D", 14)
                setOnClickListener {
                    val clipboard =
                        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "AYANA diagnostics",
                            commandHistoryStore.exportRecent(30)
                        )
                    )
                    Toast.makeText(
                        this@MainActivity,
                        "Диагностика AYANA скопирована",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            LinearLayout.LayoutParams(
                0,
                dp(42),
                1f
            )
        )

        tools.addView(
            TextView(this).apply {
                text = "Очистить историю"
                textSize = 14.5f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#FCA5A5"))
                background = softDrawable("#1B1017", "#5B2838", 14)
                setOnClickListener {
                    commandHistoryStore.clear()
                    renderHistory()
                }
            },
            LinearLayout.LayoutParams(
                0,
                dp(42),
                0.72f
            ).apply {
                marginStart = dp(10)
            }
        )

        contentContainer.addView(
            tools,
            sectionParams(top = 8)
        )

        val records =
            commandHistoryStore.recent(40)

        if (records.isEmpty()) {
            val empty = panel(20)
            empty.addView(
                TextView(this).apply {
                    text = "История пока пуста. После следующей голосовой или текстовой команды здесь появится полный диагностический след."
                    textSize = 16f
                    setTextColor(Color.parseColor("#96A4B8"))
                }
            )
            contentContainer.addView(
                empty,
                sectionParams(top = 12)
            )
            return
        }

        records.forEach { record ->
            val success =
                record.optBoolean(
                    "success",
                    false
                )
            val status =
                record.optString(
                    "status",
                    "running"
                )
            val running = status == "running"
            val cancelled = status == "cancelled"
            val duration =
                record.optLong(
                    "duration_ms",
                    -1L
                )
            val started =
                record.optLong(
                    "started_at",
                    0L
                )
            val timeText =
                if (started > 0L) {
                    SimpleDateFormat(
                        "dd.MM  HH:mm:ss",
                        Locale.getDefault()
                    ).format(Date(started))
                } else {
                    ""
                }

            val card = panel(18)
            card.setPadding(
                dp(18),
                dp(14),
                dp(18),
                dp(14)
            )

            val head =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

            head.addView(
                TextView(this).apply {
                    text =
                        when {
                            running -> "●  В РАБОТЕ"
                            cancelled -> "■  ОСТАНОВЛЕНО ПОЛЬЗОВАТЕЛЕМ"
                            success -> "✓  ВЫПОЛНЕНО"
                            else -> "!  ОШИБКА / НЕ ЗАВЕРШЕНО"
                        }
                    textSize = 12.5f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(
                        Color.parseColor(
                            when {
                                running -> "#67E8F9"
                                cancelled -> "#FCD34D"
                                success -> "#86EFAC"
                                else -> "#FCA5A5"
                            }
                        )
                    )
                },
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            head.addView(
                TextView(this).apply {
                    text = buildString {
                        append(timeText)
                        if (duration >= 0L) {
                            append("  •  ")
                            append(String.format(Locale.US, "%.1f c", duration / 1000.0))
                        }
                        val source = record.optString("source")
                        if (source.isNotBlank()) {
                            append("  •  ")
                            append(if (source == "voice") "голос" else "текст")
                        }
                    }
                    textSize = 12.5f
                    setTextColor(Color.parseColor("#75869B"))
                }
            )

            card.addView(head)

            card.addView(
                TextView(this).apply {
                    text = record.optString("command")
                    textSize = 17f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(8), 0, 0)
                }
            )

            val result = record.optString("result")
            if (result.isNotBlank()) {
                card.addView(
                    TextView(this).apply {
                        text = "Результат: $result"
                        textSize = 14.5f
                        setTextColor(Color.parseColor("#C7D2E2"))
                        setPadding(0, dp(7), 0, 0)
                    }
                )
            }

            val events = record.optJSONArray("events")
            if (events != null && events.length() > 0) {
                val startIndex = maxOf(0, events.length() - 6)
                val trace = buildString {
                    for (index in startIndex until events.length()) {
                        val event = events.optJSONObject(index) ?: continue
                        if (isNotEmpty()) append("\n")
                        append("• ")
                        append(event.optString("state"))
                        append(" — ")
                        append(event.optString("message").take(180))
                    }
                }
                card.addView(
                    TextView(this).apply {
                        text = trace
                        textSize = 12.5f
                        setTextColor(Color.parseColor("#7F91A8"))
                        setPadding(0, dp(8), 0, 0)
                    }
                )
            }

            contentContainer.addView(
                card,
                sectionParams(top = 9)
            )
        }
    }

    private fun renderDiagnostics() {

        contentContainer
            .removeAllViews()

        contentContainer.addView(
            pageTitle(
                "Диагностика",
                "Состояние ключевых систем AYANA"
            )
        )

        val checks =
            listOf(
                Triple(
                    "Микрофон",
                    checkSelfPermissionCompat(
                        Manifest.permission.RECORD_AUDIO
                    ),
                    "Доступ к голосовым командам"
                ),
                Triple(
                    "Спец. возможности",
                    isAccessibilityEnabled(),
                    "Управление интерфейсом Android"
                ),
                Triple(
                    "Голосовой сервис",
                    AyanaVoiceService.isRunning,
                    "Активация «Аяна» и фоновые команды"
                ),
                Triple(
                    "Orb AYANA",
                    true,
                    "Один глобальный Orb поверх всех окон • управляется только голосовым сервисом"
                ),
                Triple(
                    "Точные напоминания",
                    taskScheduler
                        .canScheduleExact(),
                    "Точное системное расписание"
                ),
                Triple(
                    "Уведомления",
                    notificationsEnabled(),
                    "Системные уведомления AYANA"
                ),
                Triple(
                    "Память и задачи",
                    true,
                    "${memoryStore.count()} фактов • ${taskStore.count()} задач"
                )
            )

        checks.forEach {
            check ->

            contentContainer.addView(
                diagnosticRow(
                    check.first,
                    check.second,
                    check.third
                ),
                sectionParams(
                    top =
                        7
                )
            )
        }

        val allGood =
            checks.all {
                it.second
            }

        val summary =
            panel(
                20
            )

        summary.addView(
            TextView(this).apply {

                text =
                    if (
                        allGood
                    ) {
                        "✓ Все основные системы работают"
                    } else {
                        "Некоторые разрешения требуют внимания"
                    }

                textSize =
                    16.5f

                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )

                setTextColor(
                    Color.parseColor(
                        if (
                            allGood
                        ) {
                            "#86EFAC"
                        } else {
                            "#FBBF24"
                        }
                    )
                )
            }
        )

        contentContainer.addView(
            summary,
            sectionParams(
                top =
                    14
            )
        )
    }

    private fun renderSettings() {

        contentContainer
            .removeAllViews()

        contentContainer.addView(
            pageTitle(
                "Настройки",
                "Разрешения и управление AYANA"
            )
        )

        contentContainer.addView(
            settingsAction(
                "Orb AYANA",
                if (overlayPermissionGranted()) {
                    "Один плавающий Orb поверх всех приложений • можно перетаскивать"
                } else {
                    "Нужно разрешить показ поверх других приложений"
                }
            ) {

                configureMiniOrb()
            },
            sectionParams(
                top =
                    8
            )
        )

        contentContainer.addView(
            settingsAction(
                "Спец. возможности",
                "Управление приложениями и интерфейсом Android"
            ) {

                try {

                    startActivity(
                        Intent(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS
                        )
                    )

                } catch (_: Exception) {

                    startActivity(
                        Intent(
                            Settings.ACTION_SETTINGS
                        )
                    )
                }
            },
            sectionParams(
                top =
                    8
            )
        )

        contentContainer.addView(
            settingsAction(
                "Точные напоминания",
                if (
                    taskScheduler
                        .canScheduleExact()
                ) {
                    "Разрешение уже выдано"
                } else {
                    "Открыть системный экран разрешения"
                }
            ) {

                taskScheduler
                    .openExactAlarmPermissionScreen()
            },
            sectionParams(
                top =
                    8
            )
        )

        contentContainer.addView(
            settingsAction(
                "Уведомления AYANA",
                "Открыть системные настройки уведомлений"
            ) {

                openNotificationSettings()
            },
            sectionParams(
                top =
                    8
            )
        )

        contentContainer.addView(
            settingsAction(
                "Текстовый режим",
                "Открыть тихий ввод без голосового ответа"
            ) {

                toggleTextMode()
            },
            sectionParams(
                top =
                    8
            )
        )

        contentContainer.addView(
            settingsAction(
                "Системные настройки Android",
                "Открыть настройки планшета"
            ) {

                startActivity(
                    Intent(
                        Settings.ACTION_SETTINGS
                    )
                )
            },
            sectionParams(
                top =
                    8
            )
        )
    }

    private fun buildTextPanel() {

        textPanel =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                visibility =
                    View.GONE

                setPadding(
                    dp(12),
                    dp(10),
                    dp(12),
                    dp(10)
                )

                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(
                            Color.parseColor("#0B1120"),
                            Color.parseColor("#11132A"),
                            Color.parseColor("#0B1422")
                        )
                    ).apply {
                        cornerRadius =
                            dp(20).toFloat()
                        setStroke(
                            dp(1),
                            Color.parseColor("#3A3A68")
                        )
                    }
            }

        val labelRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.CENTER_VERTICAL
            }

        labelRow.addView(
            TextView(this).apply {
                text =
                    "ТЕКСТОВАЯ КОМАНДА"
                textSize =
                    14f
                setTextColor(
                    Color.parseColor("#8B7CF6")
                )
                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
                letterSpacing =
                    0.07f
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        labelRow.addView(
            TextView(this).apply {
                text =
                    "ЗАКРЫТЬ  ×"
                textSize =
                    14f
                setTextColor(
                    Color.parseColor("#708097")
                )
                setOnClickListener {
                    toggleTextMode()
                }
            }
        )

        textPanel.addView(
            labelRow
        )

        val inputRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.CENTER_VERTICAL
                setPadding(
                    0,
                    dp(8),
                    0,
                    0
                )
            }

        textInput =
            EditText(this).apply {
                hint =
                    "Введите команду AYANA…"
                textSize =
                    16f
                setTextColor(
                    Color.WHITE
                )
                setHintTextColor(
                    Color.parseColor("#64748B")
                )
                isSingleLine =
                    true
                imeOptions =
                    EditorInfo.IME_ACTION_SEND
                background =
                    softDrawable(
                        "#070C16",
                        "#293754",
                        15
                    )
                setPadding(
                    dp(14),
                    0,
                    dp(14),
                    0
                )
                setOnEditorActionListener {
                    _,
                    actionId,
                    _ ->
                    if (
                        actionId ==
                        EditorInfo.IME_ACTION_SEND
                    ) {
                        sendTextCommand()
                        true
                    } else {
                        false
                    }
                }
            }

        inputRow.addView(
            textInput,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            )
        )

        val send =
            TextView(this).apply {
                text =
                    "➤"
                textSize =
                    23f
                gravity =
                    Gravity.CENTER
                setTextColor(
                    Color.WHITE
                )
                elevation =
                    dp(4).toFloat()
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.parseColor("#7C3AED"),
                            Color.parseColor("#4F46E5"),
                            Color.parseColor("#0284C7")
                        )
                    ).apply {
                        cornerRadius =
                            dp(15).toFloat()
                    }
                setOnClickListener {
                    sendTextCommand()
                }
            }

        inputRow.addView(
            send,
            LinearLayout.LayoutParams(
                dp(50),
                dp(48)
            ).apply {
                marginStart =
                    dp(8)
            }
        )

        textPanel.addView(
            inputRow
        )

        answerCard =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                visibility =
                    View.GONE
                setPadding(
                    dp(13),
                    dp(10),
                    dp(13),
                    dp(10)
                )
                background =
                    softDrawable(
                        "#0E1224",
                        "#37345F",
                        14
                    )
            }

        textAnswer =
            TextView(this).apply {
                textSize =
                    15.5f
                setTextColor(
                    Color.parseColor("#DCE6F5")
                )
            }

        answerCard.addView(
            textAnswer
        )

        textPanel.addView(
            answerCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin =
                    dp(8)
            }
        )
    }

    private fun toggleTextMode() {

        textModeVisible =
            !textModeVisible

        textPanel.visibility =
            if (
                textModeVisible
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        if (
            textModeVisible
        ) {

            textInput
                .requestFocus()

            textInput
                .postDelayed(
                    {

                        val keyboard =
                            getSystemService(
                                Context.INPUT_METHOD_SERVICE
                            ) as InputMethodManager

                        keyboard.showSoftInput(
                            textInput,
                            InputMethodManager.SHOW_IMPLICIT
                        )

                    },
                    150
                )

        } else {

            hideKeyboard()
        }
    }

    private fun sendTextCommand() {

        val command =
            textInput
                .text
                .toString()
                .trim()

        if (
            command.isBlank()
        ) {
            return
        }

        if (
            !AyanaVoiceService.isRunning
        ) {
            startAyanaService()
        }

        val intent =
            Intent(
                this,
                AyanaVoiceService::class.java
            ).apply {

                action =
                    AyanaVoiceService.ACTION_TEXT_COMMAND

                putExtra(
                    AyanaVoiceService.EXTRA_TEXT_COMMAND,
                    command
                )
            }

        try {

            if (
                Build.VERSION.SDK_INT >= 26
            ) {

                startForegroundService(
                    intent
                )

            } else {

                startService(
                    intent
                )
            }

            textInput
                .setText(
                    ""
                )

            answerCard.visibility =
                View.VISIBLE

            textAnswer.text =
                "AYANA думает…"

            hideKeyboard()

        } catch (_: Exception) {

            showTextAnswer(
                "Не удалось отправить команду."
            )
        }
    }

    private fun showTextAnswer(
        text: String
    ) {

        if (
            !textModeVisible
        ) {

            textModeVisible =
                true

            textPanel.visibility =
                View.VISIBLE
        }

        answerCard.visibility =
            View.VISIBLE

        textAnswer.text =
            text
    }

    private fun hideKeyboard() {

        if (
            !::textInput.isInitialized
        ) {
            return
        }

        val keyboard =
            getSystemService(
                Context.INPUT_METHOD_SERVICE
            ) as InputMethodManager

        keyboard.hideSoftInputFromWindow(
            textInput.windowToken,
            0
        )

        textInput.clearFocus()
    }

    private fun requestNeededPermissionsAndStart() {

        val permissions =
            mutableListOf<String>()

        if (
            !checkSelfPermissionCompat(
                Manifest.permission.RECORD_AUDIO
            )
        ) {

            permissions.add(
                Manifest.permission.RECORD_AUDIO
            )
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            !checkSelfPermissionCompat(
                Manifest.permission.POST_NOTIFICATIONS
            )
        ) {

            permissions.add(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        if (
            permissions.isEmpty()
        ) {

            startAyanaService()

        } else {

            permissionLauncher.launch(
                permissions.toTypedArray()
            )
        }
    }

    private fun startAyanaService() {

        val intent =
            Intent(
                this,
                AyanaVoiceService::class.java
            ).apply {

                action =
                    AyanaVoiceService.ACTION_START
            }

        try {

            if (
                Build.VERSION.SDK_INT >= 26
            ) {

                startForegroundService(
                    intent
                )

            } else {

                startService(
                    intent
                )
            }

            stopButton.isEnabled =
                true

            stopButton.alpha =
                1.0f

            if (
                ::cancelCommandButton.isInitialized
            ) {

                cancelCommandButton.isEnabled =
                    false

                cancelCommandButton.alpha =
                    0.45f
            }

            setStatus(
                "Жду: «Аяна»",
                AyanaVoiceService.STATE_LISTENING
            )

        } catch (_: Exception) {

            setStatus(
                "Не удалось запустить AYANA",
                AyanaVoiceService.STATE_ERROR
            )
        }
    }

    private fun cancelCurrentCommand() {

        if (
            !AyanaVoiceService.isRunning ||
            !isCommandBusyState(
                AyanaVoiceService.currentStatusState
            )
        ) {
            return
        }

        val intent =
            Intent(
                this,
                AyanaVoiceService::class.java
            ).apply {

                action =
                    AyanaVoiceService.ACTION_CANCEL_COMMAND
            }

        try {

            startService(
                intent
            )

        } catch (_: Exception) {
        }
    }

    private fun stopAyanaService() {

        // Critical lifecycle rule: do not start AyanaVoiceService merely to
        // deliver STOP when it is already stopped. That was the source of
        // repeated service creation and duplicated overlay Orbs.
        if (
            AyanaVoiceService.isRunning
        ) {

            val stopIntent =
                Intent(
                    this,
                    AyanaVoiceService::class.java
                ).apply {

                    action =
                        AyanaVoiceService.ACTION_STOP
                }

            try {

                startService(
                    stopIntent
                )

            } catch (_: Exception) {

                try {
                    stopService(
                        Intent(
                            this,
                            AyanaVoiceService::class.java
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }

        if (
            ::cancelCommandButton.isInitialized
        ) {

            cancelCommandButton.isEnabled =
                false

            cancelCommandButton.alpha =
                0.45f
        }

        if (
            ::stopButton.isInitialized
        ) {

            stopButton.isEnabled =
                false

            stopButton.alpha =
                0.45f
        }

        setStatus(
            "AYANA остановлена",
            AyanaVoiceService.STATE_STOPPED
        )
    }

    private fun setStatus(
        text: String,
        state: String
    ) {

        if (::statusText.isInitialized) {
            statusText.text = text
        }

        if (::statusDot.isInitialized) {
            statusDot.background =
                circleDrawable(
                    stateColor(state)
                )
        }

        homeStateTitle?.text =
            stateTitle(state)

        if (
            ::cancelCommandButton.isInitialized
        ) {

            val busy =
                isCommandBusyState(
                    state
                ) &&
                    AyanaVoiceService.isRunning

            cancelCommandButton.isEnabled =
                busy

            cancelCommandButton.alpha =
                if (
                    busy
                ) {
                    1.0f
                } else {
                    0.45f
                }
        }

        if (
            ::stopButton.isInitialized
        ) {

            val running =
                state !=
                    AyanaVoiceService.STATE_STOPPED &&
                    AyanaVoiceService.isRunning

            stopButton.isEnabled =
                running

            stopButton.alpha =
                if (
                    running
                ) {
                    1.0f
                } else {
                    0.45f
                }
        }
    }

    private fun isCommandBusyState(
        state: String
    ): Boolean {

        return state in
            setOf(
                AyanaVoiceService.STATE_COMMAND,
                AyanaVoiceService.STATE_THINKING,
                AyanaVoiceService.STATE_EXECUTING,
                AyanaVoiceService.STATE_SPEAKING
            )
    }

    private fun stateTitle(
        state: String
    ): String {

        return when (
            state
        ) {

            AyanaVoiceService.STATE_LISTENING ->
                "Слушаю"

            AyanaVoiceService.STATE_COMMAND ->
                "Распознаю команду"

            AyanaVoiceService.STATE_THINKING ->
                "Думаю"

            AyanaVoiceService.STATE_EXECUTING ->
                "Выполняю"

            AyanaVoiceService.STATE_SUCCESS ->
                "Готово"

            AyanaVoiceService.STATE_SPEAKING ->
                "Говорю"

            AyanaVoiceService.STATE_TEXT ->
                "Текстовый ответ"

            AyanaVoiceService.STATE_ERROR ->
                "Нужна помощь"

            AyanaVoiceService.STATE_CANCELLED ->
                "Команда остановлена"

            AyanaVoiceService.STATE_STOPPED ->
                "Остановлена"

            else ->
                "AYANA"
        }
    }

    private fun stateColor(
        state: String
    ): Int {

        return Color.parseColor(
            when (
                state
            ) {

                AyanaVoiceService.STATE_LISTENING ->
                    "#38BDF8"

                AyanaVoiceService.STATE_COMMAND ->
                    "#22D3EE"

                AyanaVoiceService.STATE_THINKING ->
                    "#8B5CF6"

                AyanaVoiceService.STATE_EXECUTING ->
                    "#2DD4BF"

                AyanaVoiceService.STATE_SUCCESS ->
                    "#22C55E"

                AyanaVoiceService.STATE_SPEAKING ->
                    "#6366F1"

                AyanaVoiceService.STATE_TEXT ->
                    "#67E8F9"

                AyanaVoiceService.STATE_ERROR ->
                    "#EF4444"

                AyanaVoiceService.STATE_CANCELLED ->
                    "#F59E0B"

                AyanaVoiceService.STATE_STOPPED ->
                    "#64748B"

                else ->
                    "#7C3AED"
            }
        )
    }

    private fun orbDrawable(
        state: String
    ): GradientDrawable {

        val colors =
            when (
                state
            ) {

                AyanaVoiceService.STATE_LISTENING ->
                    intArrayOf(
                        Color.parseColor(
                            "#1D4ED8"
                        ),
                        Color.parseColor(
                            "#06B6D4"
                        )
                    )

                AyanaVoiceService.STATE_THINKING ->
                    intArrayOf(
                        Color.parseColor(
                            "#4C1D95"
                        ),
                        Color.parseColor(
                            "#7C3AED"
                        )
                    )

                AyanaVoiceService.STATE_EXECUTING ->
                    intArrayOf(
                        Color.parseColor("#0F766E"),
                        Color.parseColor("#2DD4BF")
                    )

                AyanaVoiceService.STATE_SUCCESS ->
                    intArrayOf(
                        Color.parseColor("#166534"),
                        Color.parseColor("#22C55E")
                    )

                AyanaVoiceService.STATE_ERROR ->
                    intArrayOf(
                        Color.parseColor(
                            "#7F1D1D"
                        ),
                        Color.parseColor(
                            "#DC2626"
                        )
                    )

                AyanaVoiceService.STATE_STOPPED ->
                    intArrayOf(
                        Color.parseColor(
                            "#1F2937"
                        ),
                        Color.parseColor(
                            "#334155"
                        )
                    )

                else ->
                    intArrayOf(
                        Color.parseColor(
                            "#4338CA"
                        ),
                        Color.parseColor(
                            "#7C3AED"
                        ),
                        Color.parseColor(
                            "#2563EB"
                        )
                    )
            }

        return GradientDrawable(
            GradientDrawable
                .Orientation
                .TL_BR,
            colors
        ).apply {

            shape =
                GradientDrawable.OVAL

            setStroke(
                dp(4),
                stateColor(
                    state
                )
            )
        }
    }

    private fun handleOrbClick() {

        if (!AyanaVoiceService.isRunning) {
            startAyanaService()
            return
        }

        try {
            startService(
                Intent(
                    this,
                    AyanaVoiceService::class.java
                ).apply {
                    action =
                        AyanaVoiceService.ACTION_START
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun nextReminderCard():
        View {

        val card =
            panel(
                20
            )

        card.addView(
            smallSectionTitle(
                "СЛЕДУЮЩЕЕ НАПОМИНАНИЕ"
            )
        )

        val next =
            taskStore
                .getFutureTasks()
                .firstOrNull()

        if (
            next == null
        ) {

            card.addView(
                TextView(this).apply {

                    text =
                        "Пока ничего не запланировано"

                    textSize =
                    16.5f

                    setTextColor(
                        Color.parseColor(
                            "#95A3B6"
                        )
                    )

                    setPadding(
                        0,
                        dp(12),
                        0,
                        dp(4)
                    )
                }
            )

        } else {

            card.addView(
                TextView(this).apply {

                    text =
                        formatTaskTime(
                            next.triggerAtMillis
                        )

                    textSize =
                    24f

                    setTypeface(
                        Typeface.DEFAULT,
                        Typeface.BOLD
                    )

                    setTextColor(
                        Color.WHITE
                    )

                    setPadding(
                        0,
                        dp(10),
                        0,
                        0
                    )
                }
            )

            card.addView(
                TextView(this).apply {

                    text =
                        next.message

                    textSize =
                    15.5f

                    setTextColor(
                        Color.parseColor(
                            "#A7B4C6"
                        )
                    )

                    setPadding(
                        0,
                        dp(7),
                        0,
                        0
                    )
                }
            )
        }

        return card
    }

    private fun quickActionsCard():
        View {

        val card =
            panel(
                20
            )

        card.addView(
            smallSectionTitle(
                "БЫСТРЫЕ ДЕЙСТВИЯ"
            )
        )

        val row =
            HorizontalScrollView(this).apply {

                isHorizontalScrollBarEnabled =
                    false
            }

        val chips =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                setPadding(
                    0,
                    dp(10),
                    0,
                    0
                )
            }

        listOf(
            "Задачи" to Page.TASKS,
            "Память" to Page.MEMORY,
            "Проверка" to Page.DIAGNOSTICS
        ).forEach {
            item ->

            chips.addView(
                smallAction(
                    item.first
                ) {

                    switchPage(
                        item.second
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(42)
                ).apply {

                    marginEnd =
                        dp(8)
                }
            )
        }

        row.addView(
            chips
        )

        card.addView(
            row
        )

        return card
    }

    private fun modeStrip():
        View {

        val card =
            panel(
                20
            )

        card.addView(
            smallSectionTitle(
                "СОСТОЯНИЯ AYANA"
            )
        )

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    dp(12),
                    0,
                    0
                )
            }

        listOf(
            "Слушаю" to "#38BDF8",
            "Думаю" to "#8B5CF6",
            "Выполняю" to "#6366F1",
            "Говорю" to "#22D3EE",
            "Ошибка" to "#EF4444"
        ).forEach {
            item ->

            val state =
                LinearLayout(this).apply {

                    orientation =
                        LinearLayout.VERTICAL

                    gravity =
                        Gravity.CENTER

                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                }

            state.addView(
                View(this).apply {

                    background =
                        circleDrawable(
                            Color.parseColor(
                                item.second
                            )
                        )
                },
                LinearLayout.LayoutParams(
                    dp(18),
                    dp(18)
                )
            )

            state.addView(
                TextView(this).apply {

                    text =
                        item.first

                    textSize =
                    14.5f

                    setTextColor(
                        Color.parseColor(
                            "#8FA0B7"
                        )
                    )

                    setPadding(
                        0,
                        dp(5),
                        0,
                        0
                    )
                }
            )

            row.addView(
                state
            )
        }

        card.addView(
            row
        )

        return card
    }

    private fun metricCard(
        title: String,
        number: String,
        caption: String
    ): View {

        val card =
            panel(
                18
            )

        card.addView(
            TextView(this).apply {

                text =
                    title.uppercase(
                        Locale.getDefault()
                    )

                textSize =
                    14.5f

                setTextColor(
                    Color.parseColor(
                        "#73839A"
                    )
                )
            }
        )

        card.addView(
            TextView(this).apply {

                text =
                    number

                textSize =
                    31f

                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )

                setTextColor(
                    Color.WHITE
                )

                setPadding(
                    0,
                    dp(5),
                    0,
                    0
                )
            }
        )

        card.addView(
            TextView(this).apply {

                text =
                    caption

                textSize =
                    14.5f

                setTextColor(
                    Color.parseColor(
                        "#8A98AA"
                    )
                )
            }
        )

        return card
    }

    private fun diagnosticRow(
        name: String,
        ok: Boolean,
        description: String
    ): View {

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(18),
                    dp(15),
                    dp(18),
                    dp(15)
                )

                background =
                    panelDrawable(
                        corner =
                            18,
                        stroke =
                            "#1B293C"
                    )
            }

        val copy =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
            }

        copy.addView(
            TextView(this).apply {

                text =
                    name

                textSize =
                    16.5f

                setTextColor(
                    Color.WHITE
                )
            }
        )

        copy.addView(
            TextView(this).apply {

                text =
                    description

                textSize =
                    14.5f

                setTextColor(
                    Color.parseColor(
                        "#728196"
                    )
                )

                setPadding(
                    0,
                    dp(4),
                    0,
                    0
                )
            }
        )

        row.addView(
            copy
        )

        row.addView(
            TextView(this).apply {

                text =
                    if (
                        ok
                    ) {
                        "✓ Работает"
                    } else {
                        "● Требует внимания"
                    }

                textSize =
                    14.5f

                setTextColor(
                    Color.parseColor(
                        if (
                            ok
                        ) {
                            "#4ADE80"
                        } else {
                            "#FBBF24"
                        }
                    )
                )
            }
        )

        return row
    }

    private fun settingsAction(
        title: String,
        description: String,
        action: () -> Unit
    ): View {

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(18),
                    dp(16),
                    dp(18),
                    dp(16)
                )

                background =
                    panelDrawable(
                        corner =
                            18,
                        stroke =
                            "#1B293C"
                    )

                setOnClickListener {
                    action()
                }
            }

        val copy =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
            }

        copy.addView(
            TextView(this).apply {

                text =
                    title

                textSize =
                    16.5f

                setTextColor(
                    Color.WHITE
                )
            }
        )

        copy.addView(
            TextView(this).apply {

                text =
                    description

                textSize =
                    14.5f

                setTextColor(
                    Color.parseColor(
                        "#728196"
                    )
                )

                setPadding(
                    0,
                    dp(4),
                    0,
                    0
                )
            }
        )

        row.addView(
            copy
        )

        row.addView(
            TextView(this).apply {

                text =
                    "›"

                textSize =
                    30f

                setTextColor(
                    Color.parseColor(
                        "#8B5CF6"
                    )
                )
            }
        )

        return row
    }

    private fun pageTitle(
        title: String,
        subtitle: String
    ): View {

        return LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            setPadding(
                dp(2),
                dp(5),
                dp(2),
                dp(12)
            )

            addView(
                TextView(this@MainActivity).apply {

                    text =
                        title

                    textSize =
                    30f

                    setTypeface(
                        Typeface.DEFAULT,
                        Typeface.BOLD
                    )

                    setTextColor(
                        Color.WHITE
                    )
                }
            )

            addView(
                TextView(this@MainActivity).apply {

                    text =
                        subtitle

                    textSize =
                    15f

                    setTextColor(
                        Color.parseColor(
                            "#718197"
                        )
                    )

                    setPadding(
                        0,
                        dp(3),
                        0,
                        0
                    )
                }
            )
        }
    }

    private fun emptyCard(
        title: String,
        subtitle: String
    ): View {

        val card =
            panel(
                20
            )

        card.gravity =
            Gravity.CENTER_HORIZONTAL

        card.setPadding(
            dp(20),
            dp(28),
            dp(20),
            dp(28)
        )

        card.addView(
            TextView(this).apply {

                text =
                    title

                textSize =
                    18f

                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )

                setTextColor(
                    Color.parseColor(
                        "#DDE6F2"
                    )
                )
            }
        )

        card.addView(
            TextView(this).apply {

                text =
                    subtitle

                textSize =
                    15f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.parseColor(
                        "#7A899D"
                    )
                )

                setPadding(
                    0,
                    dp(8),
                    0,
                    0
                )
            }
        )

        return card
    }

    private fun panel(
        corner: Int
    ): LinearLayout {

        return LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            setPadding(
                dp(18),
                dp(16),
                dp(18),
                dp(16)
            )

            background =
                panelDrawable(
                    corner =
                        corner,
                    stroke =
                        "#1A283B"
                )
        }
    }

    private fun smallSectionTitle(
        textValue: String
    ): TextView {

        return TextView(this).apply {

            text =
                textValue

            textSize =
                    14.5f

            setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
            )

            setTextColor(
                Color.parseColor(
                    "#8B79E8"
                )
            )

            letterSpacing =
                0.04f
        }
    }

    private fun topIconButton(
        symbol: String
    ): TextView {

        return TextView(this).apply {

            text =
                symbol

            textSize =
                    22f

            gravity =
                Gravity.CENTER

            setTextColor(
                Color.parseColor(
                    "#D9E3F1"
                )
            )

            background =
                softDrawable(
                    "#0D1420",
                    "#24334B",
                    14
                )
        }
    }

    private fun smallAction(
        label: String,
        action: () -> Unit
    ): TextView {

        return TextView(this).apply {

            text =
                label

            textSize =
                    14.5f

            gravity =
                Gravity.CENTER

            setPadding(
                dp(14),
                0,
                dp(14),
                0
            )

            setTextColor(
                Color.parseColor(
                    "#D9E3F1"
                )
            )

            background =
                softDrawable(
                    "#111A2A",
                    "#31405A",
                    14
                )

            setOnClickListener {
                action()
            }
        }
    }

    private fun panelDrawable(
        corner: Int,
        stroke: String
    ): GradientDrawable {

        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#0A101B"),
                Color.parseColor("#0E1625"),
                Color.parseColor("#101226")
            )
        ).apply {
            cornerRadius =
                dp(corner).toFloat()
            setStroke(
                dp(1),
                Color.parseColor(stroke)
            )
        }
    }

    private inner class AyanaPulseView(
        context: Context
    ) : View(context) {

        private val gridPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }

        private val wavePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

        private val path = Path()
        private var attached = false

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            attached = true
            postInvalidateOnAnimation()
        }

        override fun onDetachedFromWindow() {
            attached = false
            super.onDetachedFromWindow()
        }

        override fun onDraw(
            canvas: Canvas
        ) {
            super.onDraw(canvas)

            val w = width.toFloat()
            val h = height.toFloat()

            if (w <= 1f || h <= 1f) {
                return
            }

            gridPaint.color =
                Color.argb(
                    34,
                    83,
                    108,
                    143
                )

            for (i in 1..4) {
                val y = h * i / 5f
                canvas.drawLine(
                    dp(12).toFloat(),
                    y,
                    w - dp(12),
                    y,
                    gridPaint
                )
            }

            for (i in 1..6) {
                val x = w * i / 7f
                canvas.drawLine(
                    x,
                    dp(12).toFloat(),
                    x,
                    h - dp(12),
                    gridPaint
                )
            }

            val state =
                AyanaVoiceService.currentStatusState

            val amplitude =
                when (state) {
                    AyanaVoiceService.STATE_LISTENING -> h * 0.22f
                    AyanaVoiceService.STATE_COMMAND -> h * 0.28f
                    AyanaVoiceService.STATE_THINKING -> h * 0.19f
                    AyanaVoiceService.STATE_SPEAKING -> h * 0.25f
                    AyanaVoiceService.STATE_ERROR -> h * 0.12f
                    else -> h * 0.10f
                }

            val speed =
                when (state) {
                    AyanaVoiceService.STATE_THINKING -> 3.6f
                    AyanaVoiceService.STATE_COMMAND -> 4.4f
                    AyanaVoiceService.STATE_SPEAKING -> 4.8f
                    else -> 2.7f
                }

            val t =
                (System.nanoTime() / 1_000_000_000.0).toFloat()

            val primary =
                stateColor(state)

            val secondary =
                when (state) {
                    AyanaVoiceService.STATE_ERROR ->
                        Color.parseColor("#F87171")
                    AyanaVoiceService.STATE_THINKING ->
                        Color.parseColor("#A78BFA")
                    else ->
                        Color.parseColor("#38BDF8")
                }

            for (track in 0..2) {
                path.reset()

                val phaseOffset = track * 0.85f
                val trackScale = 1f - track * 0.18f
                val center =
                    h * (0.48f + (track - 1) * 0.055f)

                var x = 0f
                var first = true

                while (x <= w) {
                    val nx = x / w
                    val envelope =
                        Math.sin(
                            Math.PI * nx
                        ).toFloat()

                    val harmonic =
                        Math.sin(
                            nx * Math.PI * 6.0 +
                                t * speed +
                                phaseOffset
                        ).toFloat()

                    val micro =
                        Math.sin(
                            nx * Math.PI * 15.0 -
                                t * (speed * 0.48f) +
                                track
                        ).toFloat() *
                            0.18f

                    val y =
                        center +
                            (harmonic + micro) *
                            amplitude *
                            envelope *
                            trackScale

                    if (first) {
                        path.moveTo(x, y)
                        first = false
                    } else {
                        path.lineTo(x, y)
                    }

                    x += 4f
                }

                wavePaint.color =
                    if (track == 1) {
                        secondary
                    } else {
                        primary
                    }

                wavePaint.alpha =
                    when (track) {
                        0 -> 120
                        1 -> 230
                        else -> 90
                    }

                wavePaint.strokeWidth =
                    when (track) {
                        1 -> dp(3).toFloat()
                        else -> dp(2).toFloat()
                    }

                canvas.drawPath(
                    path,
                    wavePaint
                )
            }

            val scanX =
                ((t * 72f) % w)

            wavePaint.color = secondary
            wavePaint.alpha = 70
            wavePaint.strokeWidth = 1f

            canvas.drawLine(
                scanX,
                dp(12).toFloat(),
                scanX,
                h - dp(12),
                wavePaint
            )

            if (attached) {
                postInvalidateOnAnimation()
            }
        }
    }

    private fun softDrawable(
        fill: String,
        stroke: String,
        corner: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            setColor(
                Color.parseColor(
                    fill
                )
            )

            cornerRadius =
                dp(corner)
                    .toFloat()

            setStroke(
                dp(1),
                Color.parseColor(
                    stroke
                )
            )
        }
    }

    private fun circleDrawable(
        color: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            shape =
                GradientDrawable.OVAL

            setColor(
                color
            )
        }
    }

    private fun equalCardParams(
        left: Int = 0
    ): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {

            marginStart =
                dp(left)
        }
    }

    private fun sectionParams(
        top: Int = 10
    ): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {

            topMargin =
                dp(top)
        }
    }

    private fun formatTaskTime(
        millis: Long
    ): String {

        return SimpleDateFormat(
            "dd MMM, HH:mm",
            Locale.getDefault()
        ).format(
            Date(
                millis
            )
        )
    }

    private fun memoryCategoryTitle(
        category: String
    ): String {

        return when (
            category
        ) {

            "preference" ->
                "ПРЕДПОЧТЕНИЯ"

            "person" ->
                "ЛЮДИ"

            "project" ->
                "ПРОЕКТЫ"

            "task" ->
                "ЗАДАЧИ"

            "place" ->
                "МЕСТА"

            else ->
                "ВАЖНОЕ"
        }
    }

    private fun diagnosticsPassed():
        Int {

        return listOf(
            checkSelfPermissionCompat(
                Manifest.permission.RECORD_AUDIO
            ),
            isAccessibilityEnabled(),
            AyanaVoiceService.isRunning,
            !ayanaPreferences.miniOrbEnabled ||
                overlayPermissionGranted(),
            taskScheduler
                .canScheduleExact(),
            notificationsEnabled(),
            true
        ).count {
            it
        }
    }

    private fun isAccessibilityEnabled():
        Boolean {

        val expected =
            ComponentName(
                this,
                AgentAccessibilityService::class.java
            )
                .flattenToString()

        val enabled =
            Settings.Secure
                .getString(
                    contentResolver,
                    Settings.Secure
                        .ENABLED_ACCESSIBILITY_SERVICES
                )
                ?: return false

        return enabled
            .split(":")
            .any {
                service ->

                service.equals(
                    expected,
                    ignoreCase =
                        true
                )
            }
    }

    private fun notificationsEnabled():
        Boolean {

        return try {

            getSystemService(
                NotificationManager::class.java
            )
                .areNotificationsEnabled()

        } catch (_: Exception) {

            true
        }
    }

    private fun overlayPermissionGranted():
        Boolean {

        return if (
            Build.VERSION.SDK_INT >= 23
        ) {

            Settings
                .canDrawOverlays(
                    this
                )

        } else {

            true
        }
    }

    private fun configureMiniOrb() {

        ayanaPreferences.miniOrbEnabled = true

        if (
            Build.VERSION.SDK_INT >= 23 &&
            !Settings.canDrawOverlays(this)
        ) {

            try {

                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse(
                            "package:$packageName"
                        )
                    )
                )

            } catch (_: Exception) {

                Toast
                    .makeText(
                        this,
                        "Разрешите AYANA показываться поверх других приложений",
                        Toast.LENGTH_LONG
                    )
                    .show()
            }

            return
        }

        refreshMiniOrb()
        renderSettings()
    }

    private fun refreshMiniOrb() {

        if (
            !AyanaVoiceService
                .isRunning
        ) {
            return
        }

        val intent =
            Intent(
                this,
                AyanaVoiceService::class.java
            ).apply {

                action =
                    AyanaVoiceService
                        .ACTION_REFRESH_OVERLAY
            }

        try {

            startService(
                intent
            )

        } catch (_: Exception) {
        }
    }

    private fun openNotificationSettings() {

        val intent =
            Intent(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS
            ).apply {

                putExtra(
                    Settings.EXTRA_APP_PACKAGE,
                    packageName
                )
            }

        try {

            startActivity(
                intent
            )

        } catch (_: Exception) {

            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse(
                        "package:$packageName"
                    )
                )
            )
        }
    }

    private fun checkSelfPermissionCompat(
        permission: String
    ): Boolean {

        return if (
            Build.VERSION.SDK_INT >= 23
        ) {

            checkSelfPermission(
                permission
            ) ==
                android.content.pm
                    .PackageManager
                    .PERMISSION_GRANTED

        } else {

            true
        }
    }

    private fun dp(
        value: Float
    ): Float {
        return value * resources.displayMetrics.density
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources
                    .displayMetrics
                    .density
            )
            .toInt()
    }

    private class ColorDrawableCompat(
        color: Int
    ) :
        android.graphics.drawable
            .ColorDrawable(
                color
            )
}
