package kg.autonomous.agent

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
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
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var stopButton: Button

    private var receiverRegistered = false

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
                    STATUS_ERROR
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
                    ) ?: STATUS_IDLE

                setStatus(text, state)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
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
                dimAmount = 0.32f
            }

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
                unregisterReceiver(statusReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }

        super.onStop()
    }

    override fun onResume() {
        super.onResume()

        val width =
            min(
                dp(420),
                resources.displayMetrics.widthPixels -
                    dp(32)
            )

        window.setLayout(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        window.setGravity(Gravity.CENTER)
    }

    private fun buildCompactInterface() {

        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                gravity = Gravity.CENTER_HORIZONTAL

                setPadding(
                    dp(24),
                    dp(22),
                    dp(24),
                    dp(22)
                )

                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.parseColor("#101827"),
                            Color.parseColor("#17233A")
                        )
                    ).apply {
                        cornerRadius =
                            dp(30).toFloat()

                        setStroke(
                            dp(1),
                            Color.parseColor("#2A3958")
                        )
                    }
            }

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
                textSize = 24f
                setTextColor(Color.WHITE)
                typeface =
                    android.graphics.Typeface.DEFAULT_BOLD
            }

        val subtitle =
            TextView(this).apply {
                text = "Персональный голосовой помощник"
                textSize = 12.5f
                setTextColor(
                    Color.parseColor("#94A3B8")
                )
                setPadding(0, dp(3), 0, 0)
            }

        titleBlock.addView(title)
        titleBlock.addView(subtitle)

        val settings =
            TextView(this).apply {
                text = "⚙"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(
                    Color.parseColor("#D7E3FF")
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(48),
                        dp(48)
                    )

                background =
                    GradientDrawable().apply {
                        shape =
                            GradientDrawable.OVAL

                        setColor(
                            Color.parseColor("#1D2B45")
                        )

                        setStroke(
                            dp(1),
                            Color.parseColor("#344869")
                        )
                    }

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
        topRow.addView(settings)

        val orb =
            TextView(this).apply {
                text = "A"
                textSize = 48f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                typeface =
                    android.graphics.Typeface.DEFAULT_BOLD

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(124),
                        dp(124)
                    ).apply {
                        topMargin = dp(24)
                        bottomMargin = dp(18)
                    }

                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.parseColor("#7558FF"),
                            Color.parseColor("#36C6F4")
                        )
                    ).apply {
                        shape =
                            GradientDrawable.OVAL

                        setStroke(
                            dp(5),
                            Color.parseColor("#233456")
                        )
                    }
            }

        ObjectAnimator
            .ofFloat(
                orb,
                View.ALPHA,
                0.70f,
                1.0f
            )
            .apply {
                duration = 1800L
                repeatCount =
                    ObjectAnimator.INFINITE
                repeatMode =
                    ObjectAnimator.REVERSE
                interpolator =
                    LinearInterpolator()
                start()
            }

        val statusRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    dp(12),
                    dp(8),
                    dp(12),
                    dp(8)
                )
            }

        statusDot =
            View(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(10),
                        dp(10)
                    ).apply {
                        marginEnd = dp(10)
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

                textSize = 16.5f

                setTextColor(
                    Color.parseColor("#E7EEF9")
                )
            }

        statusRow.addView(statusDot)
        statusRow.addView(statusText)

        val hint =
            TextView(this).apply {
                text =
                    "Скажите: «Аяна…»"

                textSize = 13.5f

                gravity = Gravity.CENTER

                setTextColor(
                    Color.parseColor("#94A3B8")
                )

                setPadding(
                    0,
                    dp(3),
                    0,
                    dp(18)
                )
            }

        stopButton =
            Button(this).apply {
                text = "■  ОСТАНОВИТЬ AYANA"
                textSize = 14f
                isAllCaps = false
                setTextColor(Color.WHITE)

                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(54)
                    )

                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(
                            Color.parseColor("#B4233E"),
                            Color.parseColor("#D13C55")
                        )
                    ).apply {
                        cornerRadius =
                            dp(17).toFloat()
                    }

                setOnClickListener {
                    stopAyanaService()

                    setStatus(
                        "AYANA остановлена",
                        STATUS_STOPPED
                    )

                    isEnabled = false
                    alpha = 0.55f
                }
            }

        root.addView(topRow)
        root.addView(orb)
        root.addView(statusRow)
        root.addView(hint)
        root.addView(stopButton)

        setContentView(root)
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

        if (permissions.isEmpty()) {
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
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            setStatus(
                "Жду: «Аяна»",
                STATUS_LISTENING
            )

        } catch (_: Exception) {
            setStatus(
                "Не удалось запустить AYANA",
                STATUS_ERROR
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
            startService(stopIntent)
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

        statusText.text = text

        val color =
            when (state) {

                STATUS_LISTENING ->
                    "#4ADE80"

                STATUS_COMMAND ->
                    "#38BDF8"

                STATUS_THINKING ->
                    "#A78BFA"

                STATUS_SPEAKING ->
                    "#F59E0B"

                STATUS_ERROR ->
                    "#FB7185"

                STATUS_STOPPED ->
                    "#94A3B8"

                else ->
                    "#CBD5E1"
            }

        statusDot.background =
            circleDrawable(
                Color.parseColor(color)
            )
    }

    private fun circleDrawable(
        color: Int
    ): GradientDrawable {

        return GradientDrawable().apply {
            shape =
                GradientDrawable.OVAL

            setColor(color)
        }
    }

    private fun checkSelfPermissionCompat(
        permission: String
    ): Boolean {

        return Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun dp(value: Int): Int {
        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }

    companion object {
        private const val STATUS_IDLE =
            "idle"

        private const val STATUS_LISTENING =
            "listening"

        private const val STATUS_COMMAND =
            "command"

        private const val STATUS_THINKING =
            "thinking"

        private const val STATUS_SPEAKING =
            "speaking"

        private const val STATUS_ERROR =
            "error"

        private const val STATUS_STOPPED =
            "stopped"
    }
}
