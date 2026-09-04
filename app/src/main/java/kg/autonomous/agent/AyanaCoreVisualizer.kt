package kg.autonomous.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Core Visualizer v16.0 — AGENT RESONANCE ENGINE
 *
 * High-density procedural live renderer rebuilt after device review of v15. The goal is the approved AYANA energy reference, but as a real state-reactive Android animation:
 *
 * 1. ОЖИДАНИЕ       — cyan / calm coherent energy field
 * 2. РАСПОЗНАВАНИЕ  — electric blue / inbound analysis scan
 * 3. ДУМАЮ          — indigo-violet / branching reasoning field
 * 4. ВЫПОЛНЯЮ       — green / directed action flow
 * 5. ОТВЕЧАЮ        — magenta / coherent outward response wave
 * 6. СТОП           — red-orange / fractured halted field
 *
 * Important:
 * - NO bitmap/photo is used;
 * - NO six-button strip is drawn;
 * - the whole visual is rendered live with Android Canvas;
 * - all geometry is bounded mathematically inside this View;
 * - animation is state-reactive UI motion, not claimed as measured mic/model load;
 * - no ORB, routing, TTS, microphone, Accessibility or execution logic changes.
 *
 * Integration contract remains:
 *   AyanaCoreVisualizer(Context)
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private val density = resources.displayMetrics.density

    private val fill =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val glow =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    private val path = Path()
    private val finePath = Path()

    private var attached = false

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
        isLongClickable = false
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        attached = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0) return

        val state = stateIndex(AyanaVoiceService.currentStatusState)
        val palette = paletteFor(state)
        val motion = motionFor(state)
        val now = SystemClock.uptimeMillis()

        val w = width.toFloat()
        val h = height.toFloat()

        val cx = w * 0.50f
        val cy = h * 0.50f

        val edge = dp(10f)

        // v16 deliberately fills the hero window. The outermost decorative
        // layer remains mathematically bounded inside the View.
        val maxRByHeight =
            (h * 0.50f - edge) / 1.035f

        val maxRByWidth =
            (w * 0.50f - edge) / 1.035f

        val preferred =
            min(
                h * 0.485f,
                w * 0.405f
            )

        val radius =
            min(
                preferred,
                min(
                    maxRByHeight,
                    maxRByWidth
                )
            )
                .coerceAtLeast(dp(36f))

        drawBackdrop(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            state = state
        )

        drawPlasmaVolume(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            state = state
        )

        drawWaveform(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawOuterTelemetry(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawCognitiveCorona(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            state = state
        )

        drawEnergyRibbons(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawCoreRings(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawStateDynamics(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            state = state
        )

        drawWordmark(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            state = state
        )

        if (attached) {
            postInvalidateDelayed(motion.frameDelayMs)
        }
    }


    private fun drawBackdrop(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        state: Int
    ) {
        // Deep background bloom: bright enough to feel alive, but the center
        // remains dark enough for the custom AYANA signature to stay crisp.
        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius * 1.035f,
                intArrayOf(
                    withAlpha(Color.WHITE, if (state == STATE_STOP) 28 else 78),
                    withAlpha(palette.primary, if (state == STATE_STOP) 58 else 148),
                    withAlpha(palette.secondary, if (state == STATE_STOP) 34 else 92),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.24f,
                    0.64f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha = 255
        canvas.drawCircle(cx, cy, radius * 1.035f, fill)
        fill.shader = null

        // Bright central carrier beam from the approved reference.
        val left = dp(2f)
        val right = width.toFloat() - dp(2f)

        val carrier =
            LinearGradient(
                left,
                cy,
                right,
                cy,
                intArrayOf(
                    Color.TRANSPARENT,
                    palette.primary,
                    Color.WHITE,
                    palette.primary,
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.24f,
                    0.50f,
                    0.76f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        glow.shader = carrier
        glow.alpha = if (state == STATE_STOP) 36 else 88
        glow.strokeWidth = dp(8.5f)
        canvas.drawLine(left, cy, right, cy, glow)

        stroke.shader = carrier
        stroke.alpha = if (state == STATE_STOP) 90 else 225
        stroke.strokeWidth = dp(0.95f)
        canvas.drawLine(left, cy, right, cy, stroke)

        glow.shader = null
        stroke.shader = null
    }



    /**
     * Volumetric plasma mass. v15 was too thin on the real Galaxy Tab S8;
     * overlapping radial fields give the live core depth without using PNGs.
     */
    private fun drawPlasmaVolume(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        state: Int
    ) {
        val blobCount =
            when(state) {
                STATE_THINKING -> 18
                STATE_EXECUTING -> 15
                STATE_ANSWERING -> 16
                STATE_STOP -> 9
                else -> 13
            }

        for (i in 0 until blobCount) {
            val angle = now / (1500.0 + i * 41.0) + i * 0.73
            val orbit = radius * (0.08f + (i % 5) * 0.045f)
            val bx = cx + cos(angle).toFloat() * orbit
            val by = cy + sin(angle * (1.15 + (i % 3) * 0.11)).toFloat() * orbit * 0.78f
            val color =
                when(i % 4) {
                    0 -> Color.WHITE
                    1 -> palette.primary
                    2 -> palette.secondary
                    else -> palette.accent
                }
            val blobR = radius * (0.22f + (i % 4) * 0.035f)

            fill.shader =
                RadialGradient(
                    bx,
                    by,
                    blobR,
                    intArrayOf(
                        withAlpha(color, if (state == STATE_STOP) 24 else 86),
                        withAlpha(
                            if (color == Color.WHITE) palette.primary else color,
                            if (state == STATE_STOP) 18 else 52
                        ),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.38f, 1f),
                    Shader.TileMode.CLAMP
                )
            fill.alpha = 255
            canvas.drawCircle(bx, by, blobR, fill)
            fill.shader = null
        }

        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius * 0.36f,
                intArrayOf(
                    withAlpha(Color.WHITE, if (state == STATE_STOP) 80 else 230),
                    withAlpha(palette.primary, if (state == STATE_STOP) 90 else 190),
                    withAlpha(palette.secondary, if (state == STATE_STOP) 38 else 80),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.22f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
        fill.alpha = 255
        canvas.drawCircle(cx, cy, radius * 0.36f, fill)
        fill.shader = null
    }

    /**
     * AI-agent-specific state corona: branching hypotheses while thinking,
     * a directed action jet during execution, coherent resonance while answering,
     * and fracture on stop. No button/dashboard metaphor.
     */
    private fun drawCognitiveCorona(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        state: Int
    ) {
        when(state) {
            STATE_THINKING -> {
                val nodes = 26
                for (i in 0 until nodes) {
                    val a = i * PI * 2.0 / nodes + sin(now / 1700.0 + i * 0.37) * 0.18
                    val rr = radius * (0.82f + (i % 4) * 0.045f)
                    val x = cx + cos(a).toFloat() * rr
                    val y = cy + sin(a).toFloat() * rr
                    val j = (i * 7 + 5) % nodes
                    val b = j * PI * 2.0 / nodes - sin(now / 1350.0 + j) * 0.13
                    val rr2 = radius * (0.80f + (j % 3) * 0.055f)
                    val x2 = cx + cos(b).toFloat() * rr2
                    val y2 = cy + sin(b).toFloat() * rr2

                    stroke.color = if (i % 3 == 0) palette.accent else palette.primary
                    stroke.alpha = if (i % 4 == 0) 110 else 66
                    stroke.strokeWidth = dp(0.62f)
                    canvas.drawLine(x, y, x2, y2, stroke)

                    fill.color = if (i % 5 == 0) Color.WHITE else palette.primary
                    fill.alpha = if (i % 5 == 0) 245 else 180
                    canvas.drawCircle(x, y, dp(if (i % 5 == 0) 2.1f else 1.15f), fill)
                }
            }

            STATE_EXECUTING -> {
                val start = cx + radius * 0.36f
                val end = width.toFloat() - dp(5f)
                glow.color = palette.primary
                glow.alpha = 98
                glow.strokeWidth = dp(12f)
                canvas.drawLine(start, cy, end, cy, glow)
                stroke.color = Color.WHITE
                stroke.alpha = 235
                stroke.strokeWidth = dp(1.15f)
                canvas.drawLine(start, cy, end, cy, stroke)
            }

            STATE_ANSWERING -> {
                val phase = (now % 1300L).toFloat() / 1300f
                for (i in 0 until 4) {
                    val rr = radius * (0.48f + phase * 0.46f + i * 0.06f)
                    if (rr > radius * 0.98f) continue
                    stroke.color = if (i % 2 == 0) palette.primary else palette.secondary
                    stroke.alpha = (185 - phase * 125f - i * 16).toInt().coerceAtLeast(20)
                    stroke.strokeWidth = dp(1.1f)
                    canvas.drawCircle(cx, cy, rr, stroke)
                }
            }

            STATE_STOP -> {
                for (i in 0 until 22) {
                    if (i % 4 == 1) continue
                    val a = i * PI * 2.0 / 22.0
                    val r1 = radius * (0.80f + (i % 3) * 0.04f)
                    val r2 = r1 + radius * 0.15f
                    stroke.color = if (i % 2 == 0) palette.primary else palette.secondary
                    stroke.alpha = 175
                    stroke.strokeWidth = dp(1.2f)
                    canvas.drawLine(
                        cx + cos(a).toFloat() * r1,
                        cy + sin(a).toFloat() * r1,
                        cx + cos(a).toFloat() * r2,
                        cy + sin(a).toFloat() * r2,
                        stroke
                    )
                }
            }

            else -> Unit
        }
    }

    private fun drawWaveform(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        path.reset()
        finePath.reset()

        val left = dp(2f)
        val right = width.toFloat() - dp(2f)
        val samples = 132
        val phase = now / motion.wavePeriodMs

        val stateBoost =
            when(state) {
                STATE_RECOGNITION -> 1.72f
                STATE_THINKING -> 1.16f
                STATE_EXECUTING -> 1.46f
                STATE_ANSWERING -> 1.62f
                STATE_STOP -> 0.36f
                else -> 1.12f
            }

        for (i in 0..samples) {
            val t = i / samples.toFloat()
            val x = left + (right - left) * t

            val edgeWeight =
                abs(
                    (x - cx) /
                        (width * 0.50f)
                )
                    .coerceIn(0f, 1f)

            // Reference-like waveform: stronger on the left/right edges,
            // quieter while crossing the luminous center.
            val envelope =
                (
                    0.20f +
                        0.80f *
                            edgeWeight
                    )
                    .coerceIn(0.20f, 1f)

            val main =
                sin(
                    t * PI * motion.waveCycles +
                        phase
                )
                    .toFloat()

            val harmonic1 =
                sin(
                    t * PI * 31.0 -
                        phase * 0.71
                )
                    .toFloat() *
                    0.30f

            val harmonic2 =
                sin(
                    t * PI * 53.0 +
                        phase * 1.13
                )
                    .toFloat() *
                    0.11f

            val y =
                cy +
                    (
                        main +
                            harmonic1 +
                            harmonic2
                    ) *
                    radius *
                    0.205f *
                    envelope *
                    stateBoost

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }

            val fine =
                sin(
                    t * PI * 76.0 +
                        phase * 1.29
                )
                    .toFloat()

            val fy =
                cy +
                    fine *
                    radius *
                    0.050f *
                    envelope *
                    stateBoost

            if (i == 0) {
                finePath.moveTo(x, fy)
            } else {
                finePath.lineTo(x, fy)
            }
        }

        val gradient =
            LinearGradient(
                left,
                cy,
                right,
                cy,
                intArrayOf(
                    Color.TRANSPARENT,
                    palette.primary,
                    Color.WHITE,
                    palette.secondary,
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.24f,
                    0.50f,
                    0.76f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        // Multi-pass glow gives much more energy than one thin Android line.
        glow.shader = gradient
        glow.alpha = if (state == STATE_STOP) 28 else motion.waveGlowAlpha
        glow.strokeWidth = dp(15f)
        canvas.drawPath(path, glow)

        glow.alpha = if (state == STATE_STOP) 36 else 76
        glow.strokeWidth = dp(8f)
        canvas.drawPath(path, glow)

        stroke.shader = gradient
        stroke.alpha = if (state == STATE_STOP) 112 else motion.waveAlpha
        stroke.strokeWidth = dp(2.05f)
        canvas.drawPath(path, stroke)

        stroke.shader = null
        glow.shader = null

        stroke.color = Color.WHITE
        stroke.alpha = if (state == STATE_STOP) 32 else 110
        stroke.strokeWidth = dp(0.48f)
        canvas.drawPath(finePath, stroke)

        // Dense spectral spikes on the outside of the energy sphere.
        val spikeCount = 128
        for (i in 0 until spikeCount) {
            val t = i / (spikeCount - 1).toFloat()
            val x = left + (right - left) * t
            val centerDistance = abs((x - cx) / radius)

            if (centerDistance < 0.58f) continue

            val pulse =
                abs(
                    sin(
                        now / 210.0 +
                            i * 1.37
                    )
                        .toFloat()
                )

            val half =
                radius *
                    (
                        0.020f +
                            pulse * 0.145f
                    ) *
                    stateBoost

            stroke.color =
                when(i % 3) {
                    0 -> palette.primary
                    1 -> Color.WHITE
                    else -> palette.secondary
                }

            stroke.alpha =
                if (state == STATE_STOP) {
                    50 + (pulse * 65f).toInt()
                } else {
                    76 + (pulse * 145f).toInt()
                }

            stroke.strokeWidth = dp(if (i % 6 == 0) 0.85f else 0.48f)

            canvas.drawLine(
                x,
                cy - half,
                x,
                cy + half,
                stroke
            )
        }
    }

    /**
     * Braided inner field. The lines are deliberately non-uniform and
     * continuously phase-shifted so this is a real live renderer, not a photo.
     */

    private fun drawEnergyRibbons(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val ribbonCount =
            when(state) {
                STATE_WAITING -> 28
                STATE_RECOGNITION -> 32
                STATE_THINKING -> 38
                STATE_EXECUTING -> 34
                STATE_ANSWERING -> 32
                STATE_STOP -> 18
                else -> 28
            }

        for (ribbon in 0 until ribbonCount) {
            path.reset()

            val points = 176

            val phase =
                now /
                    motion.ribbonPeriodMs +
                    ribbon * 0.49

            val centered =
                ribbon -
                    (ribbonCount - 1) *
                        0.5f

            val family =
                ribbon % 4

            for (i in 0..points) {
                val t = i / points.toFloat()

                val angle =
                    t * PI * 2.0 +
                        phase *
                            when(family) {
                                0 -> 1.00
                                1 -> -0.86
                                2 -> 0.72
                                else -> -0.61
                            }

                val lobe =
                    sin(
                        angle *
                            (
                                2.0 +
                                    family
                                ) +
                            phase * 0.63
                    )
                        .toFloat()

                val micro =
                    sin(
                        angle * 7.0 -
                            phase * 0.41 +
                            ribbon * 0.33
                    )
                        .toFloat()

                val familyTilt =
                    when(family) {
                        0 -> 0.80f
                        1 -> 0.67f
                        2 -> 0.90f
                        else -> 0.74f
                    }

                val base =
                    radius *
                        (
                            0.60f +
                                centered / ribbonCount.toFloat() *
                                    0.22f
                            )

                val rr =
                    base *
                        (
                            1f +
                                lobe * motion.ribbonWarp +
                                micro * 0.052f
                            )

                // Additional precession creates the dense woven sphere seen in
                // the reference instead of thin orbital circles.
                val px =
                    sin(
                        phase * 0.44 +
                            ribbon * 0.71
                    )
                        .toFloat() *
                        radius *
                        0.055f

                val py =
                    cos(
                        phase * 0.39 +
                            ribbon * 0.53
                    )
                        .toFloat() *
                        radius *
                        0.045f

                val x =
                    cx +
                        cos(angle)
                            .toFloat() *
                            rr +
                        px *
                            sin(angle * 2.0)
                                .toFloat()

                val y =
                    cy +
                        sin(angle)
                            .toFloat() *
                            rr *
                            familyTilt +
                        py *
                            cos(angle * 3.0)
                                .toFloat()

                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            val color =
                when(ribbon % 5) {
                    0 -> Color.WHITE
                    1 -> palette.primary
                    2 -> palette.secondary
                    3 -> palette.accent
                    else -> palette.primary
                }

            // Every few ribbons receive a large bloom pass.
            if (ribbon % 4 == 0) {
                glow.color =
                    if (color == Color.WHITE) {
                        palette.primary
                    } else {
                        color
                    }

                glow.alpha =
                    when(state) {
                        STATE_STOP -> 26
                        STATE_THINKING -> 72
                        else -> 58
                    }

                glow.strokeWidth = dp(if (ribbon % 8 == 0) 13f else 8f)
                canvas.drawPath(path, glow)
            }

            stroke.color = color

            stroke.alpha =
                when(state) {
                    STATE_WAITING -> if (color == Color.WHITE) 238 else 205
                    STATE_RECOGNITION -> if (color == Color.WHITE) 248 else 220
                    STATE_THINKING -> if (color == Color.WHITE) 252 else 230
                    STATE_EXECUTING -> if (color == Color.WHITE) 248 else 222
                    STATE_ANSWERING -> if (color == Color.WHITE) 250 else 224
                    STATE_STOP -> if (color == Color.WHITE) 92 else 116
                    else -> 170
                }

            stroke.strokeWidth =
                dp(
                    when {
                        color == Color.WHITE -> 1.55f
                        ribbon % 5 == 0 -> 1.30f
                        else -> 0.92f
                    }
                )

            canvas.drawPath(path, stroke)
        }
    }


    private fun drawCoreRings(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val pulse =
            (
                0.5 +
                    0.5 *
                        sin(
                            now / motion.breathePeriodMs
                        )
                )
                .toFloat()

        // Dark glass-like center surrounded by a luminous lens.
        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius * 0.74f,
                intArrayOf(
                    withAlpha(Color.WHITE, if (state == STATE_STOP) 86 else 235),
                    withAlpha(palette.primary, if (state == STATE_STOP) 102 else 238),
                    withAlpha(palette.secondary, if (state == STATE_STOP) 64 else 154),
                    withAlpha(Color.parseColor("#02050A"), 238),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.16f,
                    0.40f,
                    0.70f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha = 255
        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    0.66f +
                        pulse * 0.018f
                    ),
            fill
        )
        fill.shader = null

        // Dense concentric computation rings.
        for (ringIndex in 0 until 6) {
            val rr =
                radius *
                    (
                        0.30f +
                            ringIndex * 0.095f
                        )

            stroke.color =
                when(ringIndex % 4) {
                    0 -> Color.WHITE
                    1 -> palette.primary
                    2 -> palette.secondary
                    else -> palette.accent
                }

            stroke.alpha =
                when(state) {
                    STATE_STOP -> 38 + ringIndex * 5
                    else -> 62 + ringIndex * 10
                }
                    .coerceAtMost(205)

            stroke.strokeWidth =
                dp(
                    when {
                        ringIndex % 4 == 0 -> 0.90f
                        ringIndex % 3 == 0 -> 0.72f
                        else -> 0.48f
                    }
                )

            canvas.drawCircle(cx, cy, rr, stroke)
        }

        // Computational focus.
        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius * 0.12f,
                intArrayOf(
                    Color.WHITE,
                    palette.primary,
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.28f, 1f),
                Shader.TileMode.CLAMP
            )

        fill.alpha = if (state == STATE_STOP) 120 else 245
        canvas.drawCircle(cx, cy, radius * 0.12f, fill)
        fill.shader = null
    }

    /**
     * Outer halo made from points and tangential streaks instead of drawArc(),
     * keeping the renderer compatible with the isolated compile harness.
     */

    private fun drawOuterTelemetry(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val rotation = now / motion.haloPeriodMs

        // Several segmented rotating rings.
        for (ringIndex in 0 until 7) {
            val rr =
                radius *
                    (
                        0.74f +
                            ringIndex * 0.043f
                        )

            val segmentCount =
                54 +
                    ringIndex * 8

            val spin =
                rotation *
                    (
                        if (ringIndex % 2 == 0) {
                            1.0
                        } else {
                            -0.72
                        }
                    )

            for (segment in 0 until segmentCount) {
                if ((segment + ringIndex * 2) % 5 == 1) continue
                if ((segment + ringIndex) % 11 == 4) continue

                val a1 =
                    segment *
                        PI *
                        2.0 /
                        segmentCount +
                        spin

                val arcLen =
                    PI *
                        2.0 /
                        segmentCount *
                        (
                            if (segment % 4 == 0) {
                                0.82
                            } else {
                                0.58
                            }
                        )

                val a2 = a1 + arcLen

                val x1 = cx + cos(a1).toFloat() * rr
                val y1 = cy + sin(a1).toFloat() * rr
                val x2 = cx + cos(a2).toFloat() * rr
                val y2 = cy + sin(a2).toFloat() * rr

                val color =
                    when((segment + ringIndex) % 4) {
                        0 -> Color.WHITE
                        1 -> palette.primary
                        2 -> palette.secondary
                        else -> palette.accent
                    }

                if (segment % 9 == 0) {
                    glow.color =
                        if (color == Color.WHITE) palette.primary else color
                    glow.alpha = if (state == STATE_STOP) 18 else 46
                    glow.strokeWidth = dp(4.8f)
                    canvas.drawLine(x1, y1, x2, y2, glow)
                }

                stroke.color = color
                stroke.alpha =
                    if (state == STATE_STOP) {
                        48 + ringIndex * 9
                    } else {
                        94 + ringIndex * 17
                    }
                stroke.strokeWidth =
                    dp(
                        if (segment % 7 == 0) {
                            0.90f
                        } else {
                            0.55f
                        }
                    )

                canvas.drawLine(x1, y1, x2, y2, stroke)
            }
        }

        // Dense sparkle halo.
        val points =
            when(state) {
                STATE_THINKING -> 240
                STATE_EXECUTING -> 210
                STATE_STOP -> 120
                else -> 190
            }

        for (i in 0 until points) {
            val angle =
                i *
                    PI *
                    2.0 /
                    points +
                    rotation *
                        (
                            if (i % 2 == 0) 1.0 else -0.46
                        )

            val pulse =
                (
                    0.5 +
                        0.5 *
                            sin(
                                now / 260.0 +
                                    i * 0.93
                            )
                    )
                    .toFloat()

            val rr =
                radius *
                    (
                        0.91f +
                            (
                                i %
                                    5
                                ) *
                                0.018f
                        ) +
                    sin(
                        now / 730.0 +
                            i * 1.41
                    )
                        .toFloat() *
                        radius *
                        0.012f

            val x = cx + cos(angle).toFloat() * rr
            val y = cy + sin(angle).toFloat() * rr

            val color =
                when(i % 4) {
                    0 -> Color.WHITE
                    1 -> palette.primary
                    2 -> palette.secondary
                    else -> palette.accent
                }

            fill.color = color
            fill.alpha =
                if (state == STATE_STOP) {
                    45 + (pulse * 90f).toInt()
                } else {
                    72 + (pulse * 170f).toInt()
                }

            canvas.drawCircle(
                x,
                y,
                dp(
                    when {
                        i % 17 == 0 -> 2.9f
                        i % 7 == 0 -> 1.85f
                        else -> 0.82f
                    }
                ),
                fill
            )

            if (i % 13 == 0) {
                val tx = -sin(angle).toFloat()
                val ty = cos(angle).toFloat()
                val half = dp(3f + pulse * 5f)

                stroke.color = color
                stroke.alpha = if (state == STATE_STOP) 60 else 175
                stroke.strokeWidth = dp(0.60f)

                canvas.drawLine(
                    x - tx * half,
                    y - ty * half,
                    x + tx * half,
                    y + ty * half,
                    stroke
                )
            }
        }
    }

    private fun drawStateDynamics(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        state: Int
    ) {
        when(state) {
            STATE_WAITING ->
                drawWaitingDynamics(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            STATE_RECOGNITION ->
                drawRecognitionDynamics(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            STATE_THINKING ->
                drawThinkingDynamics(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            STATE_EXECUTING ->
                drawExecutingDynamics(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            STATE_ANSWERING ->
                drawAnsweringDynamics(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            STATE_STOP ->
                drawStopDynamics(
                    canvas,
                    cx,
                    cy,
                    radius,
                    palette
                )
        }
    }

    private fun drawWaitingDynamics(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val pulse =
            (
                0.5 +
                    0.5 *
                        sin(now / 980.0)
                )
                .toFloat()

        stroke.color = palette.primary
        stroke.alpha = (95 + pulse * 90f).toInt()
        stroke.strokeWidth = dp(0.85f)

        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    0.86f +
                        pulse *
                            0.014f
                    ),
            stroke
        )
    }

    private fun drawRecognitionDynamics(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val sweep =
            (
                now %
                    1500L
                ).toFloat() /
                1500f

        val sweepAngle =
            -PI +
                sweep *
                    PI *
                    2.0

        val x2 =
            cx +
                cos(sweepAngle)
                    .toFloat() *
                radius *
                0.95f

        val y2 =
            cy +
                sin(sweepAngle)
                    .toFloat() *
                radius *
                0.95f

        glow.color = palette.primary
        glow.alpha = 86
        glow.strokeWidth = dp(6f)
        canvas.drawLine(cx, cy, x2, y2, glow)

        stroke.color = Color.WHITE
        stroke.alpha = 225
        stroke.strokeWidth = dp(0.85f)
        canvas.drawLine(cx, cy, x2, y2, stroke)

        // Inbound analysis packets from both sides.
        for (i in 0 until 8) {
            val t =
                (
                    (
                        now /
                            900.0 +
                            i /
                                8.0
                        ) %
                        1.0
                    )
                    .toFloat()

            val side =
                if (i % 2 == 0) {
                    -1f
                } else {
                    1f
                }

            val x =
                cx +
                    side *
                        radius *
                        (
                            1.10f -
                                0.82f *
                                    t
                            )

            val y =
                cy +
                    sin(
                        now /
                            300.0 +
                            i
                    )
                        .toFloat() *
                        radius *
                        0.08f *
                        (
                            1f -
                                t
                            )

            fill.color =
                if (i % 3 == 0) {
                    Color.WHITE
                } else {
                    palette.primary
                }
            fill.alpha = 225

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (i % 3 == 0) {
                        2f
                    } else {
                        1.2f
                    }
                ),
                fill
            )
        }
    }

    private fun drawThinkingDynamics(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val nodeCount = 18

        for (i in 0 until nodeCount) {
            val a =
                i *
                    PI *
                    2.0 /
                    nodeCount +
                    sin(
                        now /
                            1800.0 +
                            i *
                                0.37
                    ) *
                        0.18

            val rr =
                radius *
                    (
                        0.82f +
                            (
                                i %
                                    4
                                ) *
                                0.065f
                        )

            val x =
                cx +
                    cos(a)
                        .toFloat() *
                    rr

            val y =
                cy +
                    sin(a)
                        .toFloat() *
                    rr

            fill.color =
                if (i % 4 == 0) {
                    Color.WHITE
                } else if (i % 2 == 0) {
                    palette.primary
                } else {
                    palette.secondary
                }

            fill.alpha =
                if (i % 4 == 0) {
                    240
                } else {
                    165
                }

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (i % 4 == 0) {
                        2f
                    } else {
                        1.1f
                    }
                ),
                fill
            )

            if (i % 2 == 0) {
                val j =
                    (
                        i * 5 +
                            3
                        ) %
                        nodeCount

                val b =
                    j *
                        PI *
                        2.0 /
                        nodeCount -
                        sin(
                            now /
                                1500.0 +
                                j
                        ) *
                            0.12

                val rr2 =
                    radius *
                        (
                            0.84f +
                                (
                                    j %
                                        3
                                    ) *
                                    0.07f
                            )

                val x2 =
                    cx +
                        cos(b)
                            .toFloat() *
                        rr2

                val y2 =
                    cy +
                        sin(b)
                            .toFloat() *
                        rr2

                stroke.color =
                    if (i % 4 == 0) {
                        palette.accent
                    } else {
                        palette.primary
                    }

                stroke.alpha = 75
                stroke.strokeWidth = dp(0.55f)
                canvas.drawLine(
                    x,
                    y,
                    x2,
                    y2,
                    stroke
                )
            }
        }
    }

    private fun drawExecutingDynamics(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val right =
            cx +
                radius *
                    1.12f

        // Chosen action path.
        glow.color = palette.primary
        glow.alpha = 90
        glow.strokeWidth = dp(7f)
        canvas.drawLine(
            cx + radius * 0.32f,
            cy,
            right,
            cy,
            glow
        )

        stroke.color = Color.WHITE
        stroke.alpha = 225
        stroke.strokeWidth = dp(0.95f)
        canvas.drawLine(
            cx + radius * 0.32f,
            cy,
            right,
            cy,
            stroke
        )

        for (i in 0 until 9) {
            val t =
                (
                    (
                        now /
                            720.0 +
                            i /
                                9.0
                        ) %
                        1.0
                    )
                    .toFloat()

            val x =
                cx +
                    radius *
                        (
                            0.34f +
                                0.76f *
                                    t
                            )

            val y =
                cy +
                    sin(
                        i *
                            1.4 +
                            now /
                                260.0
                    )
                        .toFloat() *
                        radius *
                        0.05f *
                        (
                            1f -
                                t
                            )

            fill.color =
                if (i % 3 == 0) {
                    Color.WHITE
                } else {
                    palette.primary
                }

            fill.alpha = 235
            canvas.drawCircle(
                x,
                y,
                dp(
                    if (i % 3 == 0) {
                        2.1f
                    } else {
                        1.2f
                    }
                ),
                fill
            )
        }
    }

    private fun drawAnsweringDynamics(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val pulse =
            (
                now %
                    1200L
                ).toFloat() /
                1200f

        for (i in 0 until 3) {
            val rr =
                radius *
                    (
                        0.62f +
                            pulse *
                                0.34f +
                            i *
                                0.08f
                        )

            stroke.color =
                if (i % 2 == 0) {
                    palette.primary
                } else {
                    palette.secondary
                }

            stroke.alpha =
                (
                    160 -
                        pulse *
                            120f -
                        i *
                            18
                    )
                    .toInt()
                    .coerceAtLeast(20)

            stroke.strokeWidth = dp(0.85f)
            canvas.drawCircle(
                cx,
                cy,
                rr,
                stroke
            )
        }

        // Coherent outward packets.
        for (i in 0 until 10) {
            val t =
                (
                    (
                        now /
                            760.0 +
                            i /
                                10.0
                        ) %
                        1.0
                    )
                    .toFloat()

            val side =
                if (i % 2 == 0) {
                    -1f
                } else {
                    1f
                }

            val x =
                cx +
                    side *
                        radius *
                        (
                            0.40f +
                                0.70f *
                                    t
                            )

            val y =
                cy +
                    sin(
                        now /
                            310.0 +
                            i
                    )
                        .toFloat() *
                        radius *
                        0.055f

            fill.color =
                if (i % 4 == 0) {
                    Color.WHITE
                } else {
                    palette.primary
                }
            fill.alpha =
                (
                    230 -
                        t *
                            90f
                    )
                    .toInt()

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (i % 4 == 0) {
                        2f
                    } else {
                        1.1f
                    }
                ),
                fill
            )
        }
    }

    private fun drawStopDynamics(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        // Fractured segments.
        for (i in 0 until 18) {
            if (i % 4 == 1) continue

            val a =
                i *
                    PI *
                    2.0 /
                    18.0

            val gap =
                radius *
                    (
                        0.84f +
                            (
                                i %
                                    3
                                ) *
                                0.055f
                        )

            val x1 =
                cx +
                    cos(a)
                        .toFloat() *
                    gap

            val y1 =
                cy +
                    sin(a)
                        .toFloat() *
                    gap

            val x2 =
                cx +
                    cos(a)
                        .toFloat() *
                    (
                        gap +
                            radius *
                                0.13f
                        )

            val y2 =
                cy +
                    sin(a)
                        .toFloat() *
                    (
                        gap +
                            radius *
                                0.13f
                        )

            stroke.color =
                if (i % 2 == 0) {
                    palette.primary
                } else {
                    palette.secondary
                }

            stroke.alpha =
                115 +
                    (
                        i %
                            4
                        ) *
                        20

            stroke.strokeWidth = dp(1.05f)

            canvas.drawLine(
                x1,
                y1,
                x2,
                y2,
                stroke
            )
        }

        // Halt cut across the center.
        stroke.color = Color.WHITE
        stroke.alpha = 205
        stroke.strokeWidth = dp(0.85f)

        canvas.drawLine(
            cx - radius * 1.05f,
            cy,
            cx - radius * 0.22f,
            cy,
            stroke
        )

        canvas.drawLine(
            cx + radius * 0.22f,
            cy,
            cx + radius * 1.05f,
            cy,
            stroke
        )
    }


    private fun drawWordmark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        state: Int
    ) {
        // Custom geometric AYANA signature. It avoids the ordinary Android
        // bold font that made v14 look like a logo pasted over a screensaver.
        val color =
            if (state == STATE_STOP) {
                palette.secondary
            } else {
                palette.primary
            }

        val totalWidth = radius * 1.42f
        val glyphH = radius * 0.31f
        val gap = radius * 0.050f

        // Relative glyph widths: A Y A N A
        val widths =
            floatArrayOf(
                0.19f,
                0.18f,
                0.19f,
                0.22f,
                0.19f
            )

        val usable =
            totalWidth -
                gap * 4f

        var x =
            cx -
                totalWidth *
                    0.50f

        glow.color = color
        glow.alpha = if (state == STATE_STOP) 46 else 78
        glow.strokeWidth = dp(12f)

        stroke.color =
            if (state == STATE_STOP) {
                color
            } else {
                Color.WHITE
            }
        stroke.alpha = if (state == STATE_STOP) 215 else 238
        stroke.strokeWidth = dp(2.15f)

        widths.forEachIndexed { index, proportion ->
            val gw = usable * proportion
            val centerX = x + gw * 0.50f

            drawAyanaGlyph(
                canvas = canvas,
                glyphIndex = index,
                centerX = centerX,
                centerY = cy,
                width = gw,
                height = glyphH,
                glowColor = color,
                mainColor =
                    if (state == STATE_STOP) {
                        color
                    } else {
                        Color.WHITE
                    }
            )

            x += gw + gap
        }
    }

    private fun drawAyanaGlyph(
        canvas: Canvas,
        glyphIndex: Int,
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
        glowColor: Int,
        mainColor: Int
    ) {
        val left = centerX - width * 0.50f
        val right = centerX + width * 0.50f
        val top = centerY - height * 0.50f
        val bottom = centerY + height * 0.50f
        val mid = centerY

        fun segment(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float
        ) {
            glow.color = glowColor
            glow.alpha = 68
            glow.strokeWidth = dp(6f)
            canvas.drawLine(x1, y1, x2, y2, glow)

            stroke.color = mainColor
            stroke.alpha = 235
            stroke.strokeWidth = dp(1.35f)
            canvas.drawLine(x1, y1, x2, y2, stroke)
        }

        when(glyphIndex) {
            0, 2, 4 -> {
                // Stylized Λ-shaped A from the approved reference.
                segment(left, bottom, centerX, top)
                segment(centerX, top, right, bottom)
            }

            1 -> {
                // Y.
                segment(left, top, centerX, mid)
                segment(right, top, centerX, mid)
                segment(centerX, mid, centerX, bottom)
            }

            else -> {
                // N.
                segment(left, bottom, left, top)
                segment(left, top, right, bottom)
                segment(right, bottom, right, top)
            }
        }
    }

    private fun stateIndex(
        state: String
    ): Int {
        return when(state) {
            AyanaVoiceService.STATE_LISTENING ->
                STATE_WAITING

            AyanaVoiceService.STATE_COMMAND ->
                STATE_RECOGNITION

            AyanaVoiceService.STATE_THINKING ->
                STATE_THINKING

            AyanaVoiceService.STATE_EXECUTING ->
                STATE_EXECUTING

            AyanaVoiceService.STATE_SPEAKING,
            AyanaVoiceService.STATE_TEXT,
            AyanaVoiceService.STATE_SUCCESS ->
                STATE_ANSWERING

            AyanaVoiceService.STATE_CANCELLED,
            AyanaVoiceService.STATE_ERROR ->
                STATE_STOP

            else ->
                STATE_WAITING
        }
    }

    private fun paletteFor(
        state: Int
    ): Palette {
        return when(state) {
            STATE_WAITING ->
                Palette(
                    primary = Color.parseColor("#19E4EC"),
                    secondary = Color.parseColor("#0CB8FF"),
                    accent = Color.parseColor("#7EF9FF")
                )

            STATE_RECOGNITION ->
                Palette(
                    primary = Color.parseColor("#147DFF"),
                    secondary = Color.parseColor("#00C8FF"),
                    accent = Color.parseColor("#8EDBFF")
                )

            STATE_THINKING ->
                Palette(
                    primary = Color.parseColor("#6D47FF"),
                    secondary = Color.parseColor("#9E46FF"),
                    accent = Color.parseColor("#D2B5FF")
                )

            STATE_EXECUTING ->
                Palette(
                    primary = Color.parseColor("#20E36A"),
                    secondary = Color.parseColor("#00CFAE"),
                    accent = Color.parseColor("#8AFFC0")
                )

            STATE_ANSWERING ->
                Palette(
                    primary = Color.parseColor("#F02BC1"),
                    secondary = Color.parseColor("#A94BFF"),
                    accent = Color.parseColor("#FF9DEB")
                )

            STATE_STOP ->
                Palette(
                    primary = Color.parseColor("#FF3D25"),
                    secondary = Color.parseColor("#FF7A1A"),
                    accent = Color.parseColor("#FF9A6F")
                )

            else ->
                paletteFor(STATE_WAITING)
        }
    }

    private fun motionFor(
        state: Int
    ): Motion {
        return when(state) {
            STATE_WAITING ->
                Motion(
                    frameDelayMs = 42L,
                    breathePeriodMs = 980.0,
                    wavePeriodMs = 760.0,
                    waveCycles = 7.0,
                    waveAlpha = 205,
                    waveGlowAlpha = 58,
                    ribbonPeriodMs = 1450.0,
                    ribbonWarp = 0.105f,
                    haloPeriodMs = 5400.0
                )

            STATE_RECOGNITION ->
                Motion(
                    frameDelayMs = 28L,
                    breathePeriodMs = 620.0,
                    wavePeriodMs = 360.0,
                    waveCycles = 9.2,
                    waveAlpha = 245,
                    waveGlowAlpha = 86,
                    ribbonPeriodMs = 780.0,
                    ribbonWarp = 0.125f,
                    haloPeriodMs = 2600.0
                )

            STATE_THINKING ->
                Motion(
                    frameDelayMs = 27L,
                    breathePeriodMs = 540.0,
                    wavePeriodMs = 440.0,
                    waveCycles = 8.5,
                    waveAlpha = 232,
                    waveGlowAlpha = 78,
                    ribbonPeriodMs = 610.0,
                    ribbonWarp = 0.165f,
                    haloPeriodMs = 2050.0
                )

            STATE_EXECUTING ->
                Motion(
                    frameDelayMs = 26L,
                    breathePeriodMs = 470.0,
                    wavePeriodMs = 300.0,
                    waveCycles = 9.5,
                    waveAlpha = 245,
                    waveGlowAlpha = 88,
                    ribbonPeriodMs = 560.0,
                    ribbonWarp = 0.135f,
                    haloPeriodMs = 1750.0
                )

            STATE_ANSWERING ->
                Motion(
                    frameDelayMs = 27L,
                    breathePeriodMs = 520.0,
                    wavePeriodMs = 330.0,
                    waveCycles = 8.8,
                    waveAlpha = 242,
                    waveGlowAlpha = 86,
                    ribbonPeriodMs = 650.0,
                    ribbonWarp = 0.125f,
                    haloPeriodMs = 2200.0
                )

            STATE_STOP ->
                Motion(
                    frameDelayMs = 76L,
                    breathePeriodMs = 1500.0,
                    wavePeriodMs = 1100.0,
                    waveCycles = 5.5,
                    waveAlpha = 150,
                    waveGlowAlpha = 36,
                    ribbonPeriodMs = 2500.0,
                    ribbonWarp = 0.075f,
                    haloPeriodMs = 8400.0
                )

            else ->
                motionFor(STATE_WAITING)
        }
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

    private fun dp(
        value: Float
    ): Float {
        return value * density
    }

    private data class Palette(
        val primary: Int,
        val secondary: Int,
        val accent: Int
    )

    private data class Motion(
        val frameDelayMs: Long,
        val breathePeriodMs: Double,
        val wavePeriodMs: Double,
        val waveCycles: Double,
        val waveAlpha: Int,
        val waveGlowAlpha: Int,
        val ribbonPeriodMs: Double,
        val ribbonWarp: Float,
        val haloPeriodMs: Double
    )

    companion object {
        private const val STATE_WAITING = 0
        private const val STATE_RECOGNITION = 1
        private const val STATE_THINKING = 2
        private const val STATE_EXECUTING = 3
        private const val STATE_ANSWERING = 4
        private const val STATE_STOP = 5
    }
}
