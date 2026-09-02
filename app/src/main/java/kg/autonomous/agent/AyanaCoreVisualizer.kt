package kg.autonomous.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Core Visualizer v2.1 â€” CONTAINED ENERGY SIGNATURE.
 *
 * Visual-only renderer for AYANA's main visualization area.
 *
 * Design goals:
 * - use the approved stronger AYANA energy-core direction without touching app logic;
 * - keep one circular core fully inside the visualizer window at every supported size;
 * - cyan / blue / violet energy ribbons, broken arcs and a centered AYANA signature;
 * - horizontal state-reactive waveform stays inside the View and never claims real mic amplitude;
 * - responsive composition for both compact and tall MainActivity placements;
 * - no accessibility semantics, touch handling, permissions or VoiceService changes.
 *
 * Integration contract:
 * - class name and constructor stay unchanged;
 * - MainActivity requires no modification;
 * - ORB rendering/controller is completely untouched.
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private val density =
        resources.displayMetrics.density

    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private val linePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val glowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val pointPaint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private val signalPath =
        Path()

    private val fineSignalPath =
        Path()

    private val energyPath =
        Path()

    private val wordmarkPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
        }

    private val wordmarkGlowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val arcBounds =
        RectF()

    private var attached =
        false

    private var shaderWidth =
        -1

    private var shaderHeight =
        -1

    private var shaderState =
        ""

    private var ambientShader:
        RadialGradient? = null

    private var nucleusShader:
        RadialGradient? = null

    private var lensShader:
        RadialGradient? = null

    private var railShader:
        LinearGradient? = null

    private var palette =
        Palette(
            primary = Color.parseColor("#43DFFF"),
            secondary = Color.parseColor("#5B7CFF"),
            accent = Color.parseColor("#9A63FF"),
            white = Color.parseColor("#F5FBFF"),
            deep = Color.parseColor("#07101A")
        )

    init {
        importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        rebuildShaders(
            AyanaVoiceService.currentStatusState
        )
        postInvalidateDelayed(
            frameDelayMs(
                AyanaVoiceService.currentStatusState
            )
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

        shaderWidth = -1
        shaderHeight = -1

        rebuildShaders(
            AyanaVoiceService.currentStatusState
        )
    }

    override fun onDraw(
        canvas: Canvas
    ) {
        super.onDraw(canvas)

        if (
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        val state =
            AyanaVoiceService.currentStatusState

        if (
            state != shaderState ||
            width != shaderWidth ||
            height != shaderHeight
        ) {
            rebuildShaders(state)
        }

        val now =
            SystemClock.uptimeMillis()

        val w =
            width.toFloat()

        val h =
            height.toFloat()

        val cx =
            w * 0.50f

        val cy =
            h * 0.50f

        val compact =
            h < dp(180f)

        // Strict geometric containment. The outermost circular element uses
        // at most 1.30R, so the preferred radius is additionally capped by
        // the available half-height / half-width minus a real visual margin.
        val edgeInset =
            dp(
                if (compact) {
                    10f
                } else {
                    14f
                }
            )

        val safeHalfHeight =
            (h * 0.50f - edgeInset)
                .coerceAtLeast(
                    dp(14f)
                )

        val safeHalfWidth =
            (w * 0.50f - edgeInset)
                .coerceAtLeast(
                    dp(14f)
                )

        val containedRadius =
            min(
                safeHalfHeight / 1.30f,
                safeHalfWidth / 1.30f
            )

        val preferredRadius =
            if (compact) {
                min(
                    h * 0.31f,
                    w * 0.118f
                )
            } else {
                min(
                    h * 0.31f,
                    w * 0.155f
                )
            }

        val radius =
            min(
                preferredRadius,
                containedRadius
            )
                .coerceAtLeast(
                    dp(18f)
                )

        val energy =
            stateEnergy(state)

        val breathe =
            (
                0.5 +
                    0.5 *
                    sin(
                        now /
                            (
                                890.0 -
                                    energy * 230.0
                                )
                    )
                )
                .toFloat()

        // 1) Quiet ambient light field.
        fillPaint.shader =
            ambientShader
        fillPaint.alpha =
            220

        canvas.drawCircle(
            cx,
            cy,
            radius * 1.30f,
            fillPaint
        )

        // 2) Thin horizontal signal rail anchors the composition.
        drawSignalRail(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            energy = energy
        )

        // 3) Controlled synthetic waveform. It is intentionally subtle and
        // yields around the core, so the visualization never becomes noisy.
        drawSignalField(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            energy = energy,
            compact = compact
        )

        // 4) Layered energy ribbons make the core feel alive while remaining
        // well inside the same circular containment radius.
        drawEnergyRibbon(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.74f,
            phase = now / 1350.0,
            tilt = -24f,
            color = palette.primary,
            alpha = 116,
            widthDp = if (compact) 0.92f else 1.18f
        )

        drawEnergyRibbon(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.71f,
            phase = -now / 1580.0,
            tilt = 28f,
            color = palette.accent,
            alpha = 104,
            widthDp = if (compact) 0.82f else 1.06f
        )

        drawEnergyRibbon(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.66f,
            phase = now / 1890.0,
            tilt = 76f,
            color = palette.secondary,
            alpha = 84,
            widthDp = if (compact) 0.72f else 0.92f
        )

        // 5) Nucleus glass lens.
        fillPaint.shader =
            lensShader
        fillPaint.alpha =
            220
        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    0.79f +
                        breathe * 0.018f
                    ),
            fillPaint
        )

        fillPaint.shader =
            nucleusShader
        fillPaint.alpha =
            255
        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    0.47f +
                        breathe * 0.016f
                    ),
            fillPaint
        )

        // 6) AYANA signature: exactly three broken arcs around one nucleus.
        val phaseA =
            continuousAngle(
                now,
                cycleMs =
                    14200f -
                        energy * 2200f,
                reverse = false
            )

        val phaseB =
            continuousAngle(
                now,
                cycleMs =
                    17800f -
                        energy * 2600f,
                reverse = true
            )

        val phaseC =
            continuousAngle(
                now,
                cycleMs =
                    11200f -
                        energy * 1800f,
                reverse = false
            )

        drawBrokenArc(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 1.02f,
            phase = phaseA + 14f,
            sweep = 104f,
            gap = 28f,
            color = palette.primary,
            alpha = 220,
            widthDp = if (compact) 1.55f else 1.85f
        )

        drawBrokenArc(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.84f,
            phase = phaseB + 126f,
            sweep = 88f,
            gap = 34f,
            color = palette.accent,
            alpha = 158,
            widthDp = if (compact) 1.05f else 1.28f
        )

        drawBrokenArc(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.66f,
            phase = phaseC + 236f,
            sweep = 72f,
            gap = 42f,
            color = palette.white,
            alpha = 98,
            widthDp = if (compact) 0.72f else 0.88f
        )

        // 7) Centered AYANA signature, contained entirely inside the core.
        drawAyanaWordmark(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            compact = compact
        )

        // 8) A few deterministic signal nodes add depth without clutter.
        drawSignalNodes(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            energy = energy
        )

        // 9) Small optical highlight only; no decorative star field.
        drawLensHighlight(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius
        )

        if (attached) {
            postInvalidateDelayed(
                frameDelayMs(state)
            )
        }
    }

    private fun rebuildShaders(
        state: String
    ) {
        if (
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        shaderWidth = width
        shaderHeight = height
        shaderState = state
        palette = paletteFor(state)

        val w =
            width.toFloat()

        val h =
            height.toFloat()

        val cx =
            w * 0.50f

        val cy =
            h * 0.50f

        val minSide =
            min(w, h)

        ambientShader =
            RadialGradient(
                cx,
                cy,
                minSide * 0.46f,
                intArrayOf(
                    withAlpha(
                        palette.secondary,
                        64
                    ),
                    withAlpha(
                        palette.primary,
                        34
                    ),
                    withAlpha(
                        palette.accent,
                        20
                    ),
                    withAlpha(
                        palette.deep,
                        8
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.30f,
                    0.54f,
                    0.78f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        lensShader =
            RadialGradient(
                cx - minSide * 0.018f,
                cy - minSide * 0.026f,
                minSide * 0.31f,
                intArrayOf(
                    withAlpha(
                        palette.white,
                        36
                    ),
                    withAlpha(
                        palette.primary,
                        42
                    ),
                    withAlpha(
                        palette.secondary,
                        24
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.42f,
                    0.72f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        nucleusShader =
            RadialGradient(
                cx - minSide * 0.022f,
                cy - minSide * 0.032f,
                minSide * 0.19f,
                intArrayOf(
                    Color.WHITE,
                    palette.white,
                    palette.primary,
                    withAlpha(
                        palette.secondary,
                        232
                    ),
                    withAlpha(
                        palette.accent,
                        132
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.08f,
                    0.25f,
                    0.50f,
                    0.75f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        railShader =
            LinearGradient(
                w * 0.06f,
                cy,
                w * 0.94f,
                cy,
                intArrayOf(
                    Color.TRANSPARENT,
                    withAlpha(
                        palette.primary,
                        118
                    ),
                    palette.primary,
                    palette.white,
                    palette.secondary,
                    withAlpha(
                        palette.accent,
                        112
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.14f,
                    0.34f,
                    0.50f,
                    0.67f,
                    0.86f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )
    }

    private fun drawSignalRail(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float
    ) {
        val left =
            width * 0.090f

        val right =
            width * 0.910f

        linePaint.shader =
            railShader
        linePaint.alpha =
            94
        linePaint.strokeWidth =
            dp(0.72f)

        canvas.drawLine(
            left,
            cy,
            right,
            cy,
            linePaint
        )

        glowPaint.shader =
            railShader
        glowPaint.alpha =
            24
        glowPaint.strokeWidth =
            dp(5.2f)

        canvas.drawLine(
            left,
            cy,
            right,
            cy,
            glowPaint
        )

        val travel =
            (
                (
                    now %
                        (
                            5200L -
                                (energy * 1400f)
                                    .toLong()
                            )
                    ).toFloat() /
                    (
                        5200f -
                            energy * 1400f
                        )
                )
                .coerceIn(
                    0f,
                    1f
                )

        val x =
            left +
                (right - left) *
                travel

        pointPaint.shader = null
        pointPaint.color =
            palette.white
        pointPaint.alpha =
            150

        canvas.drawCircle(
            x,
            cy,
            dp(1.45f),
            pointPaint
        )

        pointPaint.color =
            palette.primary
        pointPaint.alpha =
            36

        canvas.drawCircle(
            x,
            cy,
            dp(6.5f),
            pointPaint
        )

        // Tiny dead-zone at the nucleus keeps the rail from looking like it
        // physically cuts through the core.
        linePaint.shader = null
        linePaint.color =
            palette.deep
        linePaint.alpha =
            132
        linePaint.strokeWidth =
            radius * 0.16f

        canvas.drawLine(
            cx - radius * 0.43f,
            cy,
            cx + radius * 0.43f,
            cy,
            linePaint
        )
    }

    private fun drawSignalField(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float,
        compact: Boolean
    ) {
        val left =
            width * 0.075f

        val right =
            width * 0.925f

        val span =
            right - left

        val points =
            if (compact) {
                54
            } else {
                78
            }

        val phase =
            now /
                (
                    620.0 -
                        energy * 180.0
                    )

        val maxAmplitude =
            min(
                height * 0.16f,
                radius * 0.42f
            ) *
                (
                    0.34f +
                        energy * 0.66f
                    )

        signalPath.reset()
        fineSignalPath.reset()

        for (index in 0..points) {
            val t =
                index /
                    points.toFloat()

            val x =
                left +
                    span * t

            val coreDistance =
                abs(
                    x - cx
                ) /
                    (radius * 1.34f)

            val centerSuppression =
                coreDistance
                    .coerceIn(
                        0f,
                        1f
                    )

            val envelope =
                (
                    0.34f +
                        0.66f *
                        sin(
                            PI *
                                t
                        )
                            .toFloat()
                            .coerceAtLeast(
                                0f
                            )
                    )

            val main =
                sin(
                    t * PI * 7.0 +
                        phase
                )
                    .toFloat()

            val harmonic =
                sin(
                    t * PI * 19.0 -
                        phase * 0.72
                )
                    .toFloat() *
                    0.28f

            val y =
                cy +
                    (
                        main +
                            harmonic
                        ) *
                    maxAmplitude *
                    envelope *
                    (
                        0.26f +
                            centerSuppression *
                            0.74f
                        )

            if (index == 0) {
                signalPath.moveTo(
                    x,
                    y
                )
            } else {
                signalPath.lineTo(
                    x,
                    y
                )
            }

            val fine =
                sin(
                    t * PI * 31.0 +
                        phase * 1.17
                )
                    .toFloat()

            val fineY =
                cy +
                    fine *
                    maxAmplitude *
                    0.23f *
                    envelope *
                    (
                        0.38f +
                            centerSuppression *
                            0.62f
                        )

            if (index == 0) {
                fineSignalPath.moveTo(
                    x,
                    fineY
                )
            } else {
                fineSignalPath.lineTo(
                    x,
                    fineY
                )
            }
        }

        glowPaint.shader =
            railShader
        glowPaint.alpha =
            (
                30 +
                    energy * 26f
                )
                .toInt()
        glowPaint.strokeWidth =
            dp(5.6f)

        canvas.drawPath(
            signalPath,
            glowPaint
        )

        linePaint.shader =
            railShader
        linePaint.alpha =
            (
                132 +
                    energy * 66f
                )
                .toInt()
                .coerceAtMost(
                    220
                )
        linePaint.strokeWidth =
            dp(
                if (compact) {
                    1.15f
                } else {
                    1.42f
                }
            )

        canvas.drawPath(
            signalPath,
            linePaint
        )

        linePaint.shader = null
        linePaint.color =
            palette.white
        linePaint.alpha =
            48
        linePaint.strokeWidth =
            dp(0.62f)

        canvas.drawPath(
            fineSignalPath,
            linePaint
        )
    }

    private fun drawEnergyRibbon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        phase: Double,
        tilt: Float,
        color: Int,
        alpha: Int,
        widthDp: Float
    ) {
        energyPath.reset()

        val points =
            88

        val tiltRad =
            Math.toRadians(
                tilt.toDouble()
            )

        val cosTilt =
            cos(tiltRad)
                .toFloat()

        val sinTilt =
            sin(tiltRad)
                .toFloat()

        for (index in 0..points) {
            val t =
                index.toDouble() /
                    points.toDouble() *
                    PI *
                    2.0

            val radial =
                radius *
                    (
                        0.82f +
                            0.16f *
                            sin(
                                t * 3.0 +
                                    phase
                            )
                                .toFloat()
                        )

            val rawX =
                cos(t)
                    .toFloat() *
                    radial

            val rawY =
                sin(t)
                    .toFloat() *
                    radial *
                    0.78f

            val x =
                cx +
                    rawX * cosTilt -
                    rawY * sinTilt

            val y =
                cy +
                    rawX * sinTilt +
                    rawY * cosTilt

            if (index == 0) {
                energyPath.moveTo(
                    x,
                    y
                )
            } else {
                energyPath.lineTo(
                    x,
                    y
                )
            }
        }

        glowPaint.shader = null
        glowPaint.color = color
        glowPaint.alpha =
            (
                alpha * 0.15f
                )
                .toInt()
                .coerceIn(
                    0,
                    255
                )
        glowPaint.strokeWidth =
            dp(
                widthDp * 4.6f
            )

        canvas.drawPath(
            energyPath,
            glowPaint
        )

        linePaint.shader = null
        linePaint.color = color
        linePaint.alpha = alpha
        linePaint.strokeWidth =
            dp(widthDp)

        canvas.drawPath(
            energyPath,
            linePaint
        )
    }

    private fun drawAyanaWordmark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        compact: Boolean
    ) {
        val totalWidth =
            radius *
                if (compact) {
                    1.56f
                } else {
                    1.72f
                }

        val letterHeight =
            radius *
                if (compact) {
                    0.24f
                } else {
                    0.28f
                }

        val gap =
            totalWidth * 0.042f

        val unit =
            (
                totalWidth -
                    gap * 4f
                ) /
                5f

        val startX =
            cx -
                totalWidth / 2f

        val top =
            cy -
                letterHeight / 2f

        val bottom =
            cy +
                letterHeight / 2f

        wordmarkGlowPaint.shader =
            railShader
        wordmarkGlowPaint.alpha =
            44
        wordmarkGlowPaint.strokeWidth =
            dp(
                if (compact) {
                    6.0f
                } else {
                    8.5f
                }
            )

        wordmarkPaint.shader =
            railShader
        wordmarkPaint.alpha =
            240
        wordmarkPaint.strokeWidth =
            dp(
                if (compact) {
                    1.45f
                } else {
                    2.05f
                }
            )

        fun drawLetter(
            index: Int,
            glow: Paint,
            sharp: Paint
        ) {
            val x =
                startX +
                    (unit + gap) *
                    index

            when (index) {
                1 -> {
                    drawYLetter(
                        canvas,
                        x,
                        top,
                        unit,
                        letterHeight,
                        glow
                    )
                    drawYLetter(
                        canvas,
                        x,
                        top,
                        unit,
                        letterHeight,
                        sharp
                    )
                }

                3 -> {
                    drawNLetter(
                        canvas,
                        x,
                        top,
                        bottom,
                        unit,
                        glow
                    )
                    drawNLetter(
                        canvas,
                        x,
                        top,
                        bottom,
                        unit,
                        sharp
                    )
                }

                else -> {
                    drawALetter(
                        canvas,
                        x,
                        top,
                        unit,
                        letterHeight,
                        glow
                    )
                    drawALetter(
                        canvas,
                        x,
                        top,
                        unit,
                        letterHeight,
                        sharp
                    )
                }
            }
        }

        for (index in 0 until 5) {
            drawLetter(
                index,
                wordmarkGlowPaint,
                wordmarkPaint
            )
        }
    }

    private fun drawALetter(
        canvas: Canvas,
        x: Float,
        top: Float,
        width: Float,
        height: Float,
        paint: Paint
    ) {
        val bottom =
            top + height

        val center =
            x +
                width / 2f

        canvas.drawLine(
            x,
            bottom,
            center,
            top,
            paint
        )

        canvas.drawLine(
            center,
            top,
            x + width,
            bottom,
            paint
        )
    }

    private fun drawYLetter(
        canvas: Canvas,
        x: Float,
        top: Float,
        width: Float,
        height: Float,
        paint: Paint
    ) {
        val center =
            x +
                width / 2f

        val junctionY =
            top +
                height * 0.48f

        canvas.drawLine(
            x,
            top,
            center,
            junctionY,
            paint
        )

        canvas.drawLine(
            x + width,
            top,
            center,
            junctionY,
            paint
        )

        canvas.drawLine(
            center,
            junctionY,
            center,
            top + height,
            paint
        )
    }

    private fun drawNLetter(
        canvas: Canvas,
        x: Float,
        top: Float,
        bottom: Float,
        width: Float,
        paint: Paint
    ) {
        canvas.drawLine(
            x,
            bottom,
            x,
            top,
            paint
        )

        canvas.drawLine(
            x,
            top,
            x + width,
            bottom,
            paint
        )

        canvas.drawLine(
            x + width,
            bottom,
            x + width,
            top,
            paint
        )
    }

    private fun drawBrokenArc(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        phase: Float,
        sweep: Float,
        gap: Float,
        color: Int,
        alpha: Int,
        widthDp: Float
    ) {
        arcBounds.set(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius
        )

        val secondSweep =
            (
                360f -
                    sweep -
                    gap * 2f
                )
                .coerceAtLeast(
                    42f
                ) *
                0.46f

        glowPaint.shader = null
        glowPaint.color = color
        glowPaint.alpha =
            (
                alpha * 0.16f
                )
                .toInt()
        glowPaint.strokeWidth =
            dp(
                widthDp * 4.8f
            )

        canvas.drawArc(
            arcBounds,
            phase,
            sweep,
            false,
            glowPaint
        )

        canvas.drawArc(
            arcBounds,
            phase + sweep + gap,
            secondSweep,
            false,
            glowPaint
        )

        linePaint.shader = null
        linePaint.color = color
        linePaint.alpha = alpha
        linePaint.strokeWidth =
            dp(widthDp)

        canvas.drawArc(
            arcBounds,
            phase,
            sweep,
            false,
            linePaint
        )

        canvas.drawArc(
            arcBounds,
            phase + sweep + gap,
            secondSweep,
            false,
            linePaint
        )
    }

    private fun drawSignalNodes(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float
    ) {
        val count =
            7

        val phase =
            now /
                (
                    1800.0 -
                        energy * 340.0
                    )

        for (index in 0 until count) {
            val angle =
                phase * 0.17 +
                    index *
                    (
                        PI * 2.0 /
                            count
                        )

            val orbit =
                radius *
                    (
                        1.18f +
                            0.06f *
                            sin(
                                phase * 0.37 +
                                    index * 1.6
                            )
                                .toFloat()
                        )

            val x =
                cx +
                    cos(angle)
                        .toFloat() *
                    orbit

            val y =
                cy +
                    sin(angle)
                        .toFloat() *
                    orbit *
                    0.70f

            val pulse =
                (
                    0.50 +
                        0.50 *
                        sin(
                            phase +
                                index * 1.3
                        )
                    )
                    .toFloat()

            pointPaint.shader = null
            pointPaint.color =
                if (
                    index % 3 == 0
                ) {
                    palette.accent
                } else {
                    palette.primary
                }
            pointPaint.alpha =
                (
                    42 +
                        pulse * 86f
                    )
                    .toInt()

            canvas.drawCircle(
                x,
                y,
                dp(
                    0.72f +
                        pulse * 0.58f
                ),
                pointPaint
            )
        }
    }

    private fun drawLensHighlight(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        val x =
            cx -
                radius * 0.20f

        val y =
            cy -
                radius * 0.24f

        pointPaint.shader = null
        pointPaint.color =
            palette.white
        pointPaint.alpha =
            174

        canvas.drawCircle(
            x,
            y,
            dp(1.15f),
            pointPaint
        )

        pointPaint.color =
            palette.primary
        pointPaint.alpha =
            44

        canvas.drawCircle(
            x,
            y,
            dp(5.4f),
            pointPaint
        )
    }

    private fun stateEnergy(
        state: String
    ): Float {
        return when (state) {
            AyanaVoiceService.STATE_COMMAND ->
                0.68f

            AyanaVoiceService.STATE_THINKING ->
                0.74f

            AyanaVoiceService.STATE_EXECUTING ->
                0.88f

            AyanaVoiceService.STATE_SPEAKING ->
                0.78f

            AyanaVoiceService.STATE_LISTENING ->
                0.56f

            AyanaVoiceService.STATE_SUCCESS ->
                0.34f

            AyanaVoiceService.STATE_ERROR ->
                0.48f

            AyanaVoiceService.STATE_CANCELLED ->
                0.30f

            else ->
                0.26f
        }
    }

    private fun paletteFor(
        state: String
    ): Palette {
        return when (state) {
            AyanaVoiceService.STATE_COMMAND ->
                Palette(
                    primary = Color.parseColor("#48E7FF"),
                    secondary = Color.parseColor("#507DFF"),
                    accent = Color.parseColor("#A06AFF"),
                    white = Color.parseColor("#F7FCFF"),
                    deep = Color.parseColor("#07121C")
                )

            AyanaVoiceService.STATE_THINKING ->
                Palette(
                    primary = Color.parseColor("#73C8FF"),
                    secondary = Color.parseColor("#6A75FF"),
                    accent = Color.parseColor("#A76CFF"),
                    white = Color.parseColor("#FAFBFF"),
                    deep = Color.parseColor("#0B1021")
                )

            AyanaVoiceService.STATE_EXECUTING ->
                Palette(
                    primary = Color.parseColor("#48F2D2"),
                    secondary = Color.parseColor("#31A7C8"),
                    accent = Color.parseColor("#5F82FF"),
                    white = Color.parseColor("#F5FFFC"),
                    deep = Color.parseColor("#071B1A")
                )

            AyanaVoiceService.STATE_SPEAKING ->
                Palette(
                    primary = Color.parseColor("#61D8FF"),
                    secondary = Color.parseColor("#5074FF"),
                    accent = Color.parseColor("#A460FF"),
                    white = Color.parseColor("#FAFCFF"),
                    deep = Color.parseColor("#0D1228")
                )

            AyanaVoiceService.STATE_SUCCESS ->
                Palette(
                    primary = Color.parseColor("#58EDBF"),
                    secondary = Color.parseColor("#38BFC2"),
                    accent = Color.parseColor("#65D59E"),
                    white = Color.parseColor("#F6FFF9"),
                    deep = Color.parseColor("#071A14")
                )

            AyanaVoiceService.STATE_ERROR ->
                Palette(
                    primary = Color.parseColor("#FF718A"),
                    secondary = Color.parseColor("#D9486D"),
                    accent = Color.parseColor("#F06A9D"),
                    white = Color.parseColor("#FFF7F9"),
                    deep = Color.parseColor("#240812")
                )

            AyanaVoiceService.STATE_CANCELLED ->
                Palette(
                    primary = Color.parseColor("#FFD16A"),
                    secondary = Color.parseColor("#EFA63D"),
                    accent = Color.parseColor("#FFC37A"),
                    white = Color.parseColor("#FFFCEF"),
                    deep = Color.parseColor("#241807")
                )

            else ->
                Palette(
                    primary = Color.parseColor("#43DFFF"),
                    secondary = Color.parseColor("#5B7CFF"),
                    accent = Color.parseColor("#9A63FF"),
                    white = Color.parseColor("#F5FBFF"),
                    deep = Color.parseColor("#07101A")
                )
        }
    }

    private fun continuousAngle(
        now: Long,
        cycleMs: Float,
        reverse: Boolean
    ): Float {
        val safeCycle =
            cycleMs
                .coerceAtLeast(
                    800f
                )

        val fraction =
            (
                now %
                    safeCycle.toLong()
                ).toFloat() /
                safeCycle

        val angle =
            fraction * 360f

        return if (reverse) {
            -angle
        } else {
            angle
        }
    }

    private fun frameDelayMs(
        state: String
    ): Long {
        return when (state) {
            AyanaVoiceService.STATE_COMMAND,
            AyanaVoiceService.STATE_EXECUTING ->
                30L

            AyanaVoiceService.STATE_THINKING,
            AyanaVoiceService.STATE_SPEAKING ->
                32L

            AyanaVoiceService.STATE_LISTENING ->
                36L

            else ->
                58L
        }
    }

    private fun withAlpha(
        color: Int,
        alpha: Int
    ): Int {
        return Color.argb(
            alpha.coerceIn(
                0,
                255
            ),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun dp(
        value: Float
    ): Float {
        return value * density
    }

    private data class Palette(
        val primary: Int,
        val secondary: Int,
        val accent: Int,
        val white: Int,
        val deep: Int
    )
}
