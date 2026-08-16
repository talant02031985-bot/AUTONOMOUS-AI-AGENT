package kg.autonomous.agent

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Page {
        HOME,
        TASKS,
        MEMORY,
        DIAGNOSTICS,
        SETTINGS
    }

    private lateinit var contentContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var orbText: TextView
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
                    Page.HOME
                ) {
                    renderHome()
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

        if (
            ayanaPreferences.miniOrbEnabled &&
            overlayPermissionGranted()
        ) {
            refreshMiniOrb()
        }

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

                orientation =
                    LinearLayout.VERTICAL

                setBackgroundColor(
                    Color.parseColor(
                        "#060910"
                    )
                )

                setPadding(
                    dp(18),
                    dp(14),
                    dp(18),
                    dp(22)
                )
            }

        root.addView(
            buildTopBar()
        )

        val body =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.TOP

                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    ).apply {

                        topMargin =
                            dp(12)
                    }
            }

        body.addView(
            buildSidebar()
        )

        val rightColumn =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f
                    ).apply {

                        marginStart =
                            dp(12)
                    }
            }

        val scroll =
            ScrollView(this).apply {

                isFillViewport =
                    true

                overScrollMode =
                    View.OVER_SCROLL_NEVER

                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
            }

        contentContainer =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    0,
                    0,
                    dp(2),
                    dp(14)
                )
            }

        scroll.addView(
            contentContainer
        )

        buildTextPanel()

        rightColumn.addView(
            textPanel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin =
                    dp(10)
            }
        )

        rightColumn.addView(
            scroll
        )

        body.addView(
            rightColumn
        )

        root.addView(
            body
        )

        setContentView(
            root
        )

        renderCurrentPage()
    }

    private fun buildTopBar():
        View {

        val bar =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(16),
                    dp(10),
                    dp(12),
                    dp(10)
                )

                background =
                    panelDrawable(
                        corner =
                            20,
                        stroke =
                            "#1E2B41"
                    )
            }

        val brand =
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

        brand.addView(
            TextView(this).apply {

                text =
                    "AYANA AI 1.0"

                textSize =
                    23f

                setTextColor(
                    Color.WHITE
                )

                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }
        )

        brand.addView(
            TextView(this).apply {

                text =
                    "Ваш персональный AI-агент"

                textSize =
                    12f

                setTextColor(
                    Color.parseColor(
                        "#8292AA"
                    )
                )
            }
        )

        bar.addView(
            brand
        )

        val online =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(12),
                    dp(8),
                    dp(12),
                    dp(8)
                )

                background =
                    softDrawable(
                        "#0B1716",
                        "#174438",
                        16
                    )
            }

        online.addView(
            View(this).apply {

                background =
                    circleDrawable(
                        Color.parseColor(
                            "#22C55E"
                        )
                    )

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(8),
                        dp(8)
                    ).apply {

                        marginEnd =
                            dp(7)
                    }
            }
        )

        online.addView(
            TextView(this).apply {

                text =
                    "Онлайн"

                textSize =
                    12f

                setTextColor(
                    Color.parseColor(
                        "#A7F3D0"
                    )
                )
            }
        )

        bar.addView(
            online
        )

        textModeButton =
            topIconButton(
                "⌨"
            ).apply {

                contentDescription =
                    "Текстовый режим"

                setOnClickListener {
                    toggleTextMode()
                }
            }

        bar.addView(
            textModeButton,
            LinearLayout.LayoutParams(
                dp(44),
                dp(44)
            ).apply {

                marginStart =
                    dp(9)
            }
        )

        val settingsButton =
            topIconButton(
                "⚙"
            ).apply {

                contentDescription =
                    "Настройки"

                setOnClickListener {
                    switchPage(
                        Page.SETTINGS
                    )
                }
            }

        bar.addView(
            settingsButton,
            LinearLayout.LayoutParams(
                dp(44),
                dp(44)
            ).apply {

                marginStart =
                    dp(7)
            }
        )

        return bar
    }

    private fun buildSidebar():
        View {

        val side =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(10),
                    dp(12),
                    dp(10),
                    dp(12)
                )

                background =
                    panelDrawable(
                        corner =
                            22,
                        stroke =
                            "#1B2638"
                    )

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(178),
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
            }

        side.addView(
            smallSectionTitle(
                "AYANA"
            )
        )

        side.addView(
            navButton(
                Page.HOME,
                "⌂  Главная"
            )
        )

        side.addView(
            navButton(
                Page.TASKS,
                "◷  Задачи"
            )
        )

        side.addView(
            navButton(
                Page.MEMORY,
                "◇  Память"
            )
        )

        side.addView(
            navButton(
                Page.DIAGNOSTICS,
                "⌁  Диагностика"
            )
        )

        side.addView(
            navButton(
                Page.SETTINGS,
                "⚙  Настройки"
            )
        )

        side.addView(
            Space(this),
            LinearLayout.LayoutParams(
                1,
                0,
                1f
            )
        )

        val serviceCaption =
            TextView(this).apply {

                text =
                    "ГОЛОСОВОЙ СЕРВИС"

                textSize =
                    10f

                setTextColor(
                    Color.parseColor(
                        "#64748B"
                    )
                )

                setPadding(
                    dp(8),
                    dp(8),
                    dp(8),
                    dp(6)
                )
            }

        side.addView(
            serviceCaption
        )

        stopButton =
            Button(this).apply {

                text =
                    "■  ОСТАНОВИТЬ"

                textSize =
                    11f

                isAllCaps =
                    false

                setTextColor(
                    Color.parseColor(
                        "#FCA5A5"
                    )
                )

                background =
                    softDrawable(
                        "#241012",
                        "#5C242A",
                        16
                    )

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
                13f

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
                    dp(50)
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

        if (
            !::contentContainer.isInitialized
        ) {
            return
        }

        updateNavigation()

        when (
            currentPage
        ) {

            Page.HOME ->
                renderHome()

            Page.TASKS ->
                renderTasks()

            Page.MEMORY ->
                renderMemory()

            Page.DIAGNOSTICS ->
                renderDiagnostics()

            Page.SETTINGS ->
                renderSettings()
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
                                    "#3B237B"
                                ),
                                Color.parseColor(
                                    "#273A7A"
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

        contentContainer
            .removeAllViews()

        contentContainer.addView(
            pageTitle(
                "Главная",
                "AYANA Control Center • голос, экран и автономные действия"
            )
        )

        val topRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.TOP
            }

        topRow.addView(
            controlHeroCard(),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.55f
            )
        )

        val rightStack =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL
            }

        rightStack.addView(
            executionCard()
        )

        rightStack.addView(
            servicesCompactCard(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin =
                    dp(10)
            }
        )

        topRow.addView(
            rightStack,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart =
                    dp(10)
            }
        )

        contentContainer.addView(
            topRow,
            sectionParams()
        )

        val infoRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL
            }

        infoRow.addView(
            nextReminderCard(),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.12f
            )
        )

        infoRow.addView(
            activityOverviewCard(),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart =
                    dp(10)
            }
        )

        contentContainer.addView(
            infoRow,
            sectionParams()
        )

        contentContainer.addView(
            controlFooterStrip(),
            sectionParams()
        )
    }

    private fun controlHeroCard():
        View {

        val card =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(24),
                    dp(22),
                    dp(24),
                    dp(22)
                )

                background =
                    GradientDrawable(
                        GradientDrawable
                            .Orientation
                            .TL_BR,
                        intArrayOf(
                            Color.parseColor("#0B1423"),
                            Color.parseColor("#111A34"),
                            Color.parseColor("#1A1231")
                        )
                    ).apply {

                        cornerRadius =
                            dp(26)
                                .toFloat()

                        setStroke(
                            dp(1),
                            Color.parseColor("#303B63")
                        )
                    }
            }

        val orbFrame =
            LinearLayout(this).apply {

                gravity =
                    Gravity.CENTER

                background =
                    GradientDrawable(
                        GradientDrawable
                            .Orientation
                            .TL_BR,
                        intArrayOf(
                            Color.parseColor("#1F2B54"),
                            Color.parseColor("#5637B7"),
                            Color.parseColor("#142E59")
                        )
                    ).apply {

                        shape =
                            GradientDrawable.OVAL

                        setStroke(
                            dp(2),
                            Color.parseColor("#795CFF")
                        )
                    }
            }

        val orbInner =
            LinearLayout(this).apply {

                gravity =
                    Gravity.CENTER

                background =
                    GradientDrawable(
                        GradientDrawable
                            .Orientation
                            .TL_BR,
                        intArrayOf(
                            Color.parseColor("#0C162A"),
                            Color.parseColor("#161334")
                        )
                    ).apply {

                        shape =
                            GradientDrawable.OVAL

                        setStroke(
                            dp(2),
                            Color.parseColor("#43C8FF")
                        )
                    }
            }

        orbText =
            TextView(this).apply {

                text =
                    "A"

                textSize =
                    48f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.WHITE
                )

                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )

                background =
                    orbDrawable(
                        AyanaVoiceService
                            .currentStatusState
                    )
            }

        orbInner.addView(
            orbText,
            LinearLayout.LayoutParams(
                dp(108),
                dp(108)
            )
        )

        orbFrame.addView(
            orbInner,
            LinearLayout.LayoutParams(
                dp(132),
                dp(132)
            )
        )

        card.addView(
            orbFrame,
            LinearLayout.LayoutParams(
                dp(154),
                dp(154)
            )
        )

        val stateColumn =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        marginStart =
                            dp(24)
                    }
            }

        stateColumn.addView(
            TextView(this).apply {

                text =
                    "AYANA"

                textSize =
                    11f

                setTextColor(
                    Color.parseColor("#9A7BFF")
                )

                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }
        )

        stateColumn.addView(
            TextView(this).apply {

                text =
                    stateTitle(
                        AyanaVoiceService
                            .currentStatusState
                    )

                textSize =
                    26f

                setTextColor(
                    Color.WHITE
                )

                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )

                setPadding(
                    0,
                    dp(5),
                    0,
                    0
                )
            }
        )

        val statusLine =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    0,
                    dp(11),
                    0,
                    0
                )
            }

        statusDot =
            View(this).apply {

                background =
                    circleDrawable(
                        stateColor(
                            AyanaVoiceService
                                .currentStatusState
                        )
                    )
            }

        statusLine.addView(
            statusDot,
            LinearLayout.LayoutParams(
                dp(10),
                dp(10)
            ).apply {
                marginEnd =
                    dp(9)
            }
        )

        statusText =
            TextView(this).apply {

                text =
                    AyanaVoiceService
                        .currentStatusText

                textSize =
                    14f

                setTextColor(
                    Color.parseColor("#D3DCEC")
                )
            }

        statusLine.addView(
            statusText
        )

        stateColumn.addView(
            statusLine
        )

        stateColumn.addView(
            TextView(this).apply {

                text =
                    "Скажите «Аяна» — агент услышит команду, посмотрит экран и выполнит доступные действия."

                textSize =
                    12.5f

                setTextColor(
                    Color.parseColor("#7D8DA7")
                )

                setPadding(
                    0,
                    dp(10),
                    0,
                    0
                )
            }
        )

        val chips =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                setPadding(
                    0,
                    dp(14),
                    0,
                    0
                )
            }

        chips.addView(
            statusChip(
                "Голос"
            )
        )

        chips.addView(
            statusChip(
                "Экран"
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34)
            ).apply {
                marginStart =
                    dp(7)
            }
        )

        chips.addView(
            statusChip(
                "Agent Core"
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34)
            ).apply {
                marginStart =
                    dp(7)
            }
        )

        stateColumn.addView(
            chips
        )

        card.addView(
            stateColumn
        )

        return card
    }

    private fun executionCard():
        View {

        val card =
            panel(
                20
            )

        card.addView(
            smallSectionTitle(
                "СЕЙЧАС AYANA"
            )
        )

        card.addView(
            TextView(this).apply {

                text =
                    AyanaVoiceService
                        .currentStatusText

                textSize =
                    14f

                setTextColor(
                    Color.WHITE
                )

                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )

                setPadding(
                    0,
                    dp(10),
                    0,
                    dp(9)
                )
            }
        )

        val steps =
            when (
                AyanaVoiceService
                    .currentStatusState
            ) {

                AyanaVoiceService.STATE_THINKING ->
                    listOf(
                        "1  Команда получена",
                        "2  Планирую следующий шаг",
                        "3  Проверяю результат"
                    )

                AyanaVoiceService.STATE_COMMAND ->
                    listOf(
                        "1  Слышу обращение",
                        "2  Распознаю команду",
                        "3  Готовлю действие"
                    )

                AyanaVoiceService.STATE_SPEAKING ->
                    listOf(
                        "1  Действие завершено",
                        "2  Формирую ответ",
                        "3  Возвращаюсь к ожиданию"
                    )

                else ->
                    listOf(
                        "1  Жду обращение «Аяна»",
                        "2  Готова читать экран",
                        "3  Готова выполнять действия"
                    )
            }

        steps.forEachIndexed {
            index,
            text ->

            card.addView(
                agentStepRow(
                    text,
                    index == 0
                )
            )
        }

        return card
    }

    private fun servicesCompactCard():
        View {

        val card =
            panel(
                20
            )

        val titleRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        titleRow.addView(
            smallSectionTitle(
                "СТАТУС СЕРВИСОВ"
            ),
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        titleRow.addView(
            TextView(this).apply {

                text =
                    "${diagnosticsPassed()}/7"

                textSize =
                    12f

                setTextColor(
                    Color.parseColor("#86EFAC")
                )
            }
        )

        card.addView(
            titleRow
        )

        listOf(
            Triple(
                "Микрофон",
                checkSelfPermissionCompat(
                    Manifest.permission.RECORD_AUDIO
                ),
                "Голос"
            ),
            Triple(
                "Accessibility",
                isAccessibilityEnabled(),
                "Экран"
            ),
            Triple(
                "Agent Core",
                AyanaVoiceService.isRunning,
                "ИИ"
            ),
            Triple(
                "Напоминания",
                taskScheduler.canScheduleExact(),
                "Задачи"
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
                taskStore
                    .getFutureTasks()
                    .size
                    .toString(),
                "задач"
            ),
            equalCardParams()
        )

        row.addView(
            compactMetric(
                memoryStore
                    .count()
                    .toString(),
                "в памяти"
            ),
            equalCardParams(
                left =
                    8
            )
        )

        row.addView(
            compactMetric(
                diagnosticsPassed()
                    .toString(),
                "сервисов"
            ),
            equalCardParams(
                left =
                    8
            )
        )

        card.addView(
            row
        )

        val actions =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                setPadding(
                    0,
                    dp(12),
                    0,
                    0
                )
            }

        actions.addView(
            smallAction(
                "Задачи"
            ) {
                switchPage(
                    Page.TASKS
                )
            }
        )

        actions.addView(
            smallAction(
                "Память"
            ) {
                switchPage(
                    Page.MEMORY
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
            ).apply {
                marginStart =
                    dp(8)
            }
        )

        actions.addView(
            smallAction(
                "Проверка"
            ) {
                switchPage(
                    Page.DIAGNOSTICS
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
            ).apply {
                marginStart =
                    dp(8)
            }
        )

        card.addView(
            actions
        )

        return card
    }

    private fun controlFooterStrip():
        View {

        val card =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(16),
                    dp(12),
                    dp(16),
                    dp(12)
                )

                background =
                    panelDrawable(
                        corner =
                            20,
                        stroke =
                            "#252F4C"
                    )
            }

        card.addView(
            TextView(this).apply {

                text =
                    "СОСТОЯНИЯ"

                textSize =
                    10.5f

                setTextColor(
                    Color.parseColor("#8B7DFF")
                )

                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }
        )

        val states =
            listOf(
                "Слушаю" to "#38BDF8",
                "Думаю" to "#8B5CF6",
                "Выполняю" to "#6366F1",
                "Говорю" to "#22D3EE",
                "Ошибка" to "#EF4444"
            )

        states.forEach {
            item ->

            val chip =
                LinearLayout(this).apply {

                    orientation =
                        LinearLayout.HORIZONTAL

                    gravity =
                        Gravity.CENTER_VERTICAL

                    setPadding(
                        dp(10),
                        dp(6),
                        dp(10),
                        dp(6)
                    )
                }

            chip.addView(
                View(this).apply {

                    background =
                        circleDrawable(
                            Color.parseColor(
                                item.second
                            )
                        )
                },
                LinearLayout.LayoutParams(
                    dp(9),
                    dp(9)
                ).apply {
                    marginEnd =
                        dp(6)
                }
            )

            chip.addView(
                TextView(this).apply {

                    text =
                        item.first

                    textSize =
                        10.5f

                    setTextColor(
                        Color.parseColor("#9BAAC0")
                    )
                }
            )

            card.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart =
                        dp(10)
                }
            )
        }

        card.addView(
            Space(this),
            LinearLayout.LayoutParams(
                0,
                1,
                1f
            )
        )

        card.addView(
            TextView(this).apply {

                text =
                    "AYANA работает, чтобы вы жили проще"

                textSize =
                    10.5f

                setTextColor(
                    Color.parseColor("#65758F")
                )
            }
        )

        return card
    }

    private fun statusChip(
        label: String
    ): View {

        return TextView(this).apply {

            text =
                "✓  $label"

            textSize =
                10.5f

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
                    11.5f

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
                    11.5f

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
                    9.5f

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
                    20f

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
                    9.5f

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
                        16f

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
                        12.5f

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
                        13f

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
                            13.5f

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
                    "Accessibility",
                    isAccessibilityEnabled(),
                    "Управление интерфейсом Android"
                ),
                Triple(
                    "Голосовой сервис",
                    AyanaVoiceService.isRunning,
                    "Wake-word и фоновые команды"
                ),
                Triple(
                    "Mini-Orb",
                    !ayanaPreferences.miniOrbEnabled ||
                        overlayPermissionGranted(),
                    if (
                        ayanaPreferences.miniOrbEnabled
                    ) {
                        "Плавающий интерфейс поверх приложений"
                    } else {
                        "Отключён пользователем"
                    }
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
                    14f

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
                "Плавающий Orb",
                when {
                    !overlayPermissionGranted() ->
                        "Нужен системный доступ «Поверх других приложений»"
                    ayanaPreferences.miniOrbEnabled ->
                        "Включён • нажмите, чтобы отключить"
                    else ->
                        "Выключен • нажмите, чтобы включить"
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
                "Accessibility",
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
                    dp(14),
                    dp(12),
                    dp(14),
                    dp(12)
                )

                background =
                    panelDrawable(
                        corner =
                            20,
                        stroke =
                            "#283650"
                    )
            }

        val inputRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        textInput =
            EditText(this).apply {

                this.hint =
                    "Введите команду…"

                textSize =
                    14f

                setTextColor(
                    Color.WHITE
                )

                setHintTextColor(
                    Color.parseColor(
                        "#66758B"
                    )
                )

                isSingleLine =
                    true

                imeOptions =
                    EditorInfo.IME_ACTION_SEND

                background =
                    softDrawable(
                        "#0A101B",
                        "#26344C",
                        16
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
                    22f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.WHITE
                )

                background =
                    GradientDrawable(
                        GradientDrawable
                            .Orientation
                            .TL_BR,
                        intArrayOf(
                            Color.parseColor(
                                "#6655E8"
                            ),
                            Color.parseColor(
                                "#3867DE"
                            )
                        )
                    ).apply {

                        cornerRadius =
                            dp(16)
                                .toFloat()
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
                    dp(14),
                    dp(12),
                    dp(14),
                    dp(12)
                )

                background =
                    softDrawable(
                        "#101426",
                        "#39345E",
                        16
                    )
            }

        textAnswer =
            TextView(this).apply {

                textSize =
                    13.5f

                setTextColor(
                    Color.parseColor(
                        "#DDE6F2"
                    )
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
                    dp(9)
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

    private fun stopAyanaService() {

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

            stopService(
                Intent(
                    this,
                    AyanaVoiceService::class.java
                )
            )
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

        if (
            ::statusText.isInitialized
        ) {

            statusText.text =
                text
        }

        if (
            ::statusDot.isInitialized
        ) {

            statusDot.background =
                circleDrawable(
                    stateColor(
                        state
                    )
                )
        }

        if (
            ::orbText.isInitialized
        ) {

            orbText.background =
                orbDrawable(
                    state
                )
        }
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

            AyanaVoiceService.STATE_SPEAKING ->
                "Говорю"

            AyanaVoiceService.STATE_TEXT ->
                "Текстовый ответ"

            AyanaVoiceService.STATE_ERROR ->
                "Нужна помощь"

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

                AyanaVoiceService.STATE_SPEAKING ->
                    "#6366F1"

                AyanaVoiceService.STATE_TEXT ->
                    "#67E8F9"

                AyanaVoiceService.STATE_ERROR ->
                    "#EF4444"

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
                        14f

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
                        22f

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
                        13f

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
                        10.5f

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
                    10.5f

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
                    27f

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
                    11.5f

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
                    14f

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
                    11.5f

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
                    12f

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
                    14f

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
                    11.5f

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
                    26f

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
                        25f

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
                        12.5f

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
                    16f

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
                    12.5f

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
                10.5f

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
                20f

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
                11.5f

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
            GradientDrawable
                .Orientation
                .TL_BR,
            intArrayOf(
                Color.parseColor(
                    "#0D131F"
                ),
                Color.parseColor(
                    "#101826"
                )
            )
        ).apply {

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

        if (
            overlayPermissionGranted()
        ) {

            ayanaPreferences
                .miniOrbEnabled =
                !ayanaPreferences
                    .miniOrbEnabled

            refreshMiniOrb()

            renderSettings()

            return
        }

        ayanaPreferences
            .miniOrbEnabled =
            true

        try {

            startActivity(
                Intent(
                    Settings
                        .ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse(
                        "package:$packageName"
                    )
                )
            )

        } catch (_: Exception) {

            startActivity(
                Intent(
                    Settings
                        .ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse(
                        "package:$packageName"
                    )
                )
            )
        }
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
