package kg.autonomous.agent

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
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
 * AYANA Floating Orb v4.2 — GLASS CORE / TRANSPARENT ORBITS.
 *
 * Rules:
 * 1) exactly one overlay View per app process;
 * 2) STOPPED always removes the overlay;
 * 3) repeated refresh() only updates the existing View;
 * 4) drag position is persisted;
 * 5) tapping the Orb opens AYANA.
 * 6) the overlay background is fully transparent; the symbol itself is a light translucent glass core, never a dark disk.
 * 7) idle motion is visibly smoother/faster while remaining frame-capped to protect text input and IME latency.
 * 8) shaders are rebuilt only on size/state changes; no bitmap decoding or per-frame shader allocation.
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

        private val glassPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            )

        private val corePaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            )

        private val ringPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }

        private val finePaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }

        private val nodePaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            )

        private val arcBounds =
            RectF()

        private var glassShader: RadialGradient? = null
        private var coreShader: RadialGradient? = null

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
            attached = true
            rebuildShaders()
            postInvalidateDelayed(
                FRAME_LISTENING_MS
            )
        }

        override fun onDetachedFromWindow() {
            attached = false
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int
        ) {
            super.onSizeChanged(
                w,
                h,
                oldw,
                oldh
            )
            rebuildShaders()
        }

        fun setAyanaState(
            state: String
        ) {

            ayanaState = state
            accentColor = strokeFor(state)

            if (
                state == AyanaVoiceService.STATE_SUCCESS ||
                state == AyanaVoiceService.STATE_ERROR ||
                state == AyanaVoiceService.STATE_CANCELLED
            ) {
                terminalFlashUntil =
                    SystemClock.uptimeMillis() +
                        TERMINAL_FLASH_MS
            }

            rebuildShaders()
            invalidate()
        }

        private fun rebuildShaders() {
            if (width <= 0 || height <= 0) {
                return
            }

            val cx = width / 2f
            val cy = height / 2f
            val minSide = minOf(width, height).toFloat()
            val shellRadius = minSide * 0.225f
            val coreRadius = shellRadius * 0.42f

            val accent = accentColor
            val pale = Color.rgb(224, 247, 255)

            glassShader =
                RadialGradient(
                    cx,
                    cy,
                    shellRadius * 1.18f,
                    intArrayOf(
                        withAlpha(pale, 42),
                        withAlpha(accent, 34),
                        withAlpha(accent, 14),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(
                        0f,
                        0.38f,
                        0.76f,
                        1f
                    ),
                    Shader.TileMode.CLAMP
                )

            coreShader =
                RadialGradient(
                    cx,
                    cy,
                    coreRadius * 1.85f,
                    intArrayOf(
                        Color.WHITE,
                        withAlpha(accent, 245),
                        withAlpha(accent, 105),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(
                        0f,
                        0.22f,
                        0.62f,
                        1f
                    ),
                    Shader.TileMode.CLAMP
                )

            glassPaint.shader = glassShader
            corePaint.shader = coreShader
        }

        override fun onDraw(
            canvas: Canvas
        ) {

            super.onDraw(canvas)

            val now = SystemClock.uptimeMillis()
            val cx = width / 2f
            val cy = height / 2f
            val minSide = minOf(width, height).toFloat()
            val shellRadius = minSide * 0.225f

            val activeMotion =
                when (ayanaState) {
                    AyanaVoiceService.STATE_LISTENING,
                    AyanaVoiceService.STATE_COMMAND,
                    AyanaVoiceService.STATE_THINKING,
                    AyanaVoiceService.STATE_EXECUTING,
                    AyanaVoiceService.STATE_SPEAKING -> true
                    else -> false
                }

            val idleCycleMs =
                when (ayanaState) {
                    AyanaVoiceService.STATE_LISTENING -> 3000f
                    AyanaVoiceService.STATE_COMMAND -> 2300f
                    AyanaVoiceService.STATE_THINKING -> 2500f
                    AyanaVoiceService.STATE_EXECUTING -> 1900f
                    AyanaVoiceService.STATE_SPEAKING -> 2700f
                    else -> 3600f
                }

            val rotation =
                ((now % idleCycleMs.toLong()) / idleCycleMs) * 360f

            val reverseRotation =
                360f -
                    (
                        ((now % (idleCycleMs * 1.28f).toLong()) /
                            (idleCycleMs * 1.28f)) *
                            360f
                        )

            val breatheAmount =
                when (ayanaState) {
                    AyanaVoiceService.STATE_COMMAND,
                    AyanaVoiceService.STATE_SPEAKING -> 0.075f
                    AyanaVoiceService.STATE_THINKING,
                    AyanaVoiceService.STATE_EXECUTING -> 0.055f
                    AyanaVoiceService.STATE_LISTENING -> 0.035f
                    else -> 0f
                }

            val pulse =
                if (activeMotion) {
                    (
                        sin(
                            now / 340.0
                        ) * breatheAmount
                        ).toFloat()
                } else {
                    0f
                }

            val accent = accentColor
            val pale = Color.rgb(224, 247, 255)

            // Transparent overlay: only translucent glass/light is painted.
            glassPaint.alpha =
                if (ayanaState == AyanaVoiceService.STATE_THINKING) 210 else 185
            canvas.drawCircle(
                cx,
                cy,
                shellRadius * (1f + pulse * 0.22f),
                glassPaint
            )

            // Central luminous energy bead.
            corePaint.alpha =
                if (now < terminalFlashUntil) 255 else 238
            canvas.drawCircle(
                cx,
                cy,
                shellRadius * (0.46f + pulse * 0.55f),
                corePaint
            )

            // Glass shell: multiple very thin concentric highlights.
            finePaint.color = withAlpha(pale, 155)
            finePaint.strokeWidth = dpLocal(0.8f)
            canvas.drawCircle(
                cx,
                cy,
                shellRadius * 0.72f,
                finePaint
            )

            finePaint.color = withAlpha(accent, 115)
            finePaint.strokeWidth = dpLocal(0.7f)
            canvas.drawCircle(
                cx,
                cy,
                shellRadius * 0.92f,
                finePaint
            )

            ringPaint.color = withAlpha(pale, 175)
            ringPaint.strokeWidth = dpLocal(1.05f)
            arcBounds.set(
                cx - shellRadius,
                cy - shellRadius,
                cx + shellRadius,
                cy + shellRadius
            )
            canvas.drawArc(
                arcBounds,
                rotation + 205f,
                86f,
                false,
                ringPaint
            )

            ringPaint.color = withAlpha(accent, 190)
            ringPaint.strokeWidth = dpLocal(1.4f)
            canvas.drawArc(
                arcBounds,
                reverseRotation + 25f,
                54f,
                false,
                ringPaint
            )

            // Inner HUD segments.
            val innerRadius = shellRadius * 0.62f
            arcBounds.set(
                cx - innerRadius,
                cy - innerRadius,
                cx + innerRadius,
                cy + innerRadius
            )
            ringPaint.color = withAlpha(accent, 225)
            ringPaint.strokeWidth = dpLocal(1.5f)
            canvas.drawArc(
                arcBounds,
                rotation * 1.18f + 18f,
                42f,
                false,
                ringPaint
            )
            canvas.drawArc(
                arcBounds,
                rotation * 1.18f + 194f,
                27f,
                false,
                ringPaint
            )

            // Three outer orbital paths: thin, broken and asymmetric like the
            // approved glass-core concept. They deliberately rotate at
            // different speeds/directions so idle never looks frozen.
            drawOrbit(
                canvas = canvas,
                cx = cx,
                cy = cy,
                radius = shellRadius + dpLocal(5.5f),
                start = rotation + 8f,
                sweepA = 72f,
                sweepB = 39f,
                offsetB = 178f,
                color = withAlpha(accent, 205),
                widthDp = 1.25f
            )

            drawOrbit(
                canvas = canvas,
                cx = cx,
                cy = cy,
                radius = shellRadius + dpLocal(10.5f),
                start = reverseRotation + 74f,
                sweepA = 58f,
                sweepB = 31f,
                offsetB = 162f,
                color = withAlpha(pale, 150),
                widthDp = 0.9f
            )

            drawOrbit(
                canvas = canvas,
                cx = cx,
                cy = cy,
                radius = shellRadius + dpLocal(15.0f),
                start = rotation * 0.76f + 228f,
                sweepA = 49f,
                sweepB = 25f,
                offsetB = 151f,
                color = withAlpha(accent, 138),
                widthDp = 0.75f
            )

            // Small luminous orbital nodes. No bitmap, no allocation.
            drawNode(
                canvas,
                cx,
                cy,
                shellRadius + dpLocal(10.5f),
                reverseRotation + 93f,
                accent,
                1.35f
            )
            drawNode(
                canvas,
                cx,
                cy,
                shellRadius + dpLocal(15.0f),
                rotation * 0.76f + 254f,
                pale,
                1.15f
            )

            if (now < terminalFlashUntil) {
                val remaining =
                    (terminalFlashUntil - now)
                        .coerceAtLeast(0L)

                val phase =
                    1f -
                        remaining /
                            TERMINAL_FLASH_MS.toFloat()

                ringPaint.color = accent
                ringPaint.alpha =
                    (
                        190 *
                            (1f - phase)
                        )
                        .toInt()
                        .coerceIn(0, 190)
                ringPaint.strokeWidth = dpLocal(1.2f)

                val flashRadius =
                    shellRadius +
                        dpLocal(12f) +
                        dpLocal(10f) * phase

                canvas.drawCircle(
                    cx,
                    cy,
                    flashRadius,
                    ringPaint
                )
            }

            if (
                attached &&
                shouldAnimate(now)
            ) {
                postInvalidateDelayed(
                    frameDelayMs(ayanaState)
                )
            }
        }

        private fun drawOrbit(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            radius: Float,
            start: Float,
            sweepA: Float,
            sweepB: Float,
            offsetB: Float,
            color: Int,
            widthDp: Float
        ) {
            arcBounds.set(
                cx - radius,
                cy - radius,
                cx + radius,
                cy + radius
            )
            ringPaint.color = color
            ringPaint.alpha = Color.alpha(color)
            ringPaint.strokeWidth = dpLocal(widthDp)
            canvas.drawArc(
                arcBounds,
                start,
                sweepA,
                false,
                ringPaint
            )
            canvas.drawArc(
                arcBounds,
                start + offsetB,
                sweepB,
                false,
                ringPaint
            )
        }

        private fun drawNode(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            radius: Float,
            angleDeg: Float,
            color: Int,
            radiusDp: Float
        ) {
            val radians =
                Math.toRadians(
                    angleDeg.toDouble()
                )
            val x =
                cx +
                    Math.cos(radians)
                        .toFloat() *
                    radius
            val y =
                cy +
                    Math.sin(radians)
                        .toFloat() *
                    radius

            nodePaint.color = color
            nodePaint.alpha = 215
            canvas.drawCircle(
                x,
                y,
                dpLocal(radiusDp),
                nodePaint
            )

            nodePaint.alpha = 62
            canvas.drawCircle(
                x,
                y,
                dpLocal(radiusDp * 2.5f),
                nodePaint
            )
        }

        private fun frameDelayMs(
            state: String
        ): Long {
            return when (state) {
                AyanaVoiceService.STATE_COMMAND,
                AyanaVoiceService.STATE_EXECUTING -> FRAME_ACTIVE_MS
                AyanaVoiceService.STATE_THINKING,
                AyanaVoiceService.STATE_SPEAKING -> FRAME_NORMAL_MS
                AyanaVoiceService.STATE_LISTENING -> FRAME_LISTENING_MS
                else -> FRAME_TERMINAL_MS
            }
        }

        private fun strokeFor(
            state: String
        ): Int {
            return Color.parseColor(
                when (state) {
                    AyanaVoiceService.STATE_COMMAND -> "#67E8F9"
                    AyanaVoiceService.STATE_THINKING -> "#C4B5FD"
                    AyanaVoiceService.STATE_EXECUTING -> "#5EEAD4"
                    AyanaVoiceService.STATE_SUCCESS -> "#86EFAC"
                    AyanaVoiceService.STATE_ERROR -> "#FCA5A5"
                    AyanaVoiceService.STATE_CANCELLED -> "#FCD34D"
                    AyanaVoiceService.STATE_SPEAKING -> "#A5B4FC"
                    else -> "#67D7FF"
                }
            )
        }

        private fun shouldAnimate(
            now: Long
        ): Boolean {
            val active =
                when (ayanaState) {
                    AyanaVoiceService.STATE_LISTENING,
                    AyanaVoiceService.STATE_COMMAND,
                    AyanaVoiceService.STATE_THINKING,
                    AyanaVoiceService.STATE_EXECUTING,
                    AyanaVoiceService.STATE_SPEAKING -> true
                    else -> false
                }

            return active || now < terminalFlashUntil
        }

        private fun withAlpha(
            color: Int,
            alpha: Int
        ): Int {
            return Color.argb(
                alpha.coerceIn(0, 255),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            )
        }

        private fun dpLocal(
            value: Float
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

        // v4.3 SMOOTH ROTATION:
        // The old 36-45 ms cadence produced visibly stepped orbital motion
        // on high-refresh tablets. Keep the vector-only renderer, but raise
        // the active/idle animation cadence to ~38-42 FPS. This remains far
        // below display refresh rate and avoids the old continuous 60/120 FPS
        // load that could compete with text input.
        private const val FRAME_ACTIVE_MS =
            24L

        private const val FRAME_NORMAL_MS =
            26L

        private const val FRAME_LISTENING_MS =
            25L

        private const val FRAME_TERMINAL_MS =
            60L

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
