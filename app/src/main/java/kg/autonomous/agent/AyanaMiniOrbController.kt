package kg.autonomous.agent

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.sin

/**
 * AYANA Floating Orb v4.1 — TRANSPARENT CORE / SMOOTH IDLE.
 *
 * Rules:
 * 1) exactly one overlay View per app process;
 * 2) STOPPED always removes the overlay;
 * 3) repeated refresh() only updates the existing View;
 * 4) drag position is persisted;
 * 5) tapping the Orb opens AYANA.
 * 6) the overlay background and orb body are fully transparent; only the luminous core and arcs are rendered.
 * 7) idle motion is smoother/faster while remaining frame-capped to protect text input and IME latency.
 *
 * The service owns WHEN the Orb exists. This controller only owns HOW it is
 * rendered. It never starts/stops AyanaVoiceService itself.
 */
class AyanaMiniOrbController(
    context: Context
) {

    private val appContext =
        context.applicationContext

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val prefs =
        appContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun canDrawOverlays():
        Boolean {

        return if (
            Build.VERSION.SDK_INT >= 23
        ) {
            Settings.canDrawOverlays(
                appContext
            )
        } else {
            true
        }
    }

    fun refresh(
        enabled: Boolean,
        state: String
    ) {

        runOnMain {

            if (
                !enabled ||
                state == AyanaVoiceService.STATE_STOPPED ||
                !canDrawOverlays()
            ) {
                hideInternal()
                return@runOnMain
            }

            synchronized(
                LOCK
            ) {

                val existing =
                    sharedView

                if (
                    existing != null &&
                    existing.isAttachedToWindow
                ) {

                    existing.setAyanaState(
                        state
                    )

                    sharedParams
                        ?.let {
                            clampToScreen(
                                it
                            )
                        }

                    return@synchronized
                }

                // A detached/stale reference must never be reused.
                sharedView =
                    null

                sharedParams =
                    null

                showInternal(
                    state
                )
            }
        }
    }

    fun updateState(
        state: String
    ) {

        refresh(
            enabled =
                state != AyanaVoiceService.STATE_STOPPED,
            state = state
        )
    }

    fun hide() {

        runOnMain {
            hideInternal()
        }
    }

    private fun showInternal(
        state: String
    ) {

        if (
            !canDrawOverlays()
        ) {
            return
        }

        val wm =
            windowManager()

        val size =
            dp(
                ORB_SIZE_DP
            )

        val params =
            WindowManager.LayoutParams(
                size,
                size,
                if (
                    Build.VERSION.SDK_INT >= 26
                ) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {

                gravity =
                    Gravity.TOP or
                        Gravity.START

                val savedX =
                    prefs.getInt(
                        PREF_X,
                        Int.MIN_VALUE
                    )

                val savedY =
                    prefs.getInt(
                        PREF_Y,
                        Int.MIN_VALUE
                    )

                if (
                    savedX != Int.MIN_VALUE &&
                    savedY != Int.MIN_VALUE
                ) {

                    x =
                        savedX

                    y =
                        savedY

                } else {

                    x =
                        (
                            screenWidth() -
                                size -
                                dp(
                                    DEFAULT_MARGIN_DP
                                )
                            )
                            .coerceAtLeast(
                                0
                            )

                    y =
                        dp(
                            DEFAULT_Y_DP
                        )
                }

                clampToScreen(
                    this
                )
            }

        val view =
            FloatingOrbView(
                appContext
            ).apply {

                setAyanaState(
                    state
                )

                contentDescription =
                    "Orb AYANA. Перетащите для перемещения. Нажмите, чтобы открыть AYANA."
            }

        attachTouchBehavior(
            view,
            params
        )

        try {

            wm.addView(
                view,
                params
            )

            sharedView =
                view

            sharedParams =
                params

        } catch (_: Exception) {

            // Never keep a reference to a View that WindowManager rejected.
            sharedView =
                null

            sharedParams =
                null
        }
    }

    private fun hideInternal() {

        synchronized(
            LOCK
        ) {

            val view =
                sharedView

            if (
                view != null
            ) {

                try {

                    windowManager()
                        .removeViewImmediate(
                            view
                        )

                } catch (_: Exception) {
                }
            }

            sharedView =
                null

            sharedParams =
                null
        }
    }

    private fun attachTouchBehavior(
        view: View,
        params: WindowManager.LayoutParams
    ) {

        var downRawX =
            0f

        var downRawY =
            0f

        var startX =
            0

        var startY =
            0

        var moved =
            false

        view.setOnTouchListener {
            _,
            event ->

            when (
                event.actionMasked
            ) {

                MotionEvent.ACTION_DOWN -> {

                    downRawX =
                        event.rawX

                    downRawY =
                        event.rawY

                    startX =
                        params.x

                    startY =
                        params.y

                    moved =
                        false

                    true
                }

                MotionEvent.ACTION_MOVE -> {

                    val dx =
                        event.rawX -
                            downRawX

                    val dy =
                        event.rawY -
                            downRawY

                    if (
                        abs(
                            dx
                        ) >
                        dp(
                            MOVE_THRESHOLD_DP
                        ) ||
                        abs(
                            dy
                        ) >
                        dp(
                            MOVE_THRESHOLD_DP
                        )
                    ) {

                        moved =
                            true
                    }

                    params.x =
                        (
                            startX +
                                dx
                            )
                            .toInt()

                    params.y =
                        (
                            startY +
                                dy
                            )
                            .toInt()

                    clampToScreen(
                        params
                    )

                    try {

                        if (
                            view.isAttachedToWindow
                        ) {

                            windowManager()
                                .updateViewLayout(
                                    view,
                                    params
                                )
                        }

                    } catch (_: Exception) {
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {

                    if (
                        moved
                    ) {

                        savePosition(
                            params
                        )

                    } else {

                        openAyana()
                    }

                    true
                }

                MotionEvent.ACTION_CANCEL -> {

                    if (
                        moved
                    ) {

                        savePosition(
                            params
                        )
                    }

                    true
                }

                else ->
                    false
            }
        }
    }

    private fun savePosition(
        params: WindowManager.LayoutParams
    ) {

        prefs
            .edit()
            .putInt(
                PREF_X,
                params.x
            )
            .putInt(
                PREF_Y,
                params.y
            )
            .apply()
    }

    private fun clampToScreen(
        params: WindowManager.LayoutParams
    ) {

        val maxX =
            (
                screenWidth() -
                    params.width
                )
                .coerceAtLeast(
                    0
                )

        val maxY =
            (
                screenHeight() -
                    params.height
                )
                .coerceAtLeast(
                    0
                )

        params.x =
            params.x
                .coerceIn(
                    0,
                    maxX
                )

        params.y =
            params.y
                .coerceIn(
                    0,
                    maxY
                )
    }

    private fun openAyana() {

        try {

            appContext.startActivity(
                Intent(
                    appContext,
                    MainActivity::class.java
                ).apply {

                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
            )

        } catch (_: Exception) {
        }
    }

    private fun windowManager():
        WindowManager {

        return appContext.getSystemService(
            WindowManager::class.java
        )
    }

    private fun screenWidth():
        Int {

        return appContext
            .resources
            .displayMetrics
            .widthPixels
    }

    private fun screenHeight():
        Int {

        return appContext
            .resources
            .displayMetrics
            .heightPixels
    }

    private fun runOnMain(
        action: () -> Unit
    ) {

        if (
            Looper.myLooper() ==
            Looper.getMainLooper()
        ) {

            action()

        } else {

            mainHandler.post {
                action()
            }
        }
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                appContext
                    .resources
                    .displayMetrics
                    .density
            )
            .toInt()
    }

    /**
     * Static nested View: it does not retain a controller instance.
     * This is important because several short-lived controller objects may call
     * refresh(), while the actual overlay remains one process-wide View.
     */
    private class FloatingOrbView(
        context: Context
    ) : View(context) {

        private val corePaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            )

        private val ringPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                style =
                    Paint.Style.STROKE

                strokeWidth =
                    3f *
                        resources
                            .displayMetrics
                            .density
                strokeCap =
                    Paint.Cap.ROUND
            }

        private val arcBounds =
            RectF()

        private var accentColor =
            Color.rgb(
                125,
                211,
                252
            )

        private var attached =
            false

        private var ayanaState =
            AyanaVoiceService.STATE_LISTENING

        private var terminalFlashUntil =
            0L

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            attached =
                true
            postInvalidateDelayed(
                FRAME_LISTENING_MS
            )
        }

        override fun onDetachedFromWindow() {
            attached =
                false
            super.onDetachedFromWindow()
        }

        fun setAyanaState(
            state: String
        ) {

            ayanaState =
                state

            accentColor =
                strokeFor(
                    state
                )

            if (
                state ==
                AyanaVoiceService.STATE_SUCCESS ||
                state ==
                AyanaVoiceService.STATE_ERROR ||
                state ==
                AyanaVoiceService.STATE_CANCELLED
            ) {

                terminalFlashUntil =
                    SystemClock.uptimeMillis() +
                        TERMINAL_FLASH_MS
            }

            invalidate()
        }

        override fun onDraw(
            canvas: Canvas
        ) {

            super.onDraw(
                canvas
            )

            val now =
                SystemClock.uptimeMillis()

            val cx =
                width /
                    2f

            val cy =
                height /
                    2f

            val baseRadius =
                minOf(
                    width,
                    height
                ) *
                    0.245f

            val activeMotion =
                when (
                    ayanaState
                ) {
                    AyanaVoiceService.STATE_LISTENING,
                    AyanaVoiceService.STATE_COMMAND,
                    AyanaVoiceService.STATE_THINKING,
                    AyanaVoiceService.STATE_EXECUTING,
                    AyanaVoiceService.STATE_SPEAKING ->
                        true

                    else ->
                        false
                }

            val speedDegPerMs =
                when (
                    ayanaState
                ) {
                    AyanaVoiceService.STATE_COMMAND ->
                        0.085f

                    AyanaVoiceService.STATE_THINKING ->
                        0.055f

                    AyanaVoiceService.STATE_EXECUTING ->
                        0.105f

                    AyanaVoiceService.STATE_SPEAKING ->
                        0.075f

                    AyanaVoiceService.STATE_LISTENING ->
                        0.050f

                    else ->
                        0f
                }

            val breatheAmount =
                when (
                    ayanaState
                ) {
                    AyanaVoiceService.STATE_COMMAND,
                    AyanaVoiceService.STATE_SPEAKING ->
                        0.12f

                    AyanaVoiceService.STATE_THINKING,
                    AyanaVoiceService.STATE_EXECUTING ->
                        0.08f

                    AyanaVoiceService.STATE_LISTENING ->
                        0.045f

                    else ->
                        0f
                }

            val pulse =
                if (activeMotion) {
                    (
                        sin(
                            now /
                                360.0
                        ) *
                            breatheAmount
                        )
                        .toFloat()
                } else {
                    0f
                }

            // v4.1: fully transparent orb body. Never paint a solid backing
            // disk behind the symbol. The overlay remains visually open over
            // Word, Chrome, Settings, and other apps. Keep the render path
            // allocation-free so the smoother idle animation does not bring
            // back the IME/text-input regression.
            corePaint.shader =
                null

            val accent =
                accentColor

            // Very light outer nucleus glow; this is translucent color only,
            // not a background fill.
            corePaint.color =
                accent

            corePaint.alpha =
                48

            canvas.drawCircle(
                cx,
                cy,
                baseRadius *
                    (
                        0.42f +
                            pulse *
                                0.35f
                        ),
                corePaint
            )

            // Luminous central AYANA Core nucleus.
            corePaint.alpha =
                if (
                    now <
                    terminalFlashUntil
                ) {
                    245
                } else {
                    225
                }

            canvas.drawCircle(
                cx,
                cy,
                baseRadius *
                    (
                        0.20f +
                            pulse *
                                0.55f
                        ),
                corePaint
            )

            ringPaint.color =
                accent

            val rotation =
                (
                    now *
                        speedDegPerMs
                    ) %
                    360f

            for (ring in 0..2) {
                val radius =
                    baseRadius +
                        dpLocal(
                            4 +
                                ring *
                                    6
                        )

                arcBounds.set(
                    cx -
                        radius,
                    cy -
                        radius,
                    cx +
                        radius,
                    cy +
                        radius
                )

                ringPaint.strokeWidth =
                    dpLocal(
                        if (ring == 0) 2 else 1
                    )

                ringPaint.alpha =
                    205 -
                        ring *
                            35

                val direction =
                    if (ring % 2 == 0) {
                        1f
                    } else {
                        -1f
                    }

                val startAngle =
                    rotation *
                        direction +
                        ring *
                            74f

                canvas.drawArc(
                    arcBounds,
                    startAngle,
                    70f -
                        ring *
                            8f,
                    false,
                    ringPaint
                )

                canvas.drawArc(
                    arcBounds,
                    startAngle +
                        174f,
                    34f +
                        ring *
                            5f,
                    false,
                    ringPaint
                )
            }

            if (
                now <
                terminalFlashUntil
            ) {
                val remaining =
                    (
                        terminalFlashUntil -
                            now
                        )
                        .coerceAtLeast(
                            0L
                        )

                val phase =
                    1f -
                        remaining /
                            TERMINAL_FLASH_MS.toFloat()

                ringPaint.color =
                    accent

                ringPaint.alpha =
                    (
                        210 *
                            (
                                1f -
                                    phase
                                )
                        )
                        .toInt()
                        .coerceIn(
                            0,
                            210
                        )

                val flashRadius =
                    baseRadius +
                        dpLocal(
                            14
                        ) +
                        dpLocal(
                            9
                        ) *
                            phase

                canvas.drawCircle(
                    cx,
                    cy,
                    flashRadius,
                    ringPaint
                )
            }

            if (
                attached &&
                shouldAnimate(
                    now
                )
            ) {
                postInvalidateDelayed(
                    frameDelayMs(
                        ayanaState
                    )
                )
            }
        }

        private fun frameDelayMs(
            state: String
        ): Long {

            return when (
                state
            ) {
                AyanaVoiceService.STATE_COMMAND,
                AyanaVoiceService.STATE_EXECUTING ->
                    FRAME_ACTIVE_MS

                AyanaVoiceService.STATE_THINKING,
                AyanaVoiceService.STATE_SPEAKING ->
                    FRAME_NORMAL_MS

                AyanaVoiceService.STATE_LISTENING ->
                    FRAME_LISTENING_MS

                else ->
                    FRAME_TERMINAL_MS
            }
        }

        private fun gradientFor(
            state: String
        ): IntArray {

            return when (
                state
            ) {

                AyanaVoiceService.STATE_COMMAND ->
                    intArrayOf(
                        Color.parseColor("#22D3EE"),
                        Color.parseColor("#2563EB"),
                        Color.parseColor("#111827")
                    )

                AyanaVoiceService.STATE_THINKING ->
                    intArrayOf(
                        Color.parseColor("#A78BFA"),
                        Color.parseColor("#6D28D9"),
                        Color.parseColor("#111827")
                    )

                AyanaVoiceService.STATE_EXECUTING ->
                    intArrayOf(
                        Color.parseColor("#2DD4BF"),
                        Color.parseColor("#0E7490"),
                        Color.parseColor("#0F172A")
                    )

                AyanaVoiceService.STATE_SUCCESS ->
                    intArrayOf(
                        Color.parseColor("#4ADE80"),
                        Color.parseColor("#15803D"),
                        Color.parseColor("#0F172A")
                    )

                AyanaVoiceService.STATE_ERROR ->
                    intArrayOf(
                        Color.parseColor("#F87171"),
                        Color.parseColor("#B91C1C"),
                        Color.parseColor("#111827")
                    )

                AyanaVoiceService.STATE_CANCELLED ->
                    intArrayOf(
                        Color.parseColor("#FBBF24"),
                        Color.parseColor("#D97706"),
                        Color.parseColor("#111827")
                    )

                AyanaVoiceService.STATE_SPEAKING ->
                    intArrayOf(
                        Color.parseColor("#818CF8"),
                        Color.parseColor("#4F46E5"),
                        Color.parseColor("#111827")
                    )

                else ->
                    intArrayOf(
                        Color.parseColor("#38BDF8"),
                        Color.parseColor("#2563EB"),
                        Color.parseColor("#0F172A")
                    )
            }
        }

        private fun strokeFor(
            state: String
        ): Int {

            return Color.parseColor(
                when (
                    state
                ) {

                    AyanaVoiceService.STATE_COMMAND ->
                        "#67E8F9"

                    AyanaVoiceService.STATE_THINKING ->
                        "#C4B5FD"

                    AyanaVoiceService.STATE_EXECUTING ->
                        "#5EEAD4"

                    AyanaVoiceService.STATE_SUCCESS ->
                        "#86EFAC"

                    AyanaVoiceService.STATE_ERROR ->
                        "#FCA5A5"

                    AyanaVoiceService.STATE_CANCELLED ->
                        "#FCD34D"

                    AyanaVoiceService.STATE_SPEAKING ->
                        "#A5B4FC"

                    else ->
                        "#7DD3FC"
                }
            )
        }

        private fun shouldAnimate(
            now: Long
        ): Boolean {

            val active =
                when (
                    ayanaState
                ) {
                    AyanaVoiceService.STATE_LISTENING,
                    AyanaVoiceService.STATE_COMMAND,
                    AyanaVoiceService.STATE_THINKING,
                    AyanaVoiceService.STATE_EXECUTING,
                    AyanaVoiceService.STATE_SPEAKING ->
                        true

                    else ->
                        false
                }

            return active ||
                now <
                terminalFlashUntil
        }

        private fun dpLocal(
            value: Int
        ): Float {

            return value *
                resources
                    .displayMetrics
                    .density
        }
    }

    companion object {

        private const val PREFS_NAME =
            "ayana_floating_orb"

        private const val PREF_X =
            "orb_x"

        private const val PREF_Y =
            "orb_y"

        private const val ORB_SIZE_DP =
            72

        private const val DEFAULT_MARGIN_DP =
            22

        private const val DEFAULT_Y_DP =
            180

        private const val MOVE_THRESHOLD_DP =
            5

        private const val TERMINAL_FLASH_MS =
            1200L

        private const val FRAME_ACTIVE_MS =
            40L

        private const val FRAME_NORMAL_MS =
            55L

        private const val FRAME_LISTENING_MS =
            50L

        private const val FRAME_TERMINAL_MS =
            70L

        private val LOCK =
            Any()

        @Volatile
        private var sharedView:
            FloatingOrbView? = null

        @Volatile
        private var sharedParams:
            WindowManager.LayoutParams? = null
    }
}
