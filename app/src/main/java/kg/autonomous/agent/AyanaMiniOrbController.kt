package kg.autonomous.agent

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
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
 * AYANA Floating Orb v2
 *
 * One process-wide overlay only.
 * - TYPE_APPLICATION_OVERLAY: visible above apps and launcher.
 * - Draggable anywhere on screen.
 * - Position persists across app/service restarts.
 * - Tap opens AYANA.
 * - State-driven animation: listening / command / thinking / executing /
 *   success / speaking / error / stopped.
 *
 * No long-press hide gesture: the Orb should not disappear accidentally.
 */
class AyanaMiniOrbController(
    context: Context
) {

    private val appContext =
        context.applicationContext

    private val windowManager =
        appContext.getSystemService(
            WindowManager::class.java
        )

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val positionPrefs =
        appContext.getSharedPreferences(
            "ayana_floating_orb",
            Context.MODE_PRIVATE
        )

    fun refresh(
        enabled: Boolean,
        state: String
    ) {

        runOnMain {

            if (
                !enabled ||
                !canDrawOverlays()
            ) {
                hideInternal()
                return@runOnMain
            }

            synchronized(
                overlayLock
            ) {

                val current =
                    sharedOrbView

                if (
                    current != null &&
                    current.isAttachedToWindow
                ) {

                    current.setAyanaState(
                        state
                    )

                    sharedLayoutParams
                        ?.let {
                            clampToScreen(
                                it
                            )
                        }

                    return@synchronized
                }

                sharedOrbView =
                    null

                sharedLayoutParams =
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

        runOnMain {

            synchronized(
                overlayLock
            ) {

                val view =
                    sharedOrbView
                        ?: return@synchronized

                if (
                    !view.isAttachedToWindow
                ) {

                    sharedOrbView =
                        null

                    sharedLayoutParams =
                        null

                    return@synchronized
                }

                view.setAyanaState(
                    state
                )
            }
        }
    }

    fun hide() {

        runOnMain {
            hideInternal()
        }
    }

    fun canDrawOverlays():
        Boolean {

        return if (
            Build.VERSION.SDK_INT >= 23
        ) {

            Settings
                .canDrawOverlays(
                    appContext
                )

        } else {

            true
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

        val existing =
            sharedOrbView

        if (
            existing != null &&
            existing.isAttachedToWindow
        ) {

            existing.setAyanaState(
                state
            )

            return
        }

        val size =
            dp(
                ORB_SIZE_DP
            )

        val params =
            WindowManager
                .LayoutParams(
                    size,
                    size,
                    if (
                        Build.VERSION.SDK_INT >= 26
                    ) {
                        WindowManager
                            .LayoutParams
                            .TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager
                            .LayoutParams
                            .TYPE_PHONE
                    },
                    WindowManager
                        .LayoutParams
                        .FLAG_NOT_FOCUSABLE or
                        WindowManager
                            .LayoutParams
                            .FLAG_LAYOUT_NO_LIMITS or
                        WindowManager
                            .LayoutParams
                            .FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {

                    gravity =
                        Gravity.TOP or
                            Gravity.START

                    val savedX =
                        positionPrefs.getInt(
                            PREF_X,
                            Int.MIN_VALUE
                        )

                    val savedY =
                        positionPrefs.getInt(
                            PREF_Y,
                            Int.MIN_VALUE
                        )

                    if (
                        savedX !=
                        Int.MIN_VALUE &&
                        savedY !=
                        Int.MIN_VALUE
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
                    "Orb AYANA. Перетащите, чтобы переместить. Нажмите, чтобы открыть AYANA."
            }

        attachTouchBehavior(
            view,
            params
        )

        try {

            windowManager
                .addView(
                    view,
                    params
                )

            sharedOrbView =
                view

            sharedLayoutParams =
                params

        } catch (_: Exception) {

            sharedOrbView =
                null

            sharedLayoutParams =
                null
        }
    }

    private fun hideInternal() {

        synchronized(
            overlayLock
        ) {

            val view =
                sharedOrbView

            if (
                view != null
            ) {

                try {

                    if (
                        view.isAttachedToWindow
                    ) {

                        windowManager
                            .removeViewImmediate(
                                view
                            )
                    }

                } catch (_: Exception) {
                }
            }

            sharedOrbView =
                null

            sharedLayoutParams =
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

                    updateLayoutSafely(
                        view,
                        params
                    )

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

    private fun updateLayoutSafely(
        view: View,
        params: WindowManager.LayoutParams
    ) {

        try {

            if (
                view.isAttachedToWindow
            ) {

                windowManager
                    .updateViewLayout(
                        view,
                        params
                    )
            }

        } catch (_: Exception) {
        }
    }

    private fun savePosition(
        params: WindowManager.LayoutParams
    ) {

        positionPrefs
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

        val intent =
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

        try {

            appContext
                .startActivity(
                    intent
                )

        } catch (_: Exception) {
        }
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

    private inner class FloatingOrbView(
        context: Context
    ) : View(context) {

        private val corePaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            )

        private val glowPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                style =
                    Paint.Style.STROKE

                strokeWidth =
                    dp(
                        3
                    )
                        .toFloat()
            }

        private val labelPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                color =
                    Color.WHITE

                textAlign =
                    Paint.Align.CENTER

                typeface =
                    android.graphics
                        .Typeface
                        .DEFAULT_BOLD
            }

        private var ayanaState =
            AyanaVoiceService
                .currentStatusState

        private var successFlashUntil =
            0L

        private var errorFlashUntil =
            0L

        fun setAyanaState(
            state: String
        ) {

            ayanaState =
                state

            val now =
                SystemClock
                    .uptimeMillis()

            if (
                state ==
                AyanaVoiceService
                    .STATE_SUCCESS
            ) {

                successFlashUntil =
                    now +
                        1200L
            }

            if (
                state ==
                AyanaVoiceService
                    .STATE_ERROR
            ) {

                errorFlashUntil =
                    now +
                        1500L
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
                SystemClock
                    .uptimeMillis()

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
                    0.31f

            val speed =
                when (
                    ayanaState
                ) {

                    AyanaVoiceService.STATE_COMMAND ->
                        150.0

                    AyanaVoiceService.STATE_EXECUTING ->
                        130.0

                    AyanaVoiceService.STATE_THINKING ->
                        250.0

                    AyanaVoiceService.STATE_SPEAKING ->
                        220.0

                    AyanaVoiceService.STATE_LISTENING ->
                        520.0

                    else ->
                        700.0
                }

            val amount =
                when (
                    ayanaState
                ) {

                    AyanaVoiceService.STATE_COMMAND ->
                        0.095f

                    AyanaVoiceService.STATE_EXECUTING ->
                        0.085f

                    AyanaVoiceService.STATE_THINKING ->
                        0.065f

                    AyanaVoiceService.STATE_SPEAKING ->
                        0.07f

                    AyanaVoiceService.STATE_LISTENING ->
                        0.035f

                    else ->
                        0f
                }

            val pulse =
                (
                    sin(
                        now /
                            speed
                    ) *
                        amount
                    )
                    .toFloat()

            val radius =
                baseRadius *
                    (
                        1f +
                            pulse
                        )

            val colors =
                stateGradient(
                    ayanaState
                )

            corePaint.shader =
                RadialGradient(
                    cx -
                        radius *
                        0.28f,
                    cy -
                        radius *
                        0.30f,
                    radius *
                        1.35f,
                    colors,
                    null,
                    Shader.TileMode.CLAMP
                )

            canvas.drawCircle(
                cx,
                cy,
                radius,
                corePaint
            )

            corePaint.shader =
                null

            glowPaint.color =
                stateStrokeColor(
                    ayanaState
                )

            glowPaint.alpha =
                210

            canvas.drawCircle(
                cx,
                cy,
                radius +
                    dp(
                        5
                    ),
                glowPaint
            )

            if (
                ayanaState ==
                AyanaVoiceService
                    .STATE_EXECUTING
            ) {

                val phase =
                    (
                        now %
                            850L
                        ) /
                        850f

                glowPaint.alpha =
                    (
                        220 *
                            (
                                1f -
                                    phase
                                )
                        )
                        .toInt()
                        .coerceIn(
                            0,
                            220
                        )

                canvas.drawCircle(
                    cx,
                    cy,
                    radius +
                        dp(
                            6
                        ) +
                        dp(
                            15
                        ) *
                            phase,
                    glowPaint
                )
            }

            if (
                now <
                successFlashUntil
            ) {

                val remaining =
                    (
                        successFlashUntil -
                            now
                        )
                        .coerceAtLeast(
                            0L
                        )

                val phase =
                    1f -
                        remaining /
                            1200f

                glowPaint.color =
                    Color.parseColor(
                        "#4ADE80"
                    )

                glowPaint.alpha =
                    (
                        235 *
                            (
                                1f -
                                    phase
                                )
                        )
                        .toInt()
                        .coerceIn(
                            0,
                            235
                        )

                canvas.drawCircle(
                    cx,
                    cy,
                    radius +
                        dp(
                            7
                        ) +
                        dp(
                            19
                        ) *
                            phase,
                    glowPaint
                )
            }

            if (
                now <
                errorFlashUntil
            ) {

                val remaining =
                    (
                        errorFlashUntil -
                            now
                        )
                        .coerceAtLeast(
                            0L
                        )

                val phase =
                    1f -
                        remaining /
                            1500f

                glowPaint.color =
                    Color.parseColor(
                        "#F87171"
                    )

                glowPaint.alpha =
                    (
                        235 *
                            (
                                1f -
                                    phase
                                )
                        )
                        .toInt()
                        .coerceIn(
                            0,
                            235
                        )

                canvas.drawCircle(
                    cx,
                    cy,
                    radius +
                        dp(
                            7
                        ) +
                        dp(
                            17
                        ) *
                            phase,
                    glowPaint
                )
            }

            labelPaint.textSize =
                radius *
                    0.76f

            canvas.drawText(
                "A",
                cx,
                cy +
                    labelPaint
                        .textSize *
                        0.34f,
                labelPaint
            )

            if (
                shouldAnimate(
                    now
                )
            ) {

                postInvalidateOnAnimation()
            }
        }

        private fun shouldAnimate(
            now: Long
        ): Boolean {

            return ayanaState in
                setOf(
                    AyanaVoiceService.STATE_LISTENING,
                    AyanaVoiceService.STATE_COMMAND,
                    AyanaVoiceService.STATE_THINKING,
                    AyanaVoiceService.STATE_EXECUTING,
                    AyanaVoiceService.STATE_SPEAKING
                ) ||
                now <
                successFlashUntil ||
                now <
                errorFlashUntil
        }
    }

    private fun stateGradient(
        state: String
    ): IntArray {

        return when (
            state
        ) {

            AyanaVoiceService.STATE_COMMAND ->
                intArrayOf(
                    Color.parseColor(
                        "#22D3EE"
                    ),
                    Color.parseColor(
                        "#2563EB"
                    ),
                    Color.parseColor(
                        "#111827"
                    )
                )

            AyanaVoiceService.STATE_THINKING ->
                intArrayOf(
                    Color.parseColor(
                        "#A78BFA"
                    ),
                    Color.parseColor(
                        "#6D28D9"
                    ),
                    Color.parseColor(
                        "#111827"
                    )
                )

            AyanaVoiceService.STATE_EXECUTING ->
                intArrayOf(
                    Color.parseColor(
                        "#2DD4BF"
                    ),
                    Color.parseColor(
                        "#0E7490"
                    ),
                    Color.parseColor(
                        "#0F172A"
                    )
                )

            AyanaVoiceService.STATE_SUCCESS ->
                intArrayOf(
                    Color.parseColor(
                        "#4ADE80"
                    ),
                    Color.parseColor(
                        "#15803D"
                    ),
                    Color.parseColor(
                        "#0F172A"
                    )
                )

            AyanaVoiceService.STATE_ERROR ->
                intArrayOf(
                    Color.parseColor(
                        "#F87171"
                    ),
                    Color.parseColor(
                        "#B91C1C"
                    ),
                    Color.parseColor(
                        "#111827"
                    )
                )

            AyanaVoiceService.STATE_STOPPED ->
                intArrayOf(
                    Color.parseColor(
                        "#475569"
                    ),
                    Color.parseColor(
                        "#1F2937"
                    ),
                    Color.parseColor(
                        "#0F172A"
                    )
                )

            AyanaVoiceService.STATE_SPEAKING ->
                intArrayOf(
                    Color.parseColor(
                        "#818CF8"
                    ),
                    Color.parseColor(
                        "#4F46E5"
                    ),
                    Color.parseColor(
                        "#111827"
                    )
                )

            else ->
                intArrayOf(
                    Color.parseColor(
                        "#38BDF8"
                    ),
                    Color.parseColor(
                        "#2563EB"
                    ),
                    Color.parseColor(
                        "#0F172A"
                    )
                )
        }
    }

    private fun stateStrokeColor(
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

                AyanaVoiceService.STATE_STOPPED ->
                    "#64748B"

                AyanaVoiceService.STATE_SPEAKING ->
                    "#A5B4FC"

                else ->
                    "#7DD3FC"
            }
        )
    }

    companion object {

        private const val ORB_SIZE_DP =
            72

        private const val DEFAULT_MARGIN_DP =
            22

        private const val DEFAULT_Y_DP =
            180

        private const val MOVE_THRESHOLD_DP =
            5

        private const val PREF_X =
            "orb_x"

        private const val PREF_Y =
            "orb_y"

        private val overlayLock =
            Any()

        @Volatile
        private var sharedOrbView:
            FloatingOrbView? = null

        @Volatile
        private var sharedLayoutParams:
            WindowManager.LayoutParams? = null
    }
}
