package kg.autonomous.agent

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var stopButton: Button

    private lateinit var textModeButton: TextView
    private lateinit var textPanel: LinearLayout
    private lateinit var textInput: EditText
    private lateinit var textAnswer: TextView
    private lateinit var answerCard: LinearLayout

    private var receiverRegistered = false
    private var textModeVisible = false

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val microphoneGranted =
                permissions[Manifest.permission.RECORD_AUDIO] == true ||
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
        }

    private val statusReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                val text =
                    intent?.getStringExtra(
                        AyanaVoiceService.EXTRA_STATUS_TEXT
                    ) ?: return

                val state =
                    intent.getStringExtra(
                        AyanaVoiceService.EXTRA_STATUS_STATE
                    ) ?: AyanaVoiceService.STATE_LISTENING

                if (
                    state ==
                    AyanaVoiceService.STATE_TEXT
                ) {
                    showTextAnswer(text)
                }

                setStatus(
                    text,
                    state
                )
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setFinishOnTouchOutside(false)

        window.setBackgroundDrawableResource(
            android.R.color.transparent
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
        )

        window.attributes =
            window.attributes.apply {
                dimAmount = 0.28f
            }

        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        buildCompactInterface()

        requestNeededPermissionsAndStart()
    }

    override fun onStart() {
        super.onStart()

        if (!receiverRegistered) {

            val filter =
                IntentFilter(
                    AyanaVoiceService.ACTION_STATUS
                )

            if (Build.VERSION.SDK_INT >= 33) {

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

            receiverRegistered = true
        }

        if (AyanaVoiceService.isRunning) {

            setStatus(
                AyanaVoiceService.currentStatusText,
                AyanaVoiceService.currentStatusState
            )
        }
    }

    override fun onStop() {

        if (receiverRegistered) {

            try {
                unregisterReceiver(
                    statusReceiver
                )
            } catch (_: Exception) {
            }

            receiverRegistered = false
        }

        super.onStop()
    }

    override fun onResume() {
        super.onResume()

        resizeWindow()
    }

    private fun resizeWindow() {

        val width =
            min(
                dp(430),
                resources
                    .displayMetrics
                    .widthPixels -
                    dp(28)
            )

        window.setLayout(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        window.setGravity(
            Gravity.CENTER
        )
    }

    private fun buildCompactInterface() {

        val root =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER_HORIZONTAL

                setPadding(
                    dp(22),
                    dp(20),
                    dp(22),
                    dp(20)
                )

                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.parseColor("#0E1626"),
                            Color.parseColor("#17243B")
                        )
                    ).apply {

                        cornerRadius =
                            dp(28).toFloat()

                        setStroke(
                            dp(1),
                            Color.parseColor("#2C3C5A")
                        )
                    }
            }

        // =====================================================
        // TOP BAR
        // =====================================================

        val topRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
            }

        val titleBlock =
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

        val title =
            TextView(this).apply {

                text = "AYANA AI"
                textSize = 23f

                setTextColor(
                    Color.WHITE
                )

                typeface =
                    android.graphics.Typeface.DEFAULT_BOLD
            }

        val subtitle =
            TextView(this).apply {

                text =
                    "Персональный AI-агент"

                textSize = 12.5f

                setTextColor(
                    Color.parseColor("#93A4BD")
                )

                setPadding(
                    0,
                    dp(3),
                    0,
                    0
                )
            }

        titleBlock.addView(title)
        titleBlock.addView(subtitle)

        textModeButton =
            makeRoundTopButton(
                symbol = "⌨"
            ).apply {

                contentDescription =
                    "Текстовая команда"

                setOnClickListener {
                    toggleTextMode()
                }
            }

        val settingsButton =
            makeRoundTopButton(
                symbol = "⚙"
            ).apply {

                contentDescription =
                    "Настройки AYANA"

                setOnClickListener {

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
                }
            }

        topRow.addView(titleBlock)
        topRow.addView(textModeButton)
        topRow.addView(
            settingsButton,
            LinearLayout.LayoutParams(
                dp(46),
                dp(46)
            ).apply {
                marginStart = dp(9)
            }
        )

        // =====================================================
        // AYANA ORB
        // =====================================================

        val orb =
            TextView(this).apply {

                text = "A"
                textSize = 43f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.WHITE
                )

                typeface =
                    android.graphics.Typeface.DEFAULT_BOLD

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(108),
                        dp(108)
                    ).apply {

                        topMargin =
                            dp(18)

                        bottomMargin =
                            dp(12)
                    }

                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.parseColor("#7759FF"),
                            Color.parseColor("#2CC7EE")
                        )
                    ).apply {

                        shape =
                            GradientDrawable.OVAL

                        setStroke(
                            dp(5),
                            Color.parseColor("#243554")
                        )
                    }
            }

        // =====================================================
        // STATUS
        // =====================================================

        val statusRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    dp(10),
                    dp(5),
                    dp(10),
                    dp(5)
                )
            }

        statusDot =
            View(this).apply {

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(10),
                        dp(10)
                    ).apply {

                        marginEnd =
                            dp(10)
                    }

                background =
                    circleDrawable(
                        Color.parseColor("#4ADE80")
                    )
            }

        statusText =
            TextView(this).apply {

                text =
                    "Запускаю AYANA…"

                textSize = 15.5f

                gravity =
                    Gravity.CENTER_VERTICAL

                setTextColor(
                    Color.parseColor("#E7EEF9")
                )
            }

        statusRow.addView(
            statusDot
        )

        statusRow.addView(
            statusText
        )

        val hint =
            TextView(this).apply {

                text =
                    "Скажите «Аяна…» или нажмите ⌨"

                textSize = 12.5f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.parseColor("#8FA0B8")
                )

                setPadding(
                    0,
                    dp(2),
                    0,
                    dp(12)
                )
            }

        // =====================================================
        // TEXT MODE
        // =====================================================

        textPanel =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                visibility =
                    View.GONE

                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {

                        bottomMargin =
                            dp(12)
                    }
            }

        val inputRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                background =
                    GradientDrawable().apply {

                        cornerRadius =
                            dp(16).toFloat()

                        setColor(
                            Color.parseColor("#111C2F")
                        )

                        setStroke(
                            dp(1),
                            Color.parseColor("#334768")
                        )
                    }

                setPadding(
                    dp(6),
                    dp(4),
                    dp(5),
                    dp(4)
                )
            }

        textInput =
            EditText(this).apply {

                this.hint =
                    "Введите команду…"

                textSize = 15f

                setSingleLine(true)

                imeOptions =
                    EditorInfo.IME_ACTION_SEND

                setTextColor(
                    Color.WHITE
                )

                setHintTextColor(
                    Color.parseColor("#7F91AA")
                )

                background =
                    null

                setPadding(
                    dp(12),
                    dp(8),
                    dp(8),
                    dp(8)
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        dp(48),
                        1f
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

        val sendButton =
            TextView(this).apply {

                text = "➤"
                textSize = 23f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.WHITE
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(46),
                        dp(46)
                    )

                background =
                    GradientDrawable().apply {

                        shape =
                            GradientDrawable.OVAL

                        setColor(
                            Color.parseColor("#4C6FFF")
                        )
                    }

                setOnClickListener {
                    sendTextCommand()
                }
            }

        inputRow.addView(
            textInput
        )

        inputRow.addView(
            sendButton
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
                    GradientDrawable().apply {

                        cornerRadius =
                            dp(15).toFloat()

                        setColor(
                            Color.parseColor("#142137")
                        )

                        setStroke(
                            dp(1),
                            Color.parseColor("#304563")
                        )
                    }

                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {

                        topMargin =
                            dp(9)
                    }
            }

        val answerLabel =
            TextView(this).apply {

                text = "AYANA"

                textSize = 11.5f

                setTextColor(
                    Color.parseColor("#79D8F3")
                )

                typeface =
                    android.graphics.Typeface.DEFAULT_BOLD
            }

        textAnswer =
            TextView(this).apply {

                text = ""

                textSize = 14f

                setTextColor(
                    Color.parseColor("#E6EDF8")
                )

                setPadding(
                    0,
                    dp(5),
                    0,
                    0
                )

                maxLines = 9
            }

        answerCard.addView(
            answerLabel
        )

        answerCard.addView(
            textAnswer
        )

        textPanel.addView(
            inputRow
        )

        textPanel.addView(
            answerCard
        )

        // =====================================================
        // STOP
        // =====================================================

        stopButton =
            Button(this).apply {

                text =
                    "■  ОСТАНОВИТЬ AYANA"

                textSize = 14f
                isAllCaps = false

                setTextColor(
                    Color.WHITE
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(52)
                    )

                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(
                            Color.parseColor("#AD233E"),
                            Color.parseColor("#D23B55")
                        )
                    ).apply {

                        cornerRadius =
                            dp(16).toFloat()
                    }

                setOnClickListener {

                    stopAyanaService()

                    setStatus(
                        "AYANA остановлена",
                        AyanaVoiceService.STATE_STOPPED
                    )

                    isEnabled =
                        false

                    alpha =
                        0.55f
                }
            }

        root.addView(
            topRow
        )

        root.addView(
            orb
        )

        root.addView(
            statusRow
        )

        root.addView(
            hint
        )

        root.addView(
            textPanel
        )

        root.addView(
            stopButton
        )

        setContentView(
            root
        )
    }

    private fun makeRoundTopButton(
        symbol: String
    ): TextView {

        return TextView(this).apply {

            text = symbol
            textSize = 23f

            gravity =
                Gravity.CENTER

            setTextColor(
                Color.parseColor("#DDE8FF")
            )

            layoutParams =
                LinearLayout.LayoutParams(
                    dp(46),
                    dp(46)
                )

            background =
                GradientDrawable().apply {

                    shape =
                        GradientDrawable.OVAL

                    setColor(
                        Color.parseColor("#1B2941")
                    )

                    setStroke(
                        dp(1),
                        Color.parseColor("#354A6C")
                    )
                }
        }
    }

    private fun toggleTextMode() {

        textModeVisible =
            !textModeVisible

        if (textModeVisible) {

            textPanel.visibility =
                View.VISIBLE

            textModeButton.setTextColor(
                Color.parseColor("#77D9F3")
            )

            textInput.requestFocus()

            textInput.postDelayed(
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
                120L
            )

        } else {

            textPanel.visibility =
                View.GONE

            textModeButton.setTextColor(
                Color.parseColor("#DDE8FF")
            )

            hideKeyboard()
        }

        resizeWindow()
    }

    private fun sendTextCommand() {

        val command =
            textInput
                .text
                .toString()
                .trim()

        if (command.isBlank()) {
            return
        }

        textInput.setText("")

        answerCard.visibility =
            View.GONE

        setStatus(
            "Думаю…",
            AyanaVoiceService.STATE_THINKING
        )

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
                AyanaVoiceService.isRunning
            ) {

                startService(
                    intent
                )

            } else {

                startAyanaService()

                textInput.postDelayed(
                    {
                        try {
                            startService(
                                intent
                            )
                        } catch (_: Exception) {
                        }
                    },
                    500L
                )
            }

        } catch (_: Exception) {

            setStatus(
                "Не удалось отправить команду",
                AyanaVoiceService.STATE_ERROR
            )
        }
    }

    private fun showTextAnswer(
        answer: String
    ) {

        if (!textModeVisible) {

            textModeVisible =
                true

            textPanel.visibility =
                View.VISIBLE

            textModeButton.setTextColor(
                Color.parseColor("#77D9F3")
            )
        }

        textAnswer.text =
            answer

        answerCard.visibility =
            View.VISIBLE

        resizeWindow()
    }

    private fun hideKeyboard() {

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
    }

    private fun setStatus(
        text: String,
        state: String
    ) {

        statusText.text =
            text

        val color =
            when (state) {

                AyanaVoiceService.STATE_LISTENING ->
                    "#4ADE80"

                AyanaVoiceService.STATE_COMMAND ->
                    "#38BDF8"

                AyanaVoiceService.STATE_THINKING ->
                    "#A78BFA"

                AyanaVoiceService.STATE_SPEAKING ->
                    "#F59E0B"

                AyanaVoiceService.STATE_TEXT ->
                    "#67E8F9"

                AyanaVoiceService.STATE_ERROR ->
                    "#FB7185"

                AyanaVoiceService.STATE_STOPPED ->
                    "#94A3B8"

                else ->
                    "#CBD5E1"
            }

        statusDot.background =
            circleDrawable(
                Color.parseColor(
                    color
                )
            )
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

    private fun checkSelfPermissionCompat(
        permission: String
    ): Boolean {

        return Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(
                permission
            ) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources
                    .displayMetrics
                    .density
            ).toInt()
    }
}
