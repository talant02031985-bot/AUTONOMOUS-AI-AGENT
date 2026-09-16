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
 * AYANA Core Visualizer v3.0 — REFERENCE SIX-STATE ENERGY CORE.
 *
 * Visual-only renderer for AYANA's main visualization area.
 * v3.0 adapts the six-state native Android/Canvas concept generated from the user's
 * 2026-09-16 visual reference onto the already-integrated AyanaCoreVisualizer contract.
 *
 * Design goals:
 * - preserve the approved large contained AYANA energy-core geometry without touching app logic;
 * - map the six visual reference states onto real AYANA runtime states:
 *   waiting/listening -> cyan, recognizing/command -> blue, thinking -> violet,
 *   executing -> green, responding/speaking -> pink, stopped -> red-orange;
 * - give every primary state its own motion speed and energy level rather than only recoloring;
 * - keep every halo, particle, flare and waveform fully inside the visualizer window;
 * - retain the centered stylized AYANA signature, three broken arcs and state-reactive waveform;
 * - keep terminal SUCCESS/ERROR/CANCELLED colors distinct without changing execution semantics;
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

        // v2.2: HEIGHT-LED SCALE. The previous width-based preferred cap
        // made the approved circular core look tiny on the wide Tab S8 panel.
        // The outermost visible effect is bounded to 1.20R, so we derive the
        // hard safety cap from BOTH half-dimensions and then prefer a much
        // larger height-led body. This keeps the circle large but contained.
        val edgeInset =
            dp(
                if (compact) {
                    9f
                } else {
                    12f
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
                safeHalfHeight / 1.20f,
                safeHalfWidth / 1.20f
            )

        val preferredRadius =
            h *
                if (compact) {
                    0.345f
                } else {
                    0.385f
                }

        val radius =
            min(
                preferredRadius,
                containedRadius
            )
                .coerceAtLeast(
                    dp(20f)
                )

        val energy =
            stateEnergy(state)

        val motion =
            stateMotion(state)

        val breathe =
            (
                0.5 +
                    0.5 *
                    sin(
                        now /
                            (
                                (
                                    890.0 -
                                        energy * 230.0
                                    ) /
                                    motion.toDouble()
                                )
                    )
                )
                .toFloat()

        // 1) Quiet ambient light field.
        fillPaint.shader =
            ambientShader
        fillPaint.alpha =
            245

        canvas.drawCircle(
            cx,
            cy,
            radius * 1.16f,
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
            motion = motion,
            compact = compact
        )

        // 4) Layered energy ribbons. Five broad families reproduce the
        // approved energetic "living core" without becoming a wireframe ball.
        drawEnergyRibbon(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.86f,
            phase = now / (1280.0 / motion),
            tilt = -30f,
            color = palette.primary,
            alpha = 168,
            widthDp = if (compact) 1.08f else 1.46f
        )

        drawEnergyRibbon(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.83f,
            phase = -now / (1460.0 / motion),
            tilt = 31f,
            color = palette.accent,
            alpha = 154,
            widthDp = if (compact) 1.00f else 1.34f
        )

        drawEnergyRibbon(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.79f,
            phase = now / (1710.0 / motion),
            tilt = 77f,
            color = palette.secondary,
            alpha = 132,
            widthDp = if (compact) 0.90f else 1.20f
        )

        drawEnergyRibbon(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.73f,
            phase = -now / (1940.0 / motion),
            tilt = 8f,
            color = palette.white,
            alpha = 92,
            widthDp = if (compact) 0.68f else 0.92f
        )

        drawEnergyRibbon(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.69f,
            phase = now / (2180.0 / motion),
            tilt = -72f,
            color = palette.primary,
            alpha = 88,
            widthDp = if (compact) 0.62f else 0.82f
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
                    0.82f +
                        breathe * 0.022f
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
                    0.50f +
                        breathe * 0.020f
                    ),
            fillPaint
        )

        // 6) AYANA signature: exactly three broken arcs around one nucleus.
        val phaseA =
            continuousAngle(
                now,
                cycleMs =
                    (
                        14200f -
                            energy * 2200f
                        ) / motion,
                reverse = false
            )

        val phaseB =
            continuousAngle(
                now,
                cycleMs =
                    (
                        17800f -
                            energy * 2600f
                        ) / motion,
                reverse = true
            )

        val phaseC =
            continuousAngle(
                now,
                cycleMs =
                    (
                        11200f -
                            energy * 1800f
                        ) / motion,
                reverse = false
            )

        drawBrokenArc(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 1.04f,
            phase = phaseA + 14f,
            sweep = 104f,
            gap = 28f,
            color = palette.primary,
            alpha = 238,
            widthDp = if (compact) 1.75f else 2.20f
        )

        drawBrokenArc(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.89f,
            phase = phaseB + 126f,
            sweep = 88f,
            gap = 34f,
            color = palette.accent,
            alpha = 188,
            widthDp = if (compact) 1.15f else 1.52f
        )

        drawBrokenArc(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.72f,
            phase = phaseC + 236f,
            sweep = 72f,
            gap = 42f,
            color = palette.white,
            alpha = 126,
            widthDp = if (compact) 0.82f else 1.02f
        )

        // 7) Dense spark halo around the circular body. It stays below
        // 1.14R, inside the 1.20R hard containment budget.
        drawParticleHalo(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            energy = energy,
            motion = motion,
            compact = compact
        )

        // 8) Centered AYANA signature, contained entirely inside the core.
        drawAyanaWordmark(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            compact = compact
        )

        // 9) A few deterministic signal nodes add depth without clutter.
        drawSignalNodes(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            energy = energy
        )

        // 10) Optical highlights for the approved luminous depth.
        drawLensHighlight(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius
        )

        drawStarFlare(
            canvas = canvas,
            x = cx - radius * 0.56f,
            y = cy - radius * 0.48f,
            color = palette.white,
            strength = if (compact) 0.66f else 0.92f
        )

        drawStarFlare(
            canvas = canvas,
            x = cx + radius * 0.55f,
            y = cy - radius * 0.63f,
            color = palette.accent,
            strength = if (compact) 0.58f else 0.78f
        )

        drawStarFlare(
            canvas = canvas,
            x = cx + radius * 0.66f,
            y = cy + radius * 0.38f,
            color = palette.primary,
            strength = if (compact) 0.52f else 0.70f
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
                minSide * 0.48f,
                intArrayOf(
                    withAlpha(
                        palette.secondary,
                        88
                    ),
                    withAlpha(
                        palette.primary,
                        58
                    ),
                    withAlpha(
                        palette.accent,
                        34
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
            width * 0.055f

        val right =
            width * 0.945f

        linePaint.shader =
            railShader
        linePaint.alpha =
            146
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
            42
        glowPaint.strokeWidth =
            dp(7.2f)

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
        motion: Float,
        compact: Boolean
    ) {
        val left =
            width * 0.045f

        val right =
            width * 0.955f

        val span =
            right - left

        val points =
            if (compact) {
                72
            } else {
                112
            }

        val phase =
            now /
                (
                    (
                        620.0 -
                            energy * 180.0
                        ) /
                        motion.toDouble()
                    )

        val maxAmplitude =
            min(
                height * 0.205f,
                radius * 0.50f
            ) *
                (
                    0.48f +
                        energy * 0.72f
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
                52 +
                    energy * 42f
                )
                .toInt()
        glowPaint.strokeWidth =
            dp(8.5f)

        canvas.drawPath(
            signalPath,
            glowPaint
        )

        linePaint.shader =
            railShader
        linePaint.alpha =
            (
                176 +
                    energy * 74f
                )
                .toInt()
                .coerceAtMost(
                    220
                )
        linePaint.strokeWidth =
            dp(
                if (compact) {
                    1.30f
                } else {
                    1.72f
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
            78
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
                alpha * 0.24f
                )
                .toInt()
                .coerceIn(
                    0,
                    255
                )
        glowPaint.strokeWidth =
            dp(
                widthDp * 5.6f
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

    private fun drawParticleHalo(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float,
        motion: Float,
        compact: Boolean
    ) {
        val count =
            if (compact) {
                46
            } else {
                84
            }

        for (index in 0 until count) {
            val seed =
                index *
                    2.399963229728653

            val angle =
                seed +
                    now.toDouble() /
                    (
                        (
                            6100.0 +
                                index * 19.0
                            ) /
                            motion.toDouble()
                        )

            val band =
                when (index % 4) {
                    0 -> 1.00f
                    1 -> 1.045f
                    2 -> 1.085f
                    else -> 1.12f
                }

            val jitter =
                0.012f *
                    sin(
                        seed * 2.0 +
                            now / 1280.0
                    )
                        .toFloat()

            val r =
                radius *
                    (band + jitter)

            val x =
                cx +
                    cos(angle)
                        .toFloat() *
                    r

            val y =
                cy +
                    sin(angle)
                        .toFloat() *
                    r * 0.94f

            val color =
                when (index % 5) {
                    0 -> palette.white
                    1, 2 -> palette.primary
                    3 -> palette.secondary
                    else -> palette.accent
                }

            val pulse =
                (
                    0.5 +
                        0.5 *
                        sin(
                            now /
                                (
                                    510.0 /
                                        motion.toDouble()
                                    ) +
                                index * 0.83
                        )
                    )
                    .toFloat()

            val dot =
                dp(
                    if (compact) {
                        0.46f + pulse * 0.42f
                    } else {
                        0.58f + pulse * 0.68f
                    }
                )

            pointPaint.shader = null
            pointPaint.color = color
            pointPaint.alpha =
                (
                    62 +
                        energy * 44f +
                        pulse * 118f
                    )
                    .toInt()
                    .coerceIn(0, 214)

            canvas.drawCircle(
                x,
                y,
                dot,
                pointPaint
            )

            if (!compact && index % 17 == 0) {
                pointPaint.alpha = 32
                canvas.drawCircle(
                    x,
                    y,
                    dot * 4.0f,
                    pointPaint
                )
            }
        }
    }

    private fun drawStarFlare(
        canvas: Canvas,
        x: Float,
        y: Float,
        color: Int,
        strength: Float
    ) {
        pointPaint.shader = null
        pointPaint.color = color
        pointPaint.alpha =
            (220f * strength)
                .toInt()
                .coerceIn(0, 255)

        canvas.drawCircle(
            x,
            y,
            dp(1.20f * strength),
            pointPaint
        )

        linePaint.shader = null
        linePaint.color = color
        linePaint.alpha =
            (118f * strength)
                .toInt()
                .coerceIn(0, 180)
        linePaint.strokeWidth =
            dp(0.72f)

        val longArm =
            dp(8.5f * strength)

        val shortArm =
            dp(3.6f * strength)

        canvas.drawLine(
            x - longArm,
            y,
            x + longArm,
            y,
            linePaint
        )

        canvas.drawLine(
            x,
            y - shortArm,
            x,
            y + shortArm,
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
                    1.74f
                } else {
                    1.92f
                }

        val letterHeight =
            radius *
                if (compact) {
                    0.29f
                } else {
                    0.34f
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
            68
        wordmarkGlowPaint.strokeWidth =
            dp(
                if (compact) {
                    7.2f
                } else {
                    10.5f
                }
            )

        wordmarkPaint.shader =
            railShader
        wordmarkPaint.alpha =
            255
        wordmarkPaint.strokeWidth =
            dp(
                if (compact) {
                    1.70f
                } else {
                    2.45f
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
            // Reference state 2: RECOGNIZING.
            AyanaVoiceService.STATE_COMMAND ->
                0.88f

            // Reference state 3: THINKING.
            AyanaVoiceService.STATE_THINKING ->
                0.78f

            // Reference state 4: EXECUTING.
            AyanaVoiceService.STATE_EXECUTING ->
                0.94f

            // Reference state 5: RESPONDING.
            AyanaVoiceService.STATE_SPEAKING ->
                1.00f

            // Reference state 1: WAITING.
            AyanaVoiceService.STATE_LISTENING ->
                0.62f

            // Terminal states stay visually clear but calmer than active work.
            AyanaVoiceService.STATE_SUCCESS ->
                0.54f

            AyanaVoiceService.STATE_ERROR ->
                0.72f

            AyanaVoiceService.STATE_CANCELLED ->
                0.42f

            // Reference state 6: STOP.
            AyanaVoiceService.STATE_STOPPED ->
                0.28f

            else ->
                0.42f
        }
    }

    private fun stateMotion(
        state: String
    ): Float {
        return when (state) {
            AyanaVoiceService.STATE_LISTENING ->
                0.55f

            AyanaVoiceService.STATE_COMMAND ->
                1.08f

            AyanaVoiceService.STATE_THINKING ->
                0.82f

            AyanaVoiceService.STATE_EXECUTING ->
                1.28f

            AyanaVoiceService.STATE_SPEAKING ->
                1.00f

            AyanaVoiceService.STATE_SUCCESS ->
                0.42f

            AyanaVoiceService.STATE_ERROR ->
                0.78f

            AyanaVoiceService.STATE_CANCELLED ->
                0.34f

            AyanaVoiceService.STATE_STOPPED ->
                0.22f

            else ->
                0.45f
        }
            .coerceIn(
                0.20f,
                1.35f
            )
    }

    private fun paletteFor(
        state: String
    ): Palette {
        return when (state) {
            // 1. WAITING / LISTENING — turquoise.
            AyanaVoiceService.STATE_LISTENING ->
                Palette(
                    primary = Color.rgb(0, 235, 235),
                    secondary = Color.rgb(25, 151, 230),
                    accent = Color.rgb(80, 232, 255),
                    white = Color.parseColor("#F4FFFF"),
                    deep = Color.parseColor("#04191C")
                )

            // 2. RECOGNIZING / COMMAND — electric blue.
            AyanaVoiceService.STATE_COMMAND ->
                Palette(
                    primary = Color.rgb(30, 112, 255),
                    secondary = Color.rgb(47, 151, 255),
                    accent = Color.rgb(54, 214, 255),
                    white = Color.parseColor("#F7FBFF"),
                    deep = Color.parseColor("#07142B")
                )

            // 3. THINKING — violet.
            AyanaVoiceService.STATE_THINKING ->
                Palette(
                    primary = Color.rgb(103, 45, 255),
                    secondary = Color.rgb(122, 85, 255),
                    accent = Color.rgb(176, 92, 255),
                    white = Color.parseColor("#FBF8FF"),
                    deep = Color.parseColor("#130A2A")
                )

            // 4. EXECUTING — green / teal energy.
            AyanaVoiceService.STATE_EXECUTING ->
                Palette(
                    primary = Color.rgb(34, 230, 105),
                    secondary = Color.rgb(20, 205, 177),
                    accent = Color.rgb(76, 244, 165),
                    white = Color.parseColor("#F5FFF8"),
                    deep = Color.parseColor("#061D12")
                )

            // 5. RESPONDING / SPEAKING — pink / violet.
            AyanaVoiceService.STATE_SPEAKING ->
                Palette(
                    primary = Color.rgb(255, 46, 200),
                    secondary = Color.rgb(180, 76, 255),
                    accent = Color.rgb(255, 118, 222),
                    white = Color.parseColor("#FFF7FD"),
                    deep = Color.parseColor("#260821")
                )

            AyanaVoiceService.STATE_SUCCESS ->
                Palette(
                    primary = Color.parseColor("#4CF0A2"),
                    secondary = Color.parseColor("#19C8B0"),
                    accent = Color.parseColor("#7BFFD0"),
                    white = Color.parseColor("#F4FFF9"),
                    deep = Color.parseColor("#061B13")
                )

            AyanaVoiceService.STATE_ERROR ->
                Palette(
                    primary = Color.parseColor("#FF4664"),
                    secondary = Color.parseColor("#D92E67"),
                    accent = Color.parseColor("#FF4FA3"),
                    white = Color.parseColor("#FFF6F8"),
                    deep = Color.parseColor("#280710")
                )

            AyanaVoiceService.STATE_CANCELLED ->
                Palette(
                    primary = Color.parseColor("#FFC34F"),
                    secondary = Color.parseColor("#F28A35"),
                    accent = Color.parseColor("#FFD47B"),
                    white = Color.parseColor("#FFFBEF"),
                    deep = Color.parseColor("#251606")
                )

            // 6. STOPPED — red-orange, deliberately low-motion.
            AyanaVoiceService.STATE_STOPPED ->
                Palette(
                    primary = Color.parseColor("#FF553A"),
                    secondary = Color.parseColor("#FF8A3D"),
                    accent = Color.parseColor("#FF3D69"),
                    white = Color.parseColor("#FFF7F3"),
                    deep = Color.parseColor("#260A06")
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
            AyanaVoiceService.STATE_EXECUTING,
            AyanaVoiceService.STATE_SPEAKING ->
                30L

            AyanaVoiceService.STATE_THINKING ->
                32L

            AyanaVoiceService.STATE_LISTENING ->
                38L

            AyanaVoiceService.STATE_ERROR ->
                42L

            AyanaVoiceService.STATE_SUCCESS,
            AyanaVoiceService.STATE_CANCELLED ->
                52L

            AyanaVoiceService.STATE_STOPPED ->
                72L

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
